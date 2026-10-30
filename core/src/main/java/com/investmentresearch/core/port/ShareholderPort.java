package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 股东结构：{@code stock/shareholders.py:build_shareholders}。 */
public interface ShareholderPort {
    ToolResult shareholders(String code, int periods);
}
