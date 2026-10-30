package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** Financial statement snapshot. */
public interface FinancePort {
    ToolResult finance(String code);
}
