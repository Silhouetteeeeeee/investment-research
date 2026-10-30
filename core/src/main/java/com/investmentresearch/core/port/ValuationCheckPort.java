package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** calc_valuation 的 check 模式：{@code assistant/tools.py:_valuation_check}。 */
public interface ValuationCheckPort {
    ToolResult check(String code);
}
