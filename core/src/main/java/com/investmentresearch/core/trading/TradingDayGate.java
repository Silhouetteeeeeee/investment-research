package com.investmentresearch.core.trading;

import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.port.QuotePort;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 没有节假日日历时，用「报价日期是否等于今天」判定交易日。
 * 宁可空一天，也不在假日按旧价格下单；上游故障一律是 UNCONFIRMED，不当成休市。
 */
public final class TradingDayGate {
    public enum Status {
        UNKNOWN,
        TRADING,
        HOLIDAY,
        UNCONFIRMED
    }

    private static final List<String> PROBE_CODES =
            List.of("600000", "000001", "600519", "000002", "601318");

    private final QuotePort quotes;
    private final Clock clock;
    private final AtomicReference<LocalDate> confirmed = new AtomicReference<>();
    private final AtomicReference<LocalDate> rejected = new AtomicReference<>();
    private final AtomicReference<LocalDate> probed = new AtomicReference<>();

    public TradingDayGate(QuotePort quotes, Clock clock) {
        this.quotes = quotes;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(MarketSession.ZONE));
    }

    /** 已判定的直接返回缓存；探测过的日期不再重复打上游。 */
    public Status status(LocalDate date) {
        if (date.equals(confirmed.get())) {
            return Status.TRADING;
        }
        if (date.equals(rejected.get())) {
            return Status.HOLIDAY;
        }
        if (date.equals(probed.get())) {
            return Status.UNCONFIRMED;
        }
        return probe(date);
    }

    public Status probe(LocalDate date) {
        probed.set(date);
        List<Quote> rows;
        try {
            rows = quotes.quotes(PROBE_CODES);
        } catch (RuntimeException error) {
            return Status.UNCONFIRMED;
        }
        String expected = date.toString();
        for (Quote quote : rows) {
            if (expected.equals(quote.quoteDate())) {
                confirmed.set(date);
                return Status.TRADING;
            }
        }
        return Status.UNCONFIRMED;
    }

    public void markHoliday(LocalDate date) {
        rejected.set(date);
    }
}
