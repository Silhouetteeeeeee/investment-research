package com.investmentresearch.core.domain.trade;

/**
 * 计划里的一条委托。{@code source=AUTO} 的条目由止损/止盈追加；
 * {@code triggerPriceCents} 为空表示开盘价买入。
 */
public record PlanItem(
        long id,
        long planId,
        String code,
        String name,
        Side side,
        Long triggerPriceCents,
        int shares,
        Long stopLossCents,
        Long takeProfitCents,
        String reason,
        ItemStatus status,
        ItemSource source,
        int filledShares,
        Long filledPriceCents,
        Long orderId,
        String note) {}
