package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.ReviewPort;
import java.util.LinkedHashMap;
import java.util.Map;

/** Port of {@code assistant/tools.py:save_daily_judgment}：把当天判断存档，同一天覆盖。 */
public final class SaveJudgmentTool implements ResearchTool {
    private final ReviewPort review;
    private final ToolSchema schema;

    public SaveJudgmentTool(ReviewPort review, ToolSchemas schemas) {
        this.review = review;
        this.schema = schemas.get("save_daily_judgment");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        Map<String, Object> strategy = null;
        if (arguments.get("strategy") instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            strategy = copy;
        }
        return review.save(strategy);
    }
}
