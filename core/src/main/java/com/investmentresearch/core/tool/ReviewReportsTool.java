package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.ReviewPort;
import java.util.Map;

/** Port of {@code assistant/tools.py:review_research_reports}：horizon 1..20、limit 1..60。 */
public final class ReviewReportsTool implements ResearchTool {
    private final ReviewPort review;
    private final ToolSchema schema;

    public ReviewReportsTool(ReviewPort review, ToolSchemas schemas) {
        this.review = review;
        this.schema = schemas.get("review_research_reports");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        int horizon = ToolArguments.intArgument(arguments.get("horizon"), 5);
        int limit = ToolArguments.intArgument(arguments.get("limit"), 20);
        return review.review(Math.max(1, Math.min(60, limit)), Math.max(1, Math.min(20, horizon)));
    }
}
