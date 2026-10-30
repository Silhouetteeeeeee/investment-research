package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ForecastPort;
import com.investmentresearch.core.service.CompanyResolver;
import java.util.Map;

/** Port of {@code assistant/tools.py:_earnings_forecasts}. */
public final class EarningsForecastsTool extends CompanyTool {
    private final ForecastPort port;

    public EarningsForecastsTool(ForecastPort port, CompanyResolver resolver, ToolSchemas schemas) {
        super(resolver, schemas, "get_earnings_forecasts");
        this.port = port;
    }

    @Override
    protected ToolResult run(String code, Map<String, Object> arguments) {
        int years = clamp(valueOrDefault(arguments, "years", 3), 1, 10);
        return port.forecasts(code, years);
    }
}
