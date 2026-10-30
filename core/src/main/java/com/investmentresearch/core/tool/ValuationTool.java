package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ValuationPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_valuation}. */
public final class ValuationTool extends CompanyTool {
    private final ValuationPort port;

    public ValuationTool(ValuationPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_valuation");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        return port.valuation(code);
    }
}
