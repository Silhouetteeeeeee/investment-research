package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ResearchNotesPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_research_notes}. */
public final class ResearchNotesTool extends CompanyTool {
    private final ResearchNotesPort port;

    public ResearchNotesTool(ResearchNotesPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_research_notes");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(valueOrDefault(arguments, "years", 3), 1, 10);
        int events = clamp(valueOrDefault(arguments, "events", 3), 1, 10);
        int maxChars = clamp(valueOrDefault(arguments, "max_chars", 12_000), 3_000, 40_000);
        return port.researchNotes(code, years, events, maxChars);
    }
}
