package com.investmentresearch.core.domain.trade;

/** 盘中一次轮询的结果；{@code quotesJson}/{@code executedJson} 给交易页做时间线。 */
public record TickRecord(
        long id,
        String tradeDate,
        String checkedAt,
        String quotesJson,
        String executedJson,
        String error) {}
