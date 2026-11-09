package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.TickRecord;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradeOrder;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import java.util.List;
import java.util.function.Supplier;

/** 模拟盘的全部持久化；所有金额都是「分」。 */
public interface TradingRepository {
    TradeAccount account();

    void saveAccount(TradeAccount account);

    List<Position> positions();

    void savePosition(Position position);

    void deletePosition(String code);

    /** 某日当前 ACTIVE 的计划（版本最高），items 已填充；没有则 null。 */
    TradePlan activePlan(String tradeDate);

    TradePlan plan(long planId);

    /** 某日全部计划，按 version 升序，不含 items。 */
    List<TradePlan> plans(String tradeDate);

    long insertPlan(TradePlan plan);

    void updatePlanStatus(long planId, String status);

    void cancelPendingAgentItems(long planId);

    List<PlanItem> pendingAutoSellItems(long planId);

    PlanItem planItem(long planItemId);

    long insertPlanItem(PlanItem item);

    void updatePlanItem(PlanItem item);

    /** 抢占式成交：只有仍是 PENDING 的那一次返回 true，避免重复下单。 */
    boolean claimPendingItem(long planItemId, int filledShares, long priceCents);

    long insertOrder(TradeOrder order);

    List<TradeOrder> orders(String tradeDate);

    long realizedPnl(String tradeDate);

    void saveJournal(JournalEntry journal);

    List<JournalEntry> journals(String tradeDate);

    List<JournalEntry> recentJournals(int limit);

    void savePhase(PhaseRecord phase);

    PhaseRecord phase(String tradeDate, TradingPhase phase);

    PhaseRecord phaseById(long id);

    List<PhaseRecord> phases(String tradeDate);

    /** 启动时清理上次进程留下的 RUNNING，返回行数。 */
    int markRunningPhasesError(String error);

    /** 收盘后把当天仍 PENDING 的条目置为 EXPIRED，返回行数。 */
    int expirePendingItems(String tradeDate);

    TickRecord latestTick(String tradeDate);

    void saveTick(TickRecord tick);

    EquitySnapshot equity(String tradeDate);

    List<EquitySnapshot> equitySeries();

    void saveEquity(EquitySnapshot snapshot);

    /** 有交易记录的日期，倒序；用于页面历史与日盈亏基准。 */
    List<String> tradeDates(int limit);

    <T> T transaction(Supplier<T> work);
}
