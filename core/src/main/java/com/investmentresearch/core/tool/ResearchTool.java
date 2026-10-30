package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import java.util.Map;

/** A tool the model can call; execution failures come back as {@code ToolResult.failure}. */
public interface ResearchTool {
    ToolSchema schema();

    ToolResult execute(Map<String, Object> arguments, String defaultCompany);
}
