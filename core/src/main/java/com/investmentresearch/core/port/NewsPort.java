package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 新闻与事件取数端口（{@code research.news_events.build_news_events}）。 */
public interface NewsPort {
    /** {@code limit} 已由工具层钳到 1..20。 */
    ToolResult news(int limit);
}
