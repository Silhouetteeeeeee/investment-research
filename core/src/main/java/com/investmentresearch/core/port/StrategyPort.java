package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.ToolResult;
import java.util.List;
import java.util.Map;

/** 策略与风险管理：对照 {@code research.strategy.build_plan}。 */
public interface StrategyPort {
    /**
     * 给出有条件的操作预案。{@code marketState} 为空时由适配器现场取一次大盘状态；
     * {@code profile} 只看 {@code risk_tolerance / position_pct / cash_pct}。
     */
    ToolResult plan(
            Map<String, Object> profile,
            String marketState,
            String riskLevel,
            List<Map<String, Object>> holdings);
}
