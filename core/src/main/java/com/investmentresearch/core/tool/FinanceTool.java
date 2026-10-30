package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.FinancePort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_finance}. */
public final class FinanceTool extends CompanyTool {
    private final FinancePort port;

    public FinanceTool(FinancePort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_finance");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        return port.finance(code);
    }
}
