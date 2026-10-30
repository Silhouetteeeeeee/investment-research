package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 限售解禁：{@code stock/lockups.py:build_lockups}。 */
public interface LockupPort {
    ToolResult lockups(String code, int years, int aheadMonths);
}
