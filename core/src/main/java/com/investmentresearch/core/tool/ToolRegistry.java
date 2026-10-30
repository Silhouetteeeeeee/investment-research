package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dispatches tool calls by name; unknown names and unexpected failures become failed results. */
public final class ToolRegistry {
    private final Map<String, ResearchTool> tools;
    private final List<ToolSchema> schemas;

    public ToolRegistry(List<ResearchTool> tools) {
        Map<String, ResearchTool> byName = new LinkedHashMap<>();
        List<ToolSchema> ordered = new ArrayList<>();
        for (ResearchTool tool : tools) {
            byName.put(tool.schema().name(), tool);
            ordered.add(tool.schema());
        }
        this.tools = Map.copyOf(byName);
        this.schemas = List.copyOf(ordered);
    }

    public List<ToolSchema> schemas() {
        return schemas;
    }

    public ToolResult run(String name, Map<String, Object> arguments, String defaultCompany) {
        ResearchTool tool = tools.get(name);
        if (tool == null) {
            // Python 在此还会回填已解析出的代码；这里按 Task 14 的约定不回填
            return ToolResult.failure(null, "未知工具：" + name);
        }
        try {
            return tool.execute(arguments == null ? Map.of() : arguments, defaultCompany);
        } catch (RuntimeException error) {
            return ToolResult.failure(null, error.getMessage());
        }
    }
}
