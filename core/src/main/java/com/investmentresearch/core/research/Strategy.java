package com.investmentresearch.core.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code research/strategy.py}：把市场状态、风险等级和用户的风险承受能力、当前仓位、
 * 持仓结构放在一起，给出有条件的操作预案。
 *
 * <p>规则都是确定性的；这里不做收益预测，也不给具体买卖点。
 */
public final class Strategy {
    public static final List<String> TOLERANCES = List.of("保守", "稳健", "积极");
    public static final String DEFAULT_TOLERANCE = "稳健";

    /** 总仓位上限（%），按市场状态 × 风险承受能力。 */
    static final Map<String, Map<String, Double>> STATE_CAPS =
            Map.of(
                    MarketState.STRONG, Map.of("保守", 50.0, "稳健", 70.0, "积极", 90.0),
                    MarketState.RANGE, Map.of("保守", 40.0, "稳健", 60.0, "积极", 75.0),
                    MarketState.WEAK, Map.of("保守", 20.0, "稳健", 35.0, "积极", 50.0),
                    MarketState.EXTREME, Map.of("保守", 5.0, "稳健", 10.0, "积极", 20.0));

    /** 单只股票上限（%）。 */
    static final Map<String, Map<String, Double>> SINGLE_CAPS =
            Map.of(
                    MarketState.STRONG, Map.of("保守", 10.0, "稳健", 15.0, "积极", 20.0),
                    MarketState.RANGE, Map.of("保守", 8.0, "稳健", 12.0, "积极", 15.0),
                    MarketState.WEAK, Map.of("保守", 5.0, "稳健", 8.0, "积极", 10.0),
                    MarketState.EXTREME, Map.of("保守", 3.0, "稳健", 5.0, "积极", 8.0));

    /** 止损比例（%），弱势与极端风险市场收紧。 */
    static final Map<String, Map<String, Double>> STOP_PCT =
            Map.of(
                    MarketState.STRONG, Map.of("保守", 5.0, "稳健", 8.0, "积极", 10.0),
                    MarketState.RANGE, Map.of("保守", 5.0, "稳健", 7.0, "积极", 9.0),
                    MarketState.WEAK, Map.of("保守", 4.0, "稳健", 6.0, "积极", 8.0),
                    MarketState.EXTREME, Map.of("保守", 3.0, "稳健", 5.0, "积极", 6.0));

    static final Map<String, Double> RISK_MULTIPLIER = Map.of("低", 1.0, "中", 1.0, "高", 0.85, "极高", 0.7);

    public static final String STANCE_REDUCE = "降低风险";
    public static final String STANCE_HOLD = "持有不加仓";
    public static final String STANCE_WATCH = "观望";
    public static final String STANCE_BUY = "研究买入（分批）";

    static final double REDUCE_OVER = 5.0;
    static final double AT_CAP_BAND = 5.0;
    static final double BUY_HEADROOM = 15.0;

    private Strategy() {}

    static String tolerance(Object value) {
        return value instanceof String text && TOLERANCES.contains(text) ? text : DEFAULT_TOLERANCE;
    }

    /** 仓位与现金比例：解析不了给 null，能解析就钳在 0..100。 */
    static Double number(Object value) {
        Double parsed = SectorRotation.num(value);
        return parsed == null ? null : SectorRotation.clip(parsed, 0.0, 100.0);
    }

    /** 成本价、现价这类「必须为正」的数，非正按没提供处理。 */
    static Double positive(Object value) {
        Double parsed = SectorRotation.num(value);
        return parsed == null || parsed <= 0 ? null : parsed;
    }

    /** 总仓位上限与现金下限，带上限来源，方便解释。 */
    public static Map<String, Object> positionCap(String state, Object riskLevel, Object tolerance) {
        String base = STATE_CAPS.containsKey(state) ? state : MarketState.RANGE;
        String profile = tolerance(tolerance);
        // 常量表在 Python 侧是整数，base_pct 输出保持整数
        int basePct = STATE_CAPS.get(base).get(profile).intValue();
        double multiplier = RISK_MULTIPLIER.getOrDefault(riskLevel == null ? "" : riskLevel.toString(), 1.0);
        double cap = SectorRotation.round(basePct * multiplier, 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_pct", cap);
        result.put("base_pct", basePct);
        result.put("base_state", base);
        result.put("base_tolerance", profile);
        result.put("risk_multiplier", multiplier);
        result.put("cash_floor_pct", SectorRotation.round(100 - cap, 1));
        return result;
    }

    public static double singleCap(String state, Object tolerance) {
        String base = SINGLE_CAPS.containsKey(state) ? state : MarketState.RANGE;
        return SINGLE_CAPS.get(base).get(tolerance(tolerance));
    }

    public static double stopLossPct(String state, Object tolerance) {
        String base = STOP_PCT.containsKey(state) ? state : MarketState.RANGE;
        return STOP_PCT.get(base).get(tolerance(tolerance));
    }

    static String stance(String state, Double position, double cap) {
        if (position == null) {
            return STANCE_WATCH;
        }
        if (position > cap + REDUCE_OVER) {
            return STANCE_REDUCE;
        }
        if (position >= cap - AT_CAP_BAND) {
            return STANCE_HOLD;
        }
        if (MarketState.WEAK.equals(state) || MarketState.EXTREME.equals(state)) {
            return STANCE_WATCH;
        }
        if (MarketState.STRONG.equals(state) && position <= cap - BUY_HEADROOM) {
            return STANCE_BUY;
        }
        return STANCE_WATCH;
    }

    /** 逐只检查超配、浮亏与止损，返回明细与汇总提醒。 */
    static Map<String, Object> holdingsPlan(List<Map<String, Object>> holdings, double single, double stopPct) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> item : holdings == null ? List.<Map<String, Object>>of() : holdings) {
            if (item == null) {
                continue;
            }
            String code = text(item.get("code")).strip();
            String name = first(text(item.get("name")), code, "未命名持仓");
            Double weight = number(item.get("weight_pct"));
            Double cost = positive(item.get("cost"));
            Double price = positive(item.get("price"));

            List<String> notes = new ArrayList<>();
            String status = "正常";
            if (weight != null && weight > single) {
                status = "超配";
                notes.add("占比" + SectorRotation.fixed(weight, 1) + "%超过单只上限" + SectorRotation.fixed(single, 0)
                        + "%，考虑降到上限以内");
            }
            Double pnl = null;
            Double stopPrice = null;
            if (cost != null && price != null) {
                pnl = (price / cost - 1) * 100;
                notes.add("相对成本" + SectorRotation.signed(pnl, 1) + "%");
                if (pnl <= -stopPct) {
                    status = "触发止损";
                    notes.add("亏损超过止损线" + SectorRotation.fixed(stopPct, 0) + "%，按纪律应先处理这只");
                }
                stopPrice = cost * (1 - stopPct / 100);
            }
            if (weight != null && weight <= 0) {
                status = "空仓";
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code.isEmpty() ? null : code);
            row.put("name", name);
            row.put("weight_pct", weight);
            row.put("pnl_pct", pnl == null ? null : SectorRotation.round(pnl, 2));
            row.put("stop_price", stopPrice == null || stopPrice == 0 ? null : SectorRotation.round(stopPrice, 2));
            row.put("status", status);
            row.put("notes", notes);
            rows.add(row);
            if ("超配".equals(status) || "触发止损".equals(status)) {
                warnings.add(name + "：" + status);
            }
        }
        if (!rows.isEmpty()) {
            double total = 0;
            for (Map<String, Object> row : rows) {
                Double weight = SectorRotation.num(row.get("weight_pct"));
                total += weight == null ? 0 : weight;
            }
            if (total > 100) {
                warnings.add("持仓占比合计" + SectorRotation.fixed(total, 1) + "%，数据可能含杠杆或统计口径不一致");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("warnings", warnings);
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> conditions(Map<String, Object> market) {
        List<Map<String, Object>> basis = list(market.get("basis"));
        List<Object> invalidation = market.get("invalidation") instanceof List<?> values
                ? new ArrayList<>(values)
                : List.of();
        Map<String, Object> trend = null;
        Map<String, Object> breadth = null;
        for (Map<String, Object> item : basis) {
            String factor = text(item.get("factor"));
            if (trend == null && factor.startsWith("趋势")) {
                trend = item;
            }
            if (breadth == null && factor.startsWith("宽度")) {
                breadth = item;
            }
        }
        List<String> cut = new ArrayList<>();
        for (Object item : invalidation) {
            if (cut.size() >= 4) {
                break;
            }
            cut.add(text(item));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trend_reading", trend == null ? null : trend.get("reading"));
        result.put("breadth_reading", breadth == null ? null : breadth.get("reading"));
        result.put("invalidation", cut);
        return result;
    }

    /** 给出有条件的操作预案；缺输入时明确写缺失，不替用户假设。 */
    public static Map<String, Object> buildPlan(
            Map<String, Object> profile,
            Map<String, Object> market,
            List<Map<String, Object>> holdings) {
        Map<String, Object> riskProfile = profile == null ? Map.of() : profile;
        Map<String, Object> marketView = market == null ? Map.of() : market;
        List<String> missing = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        String state = text(marketView.get("state")).strip();
        if (!STATE_CAPS.containsKey(state)) {
            missing.add("市场状态：需要先取大盘状态分类，暂按震荡处理");
            state = MarketState.RANGE;
        }
        Object riskLevel = marketView.get("risk_level");
        Object confidence = marketView.get("confidence");
        String chosen = tolerance(riskProfile.get("risk_tolerance"));
        Object rawTolerance = riskProfile.get("risk_tolerance");
        if (rawTolerance == null || !TOLERANCES.contains(rawTolerance)) {
            missing.add("风险承受能力：未提供，按稳健处理");
        }

        Double position = number(riskProfile.get("position_pct"));
        Double cash = number(riskProfile.get("cash_pct"));
        if (position == null) {
            missing.add("当前总仓位：未提供，只给仓位区间不给具体动作");
        }
        if (cash == null) {
            missing.add("现金比例：未提供，无法判断可用资金");
        }

        Map<String, Object> cap = positionCap(state, riskLevel, riskProfile.get("risk_tolerance"));
        double total = (Double) cap.get("total_pct");
        double cashFloor = (Double) cap.get("cash_floor_pct");
        double single = singleCap(state, chosen);
        double stopPct = stopLossPct(state, chosen);
        String stance = stance(state, position, total);

        Double headroom = position == null ? null : SectorRotation.round(total - position, 1);

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> conditions = conditions(marketView);

        if (STANCE_REDUCE.equals(stance)) {
            actions.add(
                    action(
                            "减仓",
                            "当前仓位" + SectorRotation.fixed(position, 0) + "%高于上限" + SectorRotation.fixed(total, 0)
                                    + "%",
                            "把总仓位降到" + SectorRotation.fixed(total, 0) + "%以内，约减持"
                                    + SectorRotation.fixed(position - total, 0) + "个百分点；"
                                    + "先减跌破止损线、逻辑已失效或超配的持仓"));
        } else if (STANCE_HOLD.equals(stance)) {
            actions.add(
                    action(
                            "持有",
                            "仓位已接近上限" + SectorRotation.fixed(total, 0) + "%",
                            "不加仓也不主动减；跌破止损线或指数跌破20日均线时再降一档"));
        } else if (STANCE_BUY.equals(stance)) {
            double first = SectorRotation.round(Math.min((total - position) / 2, 10), 1);
            actions.add(
                    action(
                            "分批建仓",
                            "市场" + state + "且仓位低于上限" + SectorRotation.fixed(total, 0) + "%",
                            "第一批最多加" + SectorRotation.fixed(first, 0) + "个百分点，优先选已确认趋势、"
                                    + "估值不处于历史高位的标的；第二批要等指数站稳均线、"
                                    + "上涨家数占比连续回升后再考虑"));
        } else {
            String detail =
                    "不新增仓位，等触发条件出现：指数重新站上20日均线、"
                            + "上涨家数占比回升到50%以上、行业轮动出现明确的持续强势方向";
            if (MarketState.WEAK.equals(state) || MarketState.EXTREME.equals(state)) {
                detail = "市场" + state + "，不新增仓位；已有持仓按止损纪律处理，"
                        + "现金保持在上限对应的下限之上";
            }
            actions.add(
                    action(
                            "观望",
                            "市场" + state + "，风险等级" + (riskLevel == null ? "未知" : riskLevel),
                            detail));
        }

        actions.add(
                action(
                        "止损",
                        "单只亏损超过" + SectorRotation.fixed(stopPct, 0) + "%",
                        "按成本价下浮" + SectorRotation.fixed(stopPct, 0) + "%设止损；市场" + state + "时不因为"
                                + "“再等等”把止损挪低，先减到能睡得着的仓位"));

        if (cash != null && cash < cashFloor) {
            actions.add(
                    action(
                            "补充现金",
                            "现金" + SectorRotation.fixed(cash, 0) + "%低于下限" + SectorRotation.fixed(cashFloor, 0) + "%",
                            "减仓补回现金，保证极端行情下还有调整空间"));
        }

        Map<String, Object> holdingResult = holdingsPlan(holdings, single, stopPct);
        if (holdings == null) {
            missing.add("持仓明细：未提供，无法检查超配与止损状态");
        }

        if ("低".equals(confidence)) {
            notes.add("市场状态置信度为低，仓位上限" + SectorRotation.fixed(total, 0) + "%只作为区间参考");
        }

        Map<String, Object> marketBlock = new LinkedHashMap<>();
        marketBlock.put("state", state);
        marketBlock.put("risk_level", riskLevel);
        marketBlock.put("confidence", confidence);
        marketBlock.put("trend", conditions.get("trend_reading"));
        marketBlock.put("breadth", conditions.get("breadth_reading"));

        Map<String, Object> profileBlock = new LinkedHashMap<>();
        profileBlock.put("risk_tolerance", chosen);
        profileBlock.put("position_pct", position);
        profileBlock.put("cash_pct", cash);
        profileBlock.put("headroom_pct", headroom);

        Map<String, Object> capsBlock = new LinkedHashMap<>();
        capsBlock.put("total_pct", total);
        capsBlock.put("cash_floor_pct", cashFloor);
        // 单只上限在 Python 侧是整数常量表，输出保持整数，15 而不是 15.0
        capsBlock.put("single_pct", (int) single);
        capsBlock.put("stop_loss_pct", stopPct);
        capsBlock.put("base_pct", cap.get("base_pct"));
        capsBlock.put("risk_multiplier", cap.get("risk_multiplier"));

        @SuppressWarnings("unchecked")
        List<String> invalidation = (List<String>) conditions.get("invalidation");
        List<String> lower = invalidation.isEmpty()
                ? List.of("指数跌破20日均线且上涨家数占比低于35%", "放量下跌或跌停家数明显增加")
                : invalidation;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("market", marketBlock);
        result.put("profile", profileBlock);
        result.put("caps", capsBlock);
        result.put("stance", stance);
        result.put("headroom_pct", headroom);
        result.put("actions", actions);
        result.put("holdings", holdingResult.get("rows"));
        result.put("holding_warnings", holdingResult.get("warnings"));
        result.put(
                "raise_conditions",
                List.of(
                        "指数重新站上20日均线且上涨家数占比连续三个交易日高于50%",
                        "行业轮动出现持续强势方向，且成交额同步放大",
                        "市场状态由当前档位上调，并且置信度不为低"));
        result.put("lower_conditions", lower);
        result.put("missing", missing);
        result.put("notes", notes);
        return result;
    }

    private static Map<String, Object> action(String name, String condition, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("action", name);
        item.put("condition", condition);
        item.put("detail", detail);
        return item;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }
}
