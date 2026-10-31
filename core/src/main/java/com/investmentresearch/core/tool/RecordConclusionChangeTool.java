package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ResearchMemoryPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of {@code assistant/tools.py:record_conclusion_change}. */
public final class RecordConclusionChangeTool extends CompanyTool {
    private final ResearchMemoryPort memory;

    public RecordConclusionChangeTool(
            ResearchMemoryPort memory, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "record_conclusion_change");
        this.memory = memory;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        Map<String, Object> payload = new LinkedHashMap<>(arguments);
        payload.remove("company");
        Map<String, Object> result = memory.change(code, payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> change = (Map<String, Object>) result.get("change");
        Source source =
                new Source(
                        "memory:" + code + ":" + MemoryToolSupport.text(change.get("change_id")),
                        "研究档案",
                        "变化 " + MemoryToolSupport.text(change.get("change_id")),
                        MemoryToolSupport.asOf("changes", change),
                        MemoryToolSupport.fetchedNow());
        return ToolResult.ok(code, null, List.of(source), result);
    }
}
