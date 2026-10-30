package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 年度财务序列：{@code stock/finance_history.py:build_history}。 */
public interface FinancialHistoryPort {
    ToolResult financialHistory(String code, int years);
}
