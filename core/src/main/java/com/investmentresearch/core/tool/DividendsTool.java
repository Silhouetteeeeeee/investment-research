package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.DividendPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_dividends}. */
public final class DividendsTool extends CompanyTool {
    private final DividendPort port;

    public DividendsTool(DividendPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_dividends");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(valueOrDefault(arguments, "years", 10), 1, 20);
        return port.dividends(code, years);
    }
}
