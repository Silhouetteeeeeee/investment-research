package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 大盘全景与状态分类：对照 {@code stock.market.build_market_overview}。 */
public interface MarketPort {
    /**
     * 取一次盘面数据。单段失败只记进 {@code data.errors}，全部为空才是失败结果。
     *
     * <p>{@code data} 的键与 Python 同名：{@code as_of / session / indices / turnover / breadth /
     * trend / sectors / limit_up / news / errors / state}。
     */
    ToolResult overview();
}
