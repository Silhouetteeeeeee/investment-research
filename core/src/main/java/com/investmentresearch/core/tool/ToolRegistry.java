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
            if (tool == null) {
                throw new IllegalArgumentException("tool must not be null");
            }
            String name = tool.schema().name();
            if (byName.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("duplicate tool: " + name);
            }
            ordered.add(tool.schema());
        }
        this.tools = Map.copyOf(byName);
        this.schemas = List.copyOf(ordered);
    }

    public List<ToolSchema> schemas() {
        return schemas;
    }

    public ToolResult run(String name, Map<String, Object> arguments, String defaultCompany) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        // Python 先校验 company 参数再进未知工具分支；其余解析仍交给各工具
        CompanyTool.CompanyText company = CompanyTool.companyText(args.get("company"));
        if (company.error() != null) {
            return ToolResult.failure(null, company.error());
        }

        ResearchTool tool = tools.get(name);
        if (tool == null) {
            // Python 在此还会回填已解析出的代码；这里按 Task 14 的约定不回填
            return ToolResult.failure(null, "未知工具：" + name);
        }
        try {
            return tool.execute(args, defaultCompany);
        } catch (RuntimeException error) {
            // 工具自己已兜底；走到这里说明在解析出代码之前就失败了（Python 此时 code 也是 None）
            return ToolResult.failure(null, CompanyTool.message(error));
        }
    }
}
