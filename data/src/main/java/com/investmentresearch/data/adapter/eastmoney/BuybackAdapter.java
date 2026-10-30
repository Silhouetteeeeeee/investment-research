package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.BuybackPort;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** 东财股票回购，照抄 {@code stock/buybacks.py} 与 {@code assistant/tools.py:_buybacks}。 */
@Repository
public class BuybackAdapter implements BuybackPort {
    static final String REPORT_NAME = "RPTA_WEB_GETHGLIST_NEW";
    static final String PAGE_URL = "https://data.eastmoney.com/gphg/detail/%s-%s.html";
    static final String LIST_PAGE_URL = "https://data.eastmoney.com/gphg/";
    static final double MAX_AGE_HOURS = 24;
    static final int MAX_PLANS = 30;
    static final int SUMMARY_PURPOSE_CHARS = 60;

    private static final Map<String, String> PROGRESS_LABELS =
            Map.of(
                    "001", "董事会预案",
                    "002", "股东大会通过",
                    "003", "股东大会否决",
                    "004", "实施中",
                    "005", "停止实施",
                    "006", "完成实施");
    private static final List<String> ACTIVE_CODES = List.of("001", "002", "004");
    private static final List<String> PENDING_CODES = List.of("001", "002");
    private static final List<String> STOPPED_CODES = List.of("003", "005");

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public BuybackAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(store, stockMapper, upstream, endpoint, Clock.systemDefaultZone());
    }

    BuybackAdapter(
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
    public ToolResult buybacks(String rawCode, int years) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.BUYBACKS,
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
        List<Map<String, Object>> plans =
                new ArrayList<>(DatacenterSupport.plainList(ensured.data().path("plans")));
        List<Map<String, Object>> history = dedupe(plans);

        String end = DatacenterSupport.today(clock);
        String start = (Integer.parseInt(end.substring(0, 4)) - years + 1) + "-01-01";
        List<Map<String, Object>> window = new ArrayList<>();
        for (Map<String, Object> plan : history) {
            String announce = DatacenterSupport.text(plan.get("announce_date"));
            if (announce != null && start.compareTo(announce) <= 0 && announce.compareTo(end) <= 0) {
                window.add(plan);
            }
        }

        List<Map<String, Object>> annual = annualRows(window);
        Map<String, Object> counts = counts(window);
        int truncated = Math.max(0, window.size() - MAX_PLANS);
        List<String> updates = new ArrayList<>();
        for (Map<String, Object> plan : history) {
            String update = DatacenterSupport.text(plan.get("update_date"));
            updates.add(update == null ? DatacenterSupport.text(plan.get("announce_date")) : update);
        }
        String asOf =
                updates.stream().filter(value -> value != null).max(String::compareTo).orElse(null);
        List<String> notes = notes(history, window, counts, years, truncated);

        String name = stockMapper.selectNameByCode(code);
        if ((name == null || name.isEmpty()) && !plans.isEmpty()) {
            name = DatacenterSupport.text(plans.get(0).get("security_name"));
        }
        Map<String, Object> latest = history.isEmpty() ? null : planView(history.get(0));
        String url = LIST_PAGE_URL;
        if (!history.isEmpty()) {
            String announce = DatacenterSupport.text(history.get(0).get("announce_date"));
            if (announce != null) {
                url = PAGE_URL.formatted(code, announce);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf == null ? null : asOf);
        data.put("window_start", start);
        data.put("window_end", end);
        data.put("years", years);
        data.put("has_buybacks", !window.isEmpty());
        data.put("latest", latest);
        List<Map<String, Object>> annualView = new ArrayList<>();
        for (Map<String, Object> row : annual) {
            annualView.add(yearView(row));
        }
        data.put("annual", annualView);
        List<Map<String, Object>> plansView = new ArrayList<>();
        for (Map<String, Object> plan : window.subList(0, Math.min(MAX_PLANS, window.size()))) {
            plansView.add(planView(plan));
        }
        data.put("plans", plansView);
        data.put("plans_truncated", truncated);
        data.put("counts", countsView(counts));
        data.put("notes", notes);
        data.put("stale", ensured.stale());

        Source source =
                new Source(
                        asOf == null ? "buybacks:" + code : "buybacks:" + code + ":" + asOf,
                        "股票回购",
                        ((name == null || name.isEmpty() ? code : name)
                                        + " 股票回购 "
                                        + (asOf == null ? "" : asOf))
                                .strip(),
                        asOf == null ? "" : asOf,
                        ensured.fetchedAt() == null ? "" : ensured.fetchedAt(),
                        url);
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    // ---------------------------------------------------------------- 取数

    private JsonNode fetch(String code) {
        List<JsonNode> rows =
                EastmoneySupport.fetchRows(
                        upstream,
                        endpoint,
                        REPORT_NAME,
                        "(DIM_SCODE=\"" + code + "\")",
                        "UPD,DIM_DATE,DIM_SCODE:-1,-1,1",
                        100,
                        "股票回购");
        List<Map<String, Object>> plans = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> plan = normalize(row);
            if (plan != null) {
                plans.add(plan);
            }
        }
        ObjectNode payload = EastmoneySupport.MAPPER.createObjectNode();
        payload.put("code", code);
        ArrayNode array = payload.putArray("plans");
        for (Map<String, Object> plan : dedupe(plans)) {
            array.add(EastmoneySupport.MAPPER.valueToTree(plan));
        }
        payload.put("count", array.size());
        return payload;
    }

    /** {@code stock/buybacks.py:_normalize_row}。 */
    private static Map<String, Object> normalize(JsonNode row) {
        String announceDate = date(row.get("DIM_DATE"));
        if (announceDate == null) {
            return null;
        }
        String progressCode = EastmoneySupport.text(row.get("REPURPROGRESS"));
        String status = PROGRESS_LABELS.getOrDefault(progressCode == null ? "" : progressCode, "进度未知");
        Double plannedLow = EastmoneySupport.num(row.get("REPURAMOUNTLOWER"));
        Double plannedHigh = EastmoneySupport.num(row.get("REPURAMOUNTLIMIT"));
        Double actualAmount = EastmoneySupport.num(row.get("REPURAMOUNT"));
        Integer actualShares = intOrNull(row.get("REPURNUM"));
        String purpose = EastmoneySupport.text(row.get("REPUROBJECTIVE"));
        if (purpose == null) {
            purpose = "";
        }
        List<String> tags = purposeTags(purpose);
        String planId = EastmoneySupport.text(row.get("REPURCODE"));
        if (planId == null) {
            String start = EastmoneySupport.text(row.get("REPURSTARTDATE"));
            planId =
                    announceDate
                            + "-"
                            + (start == null ? "" : start)
                            + "-"
                            + (plannedLow == null ? "" : trimNumber(plannedLow));
        }
        Integer plannedSharesLow = intOrNull(row.get("REPURNUMLOWER"));
        Integer plannedSharesHigh = intOrNull(row.get("REPURNUMCAP"));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("plan_id", planId);
        plan.put("announce_date", announceDate);
        plan.put("year", Integer.parseInt(announceDate.substring(0, 4)));
        plan.put("status", status);
        plan.put("progress_code", progressCode);
        plan.put("is_active", progressCode != null && ACTIVE_CODES.contains(progressCode));
        plan.put("pending", progressCode != null && PENDING_CODES.contains(progressCode));
        plan.put("completed", "006".equals(progressCode));
        plan.put("stopped", progressCode != null && STOPPED_CODES.contains(progressCode));
        plan.put("start_date", date(row.get("REPURSTARTDATE")));
        plan.put("end_date", date(row.get("REPURENDDATE")));
        plan.put("update_date", date(row.get("UPD")));
        plan.put("finish_date", date(row.get("FINISHDATE")));
        plan.put("advanced_date", date(row.get("REPURADVANCEDATE")));
        plan.put("planned_amount_low", plannedLow);
        plan.put("planned_amount_high", plannedHigh);
        plan.put("planned_shares_low", plannedSharesLow);
        plan.put("planned_shares_high", plannedSharesHigh);
        plan.put("planned_price_low", EastmoneySupport.num(row.get("REPURPRICELOWER")));
        plan.put("planned_price_high", EastmoneySupport.num(row.get("REPURPRICECAP")));
        plan.put("planned_ratio_low_pct", EastmoneySupport.num(row.get("ZSZXX")));
        plan.put("planned_ratio_high_pct", EastmoneySupport.num(row.get("ZSZSX")));
        plan.put("actual_amount", actualAmount);
        plan.put("actual_shares", actualShares);
        plan.put("actual_price_low", EastmoneySupport.num(row.get("REPURPRICELOWER1")));
        plan.put("actual_price_high", EastmoneySupport.num(row.get("REPURPRICECAP1")));
        plan.put("actual_ratio_total_pct", EastmoneySupport.num(row.get("ZJSZBL")));
        plan.put("actual_ratio_float_pct", EastmoneySupport.num(row.get("ZJLTBL")));
        plan.put("amount_completion_pct", DatacenterSupport.completion(actualAmount, plannedLow, plannedHigh));
        plan.put(
                "shares_completion_pct",
                DatacenterSupport.completion(actualShares, plannedSharesLow, plannedSharesHigh));
        plan.put("purpose", purpose);
        plan.put("purpose_short", clip(purpose, SUMMARY_PURPOSE_CHARS));
        plan.put("purpose_tags", tags);
        plan.put("cancel", tags.contains("注销"));
        plan.put("incentive", tags.contains("员工持股") || tags.contains("股权激励"));
        plan.put("security_name", EastmoneySupport.text(row.get("SECURITYSHORTNAME")));
        return plan;
    }

    // ---------------------------------------------------------------- 计算

    private static List<Map<String, Object>> annualRows(List<Map<String, Object>> plans) {
        Map<Integer, List<Map<String, Object>>> byYear = new TreeMap<>();
        for (Map<String, Object> plan : plans) {
            byYear.computeIfAbsent(DatacenterSupport.intOf(plan.get("year")), year -> new ArrayList<>())
                    .add(plan);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byYear.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            Object actualAmount = DatacenterSupport.sumOptional(DatacenterSupport.column(group, "actual_amount"));
            Object actualShares = DatacenterSupport.sumOptional(DatacenterSupport.column(group, "actual_shares"));
            Object plannedLow = DatacenterSupport.sumOptional(DatacenterSupport.column(group, "planned_amount_low"));
            Object plannedHigh = DatacenterSupport.sumOptional(DatacenterSupport.column(group, "planned_amount_high"));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", entry.getKey());
            row.put("plans", group.size());
            row.put("active", count(group, "is_active"));
            row.put("pending", count(group, "pending"));
            row.put("completed", count(group, "completed"));
            row.put("stopped", count(group, "stopped"));
            row.put("actual_amount", actualAmount);
            row.put("actual_shares", actualShares);
            row.put("planned_amount_low", plannedLow);
            row.put("planned_amount_high", plannedHigh);
            row.put(
                    "completion_pct",
                    DatacenterSupport.round(
                            DatacenterSupport.completion(actualAmount, plannedLow, plannedHigh), 1));
            row.put("cancel_plans", count(group, "cancel"));
            row.put("incentive_plans", count(group, "incentive"));
            row.put(
                    "amount_complete",
                    group.stream().allMatch(plan -> plan.get("actual_amount") != null));
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> counts(List<Map<String, Object>> plans) {
        Object actualAmount = DatacenterSupport.sumOptional(DatacenterSupport.column(plans, "actual_amount"));
        Object actualShares = DatacenterSupport.sumOptional(DatacenterSupport.column(plans, "actual_shares"));
        Object plannedLow = DatacenterSupport.sumOptional(DatacenterSupport.column(plans, "planned_amount_low"));
        Object plannedHigh = DatacenterSupport.sumOptional(DatacenterSupport.column(plans, "planned_amount_high"));
        List<String> updates = new ArrayList<>();
        for (Map<String, Object> plan : plans) {
            String update = DatacenterSupport.text(plan.get("update_date"));
            updates.add(update == null ? DatacenterSupport.text(plan.get("announce_date")) : update);
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("plans", plans.size());
        counts.put("active", count(plans, "is_active"));
        counts.put("pending", count(plans, "pending"));
        counts.put("completed", count(plans, "completed"));
        counts.put("stopped", count(plans, "stopped"));
        counts.put("cancel_plans", count(plans, "cancel"));
        counts.put("incentive_plans", count(plans, "incentive"));
        counts.put("actual_amount", actualAmount);
        counts.put("actual_shares", actualShares);
        counts.put("planned_amount_low", plannedLow);
        counts.put("planned_amount_high", plannedHigh);
        counts.put(
                "completion_pct",
                DatacenterSupport.round(
                        DatacenterSupport.completion(actualAmount, plannedLow, plannedHigh), 1));
        counts.put(
                "missing_actual",
                (int) plans.stream().filter(plan -> plan.get("actual_amount") == null).count());
        counts.put(
                "amount_complete",
                plans.stream().allMatch(plan -> plan.get("actual_amount") != null));
        counts.put(
                "planned_amount_complete",
                plans.stream()
                        .allMatch(
                                plan ->
                                        plan.get("planned_amount_low") != null
                                                || plan.get("planned_amount_high") != null));
        counts.put(
                "latest_update",
                updates.stream().filter(value -> value != null).max(String::compareTo).orElse(null));
        return counts;
    }

    private List<String> notes(
            List<Map<String, Object>> history,
            List<Map<String, Object>> plans,
            Map<String, Object> counts,
            int years,
            int truncated) {
        List<String> notes = new ArrayList<>();
        if (history.isEmpty()) {
            notes.add("没有取到股票回购记录，可能数据源未收录");
            return notes;
        }
        if (plans.isEmpty()) {
            Map<String, Object> latest = history.get(0);
            String status = DatacenterSupport.text(latest.get("status"));
            notes.add(
                    "近"
                            + years
                            + "年没有披露的回购计划；最近一次为"
                            + latest.get("announce_date")
                            + "（"
                            + (status == null ? "进度未知" : status)
                            + "）");
            notes.add("只统计已披露的回购计划，未覆盖未披露的回购");
            return notes;
        }
        notes.add("金额与股数为累计已回购，不是某日成交；计划金额为公告区间");
        notes.add("下限完成度=实际已回购金额÷计划金额下限，超过100%表示已超过下限");
        notes.add("计划用于注销的股份在注销完成后减少总股本；员工持股或股权激励不减少总股本");
        if (DatacenterSupport.intOf(counts.get("missing_actual")) > 0) {
            notes.add(counts.get("missing_actual") + " 个计划缺少已回购金额，合计不完整");
        }
        String today = DatacenterSupport.today(clock);
        if (plans.stream()
                .anyMatch(
                        plan ->
                                DatacenterSupport.flag(plan, "is_active")
                                        && plan.get("end_date") != null
                                        && String.valueOf(plan.get("end_date")).compareTo(today) < 0)) {
            notes.add("有计划的回购期限已过但进度仍显示在途，需核对最新实施公告");
        }
        if (plans.stream()
                .anyMatch(
                        plan -> {
                            Double actual = DatacenterSupport.number(plan.get("actual_amount"));
                            Double low = DatacenterSupport.number(plan.get("planned_amount_low"));
                            return actual != null && low != null && actual > low * 1.01;
                        })) {
            notes.add("部分计划实际回购金额已超过计划下限，完成度超过100%属正常");
        }
        if (truncated > 0) {
            notes.add("明细只列最近 " + MAX_PLANS + " 个计划，年度与合计使用全部计划");
        }
        return notes;
    }

    // ---------------------------------------------------------------- 整形

    /** {@code assistant/tools.py:_buyback_plan}。 */
    private static Map<String, Object> planView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan_id", item.get("plan_id"));
        out.put("announce_date", item.get("announce_date"));
        out.put("year", item.get("year"));
        out.put("status", item.get("status"));
        out.put("progress_code", item.get("progress_code"));
        out.put("is_active", item.get("is_active") == null ? false : item.get("is_active"));
        out.put("pending", item.get("pending") == null ? false : item.get("pending"));
        out.put("completed", item.get("completed") == null ? false : item.get("completed"));
        out.put("stopped", item.get("stopped") == null ? false : item.get("stopped"));
        out.put("start_date", item.get("start_date"));
        out.put("end_date", item.get("end_date"));
        out.put("update_date", item.get("update_date"));
        out.put("finish_date", item.get("finish_date"));
        out.put("advanced_date", item.get("advanced_date"));
        out.put("planned_amount_low_yi", DatacenterSupport.yi(item.get("planned_amount_low")));
        out.put("planned_amount_high_yi", DatacenterSupport.yi(item.get("planned_amount_high")));
        out.put("planned_shares_low_wan", DatacenterSupport.wan(item.get("planned_shares_low")));
        out.put("planned_shares_high_wan", DatacenterSupport.wan(item.get("planned_shares_high")));
        out.put("planned_price_low", DatacenterSupport.round(item.get("planned_price_low"), 2));
        out.put("planned_price_high", DatacenterSupport.round(item.get("planned_price_high"), 2));
        out.put("planned_ratio_low_pct", DatacenterSupport.round(item.get("planned_ratio_low_pct"), 2));
        out.put("planned_ratio_high_pct", DatacenterSupport.round(item.get("planned_ratio_high_pct"), 2));
        out.put("actual_amount_yi", DatacenterSupport.yi(item.get("actual_amount")));
        out.put("actual_shares_wan", DatacenterSupport.wan(item.get("actual_shares")));
        out.put("actual_price_low", DatacenterSupport.round(item.get("actual_price_low"), 2));
        out.put("actual_price_high", DatacenterSupport.round(item.get("actual_price_high"), 2));
        out.put("actual_ratio_total_pct", DatacenterSupport.round(item.get("actual_ratio_total_pct"), 2));
        out.put("actual_ratio_float_pct", DatacenterSupport.round(item.get("actual_ratio_float_pct"), 2));
        out.put("amount_completion_pct", DatacenterSupport.round(item.get("amount_completion_pct"), 1));
        out.put("shares_completion_pct", DatacenterSupport.round(item.get("shares_completion_pct"), 1));
        out.put("purpose", item.get("purpose"));
        out.put("purpose_short", item.get("purpose_short"));
        out.put("purpose_tags", item.get("purpose_tags") == null ? List.of() : item.get("purpose_tags"));
        out.put("cancel", item.get("cancel") == null ? false : item.get("cancel"));
        out.put("incentive", item.get("incentive") == null ? false : item.get("incentive"));
        return out;
    }

    /** {@code assistant/tools.py:_buyback_year}。 */
    private static Map<String, Object> yearView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", item.get("year"));
        out.put("plans", item.get("plans"));
        out.put("active", item.get("active"));
        out.put("pending", item.get("pending"));
        out.put("completed", item.get("completed"));
        out.put("stopped", item.get("stopped"));
        out.put("actual_amount_yi", DatacenterSupport.yi(item.get("actual_amount")));
        out.put("actual_shares_wan", DatacenterSupport.wan(item.get("actual_shares")));
        out.put("planned_amount_low_yi", DatacenterSupport.yi(item.get("planned_amount_low")));
        out.put("planned_amount_high_yi", DatacenterSupport.yi(item.get("planned_amount_high")));
        out.put("completion_pct", DatacenterSupport.round(item.get("completion_pct"), 1));
        out.put("cancel_plans", item.get("cancel_plans"));
        out.put("incentive_plans", item.get("incentive_plans"));
        out.put("amount_complete", item.get("amount_complete") == null ? true : item.get("amount_complete"));
        return out;
    }

    /** {@code assistant/tools.py:_buyback_counts}。 */
    private static Map<String, Object> countsView(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plans", item.getOrDefault("plans", 0));
        out.put("active", item.getOrDefault("active", 0));
        out.put("pending", item.getOrDefault("pending", 0));
        out.put("completed", item.getOrDefault("completed", 0));
        out.put("stopped", item.getOrDefault("stopped", 0));
        out.put("cancel_plans", item.getOrDefault("cancel_plans", 0));
        out.put("incentive_plans", item.getOrDefault("incentive_plans", 0));
        out.put("actual_amount_yi", DatacenterSupport.yi(item.get("actual_amount")));
        out.put("actual_shares_wan", DatacenterSupport.wan(item.get("actual_shares")));
        out.put("planned_amount_low_yi", DatacenterSupport.yi(item.get("planned_amount_low")));
        out.put("planned_amount_high_yi", DatacenterSupport.yi(item.get("planned_amount_high")));
        out.put("completion_pct", DatacenterSupport.round(item.get("completion_pct"), 1));
        out.put("missing_actual", item.getOrDefault("missing_actual", 0));
        out.put("amount_complete", item.getOrDefault("amount_complete", true));
        out.put("planned_amount_complete", item.getOrDefault("planned_amount_complete", true));
        out.put("latest_update", item.get("latest_update"));
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    /** {@code _dedupe}：同一计划只留 UPD 最新的一条，按公告日与更新日倒序。 */
    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> plans) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> plan : plans) {
            if (DatacenterSupport.text(plan.get("announce_date")) == null) {
                continue;
            }
            String key = DatacenterSupport.text(plan.get("plan_id"));
            if (key == null) {
                key =
                        plan.get("announce_date")
                                + "|"
                                + plan.get("start_date")
                                + "|"
                                + plan.get("planned_amount_low");
            }
            Map<String, Object> current = byId.get(key);
            if (current == null || planStamp(plan).compareTo(planStamp(current)) > 0) {
                byId.put(key, plan);
            }
        }
        List<Map<String, Object>> ordered = new ArrayList<>(byId.values());
        ordered.sort(
                Comparator.comparing(
                                (Map<String, Object> plan) ->
                                        DatacenterSupport.text(plan.get("announce_date")))
                        .thenComparing(plan -> DatacenterSupport.text(plan.get("update_date")))
                        .reversed());
        return ordered;
    }

    private static String planStamp(Map<String, Object> plan) {
        String update = DatacenterSupport.text(plan.get("update_date"));
        return (update == null ? "" : update)
                + "#"
                + String.format("%02d", completeness(plan))
                + "#"
                + (plan.get("actual_amount") != null ? "1" : "0");
    }

    private static int completeness(Map<String, Object> plan) {
        int count = 0;
        for (String key :
                List.of(
                        "actual_amount", "actual_shares", "planned_amount_low",
                        "planned_amount_high", "planned_shares_low", "planned_shares_high",
                        "planned_price_low", "planned_price_high", "start_date", "end_date",
                        "purpose")) {
            if (plan.get(key) != null) {
                count++;
            }
        }
        return count;
    }

    private static List<String> purposeTags(String purpose) {
        List<String> tags = new ArrayList<>();
        if (purpose.contains("注销")
                || purpose.contains("减少注册资本")
                || purpose.contains("减少公司注册资本")) {
            tags.add("注销");
        }
        if (purpose.contains("员工持股")) {
            tags.add("员工持股");
        }
        if (purpose.contains("股权激励")) {
            tags.add("股权激励");
        }
        return tags;
    }

    private static int count(List<Map<String, Object>> plans, String key) {
        return (int) plans.stream().filter(plan -> DatacenterSupport.flag(plan, key)).count();
    }

    private static String clip(String text, int size) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return text.length() <= size ? text : text.substring(0, size) + "…";
    }

    private static String trimNumber(double value) {
        return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
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
