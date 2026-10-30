package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ValuationCheckPort;
import com.investmentresearch.core.port.ValuationPort;
import com.investmentresearch.core.service.CompanyResolver;
import com.investmentresearch.core.valuation.ValuationInputError;
import com.investmentresearch.core.valuation.ValuationMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Port of {@code assistant/tools.py:_calc_valuation}：check / scenario / terminal 三分支。 */
public final class CalcValuationTool extends CompanyTool {
    private static final List<String> LABELS = List.of("悲观", "中性", "乐观");

    private final ValuationPort valuation;
    private final ValuationCheckPort check;

    public CalcValuationTool(
            ValuationPort valuation,
            ValuationCheckPort check,
            CompanyResolver resolver,
            ToolSchemas schemas) {
        super(resolver, schemas, "calc_valuation");
        this.valuation = valuation;
        this.check = check;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        Object raw = arguments.get("mode");
        if (!(raw instanceof String text) || text.trim().isEmpty()) {
            return ToolResult.failure(code, "calc_valuation 需要 mode：check / scenario / terminal");
        }
        String mode = text.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "check" -> check.check(code);
            case "scenario" -> scenario(code, arguments);
            case "terminal" -> terminal(code, arguments);
            default -> ToolResult.failure(code, "未知 mode：" + mode + "；可用 check / scenario / terminal");
        };
    }

    /** {@code _valuation_scenario}。 */
    private ToolResult scenario(String code, Map<String, Object> arguments) {
        Double price = asNumber(arguments.get("price"));
        String asOf = null;
        List<Source> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (price == null) {
            ToolResult existing = valuation.valuation(code);
            if (existing.ok()) {
                price = asNumber(existing.data().get("price"));
                asOf = existing.asOf();
                if (price != null && asOf != null && !asOf.isEmpty()) {
                    sources.add(existing.sources().get(0));
                }
            }
            if (price == null) {
                warnings.add("没有当前股价，未计算相对当前价的空间");
            }
        } else if (price <= 0) {
            return ToolResult.failure(code, "price 必须大于 0");
        }

        Double baseValue = asNumber(arguments.get("base_value"));
        if (baseValue == null) {
            return ToolResult.failure(code, "scenario 需要 base_value（每股基准价值）");
        }
        String baseLabel = baseLabel(arguments.get("base_label"));

        List<Double> growths = numbers(arguments.get("growth_pct"));
        if (growths == null) {
            return ToolResult.failure(code, "growth_pct 需要 3 个数字（悲观/中性/乐观）");
        }
        List<Double> pes = numbers(arguments.get("exit_pe"));
        if (pes == null) {
            return ToolResult.failure(code, "exit_pe 需要 3 个数字（悲观/中性/乐观）");
        }

        Integer years;
        Object rawYears = arguments.get("years");
        if (rawYears == null) {
            years = 3;
        } else {
            years = asInt(rawYears);
            if (years == null) {
                return ToolResult.failure(code, "years 必须是整数");
            }
            if (years < 1 || years > 10) {
                return ToolResult.failure(code, "years 需要在 1–10 之间");
            }
        }

        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int i = 0; i < LABELS.size(); i++) {
            double growth = growths.get(i);
            double pe = pes.get(i);
            Map<String, Object> result;
            try {
                result = ValuationMath.scenario(baseValue, growth, pe, years, price);
            } catch (ValuationInputError error) {
                return ToolResult.failure(code, error.getMessage());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", LABELS.get(i));
            item.put("growth_pct", growth);
            item.put("exit_pe", pe);
            item.put("future_base_per_share", ValuationMath.round((Double) result.get("future_value"), 2));
            item.put("value_per_share", ValuationMath.round((Double) result.get("future_price"), 2));
            item.put("upside_pct", ValuationMath.round((Double) result.get("upside_pct"), 1));
            scenarios.add(item);
        }

        if (pes.stream().anyMatch(pe -> pe > 50)) {
            warnings.add("有退出 PE 高于 50 倍，确认假设依据，不要把它当成合理估值");
        }
        if (growths.stream().anyMatch(growth -> growth > 30)) {
            warnings.add("有增速假设高于 30%，确认不是把历史增速线性外推");
        }

        Map<String, Object> assumptions = new LinkedHashMap<>();
        assumptions.put("base_value", baseValue);
        assumptions.put("base_label", baseLabel);
        assumptions.put("years", years);
        assumptions.put("growth_pct", growths);
        assumptions.put("exit_pe", pes);
        assumptions.put("price", price);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "scenario");
        data.put("assumptions", assumptions);
        data.put("scenarios", scenarios);
        data.put("warnings", warnings);
        data.put(
                "notes",
                List.of(
                        "情景结果是明示假设下的算术结果，不是目标价，也不是买卖建议。",
                        "基准值与退出 PE 由调用方给出，工具不替你选择参数。"));
        return ToolResult.ok(code, asOf, sources, data);
    }

    /** {@code _valuation_terminal}。 */
    private ToolResult terminal(String code, Map<String, Object> arguments) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (String key : List.of("roic_pct", "terminal_g_pct", "discount_rate_pct")) {
            Double value = asNumber(arguments.get(key));
            if (value == null) {
                return ToolResult.failure(code, "terminal 需要 " + key);
            }
            inputs.put(key, value);
        }

        Map<String, Object> result;
        try {
            result =
                    ValuationMath.terminalPe(
                            (Double) inputs.get("roic_pct"),
                            (Double) inputs.get("terminal_g_pct"),
                            (Double) inputs.get("discount_rate_pct"));
        } catch (ValuationInputError error) {
            return ToolResult.failure(code, error.getMessage());
        }

        List<String> warnings = new ArrayList<>();
        for (Object raw : (List<?>) result.get("checks")) {
            Map<?, ?> item = (Map<?, ?>) raw;
            if ("warn".equals(item.get("status")) || "fail".equals(item.get("status"))) {
                warnings.add((String) item.get("message"));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "terminal");
        data.put("inputs", inputs);
        data.put("terminal_pe", ValuationMath.round((Double) result.get("terminal_pe"), 1));
        data.put(
                "payout_ratio_pct",
                ValuationMath.round((Double) result.get("payout_ratio_pct"), 1));
        data.put("valid", result.get("valid"));
        data.put("status", result.get("status"));
        data.put("checks", result.get("checks"));
        data.put("warnings", warnings);
        data.put("notes", List.of("终值 PE 只用于检查永续增长假设是否自洽，不是目标价。"));
        return ToolResult.ok(code, null, List.of(), data);
    }

    private static String baseLabel(Object raw) {
        if (raw instanceof String text) {
            String stripped = text.strip();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return "EPS";
    }

    /** Python {@code _as_number}：None/布尔/非有限 → null。 */
    static Double asNumber(Object value) {
        if (value == null || value instanceof Boolean) {
            return null;
        }
        double number;
        if (value instanceof Number numeric) {
            number = numeric.doubleValue();
        } else if (value instanceof String text) {
            try {
                number = Double.parseDouble(text.strip());
            } catch (NumberFormatException error) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isFinite(number) ? number : null;
    }

    /** Python {@code _as_numbers}：长度为 3 的数字列表，否则 null。 */
    private static List<Double> numbers(Object value) {
        if (!(value instanceof List<?> list) || list.size() != 3) {
            return null;
        }
        List<Double> out = new ArrayList<>();
        for (Object item : list) {
            Double number = asNumber(item);
            if (number == null) {
                return null;
            }
            out.add(number);
        }
        return out;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Boolean) {
            return null;
        }
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal)) {
                return null;
            }
            return (int) decimal;
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return null;
    }
}
