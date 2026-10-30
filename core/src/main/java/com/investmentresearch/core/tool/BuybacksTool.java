package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.BuybackPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_buybacks}. */
public final class BuybacksTool extends CompanyTool {
    private final BuybackPort port;

    public BuybacksTool(BuybackPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_buybacks");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(valueOrDefault(arguments, "years", 5), 1, 10);
        return port.buybacks(code, years);
    }
}
