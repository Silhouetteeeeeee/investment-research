package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ResearchMemoryPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of {@code assistant/tools.py:update_research_record}. */
public final class UpdateResearchRecordTool extends CompanyTool {
    private final ResearchMemoryPort memory;

    public UpdateResearchRecordTool(
            ResearchMemoryPort memory, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "update_research_record");
        this.memory = memory;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        String section = MemoryToolSupport.text(arguments.get("section"));
        Map<String, Object> patch =
                arguments.get("patch") instanceof Map<?, ?> raw ? cast(raw) : Map.of();
        Map<String, Object> result =
                memory.update(
                        code,
                        section,
                        MemoryToolSupport.text(arguments.get("record_id")),
                        patch);
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) result.get("record");
        Source source =
                MemoryToolSupport.source(code, section, record, MemoryToolSupport.fetchedNow());
        return ToolResult.ok(code, null, List.of(source), result);
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
