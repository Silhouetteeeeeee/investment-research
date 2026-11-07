package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class MarketSessionTest {
    @Test
    void continuous_trading_windows_are_half_open_at_the_close() {
        assertThat(MarketSession.inContinuousTrading(LocalTime.of(9, 29))).isFalse();
        assertThat(MarketSession.inContinuousTrading(LocalTime.of(9, 30))).isTrue();
        assertThat(MarketSession.inContinuousTrading(LocalTime.of(11, 30))).isTrue();
        assertThat(MarketSession.inContinuousTrading(LocalTime.of(11, 31))).isFalse();
        assertThat(MarketSession.inContinuousTrading(LocalTime.of(12, 59))).isFalse();
        assertThat(MarketSession.inContinuousTrading(LocalTime.of(13, 0))).isTrue();
        assertThat(MarketSession.inContinuousTrading(LocalTime.of(14, 59))).isTrue();
        assertThat(MarketSession.inContinuousTrading(LocalTime.of(15, 0))).isFalse();
    }
}
