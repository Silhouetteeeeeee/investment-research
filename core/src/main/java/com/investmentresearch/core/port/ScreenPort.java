package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;

/** 量化选股：对照 {@code research.screening.screen_candidates}。 */
public interface ScreenPort {
    /**
     * 全市场硬过滤 → 板块配额选样 → 日线量化打分。
     *
     * <p>{@code data} 的键与 Python 同名：{@code as_of / universe / filters / candidates / errors / notes /
     * missing}。
     */
    ToolResult screen(int limit, double minAmountYi);
}
