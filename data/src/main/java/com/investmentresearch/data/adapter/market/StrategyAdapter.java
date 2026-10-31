package com.investmentresearch.data.adapter.market;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.MarketPort;
import com.investmentresearch.core.port.StrategyPort;
import com.investmentresearch.core.research.MarketState;
import com.investmentresearch.core.research.Strategy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Port of {@code assistant/tools.py:_strategy_plan_tool}：市场状态可以由调用方给，也可以现场取一次大盘再判断。
 */
@Component
public final class StrategyAdapter implements StrategyPort {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> STATES =
            List.of(MarketState.STRONG, MarketState.RANGE, MarketState.WEAK, MarketState.EXTREME);

    private final MarketPort market;
    private final Clock clock;

    @Autowired
    public StrategyAdapter(MarketPort market) {
        this(market, Clock.systemDefaultZone());
    }

    StrategyAdapter(MarketPort market, Clock clock) {
        this.market = market;
        this.clock = clock;
    }

    @Override
    public ToolResult plan(
            Map<String, Object> profile,
            String marketState,
            String riskLevel,
            List<Map<String, Object>> holdings) {
        String asOf = LocalDateTime.now(clock).format(TIMESTAMP);
        List<Source> sources = List.of();
        Map<String, Object> view = new LinkedHashMap<>();
        String state = marketState == null ? "" : marketState.strip();
        if (STATES.contains(state)) {
            view.put("state", state);
            view.put("risk_level", riskLevel);
        } else {
            try {
                ToolResult overview = market.overview();
                if (!overview.ok()) {
                    throw new MarketSupport.MarketException(overview.error());
                }
                view = MarketState.classify(overview.data());
                asOf = overview.asOf() == null || overview.asOf().isEmpty() ? asOf : overview.asOf();
                sources = overview.sources();
            } catch (RuntimeException error) {
                view.put("state", null);
                view.put("error", "大盘状态获取失败：" + message(error));
            }
        }

        Map<String, Object> result;
        try {
            result = Strategy.buildPlan(profile, view, holdings);
        } catch (RuntimeException error) {
            return ToolResult.failure(null, "策略方案生成失败：" + message(error));
        }
        Object problem = view.get("error");
        if (problem != null) {
            List<Object> missing = new ArrayList<>();
            if (result.get("missing") instanceof List<?> existing) {
                missing.addAll(existing);
            }
            missing.add(problem);
            result.put("missing", missing);
        }
        return ToolResult.ok(null, asOf, sources, result);
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
