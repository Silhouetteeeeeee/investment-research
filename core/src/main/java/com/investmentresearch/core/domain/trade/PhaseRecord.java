package com.investmentresearch.core.domain.trade;

/** 一次阶段运行（盘前/午盘/收盘）；{@code payloadJson} 是 AskResult 的全量快照。 */
public record PhaseRecord(
        long id,
        String tradeDate,
        TradingPhase phase,
        String status,
        String startedAt,
        String finishedAt,
        String runId,
        String payloadJson,
        String error) {}
