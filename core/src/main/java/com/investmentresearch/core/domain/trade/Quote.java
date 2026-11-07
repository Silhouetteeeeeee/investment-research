package com.investmentresearch.core.domain.trade;

/** 秒级报价；金额全部用「分」避免浮点误差。 */
public record Quote(
        String code,
        String name,
        long priceCents,
        long prevCloseCents,
        long openCents,
        long highCents,
        long lowCents,
        String quoteDate,
        String quoteTime,
        long fetchedAtMillis) {

    public boolean tradable() {
        return priceCents > 0;
    }
}
