package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.screen.StockScreenQuery;

/** 全市场参数化筛选：读已同步的行情与财务快照，不做上游 IO。 */
public interface StockScreenPort {
    ToolResult screen(StockScreenQuery query);
}
