package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** Valuation history, percentiles and industry comparison. */
public interface ValuationPort {
    ToolResult valuation(String code);
}
