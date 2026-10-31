package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ResearchMemoryPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Port of {@code assistant/tools.py:read_research_memory}. */
public final class ReadResearchMemoryTool extends CompanyTool {
    private final ResearchMemoryPort memory;

    public ReadResearchMemoryTool(
            ResearchMemoryPort memory, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "read_research_memory");
        this.memory = memory;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        Map<String, Object> result =
                memory.read(
                        code,
                        MemoryToolSupport.text(arguments.get("section")),
                        MemoryToolSupport.text(arguments.get("record_id")),
                        MemoryToolSupport.text(arguments.get("status")),
                        orDefault(arguments, "limit", 200),
                        orDefault(arguments, "offset", 0));
        String fetched = MemoryToolSupport.fetchedNow();
        String section = String.valueOf(result.get("section"));
        List<Source> sources = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        for (Map<String, Object> record : records) {
            sources.add(MemoryToolSupport.source(code, section, record, fetched));
        }
        return ToolResult.ok(code, null, sources, result);
    }
}
