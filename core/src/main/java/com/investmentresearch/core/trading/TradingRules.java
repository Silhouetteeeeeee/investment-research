package com.investmentresearch.core.trading;

import com.investmentresearch.core.domain.trade.Position;
import java.util.List;
import java.util.Map;

/** A 股模拟盘的费用与成交规则；金额一律用「分」。 */
public final class TradingRules {
    public static final long INITIAL_CAPITAL_CENTS = 100_000_000L; // 100 万元
    public static final int LOT = 100;
    public static final int MAX_POSITIONS = 5;
    public static final double MAX_SINGLE_POSITION = 0.20;
    public static final double COMMISSION_RATE = 0.00025;
    public static final long MIN_COMMISSION_CENTS = 500;
    public static final double STAMP_TAX_RATE = 0.001;

    private TradingRules() {}

    public static long commission(long amountCents) {
        return Math.max(MIN_COMMISSION_CENTS, Math.round(amountCents * COMMISSION_RATE));
    }

    public static long stampTax(long amountCents) {
        return Math.round(amountCents * STAMP_TAX_RATE);
    }

    public static long buyCost(long priceCents, int shares) {
        long amount = priceCents * shares;
        return amount + commission(amount);
    }

    public static long sellProceeds(long priceCents, int shares) {
        long amount = priceCents * shares;
        return amount - commission(amount) - stampTax(amount);
    }

    public static boolean isLot(int shares) {
        return shares > 0 && shares % LOT == 0;
    }

    /** T+1：当天买入的股份当天不能卖。 */
    public static int sellableShares(int shares, int boughtToday) {
        return Math.max(0, shares - boughtToday);
    }

    /** 限价买入：现价不高于限价即可成交（含等于）。 */
    public static boolean buyTriggered(long limitCents, long priceCents) {
        return priceCents <= limitCents;
    }

    /** 止损与止盈都写死在早盘计划里，到价即卖（含等于）。 */
    public static boolean sellTriggered(Long stopCents, Long takeCents, long priceCents) {
        return (stopCents != null && priceCents <= stopCents)
                || (takeCents != null && priceCents >= takeCents);
    }

    public static long averageCost(
            long oldAvg, int oldShares, long priceCents, int newShares, long feeCents) {
        long total = oldAvg * oldShares + priceCents * newShares + feeCents;
        return Math.round(total / (double) (oldShares + newShares));
    }

    public static long realizedPnl(
            long sellPrice, int shares, long avgCost, long feeCents, long taxCents) {
        return sellPrice * shares - feeCents - taxCents - avgCost * shares;
    }

    /** 缺价（停牌/未取到）时按成本估值，避免权益凭空缩水。 */
    public static long equity(long cashCents, List<Position> positions, Map<String, Long> prices) {
        long marketValue = 0;
        for (Position position : positions) {
            Long price = prices == null ? null : prices.get(position.code());
            marketValue += (price == null ? position.avgCostCents() : price) * position.shares();
        }
        return cashCents + marketValue;
    }

    public static long dayPnl(long equityCents, long previousEquityCents) {
        return equityCents - previousEquityCents;
    }
}
