package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.StrategyPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of {@code assistant/tools.py:build_strategy_plan}：市场状态可由调用方传入，也可以现场取。 */
public final class StrategyPlanTool implements ResearchTool {
    private final StrategyPort strategy;
    private final ToolSchema schema;

    public StrategyPlanTool(StrategyPort strategy, ToolSchemas schemas) {
        this.strategy = strategy;
        this.schema = schemas.get("build_strategy_plan");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("risk_tolerance", arguments.get("risk_tolerance"));
        profile.put("position_pct", arguments.get("position_pct"));
        profile.put("cash_pct", arguments.get("cash_pct"));

        List<Map<String, Object>> holdings = new ArrayList<>();
        if (arguments.get("holdings") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((key, value) -> row.put(String.valueOf(key), value));
                    holdings.add(row);
                }
            }
        } else {
            holdings = null;
        }

        return strategy.plan(
                profile,
                ToolArguments.text(arguments.get("market_state")),
                ToolArguments.text(arguments.get("risk_level")),
                holdings);
    }
}
