package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.InsiderTradePort;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** 东财高管增减持，照抄 {@code stock/insider_trades.py} 与 {@code assistant/tools.py:_insider_trades}。 */
@Repository
public class InsiderTradeAdapter implements InsiderTradePort {
    static final String REPORT_NAME = "RPT_EXECUTIVE_HOLD_DETAILS";
    static final String PAGE_URL = "https://data.eastmoney.com/executive/%s.html";
    static final double MAX_AGE_HOURS = 24;
    static final int MAX_EVENTS = 60;

    private static final List<String> PLAN_KEYWORDS =
            List.of("持股计划", "员工持股", "激励计划", "持股集合");

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public InsiderTradeAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(store, stockMapper, upstream, endpoint, Clock.systemDefaultZone());
    }

    InsiderTradeAdapter(
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
    public ToolResult insiderTrades(String rawCode, int years) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.INSIDER_TRADES,
                            code,
                            null,
                            MAX_AGE_HOURS,
                            clock,
                            () -> fetch(code));
            return build(code, ensured, Math.min(Math.max(years, 1), 10));
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult build(String code, DatacenterSupport.CacheResult ensured, int years) {
        List<Map<String, Object>> history =
                dedupe(DatacenterSupport.plainList(ensured.data().path("events")));

        String end = DatacenterSupport.today(clock);
        String start = (Integer.parseInt(end.substring(0, 4)) - years + 1) + "-01-01";
        List<Map<String, Object>> window = new ArrayList<>();
        for (Map<String, Object> event : history) {
            String changeDate = DatacenterSupport.text(event.get("change_date"));
            if (changeDate != null && start.compareTo(changeDate) <= 0 && changeDate.compareTo(end) <= 0) {
                window.add(event);
            }
        }

        List<Map<String, Object>> annual = annualRows(window);
        List<Map<String, Object>> people = peopleRows(window);
        Map<String, Object> counts = counts(window, people);
        int truncated = Math.max(0, window.size() - MAX_EVENTS);
        List<String> notes = notes(history, window, counts, years, truncated);

        String name = stockMapper.selectNameByCode(code);
        if ((name == null || name.isEmpty()) && !history.isEmpty()) {
            name = DatacenterSupport.text(history.get(0).get("security_name"));
        }
        String asOf = history.isEmpty() ? null : DatacenterSupport.text(history.get(0).get("change_date"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("window_start", start);
        data.put("window_end", end);
        data.put("years", years);
        data.put("has_trades", !window.isEmpty());
        data.put("latest", history.isEmpty() ? null : eventView(history.get(0)));
        List<Map<String, Object>> annualView = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            annualView.add(yearView(row));
        }
        data.put("annual", annualView);
        List<Map<String, Object>> peopleView = new ArrayList<>();
        for (Map<String, Object> row : people) {
            peopleView.add(personView(row));
        }
        data.put("people", peopleView);
        List<Map<String, Object>> eventsView = new ArrayList<>();
        for (Map<String, Object> event : window.subList(0, Math.min(MAX_EVENTS, window.size()))) {
            eventsView.add(eventView(event));
        }
        data.put("events", eventsView);
        data.put("events_truncated", truncated);
        data.put("counts", countsView(counts));
        data.put("notes", notes);
        data.put("stale", ensured.stale());

        Source source =
                new Source(
                        asOf == null ? "insider:" + code : "insider:" + code + ":" + asOf,
                        "高管增减持",
                        ((name == null || name.isEmpty() ? code : name)
                                        + " 高管增减持 "
                                        + (asOf == null ? "" : asOf))
                                .strip(),
                        asOf == null ? "" : asOf,
                        ensured.fetchedAt() == null ? "" : ensured.fetchedAt(),
                        PAGE_URL.formatted(code));
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    // ---------------------------------------------------------------- 取数

    private JsonNode fetch(String code) {
        List<JsonNode> rows =
                EastmoneySupport.fetchRows(
                        upstream,
                        endpoint,
                        REPORT_NAME,
                        "(SECURITY_CODE=\"" + code + "\")",
                        "-1,1,1",
                        "CHANGE_DATE,SECURITY_CODE,PERSON_NAME",
                        100,
                        20,
                        "高管增减持");
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

    /** {@code stock/insider_trades.py:_normalize_row}。 */
    private static Map<String, Object> normalize(JsonNode row) {
        String changeDate = date(row.get("CHANGE_DATE"));
        if (changeDate == null) {
            return null;
        }
        Integer shares = intOrNull(row.get("CHANGE_SHARES"));
        if (shares == null || shares == 0) {
            return null;
        }
        String person = EastmoneySupport.text(row.get("PERSON_NAME"));
        if (person == null) {
            person = EastmoneySupport.text(row.get("DSE_PERSON_NAME"));
        }
        if (person == null) {
            return null;
        }
        String relation = EastmoneySupport.text(row.get("PERSON_DSE_RELATION"));
        if (relation == null) {
            relation = "未披露";
        }
        String executive = EastmoneySupport.text(row.get("DSE_PERSON_NAME"));
        boolean isPlan = relation.equals("其他组织");
        if (!isPlan) {
            for (String keyword : PLAN_KEYWORDS) {
                if (person.contains(keyword)) {
                    isPlan = true;
                    break;
                }
            }
        }

        Double price = EastmoneySupport.num(row.get("AVERAGE_PRICE"));
        Double amount = EastmoneySupport.num(row.get("CHANGE_AMOUNT"));
        boolean estimated = false;
        if (amount == null && price != null) {
            // 接口偶尔只给均价，用均价 × 股数补一个可用的估算值
            amount = EastmoneySupport.round(price * shares, 2);
            estimated = true;
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("change_date", changeDate);
        event.put("year", Integer.parseInt(changeDate.substring(0, 4)));
        event.put("person", person);
        event.put("executive", isPlan ? null : executive);
        event.put("position", isPlan ? null : EastmoneySupport.text(row.get("POSITION_NAME")));
        event.put("relation", relation);
        event.put("kind", isPlan ? "plan" : "individual");
        event.put("direction", shares > 0 ? "增持" : "减持");
        event.put("shares", shares);
        event.put("average_price", price);
        event.put("amount", amount);
        event.put("amount_estimated", estimated);
        event.put("reason", EastmoneySupport.text(row.get("CHANGE_REASON")));
        event.put("change_ratio_pct", EastmoneySupport.num(row.get("CHANGE_RATIO")));
        event.put("hold_after", intOrNull(row.get("CHANGE_AFTER_HOLDNUM")));
        event.put("begin_hold", intOrNull(row.get("BEGIN_HOLD_NUM")));
        event.put("end_hold", intOrNull(row.get("END_HOLD_NUM")));
        event.put("hold_type", EastmoneySupport.text(row.get("HOLD_TYPE")));
        event.put("security_name", EastmoneySupport.text(row.get("SECURITY_NAME")));
        event.put("key", EastmoneySupport.text(row.get("GGEID")));
        return event;
    }

    // ---------------------------------------------------------------- 计算

    private static Map<String, Object> metrics(List<Map<String, Object>> events) {
        List<Map<String, Object>> buy =
                events.stream().filter(event -> shares(event) > 0).toList();
        List<Map<String, Object>> sell =
                events.stream().filter(event -> shares(event) < 0).toList();
        long buyShares = buy.stream().mapToLong(InsiderTradeAdapter::shares).sum();
        long sellShares = Math.abs(sell.stream().mapToLong(InsiderTradeAdapter::shares).sum());
        double buyAmount = sumAmount(buy);
        double sellAmount = Math.abs(sumAmount(sell));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("buy_events", buy.size());
        metrics.put("sell_events", sell.size());
        metrics.put("buy_shares", buyShares);
        metrics.put("sell_shares", sellShares);
        metrics.put("net_shares", buyShares - sellShares);
        metrics.put("buy_amount", buyAmount);
        metrics.put("sell_amount", sellAmount);
        metrics.put("net_amount", EastmoneySupport.round(buyAmount - sellAmount, 2));
        metrics.put(
                "missing_amount_events",
                (int) events.stream().filter(event -> event.get("amount") == null).count());
        return metrics;
    }

    private static double sumAmount(List<Map<String, Object>> events) {
        List<Object> values =
                events.stream()
                        .map(event -> event.get("amount"))
                        .filter(Objects::nonNull)
                        .toList();
        if (values.isEmpty()) {
            return 0.0;
        }
        double total = 0;
        for (Object value : values) {
            total += DatacenterSupport.number(value);
        }
        return EastmoneySupport.round(total, 2);
    }

    private static List<Map<String, Object>> annualRows(List<Map<String, Object>> events) {
        Map<Integer, List<Map<String, Object>>> byYear = new TreeMap<>();
        for (Map<String, Object> event : events) {
            byYear.computeIfAbsent(DatacenterSupport.intOf(event.get("year")), year -> new ArrayList<>())
                    .add(event);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byYear.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            Map<String, Object> metrics = metrics(group);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", entry.getKey());
            row.put("events", group.size());
            row.put("buy_events", metrics.get("buy_events"));
            row.put("sell_events", metrics.get("sell_events"));
            row.put("buy_shares", metrics.get("buy_shares"));
            row.put("sell_shares", metrics.get("sell_shares"));
            row.put("net_shares", metrics.get("net_shares"));
            row.put("buy_amount", metrics.get("buy_amount"));
            row.put("sell_amount", metrics.get("sell_amount"));
            row.put("net_amount", metrics.get("net_amount"));
            row.put("missing_amount_events", metrics.get("missing_amount_events"));
            row.put("amount_complete", DatacenterSupport.intOf(metrics.get("missing_amount_events")) == 0);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> peopleRows(List<Map<String, Object>> events) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String person = DatacenterSupport.text(event.get("person"));
            String key =
                    "plan".equals(event.get("kind"))
                            ? "plan|" + person + "|"
                            : "individual|" + person + "|" + Objects.toString(event.get("executive"), "");
            groups.computeIfAbsent(key, item -> new ArrayList<>()).add(event);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Map<String, Object>> group : groups.values()) {
            List<Map<String, Object>> ordered = new ArrayList<>(group);
            ordered.sort(
                    java.util.Comparator
                            .comparing(
                                    (Map<String, Object> item) ->
                                            DatacenterSupport.text(item.get("change_date")))
                            .thenComparing(item -> DatacenterSupport.text(item.get("person")))
                            .reversed());
            Map<String, Object> latest = ordered.get(0);
            Map<String, Object> metrics = metrics(group);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("person", latest.get("person"));
            row.put("executive", latest.get("executive"));
            row.put("position", latest.get("position"));
            row.put("relation", latest.get("relation"));
            row.put("kind", latest.get("kind"));
            row.put("events", group.size());
            row.put("buy_shares", metrics.get("buy_shares"));
            row.put("sell_shares", metrics.get("sell_shares"));
            row.put("net_shares", metrics.get("net_shares"));
            row.put("buy_amount", metrics.get("buy_amount"));
            row.put("sell_amount", metrics.get("sell_amount"));
            row.put("net_amount", metrics.get("net_amount"));
            row.put("missing_amount_events", metrics.get("missing_amount_events"));
            row.put("amount_complete", DatacenterSupport.intOf(metrics.get("missing_amount_events")) == 0);
            row.put(
                    "first_date",
                    group.stream()
                            .map(event -> DatacenterSupport.text(event.get("change_date")))
                            .filter(Objects::nonNull)
                            .min(String::compareTo)
                            .orElse(null));
            row.put("latest_date", latest.get("change_date"));
            row.put("latest_hold", latest.get("hold_after"));
            row.put("latest_price", latest.get("average_price"));
            rows.add(row);
        }

        rows.sort(
                (left, right) -> {
                    Double leftNet = DatacenterSupport.number(left.get("net_amount"));
                    Double rightNet = DatacenterSupport.number(right.get("net_amount"));
                    int compared = Double.compare(Math.abs(leftNet == null ? 0 : leftNet),
                            Math.abs(rightNet == null ? 0 : rightNet));
                    if (compared != 0) {
                        return -compared;
                    }
                    compared =
                            Double.compare(
                                    Math.abs(DatacenterSupport.intOf(left.get("net_shares"))),
                                    Math.abs(DatacenterSupport.intOf(right.get("net_shares"))));
                    if (compared != 0) {
                        return -compared;
                    }
                    return String.valueOf(right.get("latest_date"))
                            .compareTo(String.valueOf(left.get("latest_date")));
                });
        return rows;
    }

    private static Map<String, Object> counts(
            List<Map<String, Object>> events, List<Map<String, Object>> people) {
        Map<String, Object> metrics = metrics(events);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("events", events.size());
        counts.put("buy_events", metrics.get("buy_events"));
        counts.put("sell_events", metrics.get("sell_events"));
        counts.put("people", people.size());
        counts.put(
                "insiders",
                (int) people.stream().filter(row -> "individual".equals(row.get("kind"))).count());
        counts.put(
                "plans", (int) people.stream().filter(row -> "plan".equals(row.get("kind"))).count());
        counts.put(
                "plan_events", (int) events.stream().filter(event -> "plan".equals(event.get("kind"))).count());
        counts.put("buy_shares", metrics.get("buy_shares"));
        counts.put("sell_shares", metrics.get("sell_shares"));
        counts.put("net_shares", metrics.get("net_shares"));
        counts.put("buy_amount", metrics.get("buy_amount"));
        counts.put("sell_amount", metrics.get("sell_amount"));
        counts.put("net_amount", metrics.get("net_amount"));
        counts.put("missing_amount_events", metrics.get("missing_amount_events"));
        counts.put(
                "estimated_amount_events",
                (int) events.stream().filter(event -> DatacenterSupport.flag(event, "amount_estimated")).count());
        counts.put("latest_date", events.isEmpty() ? null : events.get(0).get("change_date"));
        return counts;
    }

    private static List<String> notes(
            List<Map<String, Object>> history,
            List<Map<String, Object>> events,
            Map<String, Object> counts,
            int years,
            int truncated) {
        List<String> notes = new ArrayList<>();
        if (history.isEmpty()) {
            notes.add("没有取到高管增减持记录，可能数据源未收录");
            return notes;
        }
        if (events.isEmpty()) {
            Map<String, Object> latest = history.get(0);
            notes.add(
                    "近"
                            + years
                            + "年没有披露的增减持记录；最近一次为 "
                            + latest.get("change_date")
                            + " "
                            + latest.get("person")
                            + latest.get("direction")
                            + " "
                            + sharesText(Math.abs(DatacenterSupport.intOf(latest.get("shares")))));
            notes.add("只统计已披露的高管及其关联人变动，未覆盖未披露或未达披露标准的交易");
            return notes;
        }
        notes.add("只统计已披露的高管及其关联人变动，未覆盖未披露或未达披露标准的交易");
        notes.add("变动股数为正表示增持、为负表示减持；金额为披露金额，缺失时按成交均价×股数估算");
        if (events.stream()
                .anyMatch(
                        event ->
                                "individual".equals(event.get("kind"))
                                        && event.get("relation") != null
                                        && !"本人".equals(event.get("relation"))
                                        && !"未披露".equals(event.get("relation")))) {
            notes.add("关联人（配偶、子女、父母、兄弟姐妹等）交易已计入，但不等于高管本人判断");
        }
        if (DatacenterSupport.intOf(counts.get("plan_events")) > 0) {
            notes.add("持股计划/其他组织单列，金额不代表个人增持");
        }
        if (DatacenterSupport.intOf(counts.get("missing_amount_events")) > 0) {
            notes.add(counts.get("missing_amount_events") + " 笔缺少成交金额，金额合计不完整");
        }
        if (DatacenterSupport.intOf(counts.get("estimated_amount_events")) > 0) {
            notes.add(counts.get("estimated_amount_events") + " 笔成交金额按均价估算");
        }
        if (truncated > 0) {
            notes.add("明细只列最近 " + MAX_EVENTS + " 笔，年度与个人汇总使用全部记录");
        }
        return notes;
    }

    // ---------------------------------------------------------------- 整形

    /** {@code assistant/tools.py:_insider_event}。 */
    private static Map<String, Object> eventView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("change_date", item.get("change_date"));
        out.put("year", item.get("year"));
        out.put("person", item.get("person"));
        out.put("executive", item.get("executive"));
        out.put("position", item.get("position"));
        out.put("relation", item.get("relation"));
        out.put("kind", item.get("kind"));
        out.put("direction", item.get("direction"));
        out.put("shares", item.get("shares"));
        out.put("average_price", DatacenterSupport.round(item.get("average_price"), 2));
        out.put("amount_wan", DatacenterSupport.wan(item.get("amount")));
        out.put("amount_estimated", item.get("amount_estimated") == null ? false : item.get("amount_estimated"));
        out.put("reason", item.get("reason"));
        out.put("change_ratio_pct", DatacenterSupport.round(item.get("change_ratio_pct"), 4));
        out.put("hold_after", item.get("hold_after"));
        out.put("begin_hold", item.get("begin_hold"));
        out.put("end_hold", item.get("end_hold"));
        out.put("hold_type", item.get("hold_type"));
        return out;
    }

    /** {@code assistant/tools.py:_insider_year}。 */
    private static Map<String, Object> yearView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", item.get("year"));
        out.put("events", item.get("events"));
        out.put("buy_events", item.get("buy_events"));
        out.put("sell_events", item.get("sell_events"));
        out.put("buy_shares", item.get("buy_shares"));
        out.put("sell_shares", item.get("sell_shares"));
        out.put("net_shares", item.get("net_shares"));
        out.put("buy_amount_wan", DatacenterSupport.wan(item.get("buy_amount")));
        out.put("sell_amount_wan", DatacenterSupport.wan(item.get("sell_amount")));
        out.put("net_amount_wan", DatacenterSupport.wan(item.get("net_amount")));
        out.put("missing_amount_events", item.get("missing_amount_events"));
        out.put("amount_complete", item.get("amount_complete") == null ? true : item.get("amount_complete"));
        return out;
    }

    /** {@code assistant/tools.py:_insider_person}。 */
    private static Map<String, Object> personView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("person", item.get("person"));
        out.put("executive", item.get("executive"));
        out.put("position", item.get("position"));
        out.put("relation", item.get("relation"));
        out.put("kind", item.get("kind"));
        out.put("events", item.get("events"));
        out.put("buy_shares", item.get("buy_shares"));
        out.put("sell_shares", item.get("sell_shares"));
        out.put("net_shares", item.get("net_shares"));
        out.put("buy_amount_wan", DatacenterSupport.wan(item.get("buy_amount")));
        out.put("sell_amount_wan", DatacenterSupport.wan(item.get("sell_amount")));
        out.put("net_amount_wan", DatacenterSupport.wan(item.get("net_amount")));
        out.put("missing_amount_events", item.get("missing_amount_events"));
        out.put("amount_complete", item.get("amount_complete") == null ? true : item.get("amount_complete"));
        out.put("first_date", item.get("first_date"));
        out.put("latest_date", item.get("latest_date"));
        out.put("latest_hold", item.get("latest_hold"));
        out.put("latest_price", DatacenterSupport.round(item.get("latest_price"), 2));
        return out;
    }

    /** {@code assistant/tools.py:_insider_counts}。 */
    private static Map<String, Object> countsView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", item.getOrDefault("events", 0));
        out.put("buy_events", item.getOrDefault("buy_events", 0));
        out.put("sell_events", item.getOrDefault("sell_events", 0));
        out.put("people", item.getOrDefault("people", 0));
        out.put("insiders", item.getOrDefault("insiders", 0));
        out.put("plans", item.getOrDefault("plans", 0));
        out.put("plan_events", item.getOrDefault("plan_events", 0));
        out.put("buy_shares", item.getOrDefault("buy_shares", 0));
        out.put("sell_shares", item.getOrDefault("sell_shares", 0));
        out.put("net_shares", item.getOrDefault("net_shares", 0));
        out.put("buy_amount_wan", DatacenterSupport.wan(item.get("buy_amount")));
        out.put("sell_amount_wan", DatacenterSupport.wan(item.get("sell_amount")));
        out.put("net_amount_wan", DatacenterSupport.wan(item.get("net_amount")));
        out.put("missing_amount_events", item.getOrDefault("missing_amount_events", 0));
        out.put("estimated_amount_events", item.getOrDefault("estimated_amount_events", 0));
        out.put("latest_date", item.get("latest_date"));
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    /** {@code _dedupe}：同一披露编号只留一条，持股计划按日期、股数、金额合并。 */
    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> events) {
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (Map<String, Object> event : events) {
            if (event != null && DatacenterSupport.text(event.get("change_date")) != null) {
                ordered.add(event);
            }
        }
        ordered.sort(
                (left, right) ->
                        DatacenterSupport.text(right.get("change_date"))
                                .compareTo(DatacenterSupport.text(left.get("change_date"))));

        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> event : ordered) {
            String key = eventKey(event);
            Map<String, Object> current = byKey.get(key);
            if (current == null || completeness(event) > completeness(current)) {
                byKey.put(key, event);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>(byKey.values());
        result.sort(
                java.util.Comparator
                        .comparing(
                                (Map<String, Object> item) ->
                                        DatacenterSupport.text(item.get("change_date")))
                        .thenComparing(item -> DatacenterSupport.text(item.get("person")))
                        .reversed());
        return result;
    }

    private static String eventKey(Map<String, Object> event) {
        if ("plan".equals(event.get("kind"))) {
            // 同一笔持股计划会按归属高管重复出现，GGEID 不同但交易相同
            return "plan|"
                    + event.get("change_date")
                    + "|"
                    + event.get("person")
                    + "|"
                    + event.get("shares")
                    + "|"
                    + event.get("amount")
                    + "|"
                    + event.get("reason");
        }
        String key = DatacenterSupport.text(event.get("key"));
        if (key != null) {
            return "id|" + key;
        }
        return "row|"
                + event.get("change_date")
                + "|"
                + event.get("person")
                + "|"
                + event.get("executive")
                + "|"
                + event.get("shares")
                + "|"
                + event.get("amount")
                + "|"
                + event.get("reason")
                + "|"
                + event.get("relation");
    }

    private static int completeness(Map<String, Object> event) {
        int count = 0;
        for (String key : List.of("amount", "average_price", "hold_after", "begin_hold", "end_hold")) {
            if (event.get(key) != null) {
                count++;
            }
        }
        return count;
    }

    private static long shares(Map<String, Object> event) {
        Double value = DatacenterSupport.number(event.get("shares"));
        return value == null ? 0 : (long) (double) value;
    }

    /** {@code _shares}：亿股 / 万股 / 股。 */
    private static String sharesText(long value) {
        long absolute = Math.abs(value);
        if (absolute >= 100_000_000L) {
            return String.format("%.2f亿股", absolute / 1e8);
        }
        if (absolute >= 10_000L) {
            return String.format("%.2f万股", absolute / 1e4);
        }
        return absolute + "股";
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
