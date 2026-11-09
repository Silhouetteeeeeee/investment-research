package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.ItemSource;
import com.investmentresearch.core.domain.trade.ItemStatus;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.domain.trade.Side;
import com.investmentresearch.core.domain.trade.TickRecord;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradeOrder;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.QuotePort;
import com.investmentresearch.core.port.TradingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingExecutorTest {
    private static final String DATE = "2026-11-07";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant MORNING = Instant.parse("2026-11-07T02:00:00Z"); // 10:00

    private FakeRepo repo;
    private MutableClock clock;
    private QuoteCache cache;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        clock = new MutableClock(MORNING);
        cache = new QuoteCache();
    }

    @Test
    void buy_triggers_at_or_below_limit_and_creates_auto_sell_item() {
        long plan = seedPlan(buy("600000", 1_050L, 100, 980L, 1_200L));

        TradingExecutor.TickResult result = executor(List.of(quote("600000", 1_049L))).tick(DATE);

        assertThat(result.executed()).hasSize(1);
        assertThat(result.executed().get(0).priceCents()).isEqualTo(1_049L);
        assertThat(repo.account().cashCents()).isEqualTo(100_000_000L - 104_900 - 500);
        assertThat(repo.positions()).extracting(Position::code).containsExactly("600000");
        assertThat(repo.activePlan(DATE).items())
                .anySatisfy(
                        item -> {
                            assertThat(item.side()).isEqualTo(Side.SELL);
                            assertThat(item.source()).isEqualTo(ItemSource.AUTO);
                            assertThat(item.stopLossCents()).isEqualTo(980L);
                            assertThat(item.takeProfitCents()).isEqualTo(1_200L);
                        });
        assertThat(repo.orders(DATE)).hasSize(1);
        assertThat(repo.planItem(plan).status()).isEqualTo(ItemStatus.FILLED);
        assertThat(cache.byCode("600000").priceCents()).isEqualTo(1_049L);
    }

    @Test
    void buy_above_limit_does_nothing() {
        seedPlan(buy("600000", 1_050L, 100, null, null));

        TradingExecutor.TickResult result = executor(List.of(quote("600000", 1_051L))).tick(DATE);

        assertThat(result.executed()).isEmpty();
        assertThat(repo.orders(DATE)).isEmpty();
        assertThat(repo.positions()).isEmpty();
    }

    @Test
    void sell_stop_triggers() {
        repo.savePosition(new Position("600000", "浦发银行", 100, 1_000L));
        seedPlan(autoSell("600000", 900L, 1_200L, 100));

        TradingExecutor.TickResult result = executor(List.of(quote("600000", 899L))).tick(DATE);

        assertThat(result.executed()).hasSize(1);
        assertThat(result.executed().get(0).side()).isEqualTo(Side.SELL);
        assertThat(repo.positions()).isEmpty();
    }

    @Test
    void sell_take_profit_triggers() {
        repo.savePosition(new Position("600000", "浦发银行", 100, 1_000L));
        seedPlan(autoSell("600000", 900L, 1_200L, 100));

        TradingExecutor.TickResult result = executor(List.of(quote("600000", 1_201L))).tick(DATE);

        assertThat(result.executed()).hasSize(1);
        assertThat(repo.positions()).isEmpty();
    }

    @Test
    void same_day_buy_is_not_sellable() {
        repo.savePosition(new Position("600000", "浦发银行", 100, 1_000L));
        repo.insertOrder(
                new TradeOrder(
                        0, DATE, null, "600000", "浦发银行", Side.BUY, 1_000L, 100, 100_000L,
                        500L, 0L, null, "今日买入", "10:00:00"));
        long sell = seedPlan(autoSell("600000", 900L, 1_200L, 100));

        TradingExecutor.TickResult result = executor(List.of(quote("600000", 899L))).tick(DATE);

        assertThat(result.executed()).isEmpty();
        assertThat(repo.planItem(sell).status()).isEqualTo(ItemStatus.REJECTED);
        assertThat(repo.positions()).hasSize(1);
    }

    @Test
    void repeated_tick_fills_once() {
        seedPlan(buy("600000", 1_050L, 100, null, null));
        TradingExecutor executor = executor(List.of(quote("600000", 1_049L)));

        executor.tick(DATE);
        clock.advance(Duration.ofSeconds(10));
        executor.tick(DATE);

        assertThat(repo.orders(DATE)).hasSize(1);
        assertThat(repo.account().cashCents()).isEqualTo(100_000_000L - 104_900 - 500);
    }

    @Test
    void quote_date_mismatch_executes_nothing() {
        seedPlan(buy("600000", 1_050L, 100, null, null));
        Quote yesterday =
                new Quote(
                        "600000", "浦发银行", 1_049L, 980L, 990L, 1_020L, 970L, "2026-11-06",
                        "15:00:00", clock.millis());

        TradingExecutor.TickResult result = executor(List.of(yesterday)).tick(DATE);

        assertThat(result.executed()).isEmpty();
        assertThat(result.error()).isNotBlank();
        assertThat(repo.orders(DATE)).isEmpty();
        assertThat(cache.latest()).isEmpty();
    }

    @Test
    void zero_price_quote_is_skipped() {
        seedPlan(buy("600000", 1_050L, 100, null, null));

        TradingExecutor.TickResult result = executor(List.of(quote("600000", 0L))).tick(DATE);

        assertThat(result.executed()).isEmpty();
        assertThat(repo.orders(DATE)).isEmpty();
    }

    @Test
    void tick_persists_at_most_once_per_minute() {
        seedPlan(buy("600000", 1_050L, 100, null, null));
        TradingExecutor executor = executor(List.of(quote("600000", 1_051L)));

        executor.tick(DATE);
        clock.advance(Duration.ofSeconds(5));
        executor.tick(DATE);
        clock.advance(Duration.ofSeconds(5));
        executor.tick(DATE);

        assertThat(repo.tickSaves).isEqualTo(1);
    }

    @Test
    void buy_respects_cash_position_count_and_twenty_percent_cap() {
        // 20% 上限：100 万权益下单票 21 万元买不进
        seedPlan(buy("600000", 210_000L, 100, null, null));
        assertThat(executor(List.of(quote("600000", 200_100L))).tick(DATE).executed()).isEmpty();
        assertThat(repo.orders(DATE)).isEmpty();

        // 已有 5 只持仓，第 6 只买不进
        setUp();
        for (int i = 0; i < 5; i++) {
            repo.savePosition(new Position("60000" + i, "股票" + i, 100, 1_000L));
        }
        seedPlan(buy("600000", 1_050L, 100, null, null));
        executor(List.of(quote("600000", 1_049L))).tick(DATE);
        assertThat(repo.orders(DATE)).isEmpty();

        // 现金不够
        setUp();
        repo.saveAccount(new TradeAccount(100_000_000L, 50_000L));
        seedPlan(buy("600000", 1_050L, 100, null, null));
        executor(List.of(quote("600000", 1_049L))).tick(DATE);
        assertThat(repo.orders(DATE)).isEmpty();
    }

    @Test
    void twenty_percent_cap_values_other_holdings_at_the_latest_quote() {
        // 已有持仓要按最新价估值：按成本估值会低估权益，把合规买单误拒
        repo.savePosition(new Position("600519", "贵州茅台", 10_000, 1_000L));
        seedPlan(buy("600000", 1_500L, 15_000, null, null));

        TradingExecutor.TickResult result =
                executor(List.of(quote("600000", 1_500L), quote("600519", 2_000L))).tick(DATE);

        assertThat(result.executed()).hasSize(1);
        assertThat(repo.orders(DATE)).hasSize(1);
    }

    private TradingExecutor executor(List<Quote> quotes) {
        QuotePort port = codes -> quotes;
        return new TradingExecutor(repo, port, cache, clock);
    }

    private long seedPlan(PlanItem... items) {
        long planId =
                repo.insertPlan(
                        new TradePlan(
                                0, DATE, TradingPhase.PRE_MARKET, 1, "", "run-1", "ACTIVE",
                                List.of()));
        long first = 0;
        for (PlanItem item : items) {
            long id =
                    repo.insertPlanItem(
                            new PlanItem(
                                    0,
                                    planId,
                                    item.code(),
                                    item.name(),
                                    item.side(),
                                    item.triggerPriceCents(),
                                    item.shares(),
                                    item.stopLossCents(),
                                    item.takeProfitCents(),
                                    item.reason(),
                                    item.status(),
                                    item.source(),
                                    0,
                                    null,
                                    null,
                                    ""));
            if (first == 0) {
                first = id;
            }
        }
        return first;
    }

    private static PlanItem buy(
            String code, Long trigger, int shares, Long stop, Long take) {
        return item(code, Side.BUY, trigger, shares, stop, take, ItemSource.AGENT);
    }

    private static PlanItem autoSell(String code, Long stop, Long take, int shares) {
        return item(code, Side.SELL, null, shares, stop, take, ItemSource.AUTO);
    }

    private static PlanItem item(
            String code,
            Side side,
            Long trigger,
            int shares,
            Long stop,
            Long take,
            ItemSource source) {
        return new PlanItem(
                0, 0, code, "浦发银行", side, trigger, shares, stop, take, "理由",
                ItemStatus.PENDING, source, 0, null, null, "");
    }

    private static Quote quote(String code, long priceCents) {
        return new Quote(
                code, "浦发银行", priceCents, 980L, 990L, 1_020L, 970L, DATE, "10:00:00", 0L);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return SHANGHAI;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** 内存版仓储：只实现执行器用到的行为，事务直接执行。 */
    private static final class FakeRepo implements TradingRepository {
        private static final DateTimeFormatter STAMP =
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
        private TradeAccount account = new TradeAccount(100_000_000L, 100_000_000L);
        private final Map<String, Position> positions = new LinkedHashMap<>();
        private final List<TradePlan> plans = new ArrayList<>();
        private final Map<Long, PlanItem> items = new LinkedHashMap<>();
        private final List<TradeOrder> orders = new ArrayList<>();
        private final Map<String, EquitySnapshot> equity = new LinkedHashMap<>();
        private final List<JournalEntry> journals = new ArrayList<>();
        private final List<PhaseRecord> phases = new ArrayList<>();
        private TickRecord latestTick;
        private long nextId = 1;
        private int tickSaves;

        @Override
        public TradeAccount account() {
            return account;
        }

        @Override
        public void saveAccount(TradeAccount account) {
            this.account = account;
        }

        @Override
        public List<Position> positions() {
            return List.copyOf(positions.values());
        }

        @Override
        public void savePosition(Position position) {
            positions.put(position.code(), position);
        }

        @Override
        public void deletePosition(String code) {
            positions.remove(code);
        }

        @Override
        public TradePlan activePlan(String tradeDate) {
            TradePlan plan =
                    plans.stream()
                            .filter(p -> p.tradeDate().equals(tradeDate))
                            .filter(p -> "ACTIVE".equals(p.status()))
                            .max((a, b) -> Integer.compare(a.version(), b.version()))
                            .orElse(null);
            return plan == null ? null : withItems(plan);
        }

        @Override
        public TradePlan plan(long planId) {
            return plans.stream()
                    .filter(p -> p.id() == planId)
                    .findFirst()
                    .map(this::withItems)
                    .orElse(null);
        }

        @Override
        public List<TradePlan> plans(String tradeDate) {
            return plans.stream().filter(p -> p.tradeDate().equals(tradeDate)).toList();
        }

        @Override
        public long insertPlan(TradePlan plan) {
            long id = nextId++;
            plans.add(new TradePlan(id, plan.tradeDate(), plan.phase(), plan.version(),
                    plan.notes(), plan.runId(), plan.status(), List.of()));
            return id;
        }

        @Override
        public void updatePlanStatus(long planId, String status) {}

        @Override
        public void cancelPendingAgentItems(long planId) {}

        @Override
        public List<PlanItem> pendingAutoSellItems(long planId) {
            return items.values().stream()
                    .filter(i -> i.planId() == planId)
                    .filter(i -> i.source() == ItemSource.AUTO)
                    .filter(i -> i.status() == ItemStatus.PENDING)
                    .toList();
        }

        @Override
        public PlanItem planItem(long planItemId) {
            return items.get(planItemId);
        }

        @Override
        public long insertPlanItem(PlanItem item) {
            long id = nextId++;
            items.put(id, copy(item, id, item.status(), 0, null, null, ""));
            return id;
        }

        @Override
        public void updatePlanItem(PlanItem item) {
            items.put(item.id(), item);
        }

        @Override
        public boolean claimPendingItem(long planItemId, int filledShares, long priceCents) {
            PlanItem item = items.get(planItemId);
            if (item == null || item.status() != ItemStatus.PENDING) {
                return false;
            }
            items.put(
                    planItemId,
                    copy(item, item.id(), ItemStatus.FILLED, filledShares, priceCents, null, ""));
            return true;
        }

        @Override
        public long insertOrder(TradeOrder order) {
            long id = nextId++;
            orders.add(
                    new TradeOrder(
                            id, order.tradeDate(), order.planItemId(), order.code(), order.name(),
                            order.side(), order.priceCents(), order.shares(), order.amountCents(),
                            order.feeCents(), order.taxCents(), order.realizedPnlCents(),
                            order.reason(), order.quoteTime()));
            return id;
        }

        @Override
        public List<TradeOrder> orders(String tradeDate) {
            return orders.stream().filter(o -> o.tradeDate().equals(tradeDate)).toList();
        }

        @Override
        public long realizedPnl(String tradeDate) {
            return orders.stream()
                    .filter(o -> o.tradeDate().equals(tradeDate))
                    .filter(o -> o.side() == Side.SELL && o.realizedPnlCents() != null)
                    .mapToLong(TradeOrder::realizedPnlCents)
                    .sum();
        }

        @Override
        public void saveJournal(JournalEntry journal) {
            journals.add(journal);
        }

        @Override
        public List<JournalEntry> journals(String tradeDate) {
            return journals.stream().filter(j -> j.tradeDate().equals(tradeDate)).toList();
        }

        @Override
        public List<JournalEntry> recentJournals(int limit) {
            return journals.stream().limit(limit).toList();
        }

        @Override
        public void savePhase(PhaseRecord phase) {
            phases.add(phase);
        }

        @Override
        public PhaseRecord phase(String tradeDate, TradingPhase phase) {
            return phases.stream()
                    .filter(p -> p.tradeDate().equals(tradeDate) && p.phase() == phase)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public PhaseRecord phaseById(long id) {
            return null;
        }

        @Override
        public List<PhaseRecord> phases(String tradeDate) {
            return phases.stream().filter(p -> p.tradeDate().equals(tradeDate)).toList();
        }

        @Override
        public int markRunningPhasesError(String error) {
            return 0;
        }

        @Override
        public int expirePendingItems(String tradeDate) {
            return 0;
        }

        @Override
        public TickRecord latestTick(String tradeDate) {
            return latestTick;
        }

        @Override
        public void saveTick(TickRecord tick) {
            latestTick = tick;
            tickSaves++;
        }

        @Override
        public EquitySnapshot equity(String tradeDate) {
            return equity.get(tradeDate);
        }

        @Override
        public List<EquitySnapshot> equitySeries() {
            return List.copyOf(equity.values());
        }

        @Override
        public void saveEquity(EquitySnapshot snapshot) {
            equity.put(snapshot.tradeDate(), snapshot);
        }

        @Override
        public List<String> tradeDates(int limit) {
            return orders.stream().map(TradeOrder::tradeDate).distinct().toList();
        }

        @Override
        public <T> T transaction(Supplier<T> work) {
            return work.get();
        }

        private TradePlan withItems(TradePlan plan) {
            List<PlanItem> rows =
                    items.entrySet().stream()
                            .filter(e -> e.getValue().planId() == plan.id())
                            .map(Map.Entry::getValue)
                            .collect(Collectors.toList());
            return new TradePlan(
                    plan.id(), plan.tradeDate(), plan.phase(), plan.version(), plan.notes(),
                    plan.runId(), plan.status(), rows);
        }

        private static PlanItem copy(
                PlanItem item,
                long id,
                ItemStatus status,
                int filledShares,
                Long filledPrice,
                Long orderId,
                String note) {
            return new PlanItem(
                    id, item.planId(), item.code(), item.name(), item.side(),
                    item.triggerPriceCents(), item.shares(), item.stopLossCents(),
                    item.takeProfitCents(), item.reason(), status, item.source(), filledShares,
                    filledPrice, orderId, note);
        }
    }
}
