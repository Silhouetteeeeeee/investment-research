package com.investmentresearch.core.research;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Port of {@code research/sector_rotation.py} 的计算部分：把当日板块快照与历史指数合成轮动指标。
 *
 * <p>一天的涨幅只占打分的一成，主要权重给 5/20/60 日相对基准的跑赢幅度和 10 日内的相对强度天数。
 */
public final class SectorRotation {
    public static final String BENCHMARK_NAME = "沪深300";
    public static final String BENCHMARK_CODE = "sh000300";

    static final String STRONG = "持续强势";
    static final String EMERGING = "由弱转强";
    static final String WEAKENING = "由强转弱";
    static final String WEAK = "持续弱势";
    static final String NEUTRAL = "中性";

    static final int LEADER_LIMIT = 8;
    static final int LIST_LIMIT = 5;
    static final int RS_WINDOW = 10;

    /** 打分权重：短期动量只占一成，避免“只看一天涨幅”。 */
    static final Map<String, Double> WEIGHTS =
            Map.of(
                    "today", 0.10,
                    "ret_5d", 0.30,
                    "ret_20d", 0.25,
                    "ret_60d", 0.15,
                    "breadth", 0.10,
                    "persistence", 0.05,
                    "ma20", 0.05);

    private SectorRotation() {}

    // ------------------------------------------------------------------ 基础工具

    static Double num(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    static Double round(Double value, int digits) {
        return value == null ? null : new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python 的 {@code format(x, '.nf')}：按 double 精确值做银行家舍入，不用科学计数法。 */
    static String fixed(double value, int digits) {
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
    }

    static String signed(double value, int digits) {
        return (value < 0 ? "" : "+") + fixed(value, digits);
    }

    /** Python 的 {@code round()} 取整：银行家舍入，70*0.15 得 10 而不是 11。 */
    static int pytRound(double value) {
        return (int) Math.round(new BigDecimal(value).setScale(0, RoundingMode.HALF_EVEN).doubleValue());
    }

    static double clip(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    static Double mean(List<Double> values) {
        double sum = 0;
        int count = 0;
        for (Double item : values) {
            if (item != null) {
                sum += item;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    /** 与 Python 一致取上中位数（{@code values[len // 2]}），不是两数均值。 */
    static Double upperMedian(List<Double> values) {
        List<Double> sorted = new ArrayList<>();
        for (Double item : values) {
            if (item != null) {
                sorted.add(item);
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        sorted.sort(Double::compare);
        return sorted.get(sorted.size() / 2);
    }

    /** 以 {@code lastDate} 及之前最后一个交易日为终点，算 {@code days} 日涨跌幅。 */
    static Double alignedReturn(List<String> dates, List<Double> closes, String lastDate, int days) {
        int end = -1;
        for (int index = dates.size() - 1; index >= 0; index--) {
            if (dates.get(index).compareTo(lastDate) <= 0) {
                end = index;
                break;
            }
        }
        if (end < 0 || end - days < 0) {
            return null;
        }
        Double base = closes.get(end - days);
        if (base == null || base <= 0) {
            return null;
        }
        return (closes.get(end) / base - 1) * 100;
    }

    /** 最近 {@code window} 个交易日里，有多少天跑赢基准。 */
    static Integer rsDays(List<String> dates, List<Double> closes, Map<String, Double> benchmark) {
        int wins = 0;
        int used = 0;
        int start = Math.max(1, closes.size() - RS_WINDOW);
        for (int index = start; index < closes.size(); index++) {
            String day = dates.get(index);
            String previousDay = dates.get(index - 1);
            Double benchBase = benchmark.get(previousDay);
            Double benchClose = benchmark.get(day);
            if (benchBase == null || benchClose == null) {
                continue;
            }
            Double base = closes.get(index - 1);
            Double close = closes.get(index);
            if (base == null || base == 0 || benchBase == 0 || close == null) {
                continue;
            }
            double own = close / base - 1;
            double other = benchClose / benchBase - 1;
            used++;
            if (own > other) {
                wins++;
            }
        }
        return used >= 5 ? wins : null;
    }

    static String trendLabel(Integer rank5d, Integer rank20d, int total) {
        if (rank5d == null || rank20d == null || total < 10) {
            return NEUTRAL;
        }
        int top = Math.max(3, pytRound(total * 0.15));
        int bottom = total - top + 1;
        boolean strongNow = rank5d <= top;
        boolean strongMid = rank20d <= top;
        boolean weakNow = rank5d >= bottom;
        boolean weakMid = rank20d >= bottom;
        if (strongNow && strongMid) {
            return STRONG;
        }
        if (strongNow && weakMid) {
            return EMERGING;
        }
        if (strongMid && weakNow) {
            return WEAKENING;
        }
        if (weakNow && weakMid) {
            return WEAK;
        }
        return NEUTRAL;
    }

    static String persistence(Integer rsDays, Double score) {
        if (rsDays == null) {
            return score != null && Math.abs(score) >= 0.3 ? "中" : "低";
        }
        if (rsDays >= 7 && score != null && Math.abs(score) >= 0.4) {
            return "高";
        }
        if (rsDays >= 5) {
            return "中";
        }
        return "低";
    }

    static List<String> reversalSignals(Map<String, Object> sector) {
        List<String> signals = new ArrayList<>();
        Double ret20d = num(sector.get("ret_20d"));
        Double excess5d = num(sector.get("excess_5d"));
        Double chg = num(sector.get("chg_pct"));
        Double upRatio = num(sector.get("up_ratio"));
        Double amountRatio = num(sector.get("amount_ratio"));
        Boolean aboveMa = (Boolean) sector.get("above_ma20");
        Integer rank5d = intOf(sector.get("rank_5d"));
        Integer rank20d = intOf(sector.get("rank_20d"));
        int total = intOf(sector.get("total")) == null ? 0 : intOf(sector.get("total"));
        if (ret20d != null && ret20d >= 15 && excess5d != null && excess5d <= -1) {
            signals.add(
                    "20日已涨"
                            + fixed(ret20d, 2)
                            + "%，近5日跑输基准"
                            + fixed(Math.abs(excess5d), 2)
                            + "%，有高位转弱迹象");
        }
        if (chg != null && chg > 0 && upRatio != null && upRatio < 0.4) {
            signals.add("板块指数上涨但只有" + fixed(upRatio * 100, 0) + "%个股上涨，涨幅集中在少数个股或权重");
        }
        if (Boolean.FALSE.equals(aboveMa)) {
            signals.add("仍在20日均线下方，趋势尚未修复");
        }
        if (amountRatio != null && amountRatio >= 1.6 && chg != null && chg <= -0.5) {
            signals.add("成交额放大到同期" + fixed(amountRatio, 1) + "倍却在下跌，资金可能在流出");
        }
        if (total >= 10 && rank20d != null && rank5d != null) {
            if (rank20d <= pytRound(total * 0.15) && rank5d >= pytRound(total * 0.7)) {
                signals.add("20日仍排在前段但5日掉到后段，属于由强转弱");
            }
        }
        return signals;
    }

    /** 把百分比读数按经验尺度压到 [-1, 1]；缺失就返回 {@code null} 而不是 0。 */
    static Double scaled(Object value, double scale) {
        Double number = num(value);
        return number == null ? null : clip(number / scale, -1.0, 1.0);
    }

    static Double breadthComponent(Map<String, Object> sector) {
        Double upRatio = num(sector.get("up_ratio"));
        int up = num(sector.get("up_count")) == null ? 0 : (int) (double) num(sector.get("up_count"));
        int down = num(sector.get("down_count")) == null ? 0 : (int) (double) num(sector.get("down_count"));
        if (upRatio == null || up + down < 5) {
            return null;
        }
        return clip((upRatio - 0.5) * 2, -1.0, 1.0);
    }

    static Double persistenceComponent(Map<String, Object> sector) {
        Integer rs = intOf(sector.get("rs_days_10"));
        return rs == null ? null : (rs - 5) / 5.0;
    }

    /** 按权重合成轮动分；缺数据的维度不参与，权重重归一。 */
    static Double score(Map<String, Object> sector) {
        if (sector.get("excess_5d") == null && sector.get("excess_20d") == null) {
            return null;
        }
        List<Object[]> parts = new ArrayList<>();
        parts.add(new Object[] {scaled(sector.get("excess_today"), 3), WEIGHTS.get("today")});
        parts.add(new Object[] {scaled(sector.get("excess_5d"), 8), WEIGHTS.get("ret_5d")});
        parts.add(new Object[] {scaled(sector.get("excess_20d"), 15), WEIGHTS.get("ret_20d")});
        parts.add(new Object[] {scaled(sector.get("excess_60d"), 30), WEIGHTS.get("ret_60d")});
        parts.add(new Object[] {breadthComponent(sector), WEIGHTS.get("breadth")});
        parts.add(new Object[] {persistenceComponent(sector), WEIGHTS.get("persistence")});
        parts.add(new Object[] {scaled(sector.get("ma20_dev_pct"), 8), WEIGHTS.get("ma20")});
        double total = 0;
        double weight = 0;
        for (Object[] part : parts) {
            Double value = (Double) part[0];
            if (value == null) {
                continue;
            }
            total += value * (Double) part[1];
            weight += (Double) part[1];
        }
        if (weight == 0) {
            return null;
        }
        return round(clip(total / weight, -1.0, 1.0) * 10, 2);
    }

    // ------------------------------------------------------------------ 基准与单板块

    /** 把 {@code {日期: 收盘}} 整理成算超额收益用的基准结构，只算一次。 */
    public static Map<String, Object> makeBenchmark(Map<String, Double> closes) {
        List<String> dates = new ArrayList<>(closes.keySet());
        dates.sort(String::compareTo);
        List<Double> values = new ArrayList<>();
        for (String day : dates) {
            values.add(closes.get(day));
        }
        Map<String, Object> benchmark = new LinkedHashMap<>();
        benchmark.put("dates", dates);
        benchmark.put("closes", values);
        benchmark.put("map", new TreeMap<>(closes));
        benchmark.put("last", dates.isEmpty() ? null : dates.get(dates.size() - 1));
        return benchmark;
    }

    /** 把一块当日快照和一段历史合并成一个板块的轮动指标。 */
    public static Map<String, Object> sectorStats(
            Map<String, Object> summary,
            List<Map<String, Object>> history,
            Map<String, Object> benchmark,
            int total,
            Double progress) {
        String name = str(summary.get("板块")).strip();
        Double chg = num(summary.get("涨跌幅"));
        Double amount = num(summary.get("总成交额"));
        int upCount = intOrZero(summary.get("上涨家数"));
        int downCount = intOrZero(summary.get("下跌家数"));
        Double netInflow = num(summary.get("净流入"));

        List<String> dates = new ArrayList<>();
        List<Double> closes = new ArrayList<>();
        List<Double> amounts = new ArrayList<>();
        for (Map<String, Object> row : history) {
            String day = cut(str(row.get("日期")), 10);
            Double close = num(row.get("收盘价"));
            if (day.isEmpty() || close == null || close == 0) {
                continue;
            }
            dates.add(day);
            closes.add(close);
            amounts.add(num(row.get("成交额")));
        }

        String lastDate = dates.isEmpty() ? "" : dates.get(dates.size() - 1);
        Double ret5d = dates.isEmpty() ? null : alignedReturn(dates, closes, lastDate, 5);
        Double ret20d = dates.isEmpty() ? null : alignedReturn(dates, closes, lastDate, 20);
        Double ret60d = dates.isEmpty() ? null : alignedReturn(dates, closes, lastDate, 60);

        @SuppressWarnings("unchecked")
        List<String> benchDates = (List<String>) benchmark.get("dates");
        @SuppressWarnings("unchecked")
        List<Double> benchCloses = (List<Double>) benchmark.get("closes");
        @SuppressWarnings("unchecked")
        Map<String, Double> benchMap = (Map<String, Double>) benchmark.get("map");

        Double bench5d = benchReturn(benchDates, benchCloses, lastDate, 5);
        Double bench20d = benchReturn(benchDates, benchCloses, lastDate, 20);
        Double bench60d = benchReturn(benchDates, benchCloses, lastDate, 60);

        Double ma20 = closes.size() >= 20 ? mean(closes.subList(closes.size() - 20, closes.size())) : null;
        Boolean aboveMa20 = ma20 == null || ma20 == 0 ? null : closes.get(closes.size() - 1) > ma20;
        Double ma20Dev = ma20 == null || ma20 == 0 ? null : (closes.get(closes.size() - 1) / ma20 - 1) * 100;

        List<Double> recentAmounts = new ArrayList<>();
        for (Double item : amounts.subList(Math.max(0, amounts.size() - 5), amounts.size())) {
            if (item != null && item != 0) {
                recentAmounts.add(item);
            }
        }
        Double averageAmount = mean(recentAmounts);
        Double amountRatio = null;
        if (amount != null && amount != 0 && averageAmount != null && averageAmount != 0) {
            double expected = averageAmount;
            if (progress != null && progress >= 0.1 && progress <= 0.95) {
                expected = averageAmount * progress;
            }
            if (expected > 0) {
                amountRatio = amount * 1e8 / expected;
            }
        }

        Map<String, Object> sector = new LinkedHashMap<>();
        sector.put("name", name);
        sector.put("chg_pct", round(chg, 2));
        sector.put("amount", round(amount, 2));
        sector.put("net_inflow", round(netInflow, 2));
        sector.put("up_count", upCount);
        sector.put("down_count", downCount);
        sector.put("up_ratio", upCount + downCount >= 3 ? round((double) upCount / (upCount + downCount), 2) : null);
        Map<String, Object> leader = new LinkedHashMap<>();
        String leaderName = str(summary.get("领涨股")).strip();
        leader.put("name", leaderName.isEmpty() ? null : leaderName);
        leader.put("chg_pct", round(num(summary.get("领涨股-涨跌幅")), 2));
        sector.put("leader", leader);
        sector.put("ret_5d", round(ret5d, 2));
        sector.put("ret_20d", round(ret20d, 2));
        sector.put("ret_60d", round(ret60d, 2));
        sector.put("excess_5d", ret5d != null && bench5d != null ? round(ret5d - bench5d, 2) : null);
        sector.put("excess_20d", ret20d != null && bench20d != null ? round(ret20d - bench20d, 2) : null);
        sector.put("excess_60d", ret60d != null && bench60d != null ? round(ret60d - bench60d, 2) : null);
        sector.put("rs_days_10", dates.isEmpty() ? null : rsDays(dates, closes, benchMap));
        sector.put("ma20", round(ma20, 2));
        sector.put("ma20_dev_pct", round(ma20Dev, 2));
        sector.put("above_ma20", aboveMa20);
        sector.put("amount_ratio", round(amountRatio, 2));
        sector.put("history_days", closes.size());
        sector.put("last_date", lastDate.isEmpty() ? null : lastDate);
        sector.put("total", total);
        return sector;
    }

    private static Double benchReturn(List<String> dates, List<Double> closes, String lastDate, int days) {
        if (lastDate.isEmpty() || dates.isEmpty()) {
            return null;
        }
        return alignedReturn(dates, closes, lastDate, days);
    }

    // ------------------------------------------------------------------ 汇总

    /** 组装轮动结果：排名、趋势标签、反转信号与广度统计。 */
    public static Map<String, Object> build(
            List<Map<String, Object>> summary,
            Map<String, List<Map<String, Object>>> history,
            Map<String, Double> benchmarkCloses,
            Double benchmarkToday,
            Map<String, Object> session,
            String asOf,
            int limit,
            Map<String, String> errors,
            List<String> notes) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : summary) {
            String name = str(row.get("板块")).strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        int total = names.size();
        Map<String, Object> benchmark = makeBenchmark(benchmarkCloses);
        Double progress = session == null ? null : num(session.get("progress"));

        List<Map<String, Object>> sectors = new ArrayList<>();
        for (Map<String, Object> row : summary) {
            String name = str(row.get("板块")).strip();
            if (name.isEmpty()) {
                continue;
            }
            Map<String, Object> sector =
                    sectorStats(row, history.getOrDefault(name, List.of()), benchmark, total, progress);
            Double chg = num(sector.get("chg_pct"));
            sector.put(
                    "excess_today",
                    chg != null && benchmarkToday != null ? round(chg - benchmarkToday, 2) : null);
            sector.put("score", score(sector));
            sectors.add(sector);
        }

        Map<String, Integer> rankToday = rank(sectors, "excess_today");
        Map<String, Integer> rank5d = rank(sectors, "excess_5d");
        Map<String, Integer> rank20d = rank(sectors, "excess_20d");
        for (Map<String, Object> sector : sectors) {
            String name = str(sector.get("name"));
            sector.put("rank_today", rankToday.get(name));
            sector.put("rank_5d", rank5d.get(name));
            sector.put("rank_20d", rank20d.get(name));
            sector.put("trend", trendLabel(rank5d.get(name), rank20d.get(name), total));
            sector.put("persistence", persistence(intOf(sector.get("rs_days_10")), num(sector.get("score"))));
            sector.put("reversal_signals", reversalSignals(sector));
            sector.put("drivers", drivers(sector));
        }

        List<Map<String, Object>> byScore = new ArrayList<>();
        for (Map<String, Object> item : sectors) {
            if (item.get("score") != null) {
                byScore.add(item);
            }
        }
        byScore.sort(Comparator.comparingDouble((Map<String, Object> item) -> num(item.get("score"))).reversed());
        int usableDays = 0;
        for (Map<String, Object> item : sectors) {
            Integer days = intOf(item.get("history_days"));
            usableDays = Math.max(usableDays, days == null ? 0 : days);
        }
        if (usableDays < 61) {
            notes.add("板块历史不足 60 个交易日，60 日相对强度可能为空");
        }
        notes.add("行业强弱按相对沪深300的超额收益排序，单日涨幅权重仅一成");
        notes.add("盘中数据按交易进度折算成交额，开盘初期倍数波动较大");

        int upIndustries = 0;
        int downIndustries = 0;
        List<Double> changes = new ArrayList<>();
        for (Map<String, Object> item : sectors) {
            Double chg = num(item.get("chg_pct"));
            changes.add(chg);
            if (chg != null && chg > 0) {
                upIndustries++;
            }
            if (chg != null && chg < 0) {
                downIndustries++;
            }
        }
        Map<String, Object> breadth = new LinkedHashMap<>();
        breadth.put("up_industries", upIndustries);
        breadth.put("down_industries", downIndustries);
        breadth.put(
                "up_ratio",
                upIndustries + downIndustries == 0
                        ? null
                        : round((double) upIndustries / (upIndustries + downIndustries), 2));
        breadth.put("median_chg_pct", round(upperMedian(changes), 2));

        List<Map<String, Object>> leaders = new ArrayList<>();
        for (Map<String, Object> item : byScore.subList(0, Math.min(Math.max(1, Math.min(limit, 20)), byScore.size()))) {
            leaders.add(publicView(item));
        }
        List<Map<String, Object>> weak = new ArrayList<>();
        List<Map<String, Object>> reversed = new ArrayList<>(byScore);
        java.util.Collections.reverse(reversed);
        for (Map<String, Object> item : reversed.subList(0, Math.min(LIST_LIMIT, reversed.size()))) {
            weak.add(publicView(item));
        }
        List<Map<String, Object>> persistent = new ArrayList<>();
        for (Map<String, Object> item : byScore) {
            if ("高".equals(item.get("persistence")) && persistent.size() < LIST_LIMIT) {
                persistent.add(publicView(item));
            }
        }

        String tradeDate = null;
        for (Map<String, Object> item : sectors) {
            String lastDate = str(item.get("last_date"));
            if (tradeDate == null || lastDate.compareTo(tradeDate) > 0) {
                tradeDate = lastDate;
            }
        }
        if (tradeDate == null || tradeDate.isEmpty()) {
            tradeDate = null;
        }

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map<String, Object> item : byScore) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", item.get("name"));
            row.put("score", item.get("score"));
            row.put("chg_pct", item.get("chg_pct"));
            row.put("trend", item.get("trend"));
            ranking.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("as_of", asOf);
        result.put("trade_date", tradeDate);
        result.put("session", session);
        result.put("benchmark", BENCHMARK_NAME);
        result.put("benchmark_today", round(benchmarkToday, 2));
        result.put("intraday", (progress == null ? 0 : progress) < 0.999);
        result.put("total_sectors", total);
        result.put("breadth", breadth);
        result.put("leaders", leaders);
        result.put("emerging", pick(byScore, EMERGING, LIST_LIMIT));
        result.put("weakening", pick(byScore, WEAKENING, LIST_LIMIT));
        result.put("persistent", persistent);
        result.put("weak", weak);
        result.put("ranking", ranking);
        result.put("notes", notes);
        result.put("errors", errors);
        return result;
    }

    private static List<Map<String, Object>> pick(List<Map<String, Object>> byScore, String label, int size) {
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> item : byScore) {
            if (label.equals(item.get("trend")) && picked.size() < size) {
                picked.add(publicView(item));
            }
        }
        return picked;
    }

    private static Map<String, Integer> rank(List<Map<String, Object>> sectors, String key) {
        List<Map<String, Object>> usable = new ArrayList<>();
        for (Map<String, Object> item : sectors) {
            if (item.get(key) != null) {
                usable.add(item);
            }
        }
        usable.sort(Comparator.comparingDouble((Map<String, Object> item) -> num(item.get(key))).reversed());
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int index = 0; index < usable.size(); index++) {
            ranks.put(str(usable.get(index).get("name")), index + 1);
        }
        return ranks;
    }

    /** 对外只暴露这些字段，模型不必看中间量。 */
    private static Map<String, Object> publicView(Map<String, Object> sector) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (String key :
                List.of(
                        "name", "chg_pct", "excess_5d", "excess_20d", "excess_60d", "ret_5d", "ret_20d",
                        "ret_60d", "rs_days_10", "up_ratio", "up_count", "down_count", "above_ma20",
                        "ma20_dev_pct", "amount", "amount_ratio", "net_inflow", "leader", "trend",
                        "persistence", "score", "drivers", "reversal_signals")) {
            view.put(key, sector.get(key));
        }
        return view;
    }

    static List<String> drivers(Map<String, Object> sector) {
        List<String> items = new ArrayList<>();
        Double chg = num(sector.get("chg_pct"));
        Double excess5d = num(sector.get("excess_5d"));
        Double excess20d = num(sector.get("excess_20d"));
        Integer rsDays = intOf(sector.get("rs_days_10"));
        Double upRatio = num(sector.get("up_ratio"));
        Double amountRatio = num(sector.get("amount_ratio"));
        if (chg != null) {
            items.add("今日" + signed(chg, 2) + "%");
        }
        if (excess5d != null) {
            items.add("5日超额" + signed(excess5d, 2) + "%");
        }
        if (excess20d != null) {
            items.add("20日超额" + signed(excess20d, 2) + "%");
        }
        if (rsDays != null) {
            items.add("近10日有" + rsDays + "天跑赢基准");
        }
        if (upRatio != null) {
            items.add("上涨家数占比" + fixed(upRatio * 100, 0) + "%");
        }
        if (amountRatio != null) {
            items.add("成交额约为近期同期" + fixed(amountRatio, 1) + "倍");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> leader = (Map<String, Object>) sector.get("leader");
        if (leader != null && leader.get("name") != null) {
            Double leaderChg = num(leader.get("chg_pct"));
            items.add("领涨股" + leader.get("name") + signed(leaderChg == null ? 0 : leaderChg, 2) + "%");
        }
        return items;
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String cut(String value, int size) {
        return value.length() <= size ? value : value.substring(0, size);
    }

    private static Integer intOf(Object value) {
        Double number = num(value);
        return number == null ? null : (int) (double) number;
    }

    private static int intOrZero(Object value) {
        Integer number = intOf(value);
        return number == null ? 0 : number;
    }
}
