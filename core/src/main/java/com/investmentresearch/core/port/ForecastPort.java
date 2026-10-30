package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 业绩预告：{@code stock/forecasts.py:build_forecasts}。 */
public interface ForecastPort {
    ToolResult forecasts(String code, int years);
}
