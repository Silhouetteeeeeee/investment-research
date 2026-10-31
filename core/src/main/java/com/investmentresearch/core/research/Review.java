package com.investmentresearch.core.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of {@code research/review.py} 的纯计算：逐日评估市场、行业与候选股判断，汇总命中率与失误原因。
 *
 * <p>取数在适配器里，这里只接收已经按观察窗口截好的日线。
 */
public final class Review {
    public static final String BENCHMARK_NAME = "沪深300";
    public static final int HORIZON = 5;
    public static final int MAX_REPORTS = 20;
    public static final int MAX_CANDIDATES = 8;
    public static final int MAX_MISTAKES = 10;
    public static final double ROUND_TRIP_COST_PCT = 0.12;
    public static final double RANGE_BAND_PCT = 3.0;

    static final Map<String, String> STATE_EXPECTATION =
            Map.of(
                    MarketState.STRONG, "基准 5 日内上涨",
                    MarketState.RANGE, "基准 5 日内涨跌幅在 ±3% 以内",
                    MarketState.WEAK, "基准 5 日内下跌",
                    MarketState.EXTREME, "基准出现明显下跌");

    private Review() {}

    /** 兼容个股日线（date/close）与行业指数（日期/收盘价）两种字段名。 */
    public static Map<String, Object> series(List<Map<String, Object>> rows) {
        List<String> dates = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            String raw = first(text(row.get("date")), text(row.get("日期")));
            String day = raw.length() > 10 ? raw.substring(0, 10) : raw;
            Double close = SectorRotation.num(row.get("close"));
            if (close == null) {
                close = SectorRotation.num(row.get("收盘价"));
            }
            if (!day.isEmpty() && close != null && close != 0) {
                dates.add(day);
                closes.add(close);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates", dates);
        result.put("closes", closes);
        return result;
    }

    /** {@code day} 及之前最后一个交易日的下标。 */
    public static Integer indexAt(List<String> dates, String day) {
        Integer found = null;
        for (int index = 0; index < dates.size(); index++) {
            if (dates.get(index).compareTo(day) <= 0) {
                found = index;
            } else {
                break;
            }
        }
        return found;
    }

    /** 从存档日收盘算起，往后 {@code horizon} 个交易日的收益与回撤。 */
    public static Map<String, Object> forwardWindow(Map<String, Object> series, String tradeDate, int horizon) {
        List<String> dates = strings(series.get("dates"));
        List<Double> closes = numbers(series.get("closes"));
        Integer start = indexAt(dates, tradeDate);
        if (start == null) {
            return null;
        }
        int end = Math.min(start + horizon, closes.size() - 1);
        double entry = closes.get(start);
        if (entry <= 0) {
            return null;
        }
        List<Double> path = closes.subList(start, end + 1);
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (double item : path) {
            lowest = Math.min(lowest, item);
            highest = Math.max(highest, item);
        }
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("entry_date", dates.get(start));
        window.put("entry_close", SectorRotation.round(entry, 2));
        window.put("end_date", dates.get(end));
        window.put("end_close", SectorRotation.round(closes.get(end), 2));
        window.put("days", end - start);
        window.put("complete", end - start >= horizon);
        window.put("return_pct", SectorRotation.round((closes.get(end) / entry - 1) * 100, 2));
        window.put("max_drawdown_pct", SectorRotation.round((lowest / entry - 1) * 100, 2));
        window.put("max_gain_pct", SectorRotation.round((highest / entry - 1) * 100, 2));
        return window;
    }

    public static Double windowReturn(Map<String, Object> series, String startDate, String endDate) {
        List<String> dates = strings(series.get("dates"));
        List<Double> closes = numbers(series.get("closes"));
        Integer start = indexAt(dates, startDate);
        Integer end = indexAt(dates, endDate);
        if (start == null || end == null || end <= start) {
            return null;
        }
        double base = closes.get(start);
        if (base <= 0) {
            return null;
        }
        return (closes.get(end) / base - 1) * 100;
    }

    public static Boolean directionHit(Object state, Double benchmarkReturn) {
        if (benchmarkReturn == null || !(state instanceof String name) || !STATE_EXPECTATION.containsKey(name)) {
            return null;
        }
        if (MarketState.STRONG.equals(name)) {
            return benchmarkReturn > 0;
        }
        if (MarketState.WEAK.equals(name) || MarketState.EXTREME.equals(name)) {
            return benchmarkReturn < 0;
        }
        return Math.abs(benchmarkReturn) <= RANGE_BAND_PCT;
    }

    public static Map<String, Object> evaluateMarket(
            Map<String, Object> market, Map<String, Object> benchmark, String tradeDate, int horizon) {
        Map<String, Object> view = market == null ? Map.of() : market;
        Object state = view.get("state");
        Map<String, Object> window = forwardWindow(benchmark, tradeDate, horizon);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("risk_level", view.get("risk_level"));
        result.put("expectation", STATE_EXPECTATION.get(state));
        result.put("status", "数据不足");
        result.put("benchmark_return_pct", null);
        result.put("days", 0);
        result.put("hit", null);
        if (window == null) {
            return result;
        }
        result.put("status", Boolean.TRUE.equals(window.get("complete")) ? "已完成" : "待观察");
        result.put("entry_date", window.get("entry_date"));
        result.put("entry_close", window.get("entry_close"));
        result.put("end_date", window.get("end_date"));
        result.put("benchmark_return_pct", window.get("return_pct"));
        result.put("days", window.get("days"));
        if (Boolean.TRUE.equals(window.get("complete"))) {
            result.put("hit", directionHit(state, SectorRotation.num(window.get("return_pct"))));
        }
        return result;
    }

    public static Map<String, Object> evaluateCandidate(
            Map<String, Object> candidate,
            List<Map<String, Object>> rows,
            Map<String, Object> benchmark,
            String tradeDate,
            int horizon) {
        Map<String, Object> item = candidate == null ? Map.of() : candidate;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", item.get("code"));
        result.put("name", item.get("name"));
        result.put("score", item.get("score"));
        result.put("status", "数据不足");
        result.put("return_pct", null);
        result.put("benchmark_return_pct", null);
        result.put("excess_pct", null);
        result.put("net_return_pct", null);
        result.put("max_drawdown_pct", null);
        result.put("days", 0);
        result.put("hit", null);
        Map<String, Object> window = forwardWindow(series(rows), tradeDate, horizon);
        if (window == null) {
            return result;
        }
        Double benchReturn = windowReturn(benchmark, text(window.get("entry_date")), text(window.get("end_date")));
        result.put("status", Boolean.TRUE.equals(window.get("complete")) ? "已完成" : "待观察");
        result.put("entry_date", window.get("entry_date"));
        result.put("end_date", window.get("end_date"));
        result.put("return_pct", window.get("return_pct"));
        Double ret = SectorRotation.num(window.get("return_pct"));
        result.put("net_return_pct", ret == null ? null : SectorRotation.round(ret - ROUND_TRIP_COST_PCT, 2));
        result.put("max_drawdown_pct", window.get("max_drawdown_pct"));
        result.put("days", window.get("days"));
        if (benchReturn != null) {
            result.put("benchmark_return_pct", SectorRotation.round(benchReturn, 2));
            result.put("excess_pct", ret == null ? null : SectorRotation.round(ret - benchReturn, 2));
        }
        Double excess = SectorRotation.num(result.get("excess_pct"));
        if (Boolean.TRUE.equals(window.get("complete")) && excess != null) {
            result.put("hit", excess > 0);
        }
        return result;
    }

    public static Map<String, Object> evaluateSector(
            String name,
            List<Map<String, Object>> rows,
            Map<String, Object> benchmark,
            String tradeDate,
            int horizon,
            boolean expectPositive) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("bucket", expectPositive ? "领涨" : "弱势");
        result.put("status", "数据不足");
        result.put("return_pct", null);
        result.put("benchmark_return_pct", null);
        result.put("excess_pct", null);
        result.put("days", 0);
        result.put("hit", null);
        Map<String, Object> window = forwardWindow(series(rows), tradeDate, horizon);
        if (window == null) {
            return result;
        }
        Double benchReturn = windowReturn(benchmark, text(window.get("entry_date")), text(window.get("end_date")));
        result.put("status", Boolean.TRUE.equals(window.get("complete")) ? "已完成" : "待观察");
        result.put("entry_date", window.get("entry_date"));
        result.put("end_date", window.get("end_date"));
        result.put("return_pct", window.get("return_pct"));
        result.put("days", window.get("days"));
        if (benchReturn != null) {
            Double ret = SectorRotation.num(window.get("return_pct"));
            result.put("benchmark_return_pct", SectorRotation.round(benchReturn, 2));
            result.put("excess_pct", ret == null ? null : SectorRotation.round(ret - benchReturn, 2));
        }
        Double excess = SectorRotation.num(result.get("excess_pct"));
        if (Boolean.TRUE.equals(window.get("complete")) && excess != null) {
            result.put("hit", expectPositive ? excess > 0 : excess < 0);
        }
        return result;
    }

    /** 把没命中的判断翻译成可复盘的失误原因。 */
    public static List<Map<String, Object>> mistakes(Map<String, Object> row) {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> market = map(row.get("market"));
        if (Boolean.FALSE.equals(market.get("hit"))) {
            Double bench = SectorRotation.num(market.get("benchmark_return_pct"));
            items.add(
                    item(
                            "市场状态",
                            "判断为" + market.get("state") + "（预期" + market.get("expectation") + "），"
                                    + "基准实际 " + (bench == null ? "null" : SectorRotation.signed(bench, 2) + "%")));
        }
        for (Map<String, Object> sector : maps(row.get("sectors"))) {
            if (!Boolean.FALSE.equals(sector.get("hit"))) {
                continue;
            }
            String direction = "领涨".equals(sector.get("bucket")) ? "未跑赢" : "没有继续走弱";
            Double excess = SectorRotation.num(sector.get("excess_pct"));
            items.add(
                    item(
                            "行业判断",
                            sector.get("bucket") + "行业" + sector.get("name") + direction + "基准，"
                                    + "5日超额 " + (excess == null ? "null" : SectorRotation.signed(excess, 2) + "%")));
        }
        for (Map<String, Object> candidate : maps(row.get("candidates"))) {
            if (!Boolean.FALSE.equals(candidate.get("hit"))) {
                continue;
            }
            Double excess = SectorRotation.num(candidate.get("excess_pct"));
            Double drawdown = SectorRotation.num(candidate.get("max_drawdown_pct"));
            items.add(
                    item(
                            "候选股",
                            candidate.get("name") + "(" + candidate.get("code") + ") "
                                    + "跑输基准 " + (excess == null ? "null" : SectorRotation.signed(excess, 2) + "%") + "，"
                                    + "区间最大回撤 "
                                    + (drawdown == null ? "null" : SectorRotation.signed(drawdown, 2) + "%")));
        }
        return items;
    }

    static boolean isComplete(Map<String, Object> row) {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(map(row.get("market")));
        entries.addAll(maps(row.get("sectors")));
        entries.addAll(maps(row.get("candidates")));
        for (Map<String, Object> item : entries) {
            if ("已完成".equals(item.get("status"))) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> summary(List<Map<String, Object>> rows) {
        List<Boolean> marketHits = new ArrayList<>();
        List<Double> marketReturns = new ArrayList<>();
        List<Boolean> sectorHits = new ArrayList<>();
        List<Double> sectorExcess = new ArrayList<>();
        List<Boolean> candidateHits = new ArrayList<>();
        List<Double> candidateExcess = new ArrayList<>();
        List<Double> candidateReturns = new ArrayList<>();
        List<Double> drawdowns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> market = map(row.get("market"));
            if (market.get("hit") != null) {
                marketHits.add((Boolean) market.get("hit"));
                marketReturns.add(SectorRotation.num(market.get("benchmark_return_pct")));
            }
            for (Map<String, Object> sector : maps(row.get("sectors"))) {
                if (sector.get("hit") != null) {
                    sectorHits.add((Boolean) sector.get("hit"));
                    sectorExcess.add(SectorRotation.num(sector.get("excess_pct")));
                }
            }
            for (Map<String, Object> candidate : maps(row.get("candidates"))) {
                if (!"已完成".equals(candidate.get("status")) || candidate.get("hit") == null) {
                    continue;
                }
                candidateHits.add((Boolean) candidate.get("hit"));
                candidateExcess.add(SectorRotation.num(candidate.get("excess_pct")));
                candidateReturns.add(SectorRotation.num(candidate.get("return_pct")));
                drawdowns.add(SectorRotation.num(candidate.get("max_drawdown_pct")));
            }
        }
        Double avgExcess = mean(candidateExcess);

        Map<String, Object> market = new LinkedHashMap<>();
        market.put("judged", marketHits.size());
        market.put("hit", count(marketHits));
        market.put("hit_rate_pct", pct(marketHits));
        market.put("avg_benchmark_return_pct", SectorRotation.round(mean(marketReturns), 2));

        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("judged", sectorHits.size());
        sectors.put("hit", count(sectorHits));
        sectors.put("hit_rate_pct", pct(sectorHits));
        sectors.put("avg_excess_pct", SectorRotation.round(mean(sectorExcess), 2));

        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("judged", candidateHits.size());
        candidates.put("hit", count(candidateHits));
        candidates.put("hit_rate_pct", pct(candidateHits));
        candidates.put("avg_return_pct", SectorRotation.round(mean(candidateReturns), 2));
        candidates.put("avg_excess_pct", SectorRotation.round(avgExcess, 2));
        candidates.put("avg_max_drawdown_pct", SectorRotation.round(mean(drawdowns), 2));
        candidates.put("worst_drawdown_pct", drawdowns.isEmpty() ? null : SectorRotation.round(min(drawdowns), 2));
        candidates.put("cost_pct", ROUND_TRIP_COST_PCT);
        candidates.put(
                "net_avg_excess_pct", avgExcess == null ? null : SectorRotation.round(avgExcess - ROUND_TRIP_COST_PCT, 2));

        List<Map<String, Object>> mistakes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (mistakes.size() >= MAX_MISTAKES) {
                break;
            }
            for (Map<String, Object> item : maps(row.get("mistakes"))) {
                if (mistakes.size() >= MAX_MISTAKES) {
                    break;
                }
                Map<String, Object> merged = new LinkedHashMap<>();
                merged.put("trade_date", row.get("trade_date"));
                merged.putAll(item);
                mistakes.add(merged);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reports", rows.size());
        int evaluated = 0;
        for (Map<String, Object> row : rows) {
            if (isComplete(row)) {
                evaluated++;
            }
        }
        result.put("evaluated", evaluated);
        result.put("market", market);
        result.put("sectors", sectors);
        result.put("candidates", candidates);
        result.put("mistakes", mistakes);
        return result;
    }

    /** 复盘报告里记录过的行业判断（名称、预期方向）。 */
    public static List<Object[]> sectorEntries(Map<String, Object> report) {
        List<Object[]> entries = new ArrayList<>();
        Map<String, Object> sectors = map(report.get("sectors"));
        for (Map<String, Object> item : maps(sectors.get("leaders"))) {
            Object name = item.get("name");
            if (name != null && !name.toString().isEmpty()) {
                entries.add(new Object[] {name.toString(), true});
            }
        }
        for (Map<String, Object> item : maps(sectors.get("weak"))) {
            Object name = item.get("name");
            if (name != null && !name.toString().isEmpty()) {
                entries.add(new Object[] {name.toString(), false});
            }
        }
        return entries;
    }

    /** 复盘主体：存档、基准与个股/行业日线都由调用方取好。 */
    public static Map<String, Object> build(
            List<Map<String, Object>> reports,
            Map<String, Object> benchmark,
            Map<String, List<Map<String, Object>>> stockRows,
            Map<String, List<Map<String, Object>>> sectorRows,
            int horizon,
            String asOf,
            Map<String, String> errors) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> report : reports) {
            String tradeDate = text(report.get("trade_date"));
            Map<String, Object> marketResult =
                    evaluateMarket(map(report.get("market")), benchmark, tradeDate, horizon);
            List<Map<String, Object>> sectors = new ArrayList<>();
            for (Object[] entry : sectorEntries(report)) {
                String name = (String) entry[0];
                sectors.add(
                        evaluateSector(
                                name,
                                sectorRows.getOrDefault(name, List.of()),
                                benchmark,
                                tradeDate,
                                horizon,
                                (Boolean) entry[1]));
            }
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (Map<String, Object> item : maps(report.get("candidates"))) {
                String code = text(item.get("code"));
                candidates.add(
                        evaluateCandidate(
                                item, stockRows.getOrDefault(code, List.of()), benchmark, tradeDate, horizon));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trade_date", tradeDate);
            row.put("saved_at", report.get("saved_at"));
            row.put("state", map(report.get("market")).get("state"));
            row.put("risk_level", map(report.get("market")).get("risk_level"));
            row.put("strategy", report.get("strategy"));
            row.put("market", marketResult);
            row.put("sectors", sectors);
            row.put("candidates", candidates);
            row.put("complete", isComplete(row));
            row.put("mistakes", mistakes(row));
            rows.add(row);
        }

        List<String> notes = new ArrayList<>();
        notes.add("只把走完 " + horizon + " 个交易日的判断计入命中率，其余标为待观察");
        notes.add("行业判断以相对沪深300的超额收益为准，领涨看正向超额、弱势看负向超额");
        notes.add("候选股按等权估算，未考虑仓位、分批与止损执行差异");
        notes.add("交易成本按往返 " + SectorRotation.fixed(ROUND_TRIP_COST_PCT, 2) + "% 从超额收益中扣除");
        notes.add("样本少时命中率波动很大，不要据此得出方法有效的结论");

        List<String> missing = new ArrayList<>();
        if (strings(benchmark.get("dates")).isEmpty()) {
            missing.add("基准日线，无法计算超额收益");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("as_of", asOf);
        result.put("horizon", horizon);
        result.put("benchmark", BENCHMARK_NAME);
        result.put("reports", rows);
        result.put("summary", summary(rows));
        result.put("errors", errors);
        result.put("notes", notes);
        result.put("missing", missing);
        return result;
    }

    /** 还没有任何存档时的形状：与 Python 的早退分支一致。 */
    public static Map<String, Object> empty(String asOf, int horizon) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("as_of", asOf);
        result.put("horizon", horizon);
        result.put("benchmark", BENCHMARK_NAME);
        result.put("reports", List.of());
        result.put("summary", null);
        result.put("errors", new LinkedHashMap<String, String>());
        result.put(
                "notes",
                List.of("还没有保存过每日判断；先调用 save_daily_judgment 存档，之后才能复盘"));
        result.put("missing", List.of());
        return result;
    }

    /** 复盘要用的股票代码与行业名；适配器据此取日线。 */
    public static Map<String, Object> keys(List<Map<String, Object>> reports) {
        Set<String> codes = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> report : reports) {
            for (Map<String, Object> item : maps(report.get("candidates"))) {
                String code = text(item.get("code"));
                if (!code.isEmpty()) {
                    codes.add(code);
                }
            }
            for (Object[] entry : sectorEntries(report)) {
                names.add((String) entry[0]);
            }
        }
        List<String> sortedCodes = new ArrayList<>(codes);
        List<String> sortedNames = new ArrayList<>(names);
        java.util.Collections.sort(sortedCodes);
        java.util.Collections.sort(sortedNames);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("codes", sortedCodes);
        result.put("names", sortedNames);
        return result;
    }

    private static Map<String, Object> item(String type, String detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("detail", detail);
        return result;
    }

    private static int count(List<Boolean> values) {
        int hits = 0;
        for (Boolean value : values) {
            if (Boolean.TRUE.equals(value)) {
                hits++;
            }
        }
        return hits;
    }

    static Double pct(List<Boolean> hits) {
        return hits.isEmpty() ? null : SectorRotation.round(count(hits) * 100.0 / hits.size(), 1);
    }

    static Double mean(List<Double> values) {
        double total = 0;
        int count = 0;
        for (Double item : values) {
            if (item != null) {
                total += item;
                count++;
            }
        }
        return count == 0 ? null : total / count;
    }

    private static Double min(List<Double> values) {
        Double lowest = null;
        for (Double item : values) {
            if (item != null && (lowest == null || item < lowest)) {
                lowest = item;
            }
        }
        return lowest;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry) {
                    rows.add((Map<String, Object>) entry);
                }
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List<?> items ? (List<String>) items : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Double> numbers(Object value) {
        return value instanceof List<?> items ? (List<Double>) items : List.of();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String first(String a, String b) {
        return a.isEmpty() ? b : a;
    }
}
