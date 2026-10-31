package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.SearchPort;
import java.util.Map;

/** 通用联网搜索：无公司参数；结果是外部网页，只能按 sources 的 id 引用。 */
public final class WebSearchTool implements ResearchTool {
    static final int DEFAULT_COUNT = 5;
    static final int MIN_COUNT = 1;
    static final int MAX_COUNT = 10;

    private final SearchPort search;
    private final ToolSchema schema;

    public WebSearchTool(SearchPort search, ToolSchemas schemas) {
        this.search = search;
        this.schema = schemas.get("web_search");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        String query = ToolArguments.text(arguments.get("query")).strip();
        if (query.isEmpty()) {
            return ToolResult.failure(null, "web_search 缺少 query");
        }
        int count = ToolArguments.intArgument(arguments.get("count"), DEFAULT_COUNT);
        return search.search(query, Math.max(MIN_COUNT, Math.min(MAX_COUNT, count)));
    }
}
