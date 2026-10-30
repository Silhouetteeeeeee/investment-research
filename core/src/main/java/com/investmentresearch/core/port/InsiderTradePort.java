package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 高管增减持：{@code stock/insider_trades.py:build_insider_trades}。 */
public interface InsiderTradePort {
    ToolResult insiderTrades(String code, int years);
}
