package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.FinancialHistoryPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_financial_history}. */
public final class FinancialHistoryTool extends CompanyTool {
    private final FinancialHistoryPort port;

    public FinancialHistoryTool(
            FinancialHistoryPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_financial_history");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(orDefault(arguments, "years", 10), 1, 10);
        return port.financialHistory(code, years);
    }
}
