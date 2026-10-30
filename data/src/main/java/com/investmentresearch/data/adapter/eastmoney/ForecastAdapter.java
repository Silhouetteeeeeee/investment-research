package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ForecastPort;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** 东财业绩预告，照抄 {@code stock/forecasts.py} 与 {@code assistant/tools.py:_earnings_forecasts}。 */
@Repository
public class ForecastAdapter implements ForecastPort {
    static final String REPORT_NAME = "RPT_PUBLIC_OP_NEWPREDICT";
    static final String PAGE_URL = "https://data.eastmoney.com/bbsj/yjyg.html?code=%s";
    static final double MAX_AGE_HOURS = 24;
    static final int MAX_EVENTS = 30;
    static final Map<String, String> FINANCE_LABELS =
            Map.of(
                    "001", "净利润",
                    "002", "利润总额",
                    "003", "每股收益",
                    "004", "归母净利润",
                    "005", "扣非净利润",
                    "006", "营业收入");
    static final List<String> FINANCE_ORDER = List.of("004", "005", "003", "006", "001", "002");
    static final List<String> PER_SHARE_CODES = List.of("003");

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public ForecastAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(store, stockMapper, upstream, endpoint, Clock.systemDefaultZone());
    }

    ForecastAdapter(
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
    public ToolResult forecasts(String rawCode, int years) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.FORECASTS,
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
                group(DatacenterSupport.plainList(ensured.data().path("events")));

        LocalDate today = LocalDate.parse(DatacenterSupport.today(clock));
        String start = (today.getYear() - years + 1) + "-01-01";
        String end = today.toString();
        List<Map<String, Object>> window = new ArrayList<>();
        for (Map<String, Object> event : all) {
            String noticeDate = String.valueOf(event.get("notice_date"));
            if (start.compareTo(noticeDate) <= 0 && noticeDate.compareTo(end) <= 0) {
                window.add(event);
            }
        }
        Map<String, Object> latest = all.isEmpty() ? null : all.get(0);
        String asOf = null;
        for (Map<String, Object> event : all) {
            String noticeDate = String.valueOf(event.get("notice_date"));
            if (asOf == null || noticeDate.compareTo(asOf) > 0) {
                asOf = noticeDate;
            }
        }

        String name = stockMapper.selectNameByCode(code);
        if ((name == null || name.isEmpty()) && latest != null) {
            name = DatacenterSupport.text(latest.get("security_name"));
        }

        List<Map<String, Object>> events = eventViews(window.subList(0, Math.min(MAX_EVENTS, window.size())));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("window_start", start);
        data.put("window_end", end);
        data.put("years", years);
        data.put("has_forecasts", !all.isEmpty());
        data.put("latest", latest == null ? null : eventView(latest));
        data.put("events", events);
        data.put("annual", annual(window));
        data.put("type_counts", typeCounts(window));
        data.put("events_truncated", Math.max(0, window.size() - MAX_EVENTS));
        data.put("counts", countsView(counts(window, latest)));
        data.put("notes", notes(all, window, latest, years));
        data.put("stale", ensured.stale());

        String shownAsOf = asOf == null ? "" : asOf;
        String shownName = name == null || name.isEmpty() ? code : name;
        Source source =
                new Source(
                        shownAsOf.isEmpty()
                                ? "forecasts:" + code
                                : "forecasts:" + code + ":" + shownAsOf,
                        "业绩预告",
                        (shownName + " 业绩预告 " + shownAsOf).strip(),
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
                        "NOTICE_DATE:-1",
                        100,
                        "业绩预告");
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> metric = normalize(row);
            if (metric != null) {
                metrics.add(metric);
            }
        }
        ObjectNode payload = EastmoneySupport.MAPPER.createObjectNode();
        payload.put("code", code);
        ArrayNode array = payload.putArray("events");
        for (Map<String, Object> metric : metrics) {
            array.add(EastmoneySupport.MAPPER.valueToTree(metric));
        }
        payload.put("count", array.size());
        return payload;
    }

    /** {@code stock/forecasts.py:_normalize_row}。 */
    private static Map<String, Object> normalize(JsonNode row) {
        String reportDate = date(row.get("REPORT_DATE"));
        String noticeDate = date(row.get("NOTICE_DATE"));
        String financeCode = EastmoneySupport.text(row.get("PREDICT_FINANCE_CODE"));
        if (reportDate == null || noticeDate == null || financeCode == null) {
            return null;
        }
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("report_date", reportDate);
        metric.put("notice_date", noticeDate);
        metric.put("year", Integer.parseInt(reportDate.substring(0, 4)));
        metric.put("finance_code", financeCode);
        String label = FINANCE_LABELS.get(financeCode);
        if (label == null) {
            label = EastmoneySupport.text(row.get("PREDICT_FINANCE"));
            if (label == null) {
                label = financeCode;
            }
        }
        metric.put("label", label);
        metric.put("unit", PER_SHARE_CODES.contains(financeCode) ? "per_share" : "yuan");
        metric.put("lower", EastmoneySupport.num(row.get("PREDICT_AMT_LOWER")));
        metric.put("upper", EastmoneySupport.num(row.get("PREDICT_AMT_UPPER")));
        metric.put("yoy_low", EastmoneySupport.num(row.get("ADD_AMP_LOWER")));
        metric.put("yoy_high", EastmoneySupport.num(row.get("ADD_AMP_UPPER")));
        metric.put("type", EastmoneySupport.text(row.get("PREDICT_TYPE")));
        metric.put("prior", EastmoneySupport.num(row.get("PREYEAR_SAME_PERIOD")));
        metric.put("mid", EastmoneySupport.num(row.get("FORECAST_JZ")));
        metric.put("reason", EastmoneySupport.text(row.get("CHANGE_REASON_EXPLAIN")));
        metric.put("security_name", EastmoneySupport.text(row.get("SECURITY_NAME_ABBR")));
        metric.put("key", reportDate + "|" + noticeDate + "|" + financeCode);
        return metric;
    }

    // ---------------------------------------------------------------- 归并

    /** {@code stock/forecasts.py:_group}。 */
    private static List<Map<String, Object>> group(List<Map<String, Object>> metrics) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> metric : metrics) {
            String reportDate = DatacenterSupport.text(metric.get("report_date"));
            String noticeDate = DatacenterSupport.text(metric.get("notice_date"));
            String financeCode = DatacenterSupport.text(metric.get("finance_code"));
            if (reportDate == null || noticeDate == null || financeCode == null) {
                continue;
            }
            String key = reportDate + "|" + noticeDate;
            Map<String, Object> event = groups.get(key);
            if (event == null) {
                event = new LinkedHashMap<>();
                event.put("report_date", reportDate);
                event.put("notice_date", noticeDate);
                event.put("year", Integer.parseInt(reportDate.substring(0, 4)));
                event.put("type", null);
                event.put("reason", null);
                event.put("security_name", null);
                event.put("metrics", new LinkedHashMap<String, Map<String, Object>>());
                groups.put(key, event);
            }
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> byCode =
                    (Map<String, Map<String, Object>>) event.get("metrics");
            byCode.put(financeCode, metric);
            if (event.get("reason") == null && metric.get("reason") != null) {
                event.put("reason", metric.get("reason"));
            }
            if (event.get("security_name") == null && metric.get("security_name") != null) {
                event.put("security_name", metric.get("security_name"));
            }
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> event : groups.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> byCode =
                    (Map<String, Map<String, Object>>) event.get("metrics");
            List<Map<String, Object>> ordered = orderedMetrics(byCode);
            event.put("metrics", ordered);
            event.put("type", eventType(ordered));
            events.add(event);
        }

        Map<String, Map<String, Object>> byReport = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String reportDate = String.valueOf(event.get("report_date"));
            Map<String, Object> current = byReport.get(reportDate);
            if (current == null || eventStamp(event).compareTo(eventStamp(current)) > 0) {
                byReport.put(reportDate, event);
            }
        }
        List<Map<String, Object>> ordered = new ArrayList<>(byReport.values());
        ordered.sort(
                (left, right) ->
                        String.valueOf(right.get("report_date"))
                                .compareTo(String.valueOf(left.get("report_date"))));
        return ordered;
    }

    private static List<Map<String, Object>> orderedMetrics(
            Map<String, Map<String, Object>> byCode) {
        List<Map<String, Object>> ordered = new ArrayList<>();
        for (String code : FINANCE_ORDER) {
            Map<String, Object> metric = byCode.get(code);
            if (metric != null) {
                ordered.add(metric);
            }
        }
        for (String code : byCode.keySet()) {
            if (!FINANCE_ORDER.contains(code)) {
                ordered.add(byCode.get(code));
            }
        }
        return ordered;
    }

    private static String eventType(List<Map<String, Object>> metrics) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> metric : metrics) {
            byCode.put(String.valueOf(metric.get("finance_code")), metric);
        }
        for (String code : List.of("004", "001", "005")) {
            Map<String, Object> metric = byCode.get(code);
            if (metric != null && DatacenterSupport.text(metric.get("type")) != null) {
                return DatacenterSupport.text(metric.get("type"));
            }
        }
        for (Map<String, Object> metric : metrics) {
            if (DatacenterSupport.text(metric.get("type")) != null) {
                return DatacenterSupport.text(metric.get("type"));
            }
        }
        return null;
    }

    private static String eventStamp(Map<String, Object> event) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) event.get("metrics");
        int counted = 0;
        for (Map<String, Object> metric : metrics) {
            if (metric.get("lower") != null || metric.get("upper") != null) {
                counted++;
            }
        }
        return String.valueOf(event.get("notice_date")) + "#" + String.format("%02d", counted);
    }

    private static List<Map<String, Object>> annual(List<Map<String, Object>> events) {
        Map<Integer, List<Map<String, Object>>> byYear = new TreeMap<>();
        for (Map<String, Object> event : events) {
            byYear.computeIfAbsent(DatacenterSupport.intOf(event.get("year")), year -> new ArrayList<>())
                    .add(event);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byYear.entrySet()) {
            List<String> dates = new ArrayList<>();
            for (Map<String, Object> event : entry.getValue()) {
                String reportDate = String.valueOf(event.get("report_date"));
                if (!dates.contains(reportDate)) {
                    dates.add(reportDate);
                }
            }
            dates.sort((left, right) -> right.compareTo(left));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", entry.getKey());
            row.put("events", entry.getValue().size());
            row.put("report_dates", dates);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> typeCounts(List<Map<String, Object>> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String type = DatacenterSupport.text(event.get("type"));
            if (type != null) {
                counts.merge(type, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", entry.getKey());
            row.put("events", entry.getValue());
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
                    return String.valueOf(left.get("type"))
                            .compareTo(String.valueOf(right.get("type")));
                });
        return rows;
    }

    private static Map<String, Object> counts(
            List<Map<String, Object>> window, Map<String, Object> latest) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("events", window.size());
        counts.put("latest_report_date", null);
        counts.put("latest_notice_date", null);
        counts.put("latest_type", null);
        counts.put("latest_net_profit_low", null);
        counts.put("latest_net_profit_high", null);
        counts.put("latest_net_profit_yoy_low", null);
        counts.put("latest_net_profit_yoy_high", null);
        counts.put("latest_eps_low", null);
        counts.put("latest_eps_high", null);
        counts.put("latest_revenue_low", null);
        counts.put("latest_revenue_high", null);
        if (latest == null) {
            counts.put("events", 0);
            return counts;
        }
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) latest.get("metrics");
        for (Map<String, Object> metric : metrics) {
            byCode.put(String.valueOf(metric.get("finance_code")), metric);
        }
        Map<String, Object> net = byCode.getOrDefault("004", Map.of());
        Map<String, Object> eps = byCode.getOrDefault("003", Map.of());
        Map<String, Object> revenue = byCode.getOrDefault("006", Map.of());
        counts.put("latest_report_date", latest.get("report_date"));
        counts.put("latest_notice_date", latest.get("notice_date"));
        counts.put("latest_type", latest.get("type"));
        counts.put("latest_net_profit_low", net.get("lower"));
        counts.put("latest_net_profit_high", net.get("upper"));
        counts.put("latest_net_profit_yoy_low", net.get("yoy_low"));
        counts.put("latest_net_profit_yoy_high", net.get("yoy_high"));
        counts.put("latest_eps_low", eps.get("lower"));
        counts.put("latest_eps_high", eps.get("upper"));
        counts.put("latest_revenue_low", revenue.get("lower"));
        counts.put("latest_revenue_high", revenue.get("upper"));
        return counts;
    }

    private static List<String> notes(
            List<Map<String, Object>> all,
            List<Map<String, Object>> window,
            Map<String, Object> latest,
            int years) {
        List<String> notes = new ArrayList<>();
        if (all.isEmpty() || latest == null) {
            notes.add("没有取到业绩预告，可能公司未披露或数据源未收录");
            return notes;
        }
        if (window.isEmpty()) {
            notes.add("近" + years + "年没有已披露的业绩预告；最近一次为" + latest.get("report_date"));
            notes.add("预告可能被修订，最终以定期报告为准");
            return notes;
        }
        notes.add("业绩预告是公司初步测算，可能被修订，最终以定期报告为准");
        notes.add("预告多为区间或约数，不是确定值，同比基数受非经常性损益影响");
        notes.add("只含已披露预告，未覆盖未发布预告的期间");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) latest.get("metrics");
        for (Map<String, Object> metric : metrics) {
            if (!"004".equals(metric.get("finance_code"))) {
                continue;
            }
            Double prior = DatacenterSupport.number(metric.get("prior"));
            if (prior != null && prior < 0 && metric.get("yoy_low") != null) {
                notes.add("亏损公司的同比增速方向可能失真");
            }
            break;
        }
        return notes;
    }

    // ---------------------------------------------------------------- 整形

    private static List<Map<String, Object>> eventViews(List<Map<String, Object>> events) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map<String, Object> event : events) {
            views.add(eventView(event));
        }
        return views;
    }

    /** {@code assistant/tools.py:_forecast_event}。 */
    private static Map<String, Object> eventView(Map<String, Object> item) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics =
                (List<Map<String, Object>>) item.getOrDefault("metrics", List.of());
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map<String, Object> metric : metrics) {
            views.add(metricView(metric));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report_date", item.get("report_date"));
        out.put("notice_date", item.get("notice_date"));
        out.put("year", item.get("year"));
        out.put("type", item.get("type"));
        out.put("metrics", views);
        out.put("reason", clip(DatacenterSupport.text(item.get("reason")), 200));
        return out;
    }

    /** {@code assistant/tools.py:_forecast_metric}。 */
    private static Map<String, Object> metricView(Map<String, Object> item) {
        boolean perShare = "per_share".equals(item.get("unit"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("finance_code", item.get("finance_code"));
        out.put("label", item.get("label"));
        out.put("unit", perShare ? "元/股" : "亿元");
        out.put("type", item.get("type"));
        out.put(
                "lower",
                perShare
                        ? DatacenterSupport.round(item.get("lower"), 4)
                        : DatacenterSupport.yi(item.get("lower")));
        out.put(
                "upper",
                perShare
                        ? DatacenterSupport.round(item.get("upper"), 4)
                        : DatacenterSupport.yi(item.get("upper")));
        out.put("yoy_low", DatacenterSupport.round(item.get("yoy_low"), 2));
        out.put("yoy_high", DatacenterSupport.round(item.get("yoy_high"), 2));
        out.put(
                "prior",
                perShare
                        ? DatacenterSupport.round(item.get("prior"), 4)
                        : DatacenterSupport.yi(item.get("prior")));
        out.put(
                "mid",
                perShare
                        ? DatacenterSupport.round(item.get("mid"), 4)
                        : DatacenterSupport.yi(item.get("mid")));
        return out;
    }

    /** {@code assistant/tools.py:_forecast_counts}。 */
    private static Map<String, Object> countsView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", item.getOrDefault("events", 0));
        out.put("latest_report_date", item.get("latest_report_date"));
        out.put("latest_notice_date", item.get("latest_notice_date"));
        out.put("latest_type", item.get("latest_type"));
        out.put("latest_net_profit_low_yi", DatacenterSupport.yi(item.get("latest_net_profit_low")));
        out.put("latest_net_profit_high_yi", DatacenterSupport.yi(item.get("latest_net_profit_high")));
        out.put(
                "latest_net_profit_yoy_low",
                DatacenterSupport.round(item.get("latest_net_profit_yoy_low"), 2));
        out.put(
                "latest_net_profit_yoy_high",
                DatacenterSupport.round(item.get("latest_net_profit_yoy_high"), 2));
        out.put("latest_eps_low", DatacenterSupport.round(item.get("latest_eps_low"), 4));
        out.put("latest_eps_high", DatacenterSupport.round(item.get("latest_eps_high"), 4));
        out.put("latest_revenue_low_yi", DatacenterSupport.yi(item.get("latest_revenue_low")));
        out.put("latest_revenue_high_yi", DatacenterSupport.yi(item.get("latest_revenue_high")));
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    private static String clip(String text, int maxChars) {
        String value = text == null ? "" : text.strip();
        if (value.length() <= maxChars) {
            return value;
        }
        String head = value.substring(0, maxChars).replaceAll("[，、；： ]+$", "");
        return head + "…";
    }

    private static String date(JsonNode node) {
        String text = EastmoneySupport.text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }
}
