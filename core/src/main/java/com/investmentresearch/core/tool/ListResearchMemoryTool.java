package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ResearchMemoryPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of {@code assistant/tools.py:list_research_memory}. */
public final class ListResearchMemoryTool extends CompanyTool {
    private final ResearchMemoryPort memory;

    public ListResearchMemoryTool(
            ResearchMemoryPort memory, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "list_research_memory");
        this.memory = memory;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        Map<String, Object> summary = memory.company(code);
        if (summary == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", code);
            data.put("archive", false);
            data.put("message", "尚未建档");
            return ToolResult.ok(code, null, List.of(), data);
        }
        String updatedAt = MemoryToolSupport.text(summary.get("updated_at"));
        Source source =
                new Source(
                        "memory:" + code + ":index",
                        "研究档案",
                        MemoryToolSupport.text(summary.get("name")) + " 的档案索引",
                        updatedAt.length() > 10 ? updatedAt.substring(0, 10) : updatedAt,
                        MemoryToolSupport.fetchedNow());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("archive", true);
        data.putAll(summary);
        return ToolResult.ok(code, null, List.of(source), data);
    }
}
