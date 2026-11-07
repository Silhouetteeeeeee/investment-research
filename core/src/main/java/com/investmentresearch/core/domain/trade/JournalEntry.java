package com.investmentresearch.core.domain.trade;

public record JournalEntry(
        long id, String tradeDate, TradingPhase phase, String text, String runId) {}
