package com.investmentresearch.core.domain.trade;

import java.util.List;

public record TradePlan(
        long id,
        String tradeDate,
        TradingPhase phase,
        int version,
        String notes,
        String runId,
        String status,
        List<PlanItem> items) {}
