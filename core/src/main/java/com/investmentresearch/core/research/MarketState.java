package com.investmentresearch.core.research;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code research/market_state.py}：把大盘全景归类为强势/震荡/弱势/极端风险。
 *
 * <p>纯函数：输入是 {@code build_market_overview()} 形状的 Map，缺哪块就记在 {@code missing} 里，
 * 不抛异常。规则逐条对应 Python，输出键的顺序与文字也一致，测试直接比金标准。
 */
public final class MarketState {
    public static final String STRONG = "强势";
    public static final String RANGE = "震荡";
    public static final String WEAK = "弱势";
    public static final String EXTREME = "极端风险";

    static final List<String> TREND_CODES =
            List.of("sh000001", "sz399001", "sz399006", "sh000300");
    static final String BENCHMARK_CODE = "sh000300";

    private MarketState() {}

    public static Map<String, Object> classify(Map<String, Object> overview) {
        Map<String, Object> source = overview == null ? Map.of() : overview;
        List<Map<String, Object>> items = trendItems(source);
        Map<String, Object> benchmark = benchmark(items);
        Double indexChg = indexChange(source, benchmark);
        Map<String, Object> breadth = mapOrNull(source.get("breadth"));
        Map<String, Object> turnover = mapOrNull(source.get("turnover"));

        Map<String, Object> trend = trendSignal(items);
        Map<String, Object> breadthSignal = breadthSignal(breadth);
        Map<String, Object> volume = volumeSignal(turnover, indexChg);
        Map<String, Object> volatility = volatilitySignal(items, number(trend.get("score")));
        Map<String, Object> structure =
                structureSignal(
                        mapOrNull(source.get("sectors")),
                        mapOrNull(source.get("limit_up")),
                        breadth);

        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("趋势", trend);
        signals.put("市场宽度", breadthSignal);
        signals.put("量能", volume);
        signals.put("波动", volatility);
        signals.put("结构", structure);

        List<Double> scores = new ArrayList<>();
        for (Object item : signals.values()) {
            Double score = number(mapOrNull(item).get("score"));
            if (score != null) {
                scores.add(score);
            }
        }
        Double total = scores.isEmpty() ? null : sum(scores);

        int belowMa20 = 0;
        for (Map<String, Object> item : items) {
            if (Boolean.FALSE.equals(item.get("above_ma20"))) {
                belowMa20++;
            }
        }
        List<String> extremeFlags =
                extremeFlags(indexChg, breadth, turnover, number(volatility.get("volatility_20d_pct")), belowMa20);

        String state;
        if (extremeFlags.size() >= 2) {
            state = EXTREME;
        } else if (total == null) {
            state = RANGE;
        } else if (total >= 2.5) {
            state = STRONG;
        } else if (total <= -2.0) {
            state = WEAK;
        } else {
            state = RANGE;
        }

        Double volatilityValue = number(volatility.get("volatility_20d_pct"));
        Double limitDown = number((breadth == null ? Map.<String, Object>of() : breadth).get("limit_down"));
        String riskLevel;
        if (state.equals(EXTREME)) {
            riskLevel = "极高";
        } else if (state.equals(WEAK)) {
            riskLevel =
                    ((volatilityValue != null && volatilityValue >= 25)
                                    || (limitDown != null && limitDown >= 30)
                                    || (number(trend.get("score")) != null
                                            && number(trend.get("score")) <= -1.5))
                            ? "高"
                            : "中";
        } else if (state.equals(STRONG)) {
            riskLevel = (volatilityValue != null && volatilityValue >= 25) ? "中" : "低";
        } else {
            riskLevel =
                    ((volatilityValue != null && volatilityValue >= 25)
                                    || (number(breadthSignal.get("score")) != null
                                            && number(breadthSignal.get("score")) <= -1)
                                    || (number(trend.get("score")) != null
                                            && number(trend.get("score")) <= -1))
                            ? "中"
                            : "低";
        }

        List<String> missing = missing(source, signals);
        String confidence = missing.isEmpty() ? "高" : missing.size() <= 2 ? "中" : "低";
        String benchmarkName =
                benchmark == null || benchmark.get("name") == null
                        ? "沪深300"
                        : benchmark.get("name").toString();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("index_chg_pct", round(indexChg, 2));
        metrics.put("up_ratio_pct", breadthSignal.get("up_ratio_pct"));
        metrics.put("limit_up", breadthSignal.get("limit_up"));
        metrics.put("limit_down", breadthSignal.get("limit_down"));
        metrics.put("total_yi", volume.get("total_yi"));
        metrics.put("pace_ratio", volume.get("pace_ratio"));
        metrics.put("volatility_20d_pct", volatilityValue);
        metrics.put("trend_score", trend.get("score"));
        metrics.put("breadth_score", breadthSignal.get("score"));
        metrics.put("volume_score", volume.get("score"));
        metrics.put("volatility_score", volatility.get("score"));
        metrics.put("structure_score", structure.get("score"));

        List<String> notes =
                List.of(
                        "状态由趋势、宽度、量能、波动与结构五项规则打分得出；极端信号出现两项以上时直接覆盖为极端风险",
                        "盘中量能是按当前成交速度折算的估算值，收盘后应重新判断");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("score", round(total, 2));
        result.put("risk_level", riskLevel);
        result.put("confidence", confidence);
        result.put("benchmark", benchmarkName);
        result.put("basis", new ArrayList<>(signals.values()));
        result.put("extreme_flags", extremeFlags);
        result.put("contradictions", contradictions(signals));
        result.put("invalidation", invalidation(state, benchmark));
        result.put("missing", missing);
        result.put("metrics", metrics);
        result.put("notes", notes);
        return result;
    }

    private static List<Map<String, Object>> trendItems(Map<String, Object> overview) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : listOrEmpty(overview.get("trend"))) {
            Map<String, Object> item = mapOrNull(raw);
            if (item != null && TREND_CODES.contains(text(item.get("code")))) {
                items.add(item);
            }
        }
        return items;
    }

    private static Map<String, Object> benchmark(List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            if (BENCHMARK_CODE.equals(text(item.get("code")))) {
                return item;
            }
        }
        for (Map<String, Object> item : items) {
            if ("sh000001".equals(text(item.get("code")))) {
                return item;
            }
        }
        return items.isEmpty() ? null : items.get(0);
    }

    /** 当日涨跌幅在指数快照里，不在日线统计里。 */
    private static Double indexChange(Map<String, Object> overview, Map<String, Object> benchmark) {
        String code = benchmark == null ? BENCHMARK_CODE : orText(benchmark.get("code"), BENCHMARK_CODE);
        Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
        for (Object raw : listOrEmpty(overview.get("indices"))) {
            Map<String, Object> item = mapOrNull(raw);
            if (item != null) {
                snapshots.put(text(item.get("code")), item);
            }
        }
        for (String wanted : List.of(code, BENCHMARK_CODE, "sh000001")) {
            Map<String, Object> item = snapshots.get(wanted);
            Double value = item == null ? null : number(item.get("chg_pct"));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, Object> trendSignal(List<Map<String, Object>> items) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("factor", "趋势");
        if (items.isEmpty()) {
            signal.put("reading", "指数日线缺失");
            signal.put("signal", "unknown");
            signal.put("score", null);
            signal.put("above_ma20", null);
            signal.put("above_ma60", null);
            return signal;
        }

        List<Double> scores = new ArrayList<>();
        int above20 = 0;
        int above60 = 0;
        int valid20 = 0;
        int valid60 = 0;
        for (Map<String, Object> item : items) {
            double score = 0.0;
            if (Boolean.TRUE.equals(item.get("above_ma20"))) {
                score += 1;
                above20++;
                valid20++;
            } else if (Boolean.FALSE.equals(item.get("above_ma20"))) {
                score -= 1;
                valid20++;
            }
            if (Boolean.TRUE.equals(item.get("above_ma60"))) {
                score += 1;
                above60++;
                valid60++;
            } else if (Boolean.FALSE.equals(item.get("above_ma60"))) {
                score -= 1;
                valid60++;
            }
            scores.add(score);
        }

        Double score = sum(scores) / scores.size();
        String reading =
                above20 + "/" + valid20 + " 个宽基在 MA20 上方，"
                        + above60 + "/" + valid60 + " 个在 MA60 上方";
        signal.put("reading", reading);
        signal.put("signal", direction(score, 0.25));
        signal.put("score", round(score, 2));
        signal.put("above_ma20", above20);
        signal.put("above_ma60", above60);
        return signal;
    }

    private static Map<String, Object> breadthSignal(Map<String, Object> breadth) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("factor", "市场宽度");
        if (breadth == null || breadth.isEmpty()) {
            signal.put("reading", "涨跌家数缺失");
            signal.put("signal", "unknown");
            signal.put("score", null);
            return signal;
        }
        Double up = number(breadth.get("up"));
        Double down = number(breadth.get("down"));
        Double ratio = number(breadth.get("up_ratio_pct"));
        Double flat = number(breadth.get("flat"));
        if (ratio == null && up != null && down != null) {
            double total = up + down + (flat == null ? 0 : flat);
            ratio = total == 0 ? null : up / total * 100;
        }

        Double score;
        String direction;
        String reading;
        if (ratio == null) {
            score = null;
            direction = "unknown";
            reading = "上涨家数占比无法计算";
        } else {
            if (ratio >= 60) {
                score = 2.0;
            } else if (ratio >= 52) {
                score = 1.0;
            } else if (ratio >= 45) {
                score = 0.0;
            } else if (ratio >= 35) {
                score = -1.0;
            } else {
                score = -2.0;
            }
            direction = score > 0 ? "positive" : score < 0 ? "negative" : "neutral";
            reading =
                    "上涨 " + text(round(up, 0)) + " 家、下跌 " + text(round(down, 0))
                            + " 家，上涨占比 " + text(round(ratio, 1)) + "%";
        }

        Double limitUp = number(breadth.get("limit_up"));
        Double limitDown = number(breadth.get("limit_down"));
        if (score != null && limitUp != null && limitDown != null) {
            if (limitUp >= Math.max(limitDown, 1) * 3 && limitUp >= 30) {
                score += 0.5;
            } else if (limitDown >= Math.max(limitUp, 1) * 2 && limitDown >= 10) {
                score -= 0.5;
            }
            reading += "；涨停 " + text(round(limitUp, 0)) + " 家、跌停 " + text(round(limitDown, 0)) + " 家";
        }
        signal.put("reading", reading);
        signal.put("signal", direction);
        signal.put("score", round(score, 2));
        signal.put("up_ratio_pct", round(ratio, 1));
        signal.put("limit_up", round(limitUp, 0));
        signal.put("limit_down", round(limitDown, 0));
        return signal;
    }

    private static Map<String, Object> volumeSignal(Map<String, Object> turnover, Double indexChg) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("factor", "量能");
        if (turnover == null || turnover.isEmpty()) {
            signal.put("reading", "成交额缺失");
            signal.put("signal", "unknown");
            signal.put("score", null);
            return signal;
        }
        Double pace = number(turnover.get("pace_ratio"));
        Double totalYi = number(turnover.get("total_yi"));
        if (pace == null) {
            signal.put("reading", "两市成交额 " + text(round(totalYi, 1)) + " 亿元，缺少近 20 日均量对比");
            signal.put("signal", "unknown");
            signal.put("score", null);
            signal.put("total_yi", round(totalYi, 1));
            return signal;
        }

        double score;
        if (pace >= 1.15) {
            score = indexChg != null && indexChg >= 0 ? 1.0 : -1.0;
        } else if (pace <= 0.8) {
            score = -0.5;
        } else {
            score = 0.0;
        }
        String direction = score > 0 ? "positive" : score < 0 ? "negative" : "neutral";
        String reading =
                "两市成交额 " + text(round(totalYi, 1)) + " 亿元，按当前节奏折算为近 20 日均量的 "
                        + text(round(pace, 2)) + " 倍";
        if (pace >= 1.15 && indexChg != null) {
            reading += "；放量" + (indexChg >= 0 ? "上涨" : "下跌");
        }
        signal.put("reading", reading);
        signal.put("signal", direction);
        signal.put("score", round(score, 2));
        signal.put("total_yi", round(totalYi, 1));
        signal.put("pace_ratio", round(pace, 2));
        return signal;
    }

    private static Map<String, Object> volatilitySignal(List<Map<String, Object>> items, Double trendScore) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("factor", "波动");
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Double value = number(item.get("volatility_20d_pct"));
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            signal.put("reading", "20 日波动率缺失");
            signal.put("signal", "unknown");
            signal.put("score", null);
            return signal;
        }
        double volatility = median(values);
        double score;
        if (volatility >= 30) {
            score = -1.0;
        } else if (volatility >= 22) {
            score = -0.5;
        } else if (volatility <= 12) {
            score = 0.5;
        } else {
            score = 0.0;
        }
        if (volatility >= 25 && trendScore != null && trendScore < 0) {
            score -= 0.5;
        }
        signal.put("reading", "主要宽基 20 日年化波动率中位数 " + text(round(volatility, 1)) + "%");
        signal.put("signal", score > 0 ? "positive" : score < 0 ? "negative" : "neutral");
        signal.put("score", round(score, 2));
        signal.put("volatility_20d_pct", round(volatility, 1));
        return signal;
    }

    private static Map<String, Object> structureSignal(
            Map<String, Object> sectors, Map<String, Object> limitUp, Map<String, Object> breadth) {
        double score = 0.0;
        List<String> parts = new ArrayList<>();
        if (sectors != null && truthy(sectors.get("total"))) {
            Double total = number(sectors.get("total"));
            if (total == null) {
                total = 0.0;
            }
            Double upCount = number(sectors.get("up_count"));
            if (upCount == null) {
                upCount = 0.0;
            }
            if (total != 0) {
                double ratio = upCount / total;
                if (ratio >= 0.6) {
                    score += 1.0;
                } else if (ratio <= 0.3) {
                    score -= 1.0;
                }
                parts.add(text(round(upCount, 0)) + "/" + text(round(total, 0)) + " 个行业上涨");
            }
        }
        Double count = number((limitUp == null ? Map.<String, Object>of() : limitUp).get("count"));
        Double streak = number((limitUp == null ? Map.<String, Object>of() : limitUp).get("max_streak"));
        if (count != null) {
            double streakValue = streak == null ? 0 : streak;
            if (count >= 80 && streakValue >= 4) {
                score += 0.5;
            } else if (count < 30) {
                score -= 0.5;
            }
            parts.add("涨停 " + text(round(count, 0)) + " 家、最高 " + text(round(streak, 0)) + " 连板");
        }
        Double limitDown = number((breadth == null ? Map.<String, Object>of() : breadth).get("limit_down"));
        if (limitDown != null && limitDown >= 30) {
            score -= 0.5;
            parts.add("跌停 " + text(round(limitDown, 0)) + " 家");
        }

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("factor", "结构");
        if (parts.isEmpty()) {
            signal.put("reading", "行业与涨停数据缺失");
            signal.put("signal", "unknown");
            signal.put("score", null);
            return signal;
        }
        signal.put("reading", String.join("；", parts));
        signal.put("signal", score > 0 ? "positive" : score < 0 ? "negative" : "neutral");
        signal.put("score", round(score, 2));
        return signal;
    }

    private static List<String> extremeFlags(
            Double indexChg,
            Map<String, Object> breadth,
            Map<String, Object> turnover,
            Double volatility,
            int belowMa20) {
        List<String> flags = new ArrayList<>();
        Double upRatio = number((breadth == null ? Map.<String, Object>of() : breadth).get("up_ratio_pct"));
        Double limitDown = number((breadth == null ? Map.<String, Object>of() : breadth).get("limit_down"));
        Double pace = number((turnover == null ? Map.<String, Object>of() : turnover).get("pace_ratio"));
        if (indexChg != null && indexChg <= -2.5) {
            flags.add("沪深300单日跌幅达到 2.5%");
        }
        if (upRatio != null && upRatio <= 25) {
            flags.add("上涨家数占比不足 25%");
        }
        if (limitDown != null && limitDown >= 60) {
            flags.add("跌停家数达到 60 家");
        }
        if (volatility != null && volatility >= 35 && belowMa20 >= 2) {
            flags.add("20 日年化波动率超过 35% 且多数宽基跌破 MA20");
        }
        if (pace != null && pace >= 1.5 && indexChg != null && indexChg <= -1.0) {
            flags.add("放量下跌，量能达到近 20 日均量的 1.5 倍以上");
        }
        return flags;
    }

    private static List<String> contradictions(Map<String, Object> signals) {
        List<String> items = new ArrayList<>();
        String trend = text(mapOrNull(signals.get("趋势")).get("signal"));
        String breadth = text(mapOrNull(signals.get("市场宽度")).get("signal"));
        String volume = text(mapOrNull(signals.get("量能")).get("signal"));
        String structure = text(mapOrNull(signals.get("结构")).get("signal"));
        if (trend.equals("positive") && breadth.equals("negative")) {
            items.add("指数趋势偏强，但多数股票在跌，上涨广度不足");
        }
        if (trend.equals("negative") && breadth.equals("positive")) {
            items.add("指数在均线下方，但上涨家数占优，属于结构性修复而非全面转强");
        }
        if (volume.equals("negative") && trend.equals("positive")) {
            items.add("指数偏强但量能不足，突破的持续性需要观察");
        }
        if (volume.equals("positive") && trend.equals("negative")) {
            items.add("放量但指数在均线下方，先按风险释放处理，不把放量直接当成见底");
        }
        if (structure.equals("positive") && breadth.equals("negative")) {
            items.add("少数行业与涨停股活跃，但整体上涨家数不足，赚钱效应集中");
        }
        return items;
    }

    private static List<String> missing(
            Map<String, Object> overview, Map<String, Object> signals) {
        List<String> items = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        names.put("趋势", "指数日线");
        names.put("市场宽度", "涨跌家数");
        names.put("量能", "成交额与均量");
        names.put("波动", "20 日波动率");
        names.put("结构", "行业与涨停数据");
        for (Map.Entry<String, String> entry : names.entrySet()) {
            if ("unknown".equals(text(mapOrNull(signals.get(entry.getKey())).get("signal")))) {
                items.add(entry.getValue() + "缺失");
            }
        }
        if (!truthy(overview.get("indices"))) {
            items.add("指数快照缺失");
        }
        return items;
    }

    private static List<String> invalidation(String state, Map<String, Object> benchmark) {
        String name = benchmark == null ? "沪深300" : orText(benchmark.get("name"), "沪深300");
        Double ma20 = benchmark == null ? null : number(benchmark.get("ma20"));
        Double high20 = benchmark == null ? null : number(benchmark.get("high_20d"));
        Double low20 = benchmark == null ? null : number(benchmark.get("low_20d"));
        String ma20Text = ma20 == null ? "" : "（" + text(round(ma20, 2)) + "）";
        String highText = high20 == null ? "" : "（" + text(round(high20, 2)) + "）";
        String lowText = low20 == null ? "" : "（" + text(round(low20, 2)) + "）";

        if (state.equals(EXTREME)) {
            return List.of(
                    "跌停家数回落到 30 家以下、上涨家数占比回到 35% 以上，极端风险可降级",
                    name + "不再创新低并重新站上 MA20" + ma20Text + "，才谈得上修复",
                    "成交额缩到近 20 日均量以下且指数止跌，说明抛压开始释放");
        }
        if (state.equals(WEAK)) {
            return List.of(
                    name + "重新站上 MA20" + ma20Text + "，且上涨家数占比连续两日超过 50%，弱势判断失效",
                    "跌停家数回落到 20 家以下，成交额恢复到近 20 日均量的 90% 以上，风险等级可下调",
                    "行业上涨家数占比连续两日超过 55%，结构由防御转向扩散");
        }
        if (state.equals(STRONG)) {
            return List.of(
                    name + "跌破 MA20" + ma20Text + "，或上涨家数占比连续两日低于 40%，强势判断失效",
                    "涨停家数较当前水平减半、最高连板高度下降，短线情绪转弱",
                    "放量滞涨或领涨板块出现放量下跌，需先降低节奏预期");
        }
        return List.of(
                name + "放量突破 20 日高点" + highText + "且上涨家数占比超过 60%，可上调整档",
                name + "跌破 20 日低点" + lowText + "，或跌停家数超过 30 家，需下调整档",
                "行业上涨家数占比连续两日低于 35%，震荡判断转向弱势");
    }

    /** Python {@code "positive" if score > high else "negative" if score < -high else "neutral"}。 */
    private static String direction(double score, double threshold) {
        return score > threshold ? "positive" : score < -threshold ? "negative" : "neutral";
    }

    private static double sum(List<Double> values) {
        double total = 0;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    /** Python {@code statistics.median}：偶数个取中间两个的平均。 */
    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int size = sorted.size();
        return size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    /** Python {@code round(value, digits)}：按二进制精确值取最近，平局取偶。 */
    static Double round(Double value, int digits) {
        return value == null
                ? null
                : new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** Python {@code float(value)}：None、"-"、"" 与非数字都返回 null。 */
    static Double number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            return Double.isNaN(decimal) ? null : decimal;
        }
        if (value instanceof Boolean flag) {
            return flag ? 1.0 : 0.0;
        }
        String raw = value.toString().strip();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** Python {@code str(float)}，只覆盖本项目会出现的小数量级。 */
    private static String text(Double value) {
        if (value == null) {
            return "None";
        }
        double abs = Math.abs(value);
        if (abs >= 1e7 || (abs != 0 && abs < 1e-3)) {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        }
        return Double.toString(value);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String orText(Object value, String fallback) {
        String text = text(value);
        return text.isEmpty() ? fallback : text;
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof java.util.Collection<?> items) {
            return !items.isEmpty();
        }
        if (value instanceof Map<?, ?> item) {
            return !item.isEmpty();
        }
        return true;
    }

    private static List<?> listOrEmpty(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrNull(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
