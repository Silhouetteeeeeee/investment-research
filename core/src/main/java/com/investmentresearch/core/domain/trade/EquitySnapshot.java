package com.investmentresearch.core.domain.trade;

import java.util.Map;

/** 某日收盘（或最后一跳）的权益；{@code prices} 是 code -> 收盘价（分）。 */
public record EquitySnapshot(
        String tradeDate,
        long cashCents,
        long marketValueCents,
        long equityCents,
        long dayPnlCents,
        Map<String, Long> prices) {}
