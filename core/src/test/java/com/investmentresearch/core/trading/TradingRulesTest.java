package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TradingRulesTest {
    @Test
    void commission_has_a_five_yuan_floor() {
        assertThat(TradingRules.commission(100_000)).isEqualTo(500); // 1000 元 -> 0.25 元，取 5 元
        assertThat(TradingRules.commission(10_000_000)).isEqualTo(2_500); // 10 万元 -> 25 元
    }

    @Test
    void stamp_tax_is_one_per_mille_on_sell_amount() {
        assertThat(TradingRules.stampTax(1_000_000)).isEqualTo(1_000);
    }

    @Test
    void average_cost_includes_buy_commission() {
        // 100 股 @10.00 + 100 股 @11.00，佣金 5 元
        assertThat(TradingRules.averageCost(1000, 100, 1100, 100, 500)).isEqualTo(1053);
    }

    @Test
    void realized_pnl_subtracts_both_fees() {
        // 卖 100 股 @11.00：110000 - 500 佣金 - 110 印花税 - 100*1053
        assertThat(TradingRules.realizedPnl(1100, 100, 1053, 500, 110)).isEqualTo(4_090);
    }

    @Test
    void sellable_shares_exclude_same_day_buys() {
        assertThat(TradingRules.sellableShares(300, 100)).isEqualTo(200);
        assertThat(TradingRules.sellableShares(100, 100)).isZero();
        assertThat(TradingRules.sellableShares(100, 0)).isEqualTo(100);
    }

    @Test
    void triggers_are_inclusive() {
        assertThat(TradingRules.buyTriggered(1050, 1050)).isTrue();
        assertThat(TradingRules.buyTriggered(1050, 1051)).isFalse();
        assertThat(TradingRules.sellTriggered(1000L, 1300L, 1000)).isTrue(); // 止损
        assertThat(TradingRules.sellTriggered(1000L, 1300L, 1300)).isTrue(); // 止盈
        assertThat(TradingRules.sellTriggered(1000L, 1300L, 1100)).isFalse();
    }

    @Test
    void day_pnl_on_first_day_compares_with_initial_capital() {
        assertThat(TradingRules.dayPnl(103_000_000L, 100_000_000L)).isEqualTo(3_000_000L);
    }
}
