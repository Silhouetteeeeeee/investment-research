package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ResearchPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_institutional_research}. */
public final class InstitutionalResearchTool extends CompanyTool {
    private final ResearchPort port;

    public InstitutionalResearchTool(ResearchPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_institutional_research");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(valueOrDefault(arguments, "years", 3), 1, 10);
        return port.research(code, years);
    }
}
