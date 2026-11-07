package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.domain.trade.TickRecord;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.tool.TradingToolTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TradingViewTest {
    private static final String TODAY = "2026-11-07";
    private static final String YESTERDAY = "2026-11-06";
    private static final Clock CLOCK =
            Clock.fixed(
                    Instant.parse("2026-11-07T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final TradingToolTestSupport.InMemoryRepo repo =
            new TradingToolTestSupport.InMemoryRepo();
    private final QuoteCache quotes = new QuoteCache();

    private TradingView view(List<Quote> probe) {
        return new TradingView(repo, new TradingDayGate(codes -> probe, CLOCK), quotes, CLOCK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void empty_state_does_not_throw_and_reports_today_status() {
        // 探针能确认今天是交易日，但 state 不触发探测，所以仍是 UNCONFIRMED
        Map<String, Object> state = view(List.of(quote("600000", 10_000L))).state(null);

        assertThat(state).containsKeys("date", "account", "positions", "today_status", "rules");
        assertThat(state.get("date")).isEqualTo(TODAY);
        assertThat(state.get("today_status")).isEqualTo("UNCONFIRMED");
        assertThat((List<?>) state.get("positions")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void historical_date_reads_equity_snapshot() {
        repo.saveEquity(
                new EquitySnapshot(YESTERDAY, 52_000_000L, 60_000_000L, 112_000_000L, 1_000_000L,
                        Map.of("600000", 12_000L)));

        Map<String, Object> state = view(List.of()).state(YESTERDAY);
        Map<String, Object> account = (Map<String, Object>) state.get("account");

        assertThat(account.get("cash")).isEqualTo(52_000_000L);
        assertThat(account.get("equity")).isEqualTo(112_000_000L);
        assertThat(account.get("day_pnl")).isEqualTo(1_000_000L);
        // today_status 只对今天有意义
        assertThat(state.get("today_status")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void today_uses_latest_quote_for_equity() {
        repo.saveAccount(new TradeAccount(100_000_000L, 90_000_000L));
        repo.savePosition(new Position("600000", "浦发银行", 1000, 10_000L));
        quotes.update(List.of(quote("600000", 11_000L)));

        Map<String, Object> state = view(List.of()).state(TODAY);
        Map<String, Object> account = (Map<String, Object>) state.get("account");
        Map<String, Object> position =
                (Map<String, Object>) ((List<?>) state.get("positions")).get(0);

        assertThat(account.get("equity")).isEqualTo(101_000_000L);
        assertThat(position.get("price")).isEqualTo(11_000L);
        assertThat(position.get("value")).isEqualTo(11_000_000L);
        assertThat(position.get("pnl")).isEqualTo(1_000_000L);
        assertThat(position.get("available")).isEqualTo(1000);
    }

    @Test
    @SuppressWarnings("unchecked")
    void today_falls_back_to_last_tick_quote_then_cost() {
        repo.saveAccount(new TradeAccount(100_000_000L, 90_000_000L));
        repo.savePosition(new Position("600000", "浦发银行", 1000, 10_000L));
        repo.savePosition(new Position("000001", "平安银行", 100, 12_000L));
        repo.saveTick(
                new TickRecord(
                        0,
                        TODAY,
                        "2026-11-07 10:00:00",
                        "[{\"code\":\"600000\",\"name\":\"浦发银行\",\"priceCents\":12000}]",
                        "[]",
                        null));

        Map<String, Object> state = view(List.of()).state(TODAY);
        Map<String, Object> account = (Map<String, Object>) state.get("account");
        Map<String, Object> tick = (Map<String, Object>) state.get("tick");

        // 600000 用 tick 里的 12.00；000001 没有报价，回落成本 12.00
        assertThat(account.get("equity")).isEqualTo(90_000_000L + 12_000_000L + 1_200_000L);
        assertThat(tick.get("checked_at")).isEqualTo("2026-11-07 10:00:00");
        assertThat((List<?>) tick.get("quotes")).hasSize(1);
    }

    @Test
    void unknown_phase_id_is_illegal_argument() {
        assertThatThrownBy(() -> view(List.of()).phase(404))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void phase_returns_metadata_with_payload() {
        repo.savePhase(
                new PhaseRecord(
                        0,
                        TODAY,
                        TradingPhase.PRE_MARKET,
                        "OK",
                        "2026-11-07 09:20:00",
                        "2026-11-07 09:22:00",
                        "run-1",
                        "{\"answer\":\"计划已提交\"}",
                        null));

        long id = repo.phases(TODAY).get(0).id();
        Map<String, Object> phase = view(List.of()).phase(id);

        assertThat(phase.get("phase")).isEqualTo("pre_market");
        assertThat(phase.get("status")).isEqualTo("OK");
        assertThat(phase.get("answer")).isEqualTo("计划已提交");
    }

    private static Quote quote(String code, long price) {
        return new Quote(code, "浦发银行", price, price, price, price, price, TODAY, "10:00:00", 0L);
    }
}
