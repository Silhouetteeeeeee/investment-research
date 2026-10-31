package com.investmentresearch.core.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code research/screening.py} 的纯计算：全市场硬过滤 → 板块配额选样 → 日线量化打分。
 *
 * <p>只回答「哪些股票在量价上值得继续看」，不判断基本面好坏，也不给估值结论。
 */
public final class Screening {
    public static final int MIN_BARS = 40;
    public static final int DEFAULT_LIMIT = 15;
    public static final int MAX_PER_BOARD = 4;
    public static final double DEFAULT_MIN_AMOUNT_YI = 1.0;
    public static final double DEFAULT_MIN_PRICE = 2.0;
    public static final List<String> EXCLUDED_BOARDS = List.of("创业板", "科创板", "北交所");
    public static final int SHORTLIST_SIZE = 30;

    /** 按板块配额取成交额靠前的股票，避免候选全是沪深主板的权重股。 */
    public static final Map<String, Integer> BOARD_QUOTAS = boardQuotas();

    static final Map<String, Double> WEIGHTS =
            Map.of("trend", 0.35, "momentum", 0.30, "position", 0.20, "volume", 0.15);

    private Screening() {}

    private static Map<String, Integer> boardQuotas() {
        Map<String, Integer> quotas = new LinkedHashMap<>();
        quotas.put("沪市主板", 15);
        quotas.put("深市主板", 15);
        return quotas;
    }

    /** 按代码前缀给出板块名，用于配额和说明。 */
    public static String boardOf(String code) {
        String value = code == null ? "" : code.toLowerCase();
        if (value.startsWith("bj")) {
            return "北交所";
        }
        if (value.startsWith("sh688") || value.startsWith("sh689")) {
            return "科创板";
        }
        if (value.startsWith("sz30")) {
            return "创业板";
        }
        if (value.startsWith("sh60")) {
            return "沪市主板";
        }
        if (value.startsWith("sz00")) {
            return "深市主板";
        }
        return "其他";
    }

    /** N/C 开头是上市首日或前几日的简写。 */
    static boolean isNewListing(String name) {
        String text = name == null ? "" : name.strip().toUpperCase();
        return text.startsWith("N") || text.startsWith("C");
    }

    /** 返回被剔除的原因；可以通过返回 {@code null}。 */
    public static String exclusionReason(String name, Object price, Object amount) {
        String text = name == null ? "" : name;
        if (text.toUpperCase().contains("ST") || text.contains("退")) {
            return "ST/退市";
        }
        if (isNewListing(text)) {
            return "上市新股";
        }
        Double priceValue = SectorRotation.num(price);
        Double amountValue = SectorRotation.num(amount);
        if (priceValue == null || priceValue == 0 || priceValue <= 0) {
            return "停牌或无报价";
        }
        if (amountValue == null || amountValue <= 0) {
            return "无成交";
        }
        return null;
    }

    /** 按成交额在各自板块里取前几名，返回候选与过滤统计。 */
    public static Map<String, Object> shortlist(
            List<Map<String, Object>> rows, double minAmountYi, double minPrice) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("scanned", rows.size());
        Map<String, Integer> excluded = new LinkedHashMap<>();
        int afterFilters = 0;

        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (String board : BOARD_QUOTAS.keySet()) {
            buckets.put(board, new ArrayList<>());
        }
        Map<String, List<Double>> amounts = new LinkedHashMap<>();
        for (String board : BOARD_QUOTAS.keySet()) {
            amounts.put(board, new ArrayList<>());
        }

        for (Map<String, Object> row : rows) {
            String code = text(row.get("代码")).strip().toLowerCase();
            String name = first(text(row.get("名称")), text(row.get("name"))).strip();
            if (code.isEmpty() || name.isEmpty()) {
                continue;
            }
            String reason = exclusionReason(name, row.get("最新价"), row.get("成交额"));
            if (reason != null) {
                excluded.merge(reason, 1, Integer::sum);
                continue;
            }
            Double price = SectorRotation.num(row.get("最新价"));
            Double amount = SectorRotation.num(row.get("成交额"));
            if (price == null || amount == null || price < minPrice) {
                excluded.merge("价格或成交额不合适", 1, Integer::sum);
                continue;
            }
            if (amount < minAmountYi * 1e8) {
                excluded.merge("成交额不足", 1, Integer::sum);
                continue;
            }
            String board = boardOf(code);
            if (!buckets.containsKey(board)) {
                continue;
            }
            buckets.get(board).add(row);
            amounts.get(board).add(amount);
            afterFilters++;
        }

        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map.Entry<String, Integer> quota : BOARD_QUOTAS.entrySet()) {
            List<Map<String, Object>> items = buckets.get(quota.getKey());
            List<Double> values = amounts.get(quota.getKey());
            Integer[] order = new Integer[items.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            // 等价于 Python 的 sorted(..., reverse=True)：稳定，同额保持原顺序
            java.util.Arrays.sort(order, (a, b) -> Double.compare(values.get(b), values.get(a)));
            for (int i = 0; i < Math.min(quota.getValue(), order.length); i++) {
                picked.add(items.get(order[i]));
            }
        }
        stats.put("excluded", excluded);
        stats.put("after_filters", afterFilters);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("picked", picked);
        result.put("stats", stats);
        return result;
    }

    /** 把快照和日线合成一只股票的量化指标。 */
    public static Map<String, Object> stockStats(Map<String, Object> row, List<Map<String, Object>> history) {
        String code = text(row.get("代码")).strip().toLowerCase();
        String name = text(row.get("名称")).strip();
        Double price = SectorRotation.num(row.get("最新价"));
        Double chg = SectorRotation.num(row.get("涨跌幅"));
        Double spotAmount = SectorRotation.num(row.get("成交额"));

        List<Double> closes = new ArrayList<>();
        List<Double> highs = new ArrayList<>();
        List<Double> lows = new ArrayList<>();
        List<Double> amounts = new ArrayList<>();
        Map<String, Object> last = Map.of();
        for (Map<String, Object> item : history) {
            Double close = SectorRotation.num(item.get("close"));
            if (close == null) {
                continue;
            }
            closes.add(close);
            highs.add(SectorRotation.num(item.get("high")));
            lows.add(SectorRotation.num(item.get("low")));
            amounts.add(SectorRotation.num(item.get("amount")));
            last = item;
        }

        int bars = closes.size();
        Double ma20 = bars >= 20 ? mean(closes.subList(bars - 20, bars)) : null;
        Double ma60 = bars >= 60 ? mean(closes.subList(bars - 60, bars)) : null;
        Double latestClose = closes.isEmpty() ? price : closes.get(bars - 1);
        Double reference = latestClose != null ? latestClose : price;

        Double ret20d = null;
        if (bars >= 21 && closes.get(bars - 21) != 0) {
            ret20d = (closes.get(bars - 1) / closes.get(bars - 21) - 1) * 100;
        }
        Double ret60d = null;
        if (bars >= 61 && closes.get(bars - 61) != 0) {
            ret60d = (closes.get(bars - 1) / closes.get(bars - 61) - 1) * 100;
        }

        Double windowHigh = null;
        for (Double item : tail(highs, 60)) {
            if (item != null && (windowHigh == null || item > windowHigh)) {
                windowHigh = item;
            }
        }
        Double windowLow = null;
        for (Double item : tail(lows, 60)) {
            if (item != null && (windowLow == null || item < windowLow)) {
                windowLow = item;
            }
        }
        Double pos60d = null;
        if (windowHigh != null && windowLow != null && windowHigh > windowLow && reference != null && reference != 0) {
            pos60d = (reference - windowLow) / (windowHigh - windowLow);
        }
        Double drawdown = null;
        if (windowHigh != null && reference != null && reference != 0) {
            drawdown = (reference / windowHigh - 1) * 100;
        }

        Double averageAmount = null;
        if (amounts.size() >= 21) {
            averageAmount = mean(amounts.subList(amounts.size() - 21, amounts.size() - 1));
        }
        Double amountRatio = null;
        Double lastAmount = SectorRotation.num(last.get("amount"));
        if (averageAmount != null && averageAmount != 0 && lastAmount != null && lastAmount != 0) {
            amountRatio = lastAmount / averageAmount;
        }

        Double outstanding = SectorRotation.num(last.get("outstanding_share"));
        Double marketCap = null;
        if (outstanding != null && outstanding != 0 && reference != null && reference != 0) {
            marketCap = reference * outstanding / 1e8;
        }
        Double turnover = SectorRotation.num(last.get("turnover"));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("code", code);
        stats.put("name", name);
        stats.put("board", boardOf(code));
        stats.put("price", SectorRotation.round(price, 2));
        stats.put("chg_pct", SectorRotation.round(chg, 2));
        stats.put(
                "spot_amount_yi",
                spotAmount == null || spotAmount == 0 ? null : SectorRotation.round(spotAmount / 1e8, 2));
        stats.put("bars", bars);
        stats.put("ma20", SectorRotation.round(ma20, 2));
        stats.put("ma60", SectorRotation.round(ma60, 2));
        stats.put("above_ma20", truthy(ma20) && reference != null && reference != 0 ? reference > ma20 : null);
        stats.put("above_ma60", truthy(ma60) && reference != null && reference != 0 ? reference > ma60 : null);
        stats.put("ma20_above_ma60", truthy(ma20) && truthy(ma60) ? ma20 > ma60 : null);
        stats.put("ret_20d", SectorRotation.round(ret20d, 2));
        stats.put("ret_60d", SectorRotation.round(ret60d, 2));
        stats.put("high_60d", SectorRotation.round(windowHigh, 2));
        stats.put("low_60d", SectorRotation.round(windowLow, 2));
        stats.put("pos_60d", SectorRotation.round(pos60d, 2));
        stats.put("drawdown_from_high", SectorRotation.round(drawdown, 2));
        stats.put("amount_ratio", SectorRotation.round(amountRatio, 2));
        stats.put("turnover_pct", turnover == null ? null : SectorRotation.round(turnover * 100, 2));
        stats.put("market_cap_yi", SectorRotation.round(marketCap, 2));
        return stats;
    }

    static Double componentTrend(Map<String, Object> stats) {
        // Arrays.asList：缺中期指标时这里会是 null，List.of 不允许
        List<Object> checks = java.util.Arrays.asList(
                stats.get("above_ma20"), stats.get("above_ma60"), stats.get("ma20_above_ma60"));
        int known = 0;
        int hits = 0;
        for (Object item : checks) {
            if (item == null) {
                continue;
            }
            known++;
            if (Boolean.TRUE.equals(item)) {
                hits++;
            }
        }
        return known == 0 ? null : (double) hits / known * 2 - 1;
    }

    static Double componentMomentum(Map<String, Object> stats) {
        List<Double> parts = new ArrayList<>();
        Double ret20d = SectorRotation.num(stats.get("ret_20d"));
        if (ret20d != null) {
            parts.add(SectorRotation.clip(ret20d / 20, -1, 1));
        }
        Double ret60d = SectorRotation.num(stats.get("ret_60d"));
        if (ret60d != null) {
            parts.add(SectorRotation.clip(ret60d / 40, -1, 1));
        }
        return mean(parts);
    }

    /** 60 日区间位置：0.75 附近最好，贴着高点扣分，接近低点不加分。 */
    static Double componentPosition(Map<String, Object> stats) {
        Double pos = SectorRotation.num(stats.get("pos_60d"));
        if (pos == null) {
            return null;
        }
        if (pos >= 0.98) {
            return 0.2;
        }
        return SectorRotation.clip(1 - Math.abs(pos - 0.75) / 0.75, -1, 1);
    }

    static Double componentVolume(Map<String, Object> stats) {
        Double ratio = SectorRotation.num(stats.get("amount_ratio"));
        return ratio == null ? null : SectorRotation.clip((ratio - 1) / 0.5, -1, 1);
    }

    /** 合成量化分（-10 到 10）；缺数据的维度不参与，权重重归一。 */
    public static Double scoreStock(Map<String, Object> stats) {
        Double bars = SectorRotation.num(stats.get("bars"));
        if (bars == null || bars < MIN_BARS) {
            return null;
        }
        List<Double> values = List.of(
                componentTrend(stats), componentMomentum(stats), componentPosition(stats), componentVolume(stats));
        List<Double> weights = List.of(
                WEIGHTS.get("trend"), WEIGHTS.get("momentum"), WEIGHTS.get("position"), WEIGHTS.get("volume"));
        double total = 0;
        double weight = 0;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) {
                continue;
            }
            total += values.get(i) * weights.get(i);
            weight += weights.get(i);
        }
        if (weight == 0) {
            return null;
        }
        double score = SectorRotation.clip(total / weight, -1, 1) * 10;
        // 上市不足 60 日的股票缺中期维度，打分整体打折
        if (bars < 60) {
            score *= 0.9;
        }
        return SectorRotation.round(score, 2);
    }

    /** 保留打分顺序，但同一板块最多 {@code perBoard} 只。 */
    public static List<Map<String, Object>> diversify(
            List<Map<String, Object>> items, int limit, int perBoard) {
        List<Map<String, Object>> picked = new ArrayList<>();
        Map<String, Integer> used = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String board = text(item.get("board"));
            if (board.isEmpty()) {
                board = "其他";
            }
            int count = used.getOrDefault(board, 0);
            if (count >= perBoard) {
                continue;
            }
            used.put(board, count + 1);
            picked.add(item);
            if (picked.size() >= limit) {
                break;
            }
        }
        return picked;
    }

    public static List<String> reasons(Map<String, Object> stats) {
        List<String> items = new ArrayList<>();
        Object above20 = stats.get("above_ma20");
        Object above60 = stats.get("above_ma60");
        if (Boolean.TRUE.equals(above20) && Boolean.TRUE.equals(above60)) {
            items.add("站上20日与60日均线");
        } else if (Boolean.TRUE.equals(above20)) {
            items.add("站上20日均线，60日均线仍未收复");
        } else if (Boolean.FALSE.equals(above20)) {
            items.add("仍在20日均线下方");
        }
        addIfPresent(items, "20日", stats.get("ret_20d"), 1, "%");
        addIfPresent(items, "60日", stats.get("ret_60d"), 1, "%");
        addIfPresent(items, "距60日高点", stats.get("drawdown_from_high"), 1, "%");
        Double amountRatio = SectorRotation.num(stats.get("amount_ratio"));
        if (amountRatio != null) {
            items.add("成交额约为20日均值" + SectorRotation.fixed(amountRatio, 1) + "倍");
        }
        Double turnover = SectorRotation.num(stats.get("turnover_pct"));
        if (turnover != null) {
            items.add("换手" + SectorRotation.fixed(turnover, 2) + "%");
        }
        Double marketCap = SectorRotation.num(stats.get("market_cap_yi"));
        if (marketCap != null) {
            items.add("市值约" + SectorRotation.fixed(marketCap, 0) + "亿");
        }
        return items;
    }

    public static List<String> risks(Map<String, Object> stats) {
        List<String> items = new ArrayList<>();
        Double pos = SectorRotation.num(stats.get("pos_60d"));
        if (pos != null && pos >= 0.95) {
            items.add("贴着60日高点，位置偏高，追高风险");
        }
        if (Boolean.FALSE.equals(stats.get("above_ma60"))) {
            items.add("仍在60日均线下方，中期趋势未修复");
        }
        Double ratio = SectorRotation.num(stats.get("amount_ratio"));
        if (ratio != null && ratio < 0.7) {
            items.add("成交额缩到20日均值的" + SectorRotation.fixed(ratio, 1) + "倍，关注度下降");
        }
        Double ret20d = SectorRotation.num(stats.get("ret_20d"));
        if (ret20d != null && ret20d > 35) {
            items.add("20日涨幅超过35%，短期涨幅偏大");
        }
        Double bars = SectorRotation.num(stats.get("bars"));
        if (bars != null && bars < 60) {
            items.add("历史不足60个交易日，均线与区间指标参考性弱");
        }
        Double marketCap = SectorRotation.num(stats.get("market_cap_yi"));
        if (marketCap != null && marketCap < 50) {
            items.add("市值不足50亿，流动性与波动风险较高");
        }
        return items;
    }

    /** 对外只暴露这些字段。 */
    public static Map<String, Object> publicView(Map<String, Object> stats) {
        List<String> keys = List.of(
                "code", "name", "board", "price", "chg_pct", "market_cap_yi", "ret_20d", "ret_60d", "pos_60d",
                "drawdown_from_high", "above_ma20", "above_ma60", "ma20", "ma60", "amount_ratio", "turnover_pct",
                "spot_amount_yi", "score", "reasons", "risks", "valuation", "bars");
        Map<String, Object> view = new LinkedHashMap<>();
        for (String key : keys) {
            view.put(key, stats.get(key));
        }
        return view;
    }

    private static void addIfPresent(List<String> items, String label, Object raw, int digits, String suffix) {
        Double value = SectorRotation.num(raw);
        if (value != null) {
            items.add(label + SectorRotation.signed(value, digits) + suffix);
        }
    }

    private static boolean truthy(Double value) {
        return value != null && value != 0;
    }

    private static List<Double> tail(List<Double> values, int size) {
        return values.subList(Math.max(0, values.size() - size), values.size());
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

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String first(String a, String b) {
        return a.isEmpty() ? b : a;
    }
}
