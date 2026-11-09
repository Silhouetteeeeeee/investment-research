package com.investmentresearch.core.domain.screen;

/** 收盘行情快照的一行；缺失指标为 {@code null}，{@code totalCapYi} 单位为亿元。 */
public record ScreenQuote(
        String code,
        String name,
        String industry,
        Double price,
        Double chgPct,
        Double amountYi,
        Double turnover,
        Double pe,
        Double pb,
        Double totalCapYi,
        String tradeDate) {}
