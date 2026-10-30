package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 股权质押：{@code stock/pledges.py:build_pledges}。 */
public interface PledgePort {
    ToolResult pledges(String code, int years);
}
