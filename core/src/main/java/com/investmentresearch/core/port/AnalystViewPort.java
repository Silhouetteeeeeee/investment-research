package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 卖方研报观点：{@code stock/analyst_views.py:build_analyst_views}。 */
public interface AnalystViewPort {
    ToolResult analystViews(String code, int days);
}
