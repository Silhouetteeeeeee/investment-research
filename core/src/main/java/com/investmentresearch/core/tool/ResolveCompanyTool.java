package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.service.CompanyResolver;
import com.investmentresearch.core.service.ResolvedCompany;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of {@code assistant/tools.py:_resolve_company_tool}. */
public final class ResolveCompanyTool implements ResearchTool {
    private final CompanyDirectory directory;
    private final CompanyResolver resolver;
    private final ToolSchema schema;

    public ResolveCompanyTool(CompanyDirectory directory) {
        this(directory, new CompanyResolver(directory), ToolSchemas.loadDefault());
    }

    public ResolveCompanyTool(
            CompanyDirectory directory, CompanyResolver resolver, ToolSchemas schemas) {
        this.directory = directory;
        this.resolver = resolver;
        this.schema = schemas.get("resolve_company");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        Object raw = arguments == null ? null : arguments.get("name_or_code");
        String query;
        if (raw == null) {
            query = null;
        } else if (raw instanceof String text) {
            query = text.trim();
        } else if (isPythonInt(raw)) {
            query = raw.toString();
        } else {
            return ToolResult.failure(
                    null, "company 参数必须是字符串（收到 " + pythonType(raw) + "）");
        }

        // Python passes None as default here: resolve_company ignores default_company.
        ResolvedCompany resolved = resolver.resolve(query, null);
        if (resolved.code() == null) {
            return ToolResult.failure(null, resolved.error());
        }

        StockRef stock = directory.byCode(resolved.code()).orElse(null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", resolved.code());
        data.put("name", stock == null ? null : stock.name());
        List<Map<String, String>> candidates = new ArrayList<>();
        for (StockRef candidate : resolved.candidates()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", candidate.code());
            item.put("name", candidate.name());
            candidates.add(item);
        }
        data.put("candidates", candidates);
        return ToolResult.ok(resolved.code(), null, List.of(), data);
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
        if (value instanceof Double || value instanceof Float || value instanceof java.math.BigDecimal) {
            return "float";
        }
        return value.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }
}
