package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 调研纪要原文：{@code stock/research.py:build_research_notes}。 */
public interface ResearchNotesPort {
    ToolResult researchNotes(String code, int years, int events, int maxChars);
}
