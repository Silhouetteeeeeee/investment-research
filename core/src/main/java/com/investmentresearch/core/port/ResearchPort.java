package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 机构调研：{@code stock/research.py:build_research}。 */
public interface ResearchPort {
    ToolResult research(String code, int years);
}
