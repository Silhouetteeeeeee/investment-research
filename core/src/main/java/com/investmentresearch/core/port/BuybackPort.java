package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 股票回购：{@code stock/buybacks.py:build_buybacks}。 */
public interface BuybackPort {
    ToolResult buybacks(String code, int years);
}
