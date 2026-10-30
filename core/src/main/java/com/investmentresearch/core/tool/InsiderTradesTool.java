package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.InsiderTradePort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_insider_trades}. */
public final class InsiderTradesTool extends CompanyTool {
    private final InsiderTradePort port;

    public InsiderTradesTool(InsiderTradePort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_insider_trades");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(valueOrDefault(arguments, "years", 3), 1, 10);
        return port.insiderTrades(code, years);
    }
}
