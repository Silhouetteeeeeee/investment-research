package com.investmentresearch.core.tool;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 交易相关测试共用的内存仓储。 */
public final class TradingToolTestSupport {
    private TradingToolTestSupport() {}

    public static final class InMemoryRepo implements TradingRepository {
        private TradeAccount account = new TradeAccount(100_000_000L, 100_000_000L);
        private final Map<String, Position> positions = new LinkedHashMap<>();
        private final List<TradePlan> plans = new ArrayList<>();
        private final List<TradeOrder> orders = new ArrayList<>();
        private final List<JournalEntry> journals = new ArrayList<>();
        private final List<PhaseRecord> phases = new ArrayList<>();
        private final Map<String, EquitySnapshot> equity = new LinkedHashMap<>();
        private final Map<String, TickRecord> ticks = new LinkedHashMap<>();
        private long nextId = 1;

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
            long id = nextId++;
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
            replaceItems(planId, item -> item.status() == ItemStatus.PENDING
                    && item.source() == ItemSource.AGENT
                    ? copy(item, ItemStatus.CANCELLED)
                    : item);
        }

        @Override
        public List<PlanItem> pendingAutoSellItems(long planId) {
            TradePlan owner = plan(planId);
            if (owner == null) {
                return List.of();
            }
            return owner.items().stream()
                    .filter(i -> i.source() == ItemSource.AUTO)
                    .filter(i -> i.status() == ItemStatus.PENDING)
                    .toList();
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
            long id = nextId++;
            TradePlan owner = plan(item.planId());
            List<PlanItem> items = new ArrayList<>(owner.items());
            items.add(copy(item, id));
            plans.set(
                    plans.indexOf(owner),
                    new TradePlan(owner.id(), owner.tradeDate(), owner.phase(), owner.version(),
                            owner.notes(), owner.runId(), owner.status(), items));
            return id;
        }

        @Override
        public void updatePlanItem(PlanItem item) {
            replaceItems(item.planId(), existing -> existing.id() == item.id() ? item : existing);
        }

        @Override
        public boolean claimPendingItem(long planItemId, int filledShares, long priceCents) {
            PlanItem item = planItem(planItemId);
            if (item == null || item.status() != ItemStatus.PENDING) {
                return false;
            }
            updatePlanItem(copy(item, ItemStatus.FILLED));
            return true;
        }

        @Override
        public long insertOrder(TradeOrder order) {
            long id = nextId++;
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
            for (int i = 0; i < phases.size(); i++) {
                PhaseRecord existing = phases.get(i);
                if (existing.tradeDate().equals(phase.tradeDate())
                        && existing.phase() == phase.phase()) {
                    phases.set(
                            i,
                            new PhaseRecord(existing.id(), phase.tradeDate(), phase.phase(),
                                    phase.status(), phase.startedAt(), phase.finishedAt(),
                                    phase.runId(), phase.payloadJson(), phase.error()));
                    return;
                }
            }
            phases.add(
                    new PhaseRecord(nextId++, phase.tradeDate(), phase.phase(), phase.status(),
                            phase.startedAt(), phase.finishedAt(), phase.runId(),
                            phase.payloadJson(), phase.error()));
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
            return phases.stream().filter(p -> p.id() == id).findFirst().orElse(null);
        }

        @Override
        public List<PhaseRecord> phases(String tradeDate) {
            return phases.stream().filter(p -> p.tradeDate().equals(tradeDate)).toList();
        }

        @Override
        public int markRunningPhasesError(String error) {
            int count = 0;
            for (int i = 0; i < phases.size(); i++) {
                PhaseRecord phase = phases.get(i);
                if ("RUNNING".equals(phase.status())) {
                    phases.set(
                            i,
                            new PhaseRecord(phase.id(), phase.tradeDate(), phase.phase(),
                                    "ERROR", phase.startedAt(), phase.finishedAt(),
                                    phase.runId(), phase.payloadJson(), error));
                    count++;
                }
            }
            return count;
        }

        @Override
        public int expirePendingItems(String tradeDate) {
            int count = 0;
            for (TradePlan plan : plans) {
                if (!plan.tradeDate().equals(tradeDate)) {
                    continue;
                }
                for (PlanItem item : plan.items()) {
                    if (item.status() == ItemStatus.PENDING) {
                        updatePlanItem(copy(item, ItemStatus.EXPIRED));
                        count++;
                    }
                }
            }
            return count;
        }

        @Override
        public TickRecord latestTick(String tradeDate) {
            return ticks.get(tradeDate);
        }

        @Override
        public void saveTick(TickRecord tick) {
            ticks.put(tick.tradeDate(), tick);
        }

        @Override
        public EquitySnapshot equity(String tradeDate) {
            return equity.get(tradeDate);
        }

        @Override
        public List<EquitySnapshot> equitySeries() {
            return equity.values().stream()
                    .sorted((a, b) -> a.tradeDate().compareTo(b.tradeDate()))
                    .toList();
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

        private void replaceItems(long planId, java.util.function.UnaryOperator<PlanItem> mapper) {
            TradePlan owner = plan(planId);
            if (owner == null) {
                return;
            }
            List<PlanItem> items = new ArrayList<>();
            for (PlanItem item : owner.items()) {
                items.add(mapper.apply(item));
            }
            plans.set(
                    plans.indexOf(owner),
                    new TradePlan(owner.id(), owner.tradeDate(), owner.phase(), owner.version(),
                            owner.notes(), owner.runId(), owner.status(), items));
        }

        private static PlanItem copy(PlanItem item, ItemStatus status) {
            return new PlanItem(item.id(), item.planId(), item.code(), item.name(), item.side(),
                    item.triggerPriceCents(), item.shares(), item.stopLossCents(),
                    item.takeProfitCents(), item.reason(), status, item.source(),
                    item.filledShares(), item.filledPriceCents(), item.orderId(), item.note());
        }

        private static PlanItem copy(PlanItem item, long id) {
            return new PlanItem(id, item.planId(), item.code(), item.name(), item.side(),
                    item.triggerPriceCents(), item.shares(), item.stopLossCents(),
                    item.takeProfitCents(), item.reason(), item.status(), item.source(),
                    item.filledShares(), item.filledPriceCents(), item.orderId(), item.note());
        }
    }
}
