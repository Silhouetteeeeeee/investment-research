package com.investmentresearch.core.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.trade.EquitySnapshot;
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
import com.investmentresearch.core.port.TradingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 交易页读的只读视图；不调用上游，现价只读内存报价、最近 tick 或成本价。 */
public final class TradingView {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TradingRepository repo;
    private final TradingDayGate gate;
    private final QuoteCache quotes;
    private final Clock clock;

    public TradingView(
            TradingRepository repo, TradingDayGate gate, QuoteCache quotes, Clock clock) {
        this.repo = repo;
        this.gate = gate;
        this.quotes = quotes;
        this.clock = clock;
    }

    public Map<String, Object> state(String date) {
        String today = LocalDate.now(clock.withZone(MarketSession.ZONE)).toString();
        String day = date == null || date.isBlank() ? today : date.trim();
        boolean isToday = day.equals(today);

        TradeAccount account = repo.account();
        List<Position> positions = repo.positions();
        List<TradeOrder> orders = repo.orders(day);
        EquitySnapshot snapshot = repo.equity(day);
        Map<String, Long> prices = prices(day, isToday);

        long cash;
        long equity;
        long dayPnl;
        if (!isToday && snapshot != null) {
            cash = snapshot.cashCents();
            equity = snapshot.equityCents();
            dayPnl = snapshot.dayPnlCents();
        } else {
            cash = account.cashCents();
            equity = TradingRules.equity(cash, positions, prices);
            dayPnl = equity - previousEquity(day);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", day);
        data.put("account", account(account, cash, equity, dayPnl));
        data.put("positions", positionList(positions, prices, orders));
        data.put("plan", plan(repo.activePlan(day)));
        data.put("orders", orderList(orders));
        data.put("journals", journalList(repo.journals(day)));
        data.put("phases", phaseList(repo.phases(day)));
        data.put("tick", tick(repo.latestTick(day)));
        data.put("today_status", isToday ? gate.cachedStatus(LocalDate.parse(day)).name() : null);
        data.put("rules", rules());
        return data;
    }

    public List<String> dates(int limit) {
        return repo.tradeDates(limit);
    }

    public List<Map<String, Object>> equitySeries() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EquitySnapshot snapshot : repo.equitySeries()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("trade_date", snapshot.tradeDate());
            map.put("cash", snapshot.cashCents());
            map.put("market_value", snapshot.marketValueCents());
            map.put("equity", snapshot.equityCents());
            map.put("day_pnl", snapshot.dayPnlCents());
            map.put("prices", snapshot.prices());
            rows.add(map);
        }
        return rows;
    }

    /** 单个阶段的完整快照；payload 里的 answer/sources/trace/raw/run 原样展开。 */
    public Map<String, Object> phase(long id) {
        PhaseRecord phase = repo.phaseById(id);
        if (phase == null) {
            throw new IllegalArgumentException("阶段不存在：" + id);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (phase.payloadJson() != null && !phase.payloadJson().isBlank()) {
            try {
                data.putAll(JSON.readValue(phase.payloadJson(), MAP_TYPE));
            } catch (JsonProcessingException ignored) {
                // 损坏的 payload 不阻断页面，元数据仍然可读
            }
        }
        data.put("id", phase.id());
        data.put("date", phase.tradeDate());
        data.put("phase", phase.phase().dbValue());
        data.put("status", phase.status());
        data.put("started_at", phase.startedAt());
        data.put("finished_at", phase.finishedAt());
        data.put("run_id", phase.runId());
        data.put("error", phase.error());
        return data;
    }

    /** 现价优先级：内存报价（仅今天）→ 当日最近 tick → 当日权益快照 → 成本价（取用时回落）。 */
    private Map<String, Long> prices(String day, boolean isToday) {
        Map<String, Long> prices = new LinkedHashMap<>();
        if (isToday) {
            for (Quote quote : quotes.latest()) {
                if (quote.priceCents() > 0) {
                    prices.put(quote.code(), quote.priceCents());
                }
            }
        }
        TickRecord tick = repo.latestTick(day);
        if (tick != null) {
            for (Map<String, Object> row : rows(tick.quotesJson())) {
                Object code = row.get("code");
                Object price = row.get("priceCents");
                if (code != null && price instanceof Number number && number.longValue() > 0) {
                    prices.putIfAbsent(code.toString(), number.longValue());
                }
            }
        }
        EquitySnapshot snapshot = repo.equity(day);
        if (snapshot != null) {
            snapshot.prices().forEach(prices::putIfAbsent);
        }
        return prices;
    }

    /** 当日盈亏基准：本日之前最近一份权益快照，没有就用初始本金。 */
    private long previousEquity(String day) {
        long baseline = TradingRules.INITIAL_CAPITAL_CENTS;
        for (EquitySnapshot snapshot : repo.equitySeries()) {
            if (snapshot.tradeDate().compareTo(day) < 0) {
                baseline = snapshot.equityCents();
            }
        }
        return baseline;
    }

    private static Map<String, Object> account(
            TradeAccount account, long cash, long equity, long dayPnl) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cash", cash);
        map.put("initial", account.initialCents());
        map.put("equity", equity);
        map.put("day_pnl", dayPnl);
        return map;
    }

    private static List<Map<String, Object>> positionList(
            List<Position> positions, Map<String, Long> prices, List<TradeOrder> orders) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Position position : positions) {
            int boughtToday = 0;
            for (TradeOrder order : orders) {
                if (order.side() == Side.BUY && order.code().equals(position.code())) {
                    boughtToday += order.shares();
                }
            }
            long price = prices.getOrDefault(position.code(), position.avgCostCents());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", position.code());
            map.put("name", position.name());
            map.put("shares", position.shares());
            map.put("available", TradingRules.sellableShares(position.shares(), boughtToday));
            map.put("avg_cost", position.avgCostCents());
            map.put("price", price);
            map.put("value", price * position.shares());
            map.put("pnl", (price - position.avgCostCents()) * position.shares());
            rows.add(map);
        }
        return rows;
    }

    private static Map<String, Object> plan(TradePlan plan) {
        if (plan == null) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", item.id());
            map.put("code", item.code());
            map.put("name", item.name());
            map.put("side", item.side().name());
            map.put("trigger_price", item.triggerPriceCents());
            map.put("shares", item.shares());
            map.put("stop_loss", item.stopLossCents());
            map.put("take_profit", item.takeProfitCents());
            map.put("reason", item.reason());
            map.put("status", item.status().name());
            map.put("source", item.source().name());
            map.put("filled_shares", item.filledShares());
            map.put("note", item.note());
            items.add(map);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", plan.id());
        map.put("phase", plan.phase().dbValue());
        map.put("version", plan.version());
        map.put("status", plan.status());
        map.put("notes", plan.notes());
        map.put("items", items);
        return map;
    }

    private static List<Map<String, Object>> orderList(List<TradeOrder> orders) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TradeOrder order : orders) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", order.id());
            map.put("code", order.code());
            map.put("name", order.name());
            map.put("side", order.side().name());
            map.put("price", order.priceCents());
            map.put("shares", order.shares());
            map.put("amount", order.amountCents());
            map.put("fee", order.feeCents());
            map.put("tax", order.taxCents());
            map.put("realized_pnl", order.realizedPnlCents());
            map.put("reason", order.reason());
            map.put("quote_time", order.quoteTime());
            rows.add(map);
        }
        return rows;
    }

    private static List<Map<String, Object>> journalList(List<JournalEntry> journals) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JournalEntry journal : journals) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", journal.id());
            map.put("phase", journal.phase() == null ? null : journal.phase().dbValue());
            map.put("text", journal.text());
            map.put("run_id", journal.runId());
            rows.add(map);
        }
        return rows;
    }

    private static List<Map<String, Object>> phaseList(List<PhaseRecord> phases) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PhaseRecord phase : phases) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", phase.id());
            map.put("phase", phase.phase().dbValue());
            map.put("status", phase.status());
            map.put("started_at", phase.startedAt());
            map.put("finished_at", phase.finishedAt());
            map.put("run_id", phase.runId());
            map.put("error", phase.error());
            rows.add(map);
        }
        return rows;
    }

    private static Map<String, Object> tick(TickRecord tick) {
        if (tick == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("checked_at", tick.checkedAt());
        map.put("quotes", rows(tick.quotesJson()));
        map.put("executed", rows(tick.executedJson()));
        map.put("error", tick.error());
        return map;
    }

    private static List<Map<String, Object>> rows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private static Map<String, Object> rules() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("initial_capital", TradingRules.INITIAL_CAPITAL_CENTS);
        map.put("lot", TradingRules.LOT);
        map.put("max_positions", TradingRules.MAX_POSITIONS);
        map.put("max_single_position_pct", 20);
        map.put("commission_rate_pct", 0.025);
        map.put("min_commission", TradingRules.MIN_COMMISSION_CENTS);
        map.put("stamp_tax_rate_pct", 0.1);
        map.put("t_plus_one", true);
        return map;
    }
}
