package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.ScreenPort;
import java.util.Map;

/** Port of {@code assistant/tools.py:screen_candidates}：limit 3..30、min_amount_yi 0.1..50。 */
public final class ScreenCandidatesTool implements ResearchTool {
    private final ScreenPort screening;
    private final ToolSchema schema;

    public ScreenCandidatesTool(ScreenPort screening, ToolSchemas schemas) {
        this.screening = screening;
        this.schema = schemas.get("screen_candidates");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        int limit = ToolArguments.intArgument(arguments.get("limit"), 15);
        double minAmount = ToolArguments.doubleArgument(arguments.get("min_amount_yi"), 1.0);
        return screening.screen(
                Math.max(3, Math.min(30, limit)), Math.max(0.1, Math.min(50.0, minAmount)));
    }
}
