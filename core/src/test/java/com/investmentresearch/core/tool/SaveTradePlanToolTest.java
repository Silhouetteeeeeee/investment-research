package com.investmentresearch.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
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
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.QuotePort;
import com.investmentresearch.core.port.TradingRepository;
import com.investmentresearch.core.trading.TradingContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SaveTradePlanToolTest {
    private static final String DATE = "2026-11-07";
    private static final String BUY_CODE = "600000";
    private static final String OTHER_CODE = "600519";
    private static final String SIDE_BUY = "BUY";
    private static final String SIDE_SELL = "SELL";

    private FakeRepo repo;
    private TradingContext context;
    private Map<String, Long> prices;
    private SaveTradePlanTool tool;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        context = new TradingContext();
        context.begin(DATE, TradingPhase.PRE_MARKET);
        prices = defaultPrices();
        tool = new SaveTradePlanTool(
                context, repo, new FakeDirectory(), new FakeQuotes(), new ToolSchemas(Map.of()));
    }

    @Test
    void accepts_a_valid_plan_and_stages_it() {
        ToolResult result = tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 100, 900L, 1_200L, "好公司好价格"))), null);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("accepted", true).containsEntry("items", 1);
        assertThat(context.stagedPlan()).isNotNull();
        assertThat(context.stagedPlan().items()).hasSize(1);
    }

    @Test
    void rejects_missing_or_wrong_date_and_phase() {
        Map<String, Object> missingDate = new LinkedHashMap<>(args(TradingPhase.PRE_MARKET, List.of()));
        missingDate.remove("trade_date");
        assertThat(tool.execute(missingDate, null).ok()).isFalse();

        Map<String, Object> wrongDate = new LinkedHashMap<>(args(TradingPhase.PRE_MARKET, List.of()));
        wrongDate.put("trade_date", "2026-11-06");
        assertThat(tool.execute(wrongDate, null).ok()).isFalse();

        assertThat(tool.execute(args(TradingPhase.MIDDAY, List.of()), null).ok()).isFalse();
    }

    @Test
    void rejects_unknown_code_suspended_lot_size_and_bad_side() {
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy("999999", 1_000L, 100, 900L, 1_200L, "理由"))), null).ok()).isFalse();

        prices.put(BUY_CODE, 0L);
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 100, 900L, 1_200L, "理由"))), null).ok()).isFalse();

        prices.put(BUY_CODE, 1_000L);
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 150, 900L, 1_200L, "理由"))), null).ok()).isFalse();

        Map<String, Object> item = buy(BUY_CODE, 1_000L, 100, 900L, 1_200L, "理由");
        item.put("side", "HOLD");
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(item)), null).ok()).isFalse();
    }

    @Test
    void rejects_empty_reason() {
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 100, 900L, 1_200L, " "))), null).ok()).isFalse();
    }

    @Test
    void rejects_buy_without_stop_loss_or_take_profit_ordering() {
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 100, null, null, "理由"))), null).ok()).isFalse();

        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 100, 1_000L, 1_200L, "理由"))), null).ok()).isFalse();

        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 100, 900L, 1_000L, "理由"))), null).ok()).isFalse();
    }

    @Test
    void rejects_buy_price_more_than_21_percent_from_reference() {
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_300L, 100, 1_200L, 1_600L, "理由"))), null).ok()).isFalse();

        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_200L, 100, 1_100L, 1_400L, "理由"))), null).ok()).isTrue();
    }

    @Test
    void rejects_cash_and_position_limit_breaches() {
        repo.account = new TradeAccount(100_000_000L, 50_000L);
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 100, 900L, 1_200L, "理由"))), null).ok()).isFalse();

        repo.account = new TradeAccount(100_000_000L, 100_000_000L);
        for (int i = 0; i < 5; i++) {
            repo.positions.put("00000" + i, new Position("00000" + i, "股票" + i, 100, 1_000L));
        }
        prices.put("000000", 1_000L);
        prices.put("000001", 1_000L);
        prices.put("000002", 1_000L);
        prices.put("000003", 1_000L);
        prices.put("000004", 1_000L);
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(OTHER_CODE, 1_000L, 100, 900L, 1_200L, "理由"))), null).ok()).isFalse();

        repo.positions.clear();
        prices.put(BUY_CODE, 200_000L);
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 200_000L, 200, 190_000L, 240_000L, "理由"))), null).ok()).isFalse();
    }

    @Test
    void rejects_duplicate_pending_code_side() {
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(BUY_CODE, 1_000L, 100, 900L, 1_200L, "理由"),
                buy(BUY_CODE, 1_010L, 100, 900L, 1_200L, "再来一份"))), null).ok()).isFalse();
    }

    @Test
    void rejects_midday_buy_for_code_absent_from_premarket_plan() {
        long plan = repo.insertPlan(
                new TradePlan(0, DATE, TradingPhase.PRE_MARKET, 1, "", "run-1", "ACTIVE", List.of()));
        repo.insertPlanItem(
                new PlanItem(0, plan, BUY_CODE, "浦发银行", Side.BUY, 1_000L, 100, 900L, 1_200L,
                        "早盘", ItemStatus.PENDING, ItemSource.AGENT, 0, null, null, ""));
        context.begin(DATE, TradingPhase.MIDDAY);

        assertThat(tool.execute(args(TradingPhase.MIDDAY, List.of(
                buy(OTHER_CODE, 1_000L, 100, 900L, 1_200L, "理由"))), null).ok()).isFalse();
    }

    @Test
    void requires_a_sell_item_for_every_current_position() {
        repo.positions.put(BUY_CODE, new Position(BUY_CODE, "浦发银行", 100, 1_000L));

        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(OTHER_CODE, 1_000L, 100, 900L, 1_200L, "理由"))), null).ok()).isFalse();

        ToolResult result = tool.execute(args(TradingPhase.PRE_MARKET, List.of(
                buy(OTHER_CODE, 1_000L, 100, 900L, 1_200L, "理由"),
                sell(BUY_CODE, 900L, 1_200L, "持仓复盘"))), null);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void empty_plan_is_accepted_when_there_are_no_positions() {
        assertThat(tool.execute(args(TradingPhase.PRE_MARKET, List.of()), null).ok()).isTrue();
    }

    private Map<String, Object> args(TradingPhase phase, List<Map<String, Object>> items) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("trade_date", DATE);
        args.put("phase", phase.dbValue());
        args.put("notes", "今日计划");
        args.put("items", items);
        return args;
    }

    private static Map<String, Object> buy(
            String code, long trigger, int shares, Long stop, Long take, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("side", SIDE_BUY);
        item.put("shares", shares);
        item.put("trigger_price", trigger);
        item.put("stop_loss", stop);
        item.put("take_profit", take);
        item.put("reason", reason);
        return item;
    }

    private static Map<String, Object> sell(String code, Long stop, Long take, String reason) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("side", SIDE_SELL);
        item.put("shares", 100);
        item.put("stop_loss", stop);
        item.put("take_profit", take);
        item.put("reason", reason);
        return item;
    }

    private static Map<String, Long> defaultPrices() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put(BUY_CODE, 1_000L);
        map.put(OTHER_CODE, 1_000L);
        map.put("000001", 1_000L);
        return map;
    }

    private final class FakeQuotes implements QuotePort {
        @Override
        public List<Quote> quotes(List<String> codes) {
            List<Quote> rows = new ArrayList<>();
            for (String code : codes) {
                Long price = prices.getOrDefault(code, 0L);
                rows.add(
                        new Quote(
                                code, "股票", price, price, price, price, price, DATE, "10:00:00",
                                0L));
            }
            return rows;
        }
    }

    private static final class FakeDirectory implements CompanyDirectory {
        private static final Map<String, String> KNOWN =
                Map.of(BUY_CODE, "浦发银行", OTHER_CODE, "贵州茅台", "000001", "平安银行");

        @Override
        public Optional<StockRef> byCode(String code) {
            String name = KNOWN.get(code);
            return name == null ? Optional.empty() : Optional.of(new StockRef(code, name));
        }

        @Override
        public Optional<StockRef> byName(String name) {
            return KNOWN.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(name))
                    .findFirst()
                    .map(entry -> new StockRef(entry.getKey(), entry.getValue()));
        }

        @Override
        public List<StockRef> all() {
            return KNOWN.entrySet().stream()
                    .map(entry -> new StockRef(entry.getKey(), entry.getValue()))
                    .toList();
        }
    }

    /** 工具只需要账户、持仓和当天的计划；其余契约方法留空。 */
    private static final class FakeRepo implements TradingRepository {
        private TradeAccount account = new TradeAccount(100_000_000L, 100_000_000L);
        private final Map<String, Position> positions = new LinkedHashMap<>();
        private final List<TradePlan> plans = new ArrayList<>();

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
            return plans.stream()
                    .filter(p -> p.tradeDate().equals(tradeDate) && "ACTIVE".equals(p.status()))
                    .max((a, b) -> Integer.compare(a.version(), b.version()))
                    .orElse(null);
        }

        @Override
        public TradePlan plan(long planId) {
            return plans.stream().filter(p -> p.id() == planId).findFirst().orElse(null);
        }

        @Override
        public List<TradePlan> plans(String tradeDate) {
            return plans.stream().filter(p -> p.tradeDate().equals(tradeDate)).toList();
        }

        @Override
        public long insertPlan(TradePlan plan) {
            long id = plans.size() + 1L;
            plans.add(
                    new TradePlan(id, plan.tradeDate(), plan.phase(), plan.version(),
                            plan.notes(), plan.runId(), plan.status(), plan.items()));
            return id;
        }

        @Override
        public void updatePlanStatus(long planId, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelPendingAgentItems(long planId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PlanItem> pendingAutoSellItems(long planId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlanItem planItem(long planItemId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long insertPlanItem(PlanItem item) {
            TradePlan owner = plan(item.planId());
            List<PlanItem> items = new ArrayList<>(owner.items());
            items.add(item);
            int index = plans.indexOf(owner);
            plans.set(
                    index,
                    new TradePlan(owner.id(), owner.tradeDate(), owner.phase(), owner.version(),
                            owner.notes(), owner.runId(), owner.status(), items));
            return items.size();
        }

        @Override
        public void updatePlanItem(PlanItem item) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean claimPendingItem(long planItemId, int filledShares, long priceCents) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long insertOrder(TradeOrder order) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TradeOrder> orders(String tradeDate) {
            return List.of();
        }

        @Override
        public long realizedPnl(String tradeDate) {
            return 0;
        }

        @Override
        public void saveJournal(JournalEntry journal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<JournalEntry> journals(String tradeDate) {
            return List.of();
        }

        @Override
        public List<JournalEntry> recentJournals(int limit) {
            return List.of();
        }

        @Override
        public void savePhase(PhaseRecord phase) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PhaseRecord phase(String tradeDate, TradingPhase phase) {
            return null;
        }

        @Override
        public PhaseRecord phaseById(long id) {
            return null;
        }

        @Override
        public List<PhaseRecord> phases(String tradeDate) {
            return List.of();
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
            return null;
        }

        @Override
        public void saveTick(TickRecord tick) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EquitySnapshot equity(String tradeDate) {
            return null;
        }

        @Override
        public List<EquitySnapshot> equitySeries() {
            return List.of();
        }

        @Override
        public void saveEquity(EquitySnapshot snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> tradeDates(int limit) {
            return List.of();
        }

        @Override
        public <T> T transaction(Supplier<T> work) {
            return work.get();
        }
    }
}
