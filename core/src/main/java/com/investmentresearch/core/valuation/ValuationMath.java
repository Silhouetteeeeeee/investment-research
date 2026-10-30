package com.investmentresearch.core.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 估值算术，逐行对应 {@code assistant/valuation_math.py}；纯函数，不访问数据层。 */
public final class ValuationMath {
    static final double PASS_PCT = 1;
    static final double WARN_PCT = 5;
    static final double MIN_SPREAD_PCT = 5;

    private ValuationMath() {}

    /** Python {@code round(value, digits)}：按二进制精确值取最近，平局取偶，-0.0 保留。 */
    public static Double round(Double value, int digits) {
        if (value == null) {
            return null;
        }
        double rounded = new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
        // Python round(-1e-9, 2) 是 -0.0，BigDecimal 会抹掉负号。
        return rounded == 0.0 && value < 0 ? -0.0 : rounded;
    }

    /** 比较报告值与财报反推值；1% 内通过，1%–5% 提示，超过 5% 打回。 */
    public static Map<String, Object> compare(String name, Double reported, Double implied) {
        if (reported == null || implied == null) {
            return result(name, reported, implied, null, "skip", "缺少可比数据");
        }
        if (reported <= 0) {
            return result(name, reported, implied, null, "skip", "报告值非正，倍数没有意义");
        }
        if (implied <= 0) {
            return result(name, reported, implied, null, "skip", "财报反推值非正，倍数没有意义");
        }
        double diff = (implied - reported) / reported * 100;
        double absolute = Math.abs(diff);
        String status;
        String note;
        if (absolute <= PASS_PCT) {
            status = "pass";
            note = "差异 ≤ 1%，口径一致";
        } else if (absolute <= WARN_PCT) {
            status = "warn";
            note = "差异 1%–5%，注意 TTM/MRQ 口径或财报同步时点";
        } else {
            status = "fail";
            note = "差异 > 5%，引用前先核对原始财报与估值口径";
        }
        return result(name, reported, implied, diff, status, note);
    }

    private static Map<String, Object> result(
            String name, Double reported, Double implied, Double diff, String status, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("reported", reported);
        out.put("implied", implied);
        out.put("diff_pct", round(diff, 2));
        out.put("status", status);
        out.put("note", note);
        return out;
    }

    /** 自由现金流收益率（百分数）；市值非正或缺数据时返回 null。 */
    public static Double fcfYield(Double fcf, Double marketCap) {
        if (fcf == null || marketCap == null || marketCap <= 0) {
            return null;
        }
        return fcf / marketCap * 100;
    }

    /** 一档情景：基准值 ×(1+g)^年数 ×退出 PE；基准值非正时拒绝计算。 */
    public static Map<String, Object> scenario(
            Double baseValue, Double growthPct, Double exitPe, Integer years, Double price) {
        if (baseValue == null) {
            throw new ValuationInputError("基准每股价值缺失");
        }
        if (growthPct == null) {
            throw new ValuationInputError("增速缺失");
        }
        if (exitPe == null) {
            throw new ValuationInputError("退出 PE 缺失");
        }
        if (years == null) {
            throw new ValuationInputError("情景年数不是整数：null");
        }
        if (baseValue <= 0) {
            throw new ValuationInputError("基准每股价值必须大于 0");
        }
        if (growthPct <= -100) {
            throw new ValuationInputError("增速不能小于等于 -100%");
        }
        if (exitPe <= 0) {
            throw new ValuationInputError("退出 PE 必须大于 0");
        }
        if (years < 1 || years > 10) {
            throw new ValuationInputError("情景年数需要在 1–10 之间");
        }
        if (price != null && price <= 0) {
            throw new ValuationInputError("股价必须大于 0");
        }

        double futureValue = baseValue * Math.pow(1 + growthPct / 100, years);
        double futurePrice = futureValue * exitPe;
        Double upside = price == null ? null : (futurePrice / price - 1) * 100;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("future_value", futureValue);
        out.put("future_price", futurePrice);
        out.put("upside_pct", upside);
        return out;
    }

    /** 永续增长模型的终值 PE：{@code (1 - g/ROIC) / (r - g)}，只判断假设是否自洽。 */
    public static Map<String, Object> terminalPe(Double roicPct, Double gPct, Double rPct) {
        if (roicPct == null) {
            throw new ValuationInputError("ROIC缺失");
        }
        if (gPct == null) {
            throw new ValuationInputError("永续增速缺失");
        }
        if (rPct == null) {
            throw new ValuationInputError("折现率缺失");
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        boolean valid = true;
        if (roicPct <= 0) {
            add(checks, "ROIC", "fail", "ROIC 必须大于 0，否则留存率模型没有意义");
            valid = false;
        }
        if (gPct >= roicPct) {
            add(checks, "g < ROIC", "fail", "永续增速不低于 ROIC，留存率为负");
            valid = false;
        }
        if (rPct <= gPct) {
            add(checks, "r > g", "fail", "折现率必须高于永续增速，否则分母不为正");
            valid = false;
        }
        if (valid && rPct - gPct < MIN_SPREAD_PCT) {
            add(checks, "r - g", "warn", "r-g 小于 5 个百分点，终值对参数非常敏感");
        }
        if (gPct < 0) {
            add(checks, "永续增速", "warn", "永续负增长不常见，确认假设");
        }

        Double pe = null;
        Double payout = null;
        if (valid) {
            payout = (1 - gPct / roicPct) * 100;
            pe = (1 - gPct / roicPct) / ((rPct - gPct) / 100);
            if (pe <= 0) {
                add(checks, "终值 PE", "fail", "算出的终值 PE 非正，假设不成立");
                valid = false;
                pe = null;
            } else if (pe > 50) {
                add(checks, "终值 PE", "warn", "终值 PE 高于 50 倍，确认 ROIC 与 g 的假设");
            } else {
                add(checks, "终值 PE", "pass", "永续增长模型可用");
            }
        }
        boolean warned = checks.stream().anyMatch(item -> "warn".equals(item.get("status")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terminal_pe", pe);
        out.put("payout_ratio_pct", payout);
        out.put("valid", valid);
        out.put("status", !valid ? "fail" : (warned ? "warn" : "pass"));
        out.put("checks", checks);
        return out;
    }

    private static void add(List<Map<String, Object>> checks, String name, String status, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("status", status);
        item.put("message", message);
        checks.add(item);
    }
}
