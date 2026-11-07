package com.investmentresearch.app.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.investmentresearch.app.config.TradingProperties;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.TradingRepository;
import com.investmentresearch.core.trading.TradingDayGate;
import com.investmentresearch.core.trading.TradingExecutor;
import com.investmentresearch.core.trading.TradingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class TradingSchedulerTest {
    private static final String TODAY = "2026-11-07";
    private static final LocalDate DATE = LocalDate.parse(TODAY);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TradingService service = mock(TradingService.class);
    private final TradingExecutor executor = mock(TradingExecutor.class);
    private final TradingDayGate gate = mock(TradingDayGate.class);
    private final TradingRepository repo = mock(TradingRepository.class);
    private final TradingBroadcaster broadcaster = mock(TradingBroadcaster.class);
    private final MutableClock clock = new MutableClock("2026-11-07T09:25:00");

    private TradingScheduler scheduler() {
        return new TradingScheduler(
                service, executor, gate, repo, broadcaster,
                new TradingProperties(true, 10, 15), clock);
    }

    private void gateIs(TradingDayGate.Status status) {
        when(gate.today()).thenReturn(DATE);
        when(gate.status(DATE)).thenReturn(status);
    }

    @Test
    void holiday_runs_nothing() {
        gateIs(TradingDayGate.Status.HOLIDAY);
        clock.set("2026-11-07T09:30:00");
        TradingScheduler scheduler = scheduler();

        scheduler.preMarketWindow();
        clock.set("2026-11-07T10:00:00");
        scheduler.tick();
        clock.set("2026-11-07T11:35:00");
        scheduler.midday();
        clock.set("2026-11-07T15:05:00");
        scheduler.close();

        verifyNoInteractions(service, executor);
    }

    @Test
    void premarket_waits_until_gate_confirms() {
        gateIs(TradingDayGate.Status.UNCONFIRMED);
        when(repo.phase(TODAY, TradingPhase.PRE_MARKET)).thenReturn(null);
        TradingScheduler scheduler = scheduler();

        scheduler.preMarketWindow();
        verify(service, never()).runPhase(any(), any(), any());

        when(gate.status(DATE)).thenReturn(TradingDayGate.Status.TRADING);
        clock.set("2026-11-07T09:30:00");
        scheduler.preMarketWindow();

        verify(service)
                .runPhase(eq(TODAY), eq(TradingPhase.PRE_MARKET), any());
    }

    @Test
    void unconfirmed_after_0945_marks_holiday() {
        gateIs(TradingDayGate.Status.UNCONFIRMED);
        clock.set("2026-11-07T09:45:00");

        scheduler().preMarketWindow();

        verify(gate).markHoliday(DATE);
        verifyNoInteractions(service);
    }

    @Test
    void failed_premarket_retries_once_at_0945() {
        gateIs(TradingDayGate.Status.TRADING);
        PhaseRecord failed =
                new PhaseRecord(
                        1, TODAY, TradingPhase.PRE_MARKET, "ERROR", "09:30", "09:31", null, null,
                        "模型失败");
        when(repo.phase(TODAY, TradingPhase.PRE_MARKET)).thenReturn(failed);
        TradingScheduler scheduler = scheduler();

        clock.set("2026-11-07T09:30:00");
        scheduler.preMarketWindow();
        verify(service, never()).runPhase(any(), any(), any());

        clock.set("2026-11-07T09:45:00");
        scheduler.preMarketWindow();
        verify(service, times(1)).runPhase(eq(TODAY), eq(TradingPhase.PRE_MARKET), any());

        scheduler.preMarketWindow();
        verify(service, times(1)).runPhase(eq(TODAY), eq(TradingPhase.PRE_MARKET), any());
    }

    @Test
    void midday_and_close_skip_on_non_trading_day() {
        gateIs(TradingDayGate.Status.UNCONFIRMED);
        TradingScheduler scheduler = scheduler();
        clock.set("2026-11-07T11:35:00");
        scheduler.midday();
        clock.set("2026-11-07T15:05:00");
        scheduler.close();

        verifyNoInteractions(service);
    }

    @Test
    void tick_only_in_continuous_session_and_after_gate() {
        gateIs(TradingDayGate.Status.TRADING);
        TradingScheduler scheduler = scheduler();

        clock.set("2026-11-07T12:00:00");
        scheduler.tick();
        verifyNoInteractions(executor);

        when(gate.status(DATE)).thenReturn(TradingDayGate.Status.UNCONFIRMED);
        clock.set("2026-11-07T10:00:00");
        scheduler.tick();
        verifyNoInteractions(executor);

        when(gate.status(DATE)).thenReturn(TradingDayGate.Status.TRADING);
        when(executor.tick(TODAY))
                .thenReturn(
                        new TradingExecutor.TickResult(
                                TODAY, "2026-11-07 10:00:00", List.of(), List.of(), null, true));
        scheduler.tick();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> event = ArgumentCaptor.forClass(Map.class);
        verify(broadcaster).publish(event.capture());
        assertThat(event.getValue()).containsEntry("type", "monitor");
        assertThat(event.getValue()).containsEntry("checked_at", "2026-11-07 10:00:00");
    }

    @Test
    void tick_backs_off_after_three_quote_failures() {
        gateIs(TradingDayGate.Status.TRADING);
        clock.set("2026-11-07T10:00:00");
        when(executor.tick(TODAY))
                .thenReturn(
                        new TradingExecutor.TickResult(
                                TODAY, "2026-11-07 10:00:00", List.of(), List.of(), "报价失败",
                                false));
        TradingScheduler scheduler = scheduler();

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();
        verify(executor, times(3)).tick(TODAY);

        scheduler.tick();
        verify(executor, times(3)).tick(TODAY);

        clock.set("2026-11-07T10:01:01");
        scheduler.tick();
        verify(executor, times(4)).tick(TODAY);
    }

    @Test
    void startup_catch_up_runs_premarket_only_inside_window_and_without_plan() {
        gateIs(TradingDayGate.Status.TRADING);
        when(repo.activePlan(TODAY)).thenReturn(null);
        when(repo.phase(TODAY, TradingPhase.PRE_MARKET)).thenReturn(null);
        TradingScheduler scheduler = scheduler();

        clock.set("2026-11-07T09:30:00");
        scheduler.catchUp();
        verify(repo).markRunningPhasesError("应用重启中断");
        verify(service).runPhase(eq(TODAY), eq(TradingPhase.PRE_MARKET), any());

        clock.set("2026-11-07T15:00:00");
        scheduler.catchUp();
        verify(service, times(1)).runPhase(any(), any(), any());
    }

    @Test
    void startup_catch_up_skips_when_plan_exists() {
        gateIs(TradingDayGate.Status.TRADING);
        when(repo.activePlan(TODAY)).thenReturn(mock(TradePlan.class));
        clock.set("2026-11-07T09:30:00");

        scheduler().catchUp();

        verify(service, never()).runPhase(any(), any(), any());
    }

    @Test
    void phases_do_not_overlap() throws Exception {
        gateIs(TradingDayGate.Status.TRADING);
        when(repo.phase(TODAY, TradingPhase.PRE_MARKET)).thenReturn(null);
        when(repo.phase(TODAY, TradingPhase.MIDDAY)).thenReturn(null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            started.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return null;
                        })
                .when(service)
                .runPhase(any(), any(), any());
        TradingScheduler scheduler = scheduler();
        clock.set("2026-11-07T09:30:00");

        Thread running = new Thread(scheduler::preMarketWindow);
        running.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        clock.set("2026-11-07T11:35:00");
        scheduler.midday();
        verify(service, times(1)).runPhase(any(), any(), any());

        release.countDown();
        running.join(5000);
    }

    @Test
    void close_expires_pending_items_before_review() {
        gateIs(TradingDayGate.Status.TRADING);
        clock.set("2026-11-07T15:05:00");
        when(repo.phase(TODAY, TradingPhase.CLOSE)).thenReturn(null);

        scheduler().close();

        InOrder inOrder = inOrder(repo, service);
        inOrder.verify(repo).expirePendingItems(TODAY);
        inOrder.verify(service).runPhase(eq(TODAY), eq(TradingPhase.CLOSE), any());
    }

    @Test
    void startup_marks_stale_running_phases_error() {
        clock.set("2026-11-07T19:00:00");

        scheduler().catchUp();

        verify(repo).markRunningPhasesError("应用重启中断");
        verifyNoInteractions(service, gate);
    }

    /** 可在用例中间拨动的时钟。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(String local) {
            set(local);
        }

        void set(String local) {
            instant = LocalDateTime.parse(local).atZone(ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
