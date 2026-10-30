package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.DividendPort;
import com.investmentresearch.data.persistence.FinIncomeEntity;
import com.investmentresearch.data.persistence.FinIncomeMapper;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** 东财分红送配，照抄 {@code stock/dividends.py} 与 {@code assistant/tools.py:_dividends}。 */
@Repository
public class DividendAdapter implements DividendPort {
    static final String REPORT_NAME = "RPT_SHAREBONUS_DET";
    static final String PAGE_URL = "https://data.eastmoney.com/yjfp/detail/%s.html";
    static final String SOURCE_LABEL = "东方财富 分红送配";
    static final double MAX_AGE_HOURS = 24;
    static final int SUMMARY_PLAN_CHARS = 40;

    private static final Map<String, String> PERIOD_LABELS =
            Map.of("03", "一季报", "06", "中报", "09", "三季报", "12", "年报");

    private final ResearchCacheStore store;
    private final FinIncomeMapper incomeMapper;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public DividendAdapter(
            ResearchCacheStore store,
            FinIncomeMapper incomeMapper,
            StockMapper stockMapper,
            UpstreamClient upstream,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(store, incomeMapper, stockMapper, upstream, endpoint, Clock.systemDefaultZone());
    }

    DividendAdapter(
            ResearchCacheStore store,
            FinIncomeMapper incomeMapper,
            StockMapper stockMapper,
            UpstreamClient upstream,
            String endpoint,
            Clock clock) {
        this.store = store;
        this.incomeMapper = incomeMapper;
        this.stockMapper = stockMapper;
        this.upstream = upstream;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override
    public ToolResult dividends(String rawCode, int years) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.DIVIDENDS,
                            code,
                            null,
                            MAX_AGE_HOURS,
                            clock,
                            () -> fetch(code));
            return build(code, ensured, Math.min(Math.max(years, 1), 20));
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult build(String code, DatacenterSupport.CacheResult ensured, int years) {
        List<Map<String, Object>> events =
                new ArrayList<>(DatacenterSupport.plainList(ensured.data().path("events")));
        events = dedupe(events);

        List<Map<String, Object>> ordered = new ArrayList<>();
        if (!events.isEmpty()) {
            int newest =
                    events.stream()
                            .mapToInt(event -> intOf(event.get("fiscal_year")))
                            .max()
                            .orElse(0);
            int firstYear = newest - years + 1;
            for (Map<String, Object> event : events) {
                if (intOf(event.get("fiscal_year")) >= firstYear) {
                    ordered.add(event);
                }
            }
        }

        Financials financial = financials(code);
        List<Map<String, Object>> annual = annualRows(ordered, financial.byYear());
        List<Map<String, Object>> historyAnnual =
                events.size() == ordered.size()
                        ? annual
                        : annualRows(events, financial.byYear());
        Map<String, Object> counts =
                counts(ordered, annual, financial.completed(), historyAnnual);
        List<String> notes = notes(ordered, annual, counts);

        String asOf = ordered.isEmpty() ? "" : text(ordered.get(0).get("report_date"));
        boolean hasDividends =
                ordered.stream()
                        .anyMatch(
                                event ->
                                        event.get("cash_per_10") != null
                                                || number(event.get("share_bonus_per_10")) > 0);
        String name = stockMapper.selectNameByCode(code);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf.isEmpty() ? null : asOf);
        data.put("has_dividends", hasDividends);
        data.put("latest", ordered.isEmpty() ? null : eventView(ordered.get(0)));
        List<Map<String, Object>> annualView = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            annualView.add(yearView(row));
        }
        data.put("annual", annualView);
        List<Map<String, Object>> eventsView = new ArrayList<>();
        for (Map<String, Object> event : ordered) {
            eventsView.add(eventView(event));
        }
        data.put("events", eventsView);
        data.put("counts", counts);
        data.put("notes", notes);
        data.put("stale", ensured.stale());

        Source source =
                new Source(
                        asOf.isEmpty() ? "dividends:" + code : "dividends:" + code + ":" + asOf,
                        "分红历史",
                        ((name == null || name.isEmpty() ? code : name) + " 分红 " + asOf).strip(),
                        asOf,
                        ensured.fetchedAt() == null ? "" : ensured.fetchedAt(),
                        PAGE_URL.formatted(code));
        return ToolResult.ok(code, asOf.isEmpty() ? null : asOf, List.of(source), data);
    }

    // ---------------------------------------------------------------- 取数

    private JsonNode fetch(String code) {
        List<JsonNode> rows =
                EastmoneySupport.fetchRows(
                        upstream,
                        endpoint,
                        REPORT_NAME,
                        "(SECURITY_CODE=\"" + code + "\")",
                        "-1:REPORT_DATE",
                        100,
                        "分红");
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

    /** {@code stock/dividends.py:_normalize_row}。 */
    private static Map<String, Object> normalize(JsonNode row) {
        String reportDate = date(row.get("REPORT_DATE"));
        if (reportDate == null) {
            return null;
        }
        String plan = EastmoneySupport.text(row.get("IMPL_PLAN_PROFILE"));
        Double cash = EastmoneySupport.num(row.get("PRETAX_BONUS_RMB"));
        Double bonus = EastmoneySupport.num(row.get("BONUS_RATIO"));
        Double transfer = EastmoneySupport.num(row.get("IT_RATIO"));
        Double shareBonus = EastmoneySupport.num(row.get("BONUS_IT_RATIO"));
        if (shareBonus == null) {
            // 送转合计数缺失时按送股 + 转增相加
            shareBonus = bonus == null ? transfer : transfer == null ? bonus : bonus + transfer;
        }
        if (cash == null && !(shareBonus != null && shareBonus != 0) && plan == null) {
            return null;
        }

        Long totalShares = intOrNull(row.get("TOTAL_SHARES"));
        if (totalShares != null && totalShares <= 0) {
            totalShares = null;
        }
        Double cashTotal =
                cash == null || totalShares == null ? null : cash / 10.0 * totalShares;
        Double dividendRatio = EastmoneySupport.num(row.get("DIVIDENT_RATIO"));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("report_date", reportDate);
        event.put("fiscal_year", Integer.parseInt(reportDate.substring(0, 4)));
        event.put(
                "period_label",
                reportDate.length() >= 7 ? PERIOD_LABELS.get(reportDate.substring(5, 7)) : null);
        event.put("plan", plan);
        event.put("plan_short", shortPlan(plan));
        event.put("status", EastmoneySupport.text(row.get("ASSIGN_PROGRESS")));
        event.put("cash_per_10", cash);
        event.put("cash_per_share", cash == null ? null : cash / 10.0);
        event.put("cash_total", cashTotal);
        event.put("bonus_per_10", bonus);
        event.put("transfer_per_10", transfer);
        event.put("share_bonus_per_10", shareBonus);
        event.put("total_shares", totalShares);
        event.put("yield_pct", dividendRatio == null ? null : dividendRatio * 100.0);
        event.put("eps", EastmoneySupport.num(row.get("BASIC_EPS")));
        event.put("plan_notice_date", date(row.get("PLAN_NOTICE_DATE")));
        event.put("notice_date", date(row.get("NOTICE_DATE")));
        event.put("record_date", date(row.get("EQUITY_RECORD_DATE")));
        event.put("ex_date", date(row.get("EX_DIVIDEND_DATE")));
        return event;
    }

    // ---------------------------------------------------------------- 计算

    private record Financials(
            Map<Integer, Object[]> byYear, java.util.Set<Integer> completed) {}

    /** {@code _annual_financials}：只认年报（-12-31）的归母净利润。 */
    private Financials financials(String code) {
        Map<Integer, Object[]> byYear = new TreeMap<>();
        java.util.Set<Integer> completed = new java.util.HashSet<>();
        List<FinIncomeEntity> rows =
                incomeMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<FinIncomeEntity>query()
                                .eq("code", code));
        for (FinIncomeEntity row : rows) {
            String reportDate = row.getReportDate();
            if (reportDate == null || !reportDate.endsWith("-12-31") || reportDate.length() < 4) {
                continue;
            }
            int year;
            try {
                year = Integer.parseInt(reportDate.substring(0, 4));
            } catch (NumberFormatException error) {
                continue;
            }
            completed.add(year);
            Double profit = row.getNetProfitAttributable();
            String basis = "归母净利润";
            if (profit == null) {
                profit = row.getNetProfit();
                basis = "净利润";
            }
            if (profit != null) {
                byYear.put(year, new Object[] {profit, basis});
            }
        }
        return new Financials(byYear, completed);
    }

    /** {@code _annual_rows}。 */
    private static List<Map<String, Object>> annualRows(
            List<Map<String, Object>> events, Map<Integer, Object[]> financial) {
        Map<Integer, List<Map<String, Object>>> byYear = new TreeMap<>();
        for (Map<String, Object> event : events) {
            byYear.computeIfAbsent(intOf(event.get("fiscal_year")), year -> new ArrayList<>())
                    .add(event);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byYear.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            boolean cashComplete = true;
            double cashTotal = 0.0;
            for (Map<String, Object> event : group) {
                if (event.get("cash_per_10") == null) {
                    continue;
                }
                if (event.get("cash_total") == null) {
                    cashComplete = false;
                } else {
                    cashTotal += number(event.get("cash_total"));
                }
            }
            Object[] profit = financial.get(entry.getKey());
            Double netProfit = profit == null ? null : (Double) profit[0];
            String basis = profit == null ? null : (String) profit[1];
            Double payout = null;
            if (cashComplete && netProfit != null && netProfit > 0) {
                payout = EastmoneySupport.round(cashTotal / netProfit * 100.0, 1);
            }

            List<String> periods = new ArrayList<>();
            for (Map<String, Object> event : group) {
                periods.add(text(event.get("report_date")));
            }
            periods.sort(String::compareTo);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", entry.getKey());
            row.put("periods", periods);
            row.put("report_count", group.size());
            row.put("cash_total", cashComplete ? (Object) cashTotal : null);
            row.put("cash_complete", cashComplete);
            row.put("net_profit", netProfit);
            row.put("payout_ratio", payout);
            row.put("payout_basis", payout == null ? null : basis);
            row.put(
                    "share_bonus_count",
                    (int)
                            group.stream()
                                    .filter(event -> number(event.get("share_bonus_per_10")) > 0)
                                    .count());
            rows.add(row);
        }
        return rows;
    }

    /** {@code _counts}。 */
    private static Map<String, Object> counts(
            List<Map<String, Object>> events,
            List<Map<String, Object>> annual,
            java.util.Set<Integer> completedYears,
            List<Map<String, Object>> historyAnnual) {
        List<Integer> cashYears = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            Double cash = numberOrNull(row.get("cash_total"));
            if (cash != null && cash > 0) {
                cashYears.add(intOf(row.get("year")));
            }
        }
        Integer latestCashYear = cashYears.isEmpty() ? null : cashYears.stream().max(Integer::compare).get();

        List<Integer> historyCashYears = new ArrayList<>();
        for (Map<String, Object> row : historyAnnual) {
            Double cash = numberOrNull(row.get("cash_total"));
            if (cash != null && cash > 0) {
                historyCashYears.add(intOf(row.get("year")));
            }
        }
        int consecutive = 0;
        if (!historyCashYears.isEmpty()) {
            // 只从完整年度往前数，避免把当年中期分红当成完整年度
            java.util.Set<Integer> complete = new java.util.HashSet<>(completedYears);
            if (complete.isEmpty()) {
                for (Map<String, Object> row : historyAnnual) {
                    int year = intOf(row.get("year"));
                    @SuppressWarnings("unchecked")
                    List<String> periods = (List<String>) row.get("periods");
                    if (periods != null && periods.contains(year + "-12-31")) {
                        complete.add(year);
                    }
                }
            }
            int start =
                    complete.isEmpty()
                            ? historyCashYears.stream().max(Integer::compare).get()
                            : complete.stream().max(Integer::compare).get();
            Map<Integer, Map<String, Object>> byYear = new LinkedHashMap<>();
            for (Map<String, Object> row : historyAnnual) {
                byYear.put(intOf(row.get("year")), row);
            }
            while (byYear.containsKey(start)) {
                Double cash = numberOrNull(byYear.get(start).get("cash_total"));
                if (cash == null || cash <= 0) {
                    break;
                }
                consecutive++;
                start--;
            }
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("events", events.size());
        counts.put("years", annual.size());
        counts.put("cash_years", cashYears.size());
        counts.put("consecutive_years", consecutive);
        counts.put("latest_cash_year", latestCashYear);
        return counts;
    }

    /** {@code _notes}。 */
    private static List<String> notes(
            List<Map<String, Object>> events,
            List<Map<String, Object>> annual,
            Map<String, Object> counts) {
        List<String> notes = new ArrayList<>();
        if (events.isEmpty()) {
            notes.add("没有取到分红记录，可能从未分红或数据源未收录");
            return notes;
        }
        boolean hasCash = events.stream().anyMatch(event -> event.get("cash_per_10") != null);
        if (hasCash) {
            notes.add("分红总额按税前每 10 股派息 ÷ 10 × 报告期总股本估算，未考虑回购专户等股本差异");
            notes.add("分红率 = 年度现金分红总额 ÷ 年报归母净利润，按报告期归年");
        }
        if (events.stream().anyMatch(event -> event.get("yield_pct") != null)) {
            notes.add("股息率由数据源按公告时点股价计算，不能代表当前股息率");
        }

        List<String> missingProfit = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            Double cash = numberOrNull(row.get("cash_total"));
            if (cash != null && cash != 0 && row.get("net_profit") == null) {
                missingProfit.add(String.valueOf(row.get("year")));
            }
        }
        if (!missingProfit.isEmpty()) {
            List<String> shown = missingProfit.subList(Math.max(0, missingProfit.size() - 3), missingProfit.size());
            String joined = String.join("、", shown);
            if (missingProfit.size() > 3) {
                notes.add(joined + " 等年度缺少年报净利润，未计算分红率");
            } else {
                notes.add(joined + " 年度缺少年报净利润，未计算分红率");
            }
        }

        List<String> incomplete = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            if (!Boolean.TRUE.equals(row.get("cash_complete"))) {
                incomplete.add(String.valueOf(row.get("year")));
            }
        }
        if (!incomplete.isEmpty()) {
            notes.add(String.join("、", incomplete) + " 年度部分分红缺少总股本，现金分红总额不完整");
        }

        List<String> high = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            Double payout = numberOrNull(row.get("payout_ratio"));
            if (payout != null && payout > 100) {
                high.add(row.get("year") + " 年 " + String.format("%.1f", payout) + "%");
            }
        }
        if (!high.isEmpty()) {
            notes.add("分红率超过 100%：" + String.join("、", high) + "，检查是否含特别分红或股本口径差异");
        }
        boolean anyCash = events.stream().anyMatch(event -> number(event.get("cash_per_10")) != 0);
        if (!anyCash) {
            boolean anyBonus =
                    events.stream().anyMatch(event -> number(event.get("share_bonus_per_10")) > 0);
            if (anyBonus) {
                notes.add("有送转记录，但没有现金分红；送转股不增加股东现金回报");
            } else {
                notes.add("记录为不分配不转增，没有现金分红或送转");
            }
        }
        return notes;
    }

    // ---------------------------------------------------------------- 整形

    /** {@code assistant/tools.py:_dividend_event}。 */
    private static Map<String, Object> eventView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report_date", item.get("report_date"));
        out.put("fiscal_year", item.get("fiscal_year"));
        out.put("period_label", item.get("period_label"));
        out.put("plan", item.get("plan"));
        out.put("plan_short", item.get("plan_short"));
        out.put("status", item.get("status"));
        out.put("cash_per_10", DatacenterSupport.round(item.get("cash_per_10"), 2));
        out.put("cash_per_share", DatacenterSupport.round(item.get("cash_per_share"), 3));
        out.put("cash_total_yi", DatacenterSupport.yi(item.get("cash_total")));
        out.put("bonus_per_10", DatacenterSupport.round(item.get("bonus_per_10"), 2));
        out.put("transfer_per_10", DatacenterSupport.round(item.get("transfer_per_10"), 2));
        out.put("share_bonus_per_10", DatacenterSupport.round(item.get("share_bonus_per_10"), 2));
        out.put("total_shares_wan", DatacenterSupport.wan(item.get("total_shares")));
        out.put("yield_pct", DatacenterSupport.round(item.get("yield_pct"), 2));
        out.put("eps", DatacenterSupport.round(item.get("eps"), 2));
        out.put("plan_notice_date", item.get("plan_notice_date"));
        out.put("notice_date", item.get("notice_date"));
        out.put("record_date", item.get("record_date"));
        out.put("ex_date", item.get("ex_date"));
        return out;
    }

    /** {@code assistant/tools.py:_dividend_year}。 */
    private static Map<String, Object> yearView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", item.get("year"));
        out.put("periods", item.get("periods") == null ? List.of() : item.get("periods"));
        out.put("report_count", item.get("report_count"));
        out.put("cash_total_yi", DatacenterSupport.yi(item.get("cash_total")));
        out.put("cash_complete", item.get("cash_complete") == null ? true : item.get("cash_complete"));
        out.put("net_profit_yi", DatacenterSupport.yi(item.get("net_profit")));
        out.put("payout_ratio", DatacenterSupport.pct(item.get("payout_ratio")));
        out.put("payout_basis", item.get("payout_basis"));
        out.put("share_bonus_count", item.get("share_bonus_count"));
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    /** {@code _dedupe}：同一报告期只留最新公告的一条，按报告期倒序。 */
    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> events) {
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String reportDate = text(event.get("report_date"));
            if (reportDate == null) {
                continue;
            }
            Map<String, Object> current = byDate.get(reportDate);
            if (current == null || stamp(event).compareTo(stamp(current)) >= 0) {
                byDate.put(reportDate, event);
            }
        }
        List<Map<String, Object>> ordered = new ArrayList<>(byDate.values());
        ordered.sort((left, right) -> text(right.get("report_date")).compareTo(text(left.get("report_date"))));
        return ordered;
    }

    private static String stamp(Map<String, Object> event) {
        String notice = text(event.get("notice_date"));
        if (notice != null) {
            return notice;
        }
        String planNotice = text(event.get("plan_notice_date"));
        return planNotice == null ? "" : planNotice;
    }

    /** {@code _short_plan}。 */
    private static String shortPlan(String plan) {
        if (plan == null || plan.isEmpty()) {
            return null;
        }
        String text = plan;
        for (String separator : List.of("(", "（")) {
            if (text.contains(separator)) {
                text = text.substring(0, text.indexOf(separator));
            }
        }
        text = text.strip();
        if (text.isEmpty()) {
            text = plan;
        }
        if (text.length() > SUMMARY_PLAN_CHARS) {
            text = text.substring(0, SUMMARY_PLAN_CHARS).replaceAll("[，、；： ]+$", "") + "…";
        }
        return text;
    }

    private static String date(JsonNode node) {
        String text = EastmoneySupport.text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }

    private static Long intOrNull(JsonNode node) {
        Double value = EastmoneySupport.num(node);
        return value == null ? null : (long) (double) value;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() || text.equals("-") ? null : text;
    }

    private static int intOf(Object value) {
        Double number = numberOrNull(value);
        return number == null ? 0 : (int) (double) number;
    }

    private static double number(Object value) {
        Double number = numberOrNull(value);
        return number == null ? 0.0 : number;
    }

    private static Double numberOrNull(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return null;
    }
}
