package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.trade.EquitySnapshot;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 交易工具测试用的内存仓储；只实现读取状态和写复盘需要的行为。 */
final class TradingToolTestSupport {
    private TradingToolTestSupport() {}

    static final class InMemoryRepo implements TradingRepository {
        private TradeAccount account = new TradeAccount(100_000_000L, 100_000_000L);
        private final Map<String, Position> positions = new LinkedHashMap<>();
        private final List<TradePlan> plans = new ArrayList<>();
        private final List<TradeOrder> orders = new ArrayList<>();
        private final List<JournalEntry> journals = new ArrayList<>();

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
            TradePlan owner = plan(planId);
            if (owner == null) {
                return;
            }
            plans.set(
                    plans.indexOf(owner),
                    new TradePlan(owner.id(), owner.tradeDate(), owner.phase(), owner.version(),
                            owner.notes(), owner.runId(), status, owner.items()));
        }

        @Override
        public void cancelPendingAgentItems(long planId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PlanItem> pendingAutoSellItems(long planId) {
            return List.of();
        }

        @Override
        public PlanItem planItem(long planItemId) {
            for (TradePlan plan : plans) {
                for (PlanItem item : plan.items()) {
                    if (item.id() == planItemId) {
                        return item;
                    }
                }
            }
            return null;
        }

        @Override
        public long insertPlanItem(PlanItem item) {
            TradePlan owner = plan(item.planId());
            List<PlanItem> items = new ArrayList<>(owner.items());
            items.add(item);
            plans.set(
                    plans.indexOf(owner),
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
            long id = orders.size() + 1L;
            orders.add(
                    new TradeOrder(id, order.tradeDate(), order.planItemId(), order.code(),
                            order.name(), order.side(), order.priceCents(), order.shares(),
                            order.amountCents(), order.feeCents(), order.taxCents(),
                            order.realizedPnlCents(), order.reason(), order.quoteTime()));
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
