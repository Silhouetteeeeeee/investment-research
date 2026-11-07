package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.port.QuotePort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TradingDayGateTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-07T01:25:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    @Test
    void quotes_from_yesterday_leave_the_day_unconfirmed() {
        FakeQuotes quotes = new FakeQuotes("2026-11-06");

        TradingDayGate gate = new TradingDayGate(quotes, CLOCK);

        assertThat(gate.probe(TODAY)).isEqualTo(TradingDayGate.Status.UNCONFIRMED);
        assertThat(gate.status(TODAY)).isEqualTo(TradingDayGate.Status.UNCONFIRMED);
    }

    @Test
    void quotes_from_today_confirm_trading_and_are_cached() {
        FakeQuotes quotes = new FakeQuotes("2026-11-07");
        TradingDayGate gate = new TradingDayGate(quotes, CLOCK);

        assertThat(gate.status(TODAY)).isEqualTo(TradingDayGate.Status.TRADING);
        assertThat(gate.status(TODAY)).isEqualTo(TradingDayGate.Status.TRADING);

        assertThat(quotes.calls.get()).isEqualTo(1);
    }

    @Test
    void marking_a_holiday_wins_over_previous_probe() {
        TradingDayGate gate = new TradingDayGate(new FakeQuotes("2026-11-06"), CLOCK);

        gate.markHoliday(TODAY);

        assertThat(gate.status(TODAY)).isEqualTo(TradingDayGate.Status.HOLIDAY);
    }

    @Test
    void upstream_failure_is_unconfirmed_not_holiday() {
        QuotePort broken =
                codes -> {
                    throw new IllegalStateException("上游挂了");
                };

        assertThat(new TradingDayGate(broken, CLOCK).probe(TODAY))
                .isEqualTo(TradingDayGate.Status.UNCONFIRMED);
    }

    private static final class FakeQuotes implements QuotePort {
        private final String quoteDate;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeQuotes(String quoteDate) {
            this.quoteDate = quoteDate;
        }

        @Override
        public List<Quote> quotes(List<String> codes) {
            calls.incrementAndGet();
            return List.of(
                    new Quote(
                            "600000",
                            "浦发银行",
                            1010,
                            980,
                            990,
                            1020,
                            970,
                            quoteDate,
                            "15:00:00",
                            CLOCK.millis()));
        }
    }
}
