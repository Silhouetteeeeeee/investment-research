package com.investmentresearch.data.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.ItemSource;
import com.investmentresearch.core.domain.trade.ItemStatus;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Side;
import com.investmentresearch.core.domain.trade.TickRecord;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradeOrder;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.TradingRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TradingRepositoryImplTest {
    private static final String DATE = "2026-11-07";

    @TempDir Path tempDir;

    private TradingRepository repository;

    @BeforeEach
    void setUp() {
        String url =
                "jdbc:sqlite:"
                        + tempDir.resolve("app.db").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration/sqlite")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository =
                new TradingRepositoryImpl(
                        jdbc,
                        new DataSourceTransactionManager(dataSource),
                        new ObjectMapper());
    }

    @Test
    void account_and_positions_round_trip() {
        assertThat(repository.account().cashCents()).isEqualTo(100_000_000L);

        repository.saveAccount(new TradeAccount(100_000_000L, 90_000_000L));
        assertThat(repository.account().cashCents()).isEqualTo(90_000_000L);

        repository.savePosition(new Position("605499", "东鹏饮料", 100, 20_000L));
        assertThat(repository.positions()).containsExactly(new Position("605499", "东鹏饮料", 100, 20_000L));

        repository.deletePosition("605499");
        assertThat(repository.positions()).isEmpty();
    }

    @Test
    void active_plan_returns_only_active_with_items() {
        long v1 = newPlan(1, "ACTIVE");
        newItem(v1, "605499", Side.BUY, ItemStatus.PENDING, ItemSource.AGENT);

        TradePlan active = repository.activePlan(DATE);
        assertThat(active.id()).isEqualTo(v1);
        assertThat(active.items()).hasSize(1);

        long v2 = newPlan(2, "ACTIVE");
        repository.updatePlanStatus(v1, "SUPERSEDED");

        assertThat(repository.activePlan(DATE).id()).isEqualTo(v2);
        assertThat(repository.activePlan(DATE).items()).isEmpty();
        assertThat(repository.plans(DATE)).extracting(TradePlan::version).containsExactly(1, 2);
        assertThat(repository.plans(DATE).get(0).items()).isEmpty();
    }

    @Test
    void claim_pending_item_is_single_shot() {
        long plan = newPlan(1, "ACTIVE");
        long item = newItem(plan, "605499", Side.BUY, ItemStatus.PENDING, ItemSource.AGENT);

        assertThat(repository.claimPendingItem(item, 100, 19_900L)).isTrue();
        assertThat(repository.claimPendingItem(item, 100, 19_900L)).isFalse();

        PlanItem updated = repository.planItem(item);
        assertThat(updated.status()).isEqualTo(ItemStatus.FILLED);
        assertThat(updated.filledShares()).isEqualTo(100);
        assertThat(updated.filledPriceCents()).isEqualTo(19_900L);
    }

    @Test
    void transaction_rolls_back_on_exception() {
        assertThatThrownBy(
                        () ->
                                repository.transaction(
                                        () -> {
                                            repository.insertOrder(order(Side.BUY, null, 0L));
                                            throw new IllegalStateException("boom");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.orders(DATE)).isEmpty();
    }

    @Test
    void phase_upsert_replaces_payload() {
        repository.savePhase(
                new PhaseRecord(0, DATE, TradingPhase.PRE_MARKET, "RUNNING", "s1", null, "run-1", "{\"a\":1}", null));
        repository.savePhase(
                new PhaseRecord(0, DATE, TradingPhase.PRE_MARKET, "OK", "s1", "s2", "run-1", "{\"a\":2}", null));

        PhaseRecord loaded = repository.phase(DATE, TradingPhase.PRE_MARKET);
        assertThat(loaded.status()).isEqualTo("OK");
        assertThat(loaded.payloadJson()).isEqualTo("{\"a\":2}");
        assertThat(loaded.finishedAt()).isEqualTo("s2");
        assertThat(repository.phases(DATE)).hasSize(1);
        assertThat(repository.phaseById(loaded.id()).startedAt()).isEqualTo("s1");
    }

    @Test
    void realized_pnl_sums_sell_orders_of_the_day() {
        repository.insertOrder(order(Side.SELL, 100L, 0L));
        repository.insertOrder(order(Side.SELL, 200L, 0L));
        repository.insertOrder(order(Side.BUY, null, 0L));

        assertThat(repository.orders(DATE)).hasSize(3);
        assertThat(repository.realizedPnl(DATE)).isEqualTo(300L);

        repository.saveJournal(new JournalEntry(0, DATE, TradingPhase.MIDDAY, "午盘复盘", "run-1"));
        assertThat(repository.journals(DATE)).extracting(JournalEntry::text).containsExactly("午盘复盘");
        assertThat(repository.recentJournals(5)).hasSize(1);
    }

    @Test
    void running_phases_become_error_and_pending_items_expire() {
        repository.savePhase(
                new PhaseRecord(0, DATE, TradingPhase.MIDDAY, "RUNNING", "s1", null, "run-1", null, null));

        assertThat(repository.markRunningPhasesError("进程重启")).isEqualTo(1);
        assertThat(repository.phase(DATE, TradingPhase.MIDDAY).status()).isEqualTo("ERROR");
        assertThat(repository.phase(DATE, TradingPhase.MIDDAY).error()).isEqualTo("进程重启");

        long plan = newPlan(1, "ACTIVE");
        long item = newItem(plan, "605499", Side.BUY, ItemStatus.PENDING, ItemSource.AGENT);

        assertThat(repository.expirePendingItems(DATE)).isEqualTo(1);
        assertThat(repository.planItem(item).status()).isEqualTo(ItemStatus.EXPIRED);
        assertThat(repository.expirePendingItems(DATE)).isZero();
    }

    @Test
    void tick_and_equity_round_trip() {
        repository.saveTick(
                new TickRecord(0, DATE, "t1", "[{\"code\":\"605499\"}]", "[]", null));
        assertThat(repository.latestTick(DATE).checkedAt()).isEqualTo("t1");

        repository.saveEquity(
                new EquitySnapshot(DATE, 60_000_000L, 20_000L, 60_020_000L, 20_000L, Map.of("605499", 200L)));
        EquitySnapshot equity = repository.equity(DATE);
        assertThat(equity.marketValueCents()).isEqualTo(20_000L);
        assertThat(equity.prices()).containsEntry("605499", 200L);
        assertThat(repository.equitySeries()).hasSize(1);
        assertThat(repository.tradeDates(10)).containsExactly(DATE);
    }

    private long newPlan(int version, String status) {
        return repository.insertPlan(
                new TradePlan(0, DATE, TradingPhase.PRE_MARKET, version, "notes", "run-1", status, List.of()));
    }

    private long newItem(
            long planId, String code, Side side, ItemStatus status, ItemSource source) {
        return repository.insertPlanItem(
                new PlanItem(
                        0,
                        planId,
                        code,
                        "东鹏饮料",
                        side,
                        20_000L,
                        100,
                        null,
                        null,
                        "理由",
                        status,
                        source,
                        0,
                        null,
                        null,
                        ""));
    }

    private TradeOrder order(Side side, Long pnl, long amount) {
        return new TradeOrder(
                0,
                DATE,
                null,
                "605499",
                "东鹏饮料",
                side,
                1_100L,
                100,
                amount,
                500L,
                110L,
                pnl,
                "测试",
                "15:00:00");
    }
}
