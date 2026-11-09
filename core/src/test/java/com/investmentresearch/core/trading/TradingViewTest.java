package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.domain.trade.TickRecord;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.tool.TradingToolTestSupport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TradingViewTest {
    private static final String TODAY = "2026-11-07";
    private static final String YESTERDAY = "2026-11-06";
    private static final Clock CLOCK =
            Clock.fixed(
                    Instant.parse("2026-11-07T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final TradingToolTestSupport.InMemoryRepo repo =
            new TradingToolTestSupport.InMemoryRepo();
    private final QuoteCache quotes = new QuoteCache();

    @TempDir Path dir;

    private TradingExperience experience() {
        return new TradingExperience(dir.resolve("trading"), CLOCK);
    }

    private TradingView view(List<Quote> probe) {
        return new TradingView(
                repo, new TradingDayGate(codes -> probe, CLOCK), quotes, CLOCK, experience());
    }

    @Test
    void experience_endpoint_reads_the_document_and_its_update_time() {
        experience().save("# 交易经验\n- 止损后不要当天回补");

        Map<String, Object> data = view(List.of()).experience();

        assertThat(data).containsEntry("content", "# 交易经验\n- 止损后不要当天回补");
        assertThat(data.get("updated_at")).isNotNull();
    }

    @Test
    void experience_endpoint_is_empty_before_the_first_close_review() {
        Map<String, Object> data = view(List.of()).experience();

        assertThat(data).containsEntry("content", "");
        assertThat(data.get("updated_at")).isNull();
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

    @Test
    @SuppressWarnings("unchecked")
    void journals_archive_carries_date_and_phase_sources() {
        repo.saveJournal(
                new JournalEntry(1, YESTERDAY, TradingPhase.CLOSE, "复盘 [[fin:600000]]", "run-1"));
        repo.savePhase(
                new PhaseRecord(
                        0,
                        YESTERDAY,
                        TradingPhase.CLOSE,
                        "OK",
                        "2026-11-06 15:05:00",
                        "2026-11-06 15:10:00",
                        "run-1",
                        "{\"sources\":[{\"id\":\"fin:600000\",\"label\":\"财报\",\"detail\":\"营收\"}]}",
                        null));

        List<Map<String, Object>> rows = view(List.of()).journals(10);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("date")).isEqualTo(YESTERDAY);
        assertThat(row.get("phase")).isEqualTo("close");
        assertThat(row.get("text")).isEqualTo("复盘 [[fin:600000]]");
        assertThat(row.get("phase_id")).isNotNull();
        assertThat((List<Map<String, Object>>) row.get("sources"))
                .singleElement()
                .satisfies(source -> assertThat(source.get("id")).isEqualTo("fin:600000"));
    }

    private static Quote quote(String code, long price) {
        return new Quote(code, "浦发银行", price, price, price, price, price, TODAY, "10:00:00", 0L);
    }
}
