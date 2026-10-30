package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** Revenue segments by product and region. */
public interface SegmentPort {
    ToolResult segments(String code, int years);
}
