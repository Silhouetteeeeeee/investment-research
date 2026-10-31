package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.port.NewsPort;
import java.util.Map;

/** Port of {@code assistant/tools.py:get_news_events}：无公司参数，limit 默认 12、钳在 1..20。 */
public final class NewsEventsTool implements ResearchTool {
    private final NewsPort news;
    private final ToolSchema schema;

    public NewsEventsTool(NewsPort news, ToolSchemas schemas) {
        this.news = news;
        this.schema = schemas.get("get_news_events");
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        return news.news(Math.max(1, Math.min(20, limitOf(arguments))));
    }

    /** 与 Python {@code int(arguments.get("limit") or 12)} 一致：解析不了就当默认值。 */
    static int limitOf(Map<String, Object> arguments) {
        Object raw = arguments.get("limit");
        if (raw == null) {
            return 12;
        }
        if (raw instanceof Number number) {
            return (int) number.doubleValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException error) {
            try {
                return (int) Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException again) {
                return 12;
            }
        }
    }
}
