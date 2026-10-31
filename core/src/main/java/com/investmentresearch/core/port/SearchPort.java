package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 通用联网搜索端口。 */
public interface SearchPort {
    /** {@code query} 已由工具层去空校验，{@code count} 已钳到 1..10。 */
    ToolResult search(String query, int count);
}
