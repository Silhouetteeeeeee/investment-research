package com.investmentresearch.core.domain.trade;

public record TradeOrder(
        long id,
        String tradeDate,
        Long planItemId,
        String code,
        String name,
        Side side,
        long priceCents,
        int shares,
        long amountCents,
        long feeCents,
        long taxCents,
        Long realizedPnlCents,
        String reason,
        String quoteTime) {}
