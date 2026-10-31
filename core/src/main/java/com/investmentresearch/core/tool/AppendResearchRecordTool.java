package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.ResearchMemoryPort;
import com.investmentresearch.core.research.MemoryException;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of {@code assistant/tools.py:append_research_record}. */
public final class AppendResearchRecordTool extends CompanyTool {
    private final ResearchMemoryPort memory;
    private final CompanyDirectory directory;

    public AppendResearchRecordTool(
            ResearchMemoryPort memory,
            CompanyDirectory directory,
            CompanyResolver resolver,
            ToolSchemas schemas) {
        super(resolver, schemas, "append_research_record");
        this.memory = memory;
        this.directory = directory;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        String section = MemoryToolSupport.text(arguments.get("section"));
        Map<String, Object> record =
                arguments.get("record") instanceof Map<?, ?> raw ? cast(raw) : Map.of();
        String name = directory.byCode(code).map(stock -> stock.name()).orElse("");
        try {
            Map<String, Object> result = memory.append(code, section, record, name);
            @SuppressWarnings("unchecked")
            Map<String, Object> appended = (Map<String, Object>) result.get("record");
            Source source =
                    MemoryToolSupport.source(code, section, appended, MemoryToolSupport.fetchedNow());
            return ToolResult.ok(code, null, List.of(source), result);
        } catch (MemoryException error) {
            List<String> required = MemoryToolSupport.REQUIRED.get(section);
            if (required == null) {
                throw error;
            }
            // 一次说清必填字段，避免模型逐个试错
            return ToolResult.failure(
                    code,
                    error.getMessage()
                            + "。"
                            + section
                            + " 必填字段："
                            + String.join("、", required)
                            + "；事实正文用 content，假设与结论正文用 statement。"
                            + "请一次补齐后重试，或直接基于已有资料作答。");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
