package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.PledgePort;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** 东财股权质押，照抄 {@code stock/pledges.py} 与 {@code assistant/tools.py:_pledges}。 */
@Repository
public class PledgeAdapter implements PledgePort {
    private static final Logger LOG = LoggerFactory.getLogger(PledgeAdapter.class);
    static final String REPORT_NAME = "RPT_CSDC_LIST";
    static final String PAGE_URL = "https://data.eastmoney.com/gpzy/detail/%s.html";
    static final double MAX_AGE_HOURS = 24;
    static final double HIGH_PLEDGE_PCT = 50.0;
    static final double WATCH_PLEDGE_PCT = 30.0;
    static final double DIRECTION_THRESHOLD_PP = 1.0;
    static final int MAX_SERIES_MONTHS = 60;

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public PledgeAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(store, stockMapper, upstream, endpoint, Clock.systemDefaultZone());
    }

    PledgeAdapter(
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
    public ToolResult pledges(String rawCode, int years) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.PLEDGES,
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
        List<Map<String, Object>> all =
                dedupe(DatacenterSupport.plainList(ensured.data().path("events")));

        LocalDate today = LocalDate.parse(DatacenterSupport.today(clock));
        String end = today.toString();
        String start = (today.getYear() - years + 1) + "-01-01";
        List<Map<String, Object>> window = new ArrayList<>();
        for (Map<String, Object> event : all) {
            String tradeDate = String.valueOf(event.get("trade_date"));
            if (start.compareTo(tradeDate) <= 0 && tradeDate.compareTo(end) <= 0) {
                window.add(event);
            }
        }
        Map<String, Object> latest = all.isEmpty() ? null : all.get(0);
        List<Map<String, Object>> series = monthly(window);
        List<Map<String, Object>> annual = annual(window);
        Map<String, Object> counts = counts(window, latest, today);
        List<String> notes = notes(all, window, latest, counts, years);
        String asOf = latest == null ? null : String.valueOf(latest.get("trade_date"));

        String name = stockMapper.selectNameByCode(code);
        if ((name == null || name.isEmpty()) && latest != null) {
            name = DatacenterSupport.text(latest.get("security_name"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("window_start", start);
        data.put("window_end", end);
        data.put("years", years);
        data.put("has_pledges", !all.isEmpty());
        data.put("latest", latest == null ? null : pointView(latest));
        List<Map<String, Object>> seriesView = new ArrayList<>();
        for (Map<String, Object> row : series) {
            seriesView.add(pointView(row));
        }
        data.put("series", seriesView);
        List<Map<String, Object>> annualView = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            annualView.add(yearView(row));
        }
        data.put("annual", annualView);
        data.put("counts", countsView(counts));
        data.put("notes", notes);
        data.put("stale", ensured.stale());

        String shownAsOf = asOf == null ? "" : asOf;
        String shownName = name == null || name.isEmpty() ? code : name;
        Source source =
                new Source(
                        shownAsOf.isEmpty() ? "pledges:" + code : "pledges:" + code + ":" + shownAsOf,
                        "股权质押",
                        (shownName + " 股权质押 " + shownAsOf).strip(),
                        shownAsOf,
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
                        "TRADE_DATE:-1",
                        500,
                        20,
                        "股权质押");
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

    /** {@code stock/pledges.py:_normalize_row}。 */
    private static Map<String, Object> normalize(JsonNode row) {
        String tradeDate = date(row.get("TRADE_DATE"));
        if (tradeDate == null) {
            return null;
        }
        Double ratio = EastmoneySupport.num(row.get("PLEDGE_RATIO"));
        Integer balance = scaledInt(row.get("REPURCHASE_BALANCE"), 10_000);
        if (ratio == null && balance == null) {
            return null;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("trade_date", tradeDate);
        event.put("year", Integer.parseInt(tradeDate.substring(0, 4)));
        event.put("ratio_pct", ratio);
        event.put("balance_shares", balance);
        event.put("market_cap", scaledNumber(row.get("PLEDGE_MARKET_CAP"), 10_000));
        event.put("deal_num", intOrNull(row.get("PLEDGE_DEAL_NUM")));
        event.put(
                "unlimited_shares", scaledInt(row.get("REPURCHASE_UNLIMITED_BALANCE"), 10_000));
        event.put("limited_shares", scaledInt(row.get("REPURCHASE_LIMITED_BALANCE"), 10_000));
        event.put("industry", EastmoneySupport.text(row.get("INDUSTRY")));
        event.put("y1_change_pct", EastmoneySupport.num(row.get("Y1_CLOSE_ADJCHRATE")));
        event.put("security_name", EastmoneySupport.text(row.get("SECURITY_NAME_ABBR")));
        event.put("key", tradeDate);
        return event;
    }

    // ---------------------------------------------------------------- 计算

    private static List<Map<String, Object>> monthly(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> byMonth = new TreeMap<>();
        for (Map<String, Object> event : events) {
            String month = String.valueOf(event.get("trade_date")).substring(0, 7);
            byMonth.putIfAbsent(month, event);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> months = new ArrayList<>(byMonth.keySet());
        for (String month : months.subList(Math.max(0, months.size() - MAX_SERIES_MONTHS), months.size())) {
            Map<String, Object> event = byMonth.get(month);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", month);
            row.put("trade_date", event.get("trade_date"));
            row.put("ratio_pct", event.get("ratio_pct"));
            row.put("balance_shares", event.get("balance_shares"));
            row.put("market_cap", event.get("market_cap"));
            row.put("deal_num", event.get("deal_num"));
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> annual(List<Map<String, Object>> events) {
        Map<Integer, List<Map<String, Object>>> byYear = new TreeMap<>();
        for (Map<String, Object> event : events) {
            byYear.computeIfAbsent(DatacenterSupport.intOf(event.get("year")), year -> new ArrayList<>())
                    .add(event);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byYear.entrySet()) {
            List<Map<String, Object>> group = new ArrayList<>(entry.getValue());
            group.sort((left, right) -> String.valueOf(left.get("trade_date")).compareTo(String.valueOf(right.get("trade_date"))));
            Map<String, Object> last = group.get(group.size() - 1);
            List<Double> ratios = new ArrayList<>();
            for (Map<String, Object> item : group) {
                Double ratio = DatacenterSupport.number(item.get("ratio_pct"));
                if (ratio != null) {
                    ratios.add(ratio);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", entry.getKey());
            row.put("points", group.size());
            row.put("latest_date", last.get("trade_date"));
            row.put("latest_ratio_pct", last.get("ratio_pct"));
            row.put("balance_shares", last.get("balance_shares"));
            row.put("market_cap", last.get("market_cap"));
            row.put("peak_ratio_pct", ratios.isEmpty() ? null : max(ratios));
            row.put("low_ratio_pct", ratios.isEmpty() ? null : min(ratios));
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> counts(
            List<Map<String, Object>> window, Map<String, Object> latest, LocalDate today) {
        Map<String, Object> counts = emptyCounts();
        if (latest == null) {
            return counts;
        }
        List<Double> ratios = new ArrayList<>();
        for (Map<String, Object> event : window) {
            Double ratio = DatacenterSupport.number(event.get("ratio_pct"));
            if (ratio != null) {
                ratios.add(ratio);
            }
        }
        Map<String, Object> peak = extreme(window, true);
        Map<String, Object> low = extreme(window, false);

        String target = today.minusDays(365).toString();
        Map<String, Object> yearAgo = null;
        for (Map<String, Object> event : window) {
            String tradeDate = String.valueOf(event.get("trade_date"));
            if (tradeDate.compareTo(target) <= 0
                    && (yearAgo == null
                            || tradeDate.compareTo(String.valueOf(yearAgo.get("trade_date"))) > 0)) {
                yearAgo = event;
            }
        }

        Double latestRatio = DatacenterSupport.number(latest.get("ratio_pct"));
        Double yearAgoRatio = yearAgo == null ? null : DatacenterSupport.number(yearAgo.get("ratio_pct"));
        Double change = null;
        String direction = null;
        if (yearAgo != null && latestRatio != null && yearAgoRatio != null) {
            change = (double) DatacenterSupport.round(latestRatio - yearAgoRatio, 2);
            if (change > DIRECTION_THRESHOLD_PP) {
                direction = "上升";
            } else if (change < -DIRECTION_THRESHOLD_PP) {
                direction = "下降";
            } else {
                direction = "持平";
            }
        }

        counts.put("points", window.size());
        counts.put("latest_date", latest.get("trade_date"));
        counts.put("latest_ratio_pct", latestRatio);
        counts.put("latest_balance_shares", latest.get("balance_shares"));
        counts.put("latest_market_cap", latest.get("market_cap"));
        counts.put("latest_deal_num", latest.get("deal_num"));
        counts.put("latest_unlimited_shares", latest.get("unlimited_shares"));
        counts.put("latest_limited_shares", latest.get("limited_shares"));
        counts.put("latest_industry", latest.get("industry"));
        counts.put("year_ago_date", yearAgo == null ? null : yearAgo.get("trade_date"));
        counts.put("year_ago_ratio_pct", yearAgoRatio);
        counts.put("change_pp", change);
        counts.put("direction", direction);
        counts.put("peak_ratio_pct", peak == null ? null : peak.get("ratio_pct"));
        counts.put("peak_date", peak == null ? null : peak.get("trade_date"));
        counts.put("low_ratio_pct", low == null ? null : low.get("ratio_pct"));
        counts.put("low_date", low == null ? null : low.get("trade_date"));
        counts.put(
                "avg_ratio_pct",
                ratios.isEmpty()
                        ? null
                        : (double)
                                DatacenterSupport.round(
                                        ratios.stream().mapToDouble(Double::doubleValue).sum()
                                                / ratios.size(),
                                        2));
        counts.put("high_pledge", latestRatio != null && latestRatio >= HIGH_PLEDGE_PCT);
        counts.put(
                "watch_pledge",
                latestRatio != null
                        && latestRatio >= WATCH_PLEDGE_PCT
                        && latestRatio < HIGH_PLEDGE_PCT);
        return counts;
    }

    /** Python {@code max}/{@code min} 的键：缺比例的按 ∓inf 处理，其次看日期。 */
    private static Map<String, Object> extreme(List<Map<String, Object>> window, boolean highest) {
        Map<String, Object> best = null;
        Double bestRatio = null;
        for (Map<String, Object> event : window) {
            Double ratio = DatacenterSupport.number(event.get("ratio_pct"));
            double value = ratio == null ? (highest ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY) : ratio;
            if (best == null) {
                best = event;
                bestRatio = value;
                continue;
            }
            int compared = Double.compare(value, bestRatio);
            if (highest ? compared > 0 : compared < 0) {
                best = event;
                bestRatio = value;
            } else if (compared == 0
                    && (highest ? 1 : -1)
                                    * String.valueOf(event.get("trade_date"))
                                            .compareTo(String.valueOf(best.get("trade_date")))
                            > 0) {
                best = event;
                bestRatio = value;
            }
        }
        return best;
    }

    private static Map<String, Object> emptyCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "points", "latest_date", "latest_ratio_pct", "latest_balance_shares",
                        "latest_market_cap", "latest_deal_num", "latest_unlimited_shares",
                        "latest_limited_shares", "latest_industry", "year_ago_date",
                        "year_ago_ratio_pct", "change_pp", "direction", "peak_ratio_pct",
                        "peak_date", "low_ratio_pct", "low_date", "avg_ratio_pct")) {
            counts.put(key, null);
        }
        counts.put("points", 0);
        counts.put("high_pledge", false);
        counts.put("watch_pledge", false);
        return counts;
    }

    private static List<String> notes(
            List<Map<String, Object>> all,
            List<Map<String, Object>> window,
            Map<String, Object> latest,
            Map<String, Object> counts,
            int years) {
        List<String> notes = new ArrayList<>();
        if (all.isEmpty() || latest == null) {
            notes.add("没有取到股权质押记录，可能公司没有质押或数据源未收录");
            return notes;
        }
        if (window.isEmpty()) {
            notes.add("近" + years + "年没有披露的质押记录；最近一次为" + latest.get("trade_date"));
            notes.add("数据按周披露，早期记录可能已停止更新");
            return notes;
        }
        notes.add("质押比例=质押股份÷总股本，按周披露");
        notes.add("质押比例高说明股东资金压力或控制权风险，不等于已经违约");
        notes.add("质押市值按披露参考价估算，会随股价变化");
        Long age = daysSince(String.valueOf(latest.get("trade_date")));
        if (age != null && age > 30) {
            notes.add("最新一期距今天" + age + "天，可能未更新");
        }
        if (DatacenterSupport.flag(counts, "high_pledge")) {
            notes.add("最新质押比例超过50%，需要重点看公告与股东资金安排");
        }
        return notes;
    }

    // ---------------------------------------------------------------- 整形

    /** {@code assistant/tools.py:_pledge_point}。 */
    private static Map<String, Object> pointView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", item.get("trade_date"));
        out.put("year", item.get("year"));
        out.put("month", item.get("month"));
        out.put("ratio_pct", DatacenterSupport.round(item.get("ratio_pct"), 2));
        out.put("balance_shares_wan", DatacenterSupport.wan(item.get("balance_shares")));
        out.put("market_cap_yi", DatacenterSupport.yi(item.get("market_cap")));
        out.put("deal_num", item.get("deal_num"));
        out.put("unlimited_shares_wan", DatacenterSupport.wan(item.get("unlimited_shares")));
        out.put("limited_shares_wan", DatacenterSupport.wan(item.get("limited_shares")));
        out.put("industry", item.get("industry"));
        return out;
    }

    /** {@code assistant/tools.py:_pledge_year}。 */
    private static Map<String, Object> yearView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", item.get("year"));
        out.put("points", item.get("points"));
        out.put("latest_date", item.get("latest_date"));
        out.put("latest_ratio_pct", DatacenterSupport.round(item.get("latest_ratio_pct"), 2));
        out.put("balance_shares_wan", DatacenterSupport.wan(item.get("balance_shares")));
        out.put("market_cap_yi", DatacenterSupport.yi(item.get("market_cap")));
        out.put("peak_ratio_pct", DatacenterSupport.round(item.get("peak_ratio_pct"), 2));
        out.put("low_ratio_pct", DatacenterSupport.round(item.get("low_ratio_pct"), 2));
        return out;
    }

    /** {@code assistant/tools.py:_pledge_counts}。 */
    private static Map<String, Object> countsView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", item.getOrDefault("points", 0));
        out.put("latest_date", item.get("latest_date"));
        out.put("latest_ratio_pct", DatacenterSupport.round(item.get("latest_ratio_pct"), 2));
        out.put(
                "latest_balance_shares_wan",
                DatacenterSupport.wan(item.get("latest_balance_shares")));
        out.put("latest_market_cap_yi", DatacenterSupport.yi(item.get("latest_market_cap")));
        out.put("latest_deal_num", item.get("latest_deal_num"));
        out.put(
                "latest_unlimited_shares_wan",
                DatacenterSupport.wan(item.get("latest_unlimited_shares")));
        out.put(
                "latest_limited_shares_wan",
                DatacenterSupport.wan(item.get("latest_limited_shares")));
        out.put("latest_industry", item.get("latest_industry"));
        out.put("year_ago_date", item.get("year_ago_date"));
        out.put("year_ago_ratio_pct", DatacenterSupport.round(item.get("year_ago_ratio_pct"), 2));
        out.put("change_pp", DatacenterSupport.round(item.get("change_pp"), 2));
        out.put("direction", item.get("direction"));
        out.put("peak_ratio_pct", DatacenterSupport.round(item.get("peak_ratio_pct"), 2));
        out.put("peak_date", item.get("peak_date"));
        out.put("low_ratio_pct", DatacenterSupport.round(item.get("low_ratio_pct"), 2));
        out.put("low_date", item.get("low_date"));
        out.put("avg_ratio_pct", DatacenterSupport.round(item.get("avg_ratio_pct"), 2));
        out.put("high_pledge", item.getOrDefault("high_pledge", false));
        out.put("watch_pledge", item.getOrDefault("watch_pledge", false));
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String tradeDate = DatacenterSupport.text(event.get("trade_date"));
            if (tradeDate == null) {
                continue;
            }
            String key = DatacenterSupport.text(event.get("key"));
            Map<String, Object> current = byKey.get(key == null ? tradeDate : key);
            if (current == null || completeness(event) > completeness(current)) {
                byKey.put(key == null ? tradeDate : key, event);
            }
        }
        List<Map<String, Object>> ordered = new ArrayList<>(byKey.values());
        ordered.sort(
                (left, right) ->
                        String.valueOf(right.get("trade_date"))
                                .compareTo(String.valueOf(left.get("trade_date"))));
        return ordered;
    }

    private static int completeness(Map<String, Object> event) {
        int count = 0;
        for (String key :
                List.of(
                        "ratio_pct", "balance_shares", "market_cap", "deal_num",
                        "unlimited_shares", "limited_shares", "industry")) {
            if (event.get(key) != null) {
                count++;
            }
        }
        return count;
    }

    private static Long daysSince(String value) {
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(value), LocalDate.now());
        } catch (RuntimeException error) {
            LOG.debug("质押日期无法解析：{}", value);
            return null;
        }
    }

    private static Double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
    }

    private static Double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
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
