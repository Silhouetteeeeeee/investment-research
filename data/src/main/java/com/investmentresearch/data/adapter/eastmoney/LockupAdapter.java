package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.LockupPort;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** 东财限售解禁，照抄 {@code stock/lockups.py} 与 {@code assistant/tools.py:_lockup_expiries}。 */
@Repository
public class LockupAdapter implements LockupPort {
    static final String REPORT_NAME = "RPT_LIFT_STAGE";
    static final String PAGE_URL = "https://data.eastmoney.com/dxf/detail.html?code=%s";
    static final double MAX_AGE_HOURS = 24;
    static final int MAX_EVENTS = 40;

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public LockupAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(store, stockMapper, upstream, endpoint, Clock.systemDefaultZone());
    }

    LockupAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            String endpoint,
            Clock clock) {
        this.store = store;
        this.stockMapper = stockMapper;
        this.upstream = upstream;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override
    public ToolResult lockups(String rawCode, int years, int aheadMonths) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.LOCKUPS,
                            code,
                            null,
                            MAX_AGE_HOURS,
                            clock,
                            () -> fetch(code));
            return build(
                    code,
                    ensured,
                    Math.min(Math.max(years, 1), 10),
                    Math.min(Math.max(aheadMonths, 0), 36));
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult build(
            String code, DatacenterSupport.CacheResult ensured, int years, int aheadMonths) {
        List<Map<String, Object>> history =
                dedupe(DatacenterSupport.plainList(ensured.data().path("events")));

        LocalDate today = LocalDate.parse(DatacenterSupport.today(clock));
        String end = today.toString();
        for (Map<String, Object> event : history) {
            String freeDate = String.valueOf(event.get("free_date"));
            event.put("is_upcoming", freeDate.compareTo(end) > 0);
            LocalDate day = parseDate(freeDate);
            event.put("days_until", day == null ? null : (int) ChronoUnit.DAYS.between(today, day));
        }
        String start = (today.getYear() - years + 1) + "-01-01";
        String futureEnd = addMonths(today, aheadMonths).toString();

        List<Map<String, Object>> window = new ArrayList<>();
        for (Map<String, Object> event : history) {
            String freeDate = String.valueOf(event.get("free_date"));
            if (start.compareTo(freeDate) <= 0 && freeDate.compareTo(futureEnd) <= 0) {
                window.add(event);
            }
        }
        List<Map<String, Object>> past = new ArrayList<>();
        List<Map<String, Object>> future = new ArrayList<>();
        for (Map<String, Object> event : window) {
            if (String.valueOf(event.get("free_date")).compareTo(end) <= 0) {
                past.add(event);
            } else {
                future.add(event);
            }
        }
        future.sort(
                (left, right) -> {
                    int compared =
                            String.valueOf(left.get("free_date"))
                                    .compareTo(String.valueOf(right.get("free_date")));
                    if (compared != 0) {
                        return compared;
                    }
                    return Double.compare(
                            -DatacenterSupport.intOf(left.get("shares")),
                            -DatacenterSupport.intOf(right.get("shares")));
                });
        past.sort(
                (left, right) -> {
                    int compared =
                            String.valueOf(right.get("free_date"))
                                    .compareTo(String.valueOf(left.get("free_date")));
                    if (compared != 0) {
                        return compared;
                    }
                    return Integer.compare(
                            DatacenterSupport.intOf(right.get("shares")),
                            DatacenterSupport.intOf(left.get("shares")));
                });

        Map<String, Object> counts = counts(window, past, future);
        List<Map<String, Object>> annual = annualRows(window);
        List<Map<String, Object>> types = typeRows(window);
        List<Map<String, Object>> upcoming = future.subList(0, Math.min(MAX_EVENTS, future.size()));
        int remain = Math.max(0, MAX_EVENTS - upcoming.size());
        List<Map<String, Object>> historyDisplay =
                past.subList(0, Math.min(remain, past.size()));
        int truncated = window.size() - upcoming.size() - historyDisplay.size();

        List<String> updates = new ArrayList<>();
        for (Map<String, Object> event : history) {
            String update = DatacenterSupport.text(event.get("update_date"));
            updates.add(update == null ? String.valueOf(event.get("free_date")) : update);
        }
        String asOf = updates.stream().max(String::compareTo).orElse(null);
        Map<String, Object> latest =
                past.isEmpty() ? (history.isEmpty() ? null : history.get(0)) : past.get(0);
        Map<String, Object> next = future.isEmpty() ? null : future.get(0);
        List<String> notes = notes(history, window, future, counts, years, aheadMonths);

        String name = stockMapper.selectNameByCode(code);
        if ((name == null || name.isEmpty())
                && !DatacenterSupport.plainList(ensured.data().path("events")).isEmpty()) {
            name =
                    DatacenterSupport.text(
                            DatacenterSupport.plainList(ensured.data().path("events"))
                                    .get(0)
                                    .get("security_name"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("window_start", start);
        data.put("window_end", futureEnd);
        data.put("years", years);
        data.put("ahead_months", aheadMonths);
        data.put("has_lockups", !window.isEmpty());
        data.put("latest", latest == null ? null : eventView(latest));
        data.put("next", next == null ? null : eventView(next));
        data.put("upcoming", eventViews(upcoming));
        data.put("history", eventViews(historyDisplay));
        List<Map<String, Object>> annualView = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            annualView.add(yearView(row));
        }
        data.put("annual", annualView);
        List<Map<String, Object>> typeView = new ArrayList<>();
        for (Map<String, Object> row : types) {
            typeView.add(typeView(row));
        }
        data.put("types", typeView);
        data.put("events_truncated", Math.max(0, truncated));
        data.put("counts", countsView(counts));
        data.put("notes", notes);
        data.put("stale", ensured.stale());

        Source source =
                new Source(
                        asOf == null ? "lockups:" + code : "lockups:" + code + ":" + asOf,
                        "限售解禁",
                        ((name == null || name.isEmpty() ? code : name)
                                        + " 限售解禁 "
                                        + (asOf == null ? "" : asOf))
                                .strip(),
                        asOf == null ? "" : asOf,
                        ensured.fetchedAt() == null ? "" : ensured.fetchedAt(),
                        PAGE_URL.formatted(code));
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    private static List<Map<String, Object>> eventViews(List<Map<String, Object>> events) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map<String, Object> event : events) {
            views.add(eventView(event));
        }
        return views;
    }

    // ---------------------------------------------------------------- 取数

    private JsonNode fetch(String code) {
        List<JsonNode> rows =
                EastmoneySupport.fetchRows(
                        upstream,
                        endpoint,
                        REPORT_NAME,
                        "(SECURITY_CODE=\"" + code + "\")",
                        "FREE_DATE:-1",
                        100,
                        "限售解禁");
        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> event = normalize(row);
            if (event != null) {
                events.add(event);
            }
        }
        ObjectNode payload = EastmoneySupport.MAPPER.createObjectNode();
        payload.put("code", code);
        ArrayNode array = payload.putArray("events");
        for (Map<String, Object> event : dedupe(events)) {
            array.add(EastmoneySupport.MAPPER.valueToTree(event));
        }
        payload.put("count", array.size());
        return payload;
    }

    /** {@code stock/lockups.py:_normalize_row}。 */
    private static Map<String, Object> normalize(JsonNode row) {
        String freeDate = date(row.get("FREE_DATE"));
        if (freeDate == null) {
            return null;
        }
        Integer shares = scaledInt(row.get("CURRENT_FREE_SHARES"), 10_000);
        if (shares == null || shares <= 0) {
            return null;
        }
        String sharesType = EastmoneySupport.text(row.get("FREE_SHARES_TYPE"));
        Integer holders = intOrNull(row.get("BATCH_HOLDER_NUM"));
        JsonNode totalRatio =
                row.get("TOTAL_RATIO") != null && !row.get("TOTAL_RATIO").isNull()
                        ? row.get("TOTAL_RATIO")
                        : row.get("TOTALSHARES_RATIO");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("free_date", freeDate);
        event.put("year", Integer.parseInt(freeDate.substring(0, 4)));
        event.put("is_upcoming", false);
        event.put("days_until", null);
        event.put("shares", shares);
        event.put("market_cap", scaledNumber(row.get("LIFT_MARKET_CAP"), 10_000));
        event.put("price", EastmoneySupport.num(row.get("NEW")));
        event.put("ratio_total_pct", percent(totalRatio));
        event.put("ratio_free_pct", percent(row.get("FREE_RATIO")));
        event.put("holders", holders);
        event.put("shares_type", sharesType);
        event.put("market_type", EastmoneySupport.text(row.get("MARKET_TYPE")));
        event.put("free_shares_after", scaledInt(row.get("FREE_SHARES"), 10_000));
        event.put("non_free_shares_after", scaledInt(row.get("NON_FREE_SHARES"), 10_000));
        event.put("able_free_shares", scaledInt(row.get("ABLE_FREE_SHARES"), 10_000));
        event.put("update_date", date(row.get("EUTIME")));
        event.put("security_name", EastmoneySupport.text(row.get("SECURITY_NAME_ABBR")));
        event.put("key", freeDate + "|" + sharesType + "|" + shares + "|" + holders);
        return event;
    }

    // ---------------------------------------------------------------- 计算

    private static List<Map<String, Object>> annualRows(List<Map<String, Object>> events) {
        Map<Integer, List<Map<String, Object>>> byYear = new TreeMap<>();
        for (Map<String, Object> event : events) {
            byYear.computeIfAbsent(DatacenterSupport.intOf(event.get("year")), year -> new ArrayList<>())
                    .add(event);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byYear.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", entry.getKey());
            row.put("events", group.size());
            row.put(
                    "history_events",
                    (int) group.stream().filter(event -> !DatacenterSupport.flag(event, "is_upcoming")).count());
            row.put(
                    "upcoming_events",
                    (int) group.stream().filter(event -> DatacenterSupport.flag(event, "is_upcoming")).count());
            row.put("shares", DatacenterSupport.sumOptional(DatacenterSupport.column(group, "shares")));
            row.put(
                    "market_cap",
                    DatacenterSupport.sumOptional(DatacenterSupport.column(group, "market_cap")));
            row.put(
                    "missing_market_cap",
                    (int) group.stream().filter(event -> event.get("market_cap") == null).count());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> typeRows(List<Map<String, Object>> events) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String type = DatacenterSupport.text(event.get("shares_type"));
            groups.computeIfAbsent(type == null ? "未披露" : type, key -> new ArrayList<>()).add(event);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", entry.getKey());
            row.put("events", group.size());
            row.put(
                    "upcoming",
                    (int) group.stream().filter(event -> DatacenterSupport.flag(event, "is_upcoming")).count());
            row.put("shares", DatacenterSupport.sumOptional(DatacenterSupport.column(group, "shares")));
            row.put(
                    "market_cap",
                    DatacenterSupport.sumOptional(DatacenterSupport.column(group, "market_cap")));
            rows.add(row);
        }
        rows.sort(
                (left, right) -> {
                    int compared =
                            Integer.compare(
                                    DatacenterSupport.intOf(right.get("events")),
                                    DatacenterSupport.intOf(left.get("events")));
                    if (compared != 0) {
                        return compared;
                    }
                    return Double.compare(
                            DatacenterSupport.number(right.get("shares")) == null
                                    ? 0
                                    : DatacenterSupport.number(right.get("shares")),
                            DatacenterSupport.number(left.get("shares")) == null
                                    ? 0
                                    : DatacenterSupport.number(left.get("shares")));
                });
        return rows;
    }

    private static Map<String, Object> counts(
            List<Map<String, Object>> events,
            List<Map<String, Object>> past,
            List<Map<String, Object>> future) {
        Map<String, Object> next = future.isEmpty() ? null : future.get(0);
        Map<String, Object> latest = past.isEmpty() ? null : past.get(0);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("events", events.size());
        counts.put("history_events", past.size());
        counts.put("upcoming_events", future.size());
        counts.put("next_date", next == null ? null : next.get("free_date"));
        counts.put("next_days", next == null ? null : next.get("days_until"));
        counts.put("next_shares", next == null ? null : next.get("shares"));
        counts.put("next_market_cap", next == null ? null : next.get("market_cap"));
        counts.put("next_ratio_pct", next == null ? null : next.get("ratio_total_pct"));
        counts.put("next_holders", next == null ? null : next.get("holders"));
        counts.put("next_type", next == null ? null : next.get("shares_type"));
        counts.put(
                "upcoming_shares",
                DatacenterSupport.sumOptional(DatacenterSupport.column(future, "shares")));
        counts.put(
                "upcoming_market_cap",
                DatacenterSupport.sumOptional(DatacenterSupport.column(future, "market_cap")));
        counts.put(
                "upcoming_ratio_pct",
                DatacenterSupport.sumOptional(DatacenterSupport.column(future, "ratio_total_pct")));
        counts.put(
                "history_shares",
                DatacenterSupport.sumOptional(DatacenterSupport.column(past, "shares")));
        counts.put(
                "history_market_cap",
                DatacenterSupport.sumOptional(DatacenterSupport.column(past, "market_cap")));
        counts.put(
                "total_shares",
                DatacenterSupport.sumOptional(DatacenterSupport.column(events, "shares")));
        counts.put(
                "total_market_cap",
                DatacenterSupport.sumOptional(DatacenterSupport.column(events, "market_cap")));
        counts.put("latest_date", latest == null ? null : latest.get("free_date"));
        counts.put("latest_type", latest == null ? null : latest.get("shares_type"));
        counts.put(
                "missing_market_cap",
                (int) events.stream().filter(event -> event.get("market_cap") == null).count());
        return counts;
    }

    private static List<String> notes(
            List<Map<String, Object>> history,
            List<Map<String, Object>> events,
            List<Map<String, Object>> future,
            Map<String, Object> counts,
            int years,
            int aheadMonths) {
        List<String> notes = new ArrayList<>();
        if (history.isEmpty()) {
            notes.add("没有取到限售解禁记录，可能数据源未收录");
            return notes;
        }
        if (events.isEmpty()) {
            Map<String, Object> latest = history.get(0);
            notes.add("近" + years + "年及未来" + aheadMonths + "个月没有披露的解禁；最近一次为" + latest.get("free_date"));
            notes.add("只统计已披露的解禁批次，未覆盖未公告的安排");
            return notes;
        }
        notes.add("解禁不等于减持，股东是否卖出取决于意愿和减持规则");
        notes.add("解禁数量按公告，市值按披露参考价估算，会随股价变化");
        if (!future.isEmpty()) {
            notes.add(
                    "未来"
                            + aheadMonths
                            + "个月有"
                            + future.size()
                            + "批解禁，合计"
                            + sharesText(DatacenterSupport.number(counts.get("upcoming_shares"))));
        } else {
            notes.add("未来" + aheadMonths + "个月没有已披露的解禁");
        }
        if (DatacenterSupport.intOf(counts.get("missing_market_cap")) > 0) {
            notes.add(counts.get("missing_market_cap") + " 批缺少解禁市值，合计不完整");
        }
        return notes;
    }

    // ---------------------------------------------------------------- 整形

    /** {@code assistant/tools.py:_lockup_event}。 */
    private static Map<String, Object> eventView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("free_date", item.get("free_date"));
        out.put("year", item.get("year"));
        out.put("is_upcoming", item.get("is_upcoming") == null ? false : item.get("is_upcoming"));
        out.put("days_until", item.get("days_until"));
        out.put("shares_wan", DatacenterSupport.wan(item.get("shares")));
        out.put("market_cap_yi", DatacenterSupport.yi(item.get("market_cap")));
        out.put("price", DatacenterSupport.round(item.get("price"), 2));
        out.put("ratio_total_pct", DatacenterSupport.round(item.get("ratio_total_pct"), 2));
        out.put("ratio_free_pct", DatacenterSupport.round(item.get("ratio_free_pct"), 2));
        out.put("holders", item.get("holders"));
        out.put("shares_type", item.get("shares_type"));
        out.put("market_type", item.get("market_type"));
        out.put("free_shares_after_wan", DatacenterSupport.wan(item.get("free_shares_after")));
        out.put("non_free_shares_after_wan", DatacenterSupport.wan(item.get("non_free_shares_after")));
        out.put("able_free_shares_wan", DatacenterSupport.wan(item.get("able_free_shares")));
        out.put("update_date", item.get("update_date"));
        return out;
    }

    /** {@code assistant/tools.py:_lockup_year}。 */
    private static Map<String, Object> yearView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", item.get("year"));
        out.put("events", item.get("events"));
        out.put("history_events", item.get("history_events"));
        out.put("upcoming_events", item.get("upcoming_events"));
        out.put("shares_wan", DatacenterSupport.wan(item.get("shares")));
        out.put("market_cap_yi", DatacenterSupport.yi(item.get("market_cap")));
        out.put("missing_market_cap", item.getOrDefault("missing_market_cap", 0));
        return out;
    }

    /** {@code assistant/tools.py:_lockup_type}。 */
    private static Map<String, Object> typeView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", item.get("type"));
        out.put("events", item.get("events"));
        out.put("upcoming", item.get("upcoming"));
        out.put("shares_wan", DatacenterSupport.wan(item.get("shares")));
        out.put("market_cap_yi", DatacenterSupport.yi(item.get("market_cap")));
        return out;
    }

    /** {@code assistant/tools.py:_lockup_counts}。 */
    private static Map<String, Object> countsView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", item.getOrDefault("events", 0));
        out.put("history_events", item.getOrDefault("history_events", 0));
        out.put("upcoming_events", item.getOrDefault("upcoming_events", 0));
        out.put("next_date", item.get("next_date"));
        out.put("next_days", item.get("next_days"));
        out.put("next_shares_wan", DatacenterSupport.wan(item.get("next_shares")));
        out.put("next_market_cap_yi", DatacenterSupport.yi(item.get("next_market_cap")));
        out.put("next_ratio_pct", DatacenterSupport.round(item.get("next_ratio_pct"), 2));
        out.put("next_holders", item.get("next_holders"));
        out.put("next_type", item.get("next_type"));
        out.put("upcoming_shares_wan", DatacenterSupport.wan(item.get("upcoming_shares")));
        out.put("upcoming_market_cap_yi", DatacenterSupport.yi(item.get("upcoming_market_cap")));
        out.put("upcoming_ratio_pct", DatacenterSupport.round(item.get("upcoming_ratio_pct"), 2));
        out.put("history_shares_wan", DatacenterSupport.wan(item.get("history_shares")));
        out.put("history_market_cap_yi", DatacenterSupport.yi(item.get("history_market_cap")));
        out.put("total_shares_wan", DatacenterSupport.wan(item.get("total_shares")));
        out.put("total_market_cap_yi", DatacenterSupport.yi(item.get("total_market_cap")));
        out.put("latest_date", item.get("latest_date"));
        out.put("latest_type", item.get("latest_type"));
        out.put("missing_market_cap", item.getOrDefault("missing_market_cap", 0));
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    /** {@code _dedupe}：同一批解禁只留更新更晚、字段更完整的一条。 */
    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            if (DatacenterSupport.text(event.get("free_date")) == null) {
                continue;
            }
            String key = String.valueOf(event.get("key"));
            Map<String, Object> current = byKey.get(key);
            if (current == null || eventStamp(event).compareTo(eventStamp(current)) > 0) {
                byKey.put(key, event);
            }
        }
        List<Map<String, Object>> ordered = new ArrayList<>(byKey.values());
        ordered.sort(
                (left, right) -> {
                    int compared =
                            String.valueOf(right.get("free_date"))
                                    .compareTo(String.valueOf(left.get("free_date")));
                    if (compared != 0) {
                        return compared;
                    }
                    return Integer.compare(
                            DatacenterSupport.intOf(right.get("shares")),
                            DatacenterSupport.intOf(left.get("shares")));
                });
        return ordered;
    }

    private static String eventStamp(Map<String, Object> event) {
        String update = DatacenterSupport.text(event.get("update_date"));
        return (update == null ? "" : update) + "#" + String.format("%02d", completeness(event));
    }

    private static int completeness(Map<String, Object> event) {
        int count = 0;
        for (String key :
                List.of(
                        "market_cap", "price", "ratio_total_pct", "ratio_free_pct", "holders",
                        "shares_type", "free_shares_after", "non_free_shares_after",
                        "able_free_shares")) {
            if (event.get(key) != null) {
                count++;
            }
        }
        return count;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** Python {@code _add_months}：月末按目标月的最后一天截断。 */
    private static LocalDate addMonths(LocalDate day, int months) {
        int index = day.getMonthValue() - 1 + months;
        int year = day.getYear() + Math.floorDiv(index, 12);
        int month = Math.floorMod(index, 12) + 1;
        int lastDay = LocalDate.of(year, month, 1).lengthOfMonth();
        return LocalDate.of(year, month, Math.min(day.getDayOfMonth(), lastDay));
    }

    /** {@code _shares}：亿股 / 万股 / 股。 */
    private static String sharesText(Double value) {
        if (value == null) {
            return "—";
        }
        double absolute = Math.abs(value);
        if (absolute >= 1e8) {
            return String.format("%.2f亿股", value / 1e8);
        }
        if (absolute >= 1e4) {
            return String.format("%.2f万股", value / 1e4);
        }
        return String.format("%d股", (long) (double) value);
    }

    private static Double percent(JsonNode node) {
        Double value = EastmoneySupport.num(node);
        return value == null ? null : value * 100;
    }

    private static Double scaledNumber(JsonNode node, int scale) {
        Double value = EastmoneySupport.num(node);
        return value == null ? null : EastmoneySupport.round(value * scale, 2);
    }

    private static Integer scaledInt(JsonNode node, int scale) {
        Double value = EastmoneySupport.num(node);
        return value == null ? null : (int) Math.round(value * scale);
    }

    private static String date(JsonNode node) {
        String text = EastmoneySupport.text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }

    private static Integer intOrNull(JsonNode node) {
        Double value = EastmoneySupport.num(node);
        return value == null ? null : (int) (double) value;
    }
}
