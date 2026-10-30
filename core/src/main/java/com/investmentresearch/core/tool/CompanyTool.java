package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.service.CompanyResolver;
import com.investmentresearch.core.service.ResolvedCompany;
import java.util.Map;

/**
 * Shared plumbing for tools that take a {@code company} argument: resolve it through {@link
 * CompanyResolver}, then delegate to the port. Failures stay values.
 */
abstract class CompanyTool implements ResearchTool {
    private final CompanyResolver resolver;
    private final ToolSchema schema;

    CompanyTool(CompanyResolver resolver, ToolSchemas schemas, String name) {
        this.resolver = resolver;
        this.schema = schemas.get(name);
    }

    @Override
    public final ToolSchema schema() {
        return schema;
    }

    @Override
    public final ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        CompanyText company = companyText(args.get("company"));
        if (company.error() != null) {
            return ToolResult.failure(null, company.error());
        }

        String code = null;
        try {
            ResolvedCompany resolved = resolver.resolve(company.text(), defaultCompany);
            if (resolved.code() == null) {
                return ToolResult.failure(null, resolved.error());
            }
            code = resolved.code();
            return run(code, args);
        } catch (RuntimeException error) {
            // Python run_tool 在解析成功后同样把已解析的代码带回失败结果
            return ToolResult.failure(code, message(error));
        }
    }

    /** Python {@code assistant/tools.py:_company_text}, including its exact type names. */
    static CompanyText companyText(Object raw) {
        if (raw == null) {
            return new CompanyText(null, null);
        }
        if (raw instanceof String text) {
            return new CompanyText(text, null);
        }
        if (isPythonInt(raw)) {
            return new CompanyText(raw.toString(), null);
        }
        return new CompanyText(
                null, "company 参数必须是字符串（收到 " + pythonType(raw) + "）");
    }

    /** Python {@code str(error)}; an exception without a message renders as the empty string. */
    static String message(RuntimeException error) {
        return error.getMessage() == null ? "" : error.getMessage();
    }

    record CompanyText(String text, String error) {}

    protected abstract ToolResult run(String code, Map<String, Object> arguments);

    /** Python {@code int(arguments.get(key) or fallback)}: null, 0, "" and false use the fallback. */
    protected static int orDefault(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        if (value == null || Boolean.FALSE.equals(value) || "".equals(value)) {
            return fallback;
        }
        if (value instanceof Number number && number.doubleValue() == 0) {
            return fallback;
        }
        try {
            return asInt(value);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    /** Python {@code int(arguments.get(key, fallback))}: only missing or unconvertible values default. */
    protected static int valueOrDefault(
            Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return asInt(value);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    protected static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static int asInt(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal)) {
                throw new NumberFormatException("cannot convert " + value + " to int");
            }
            // clamp before narrowing: 3e9 must reach the caller's clamp as "too large", not overflow
            if (decimal >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (decimal <= Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            return (int) decimal;
        }
        if (value instanceof String text) {
            long parsed = Long.parseLong(text.trim());
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, parsed));
        }
        throw new NumberFormatException("cannot convert " + value + " to int");
    }

    private static boolean isPythonInt(Object value) {
        return value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte
                || value instanceof java.math.BigInteger;
    }

    private static String pythonType(Object value) {
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Map<?, ?>) {
            return "dict";
        }
        if (value instanceof Iterable<?> || value.getClass().isArray()) {
            return "list";
        }
        if (value instanceof Double
                || value instanceof Float
                || value instanceof java.math.BigDecimal) {
            return "float";
        }
        return value.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }
}
