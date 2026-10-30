package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 分红历史：{@code stock/dividends.py:build_dividends}。 */
public interface DividendPort {
    ToolResult dividends(String code, int years);
}
