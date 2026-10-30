package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.AnalystViewPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_analyst_views}. */
public final class AnalystViewsTool extends CompanyTool {
    private final AnalystViewPort port;

    public AnalystViewsTool(AnalystViewPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_analyst_views");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int days = clamp(valueOrDefault(arguments, "days", 365), 30, 730);
        return port.analystViews(code, days);
    }
}
