package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.domain.trade.Side;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradeOrder;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.port.QuotePort;
import com.investmentresearch.core.port.TradingRepository;
import com.investmentresearch.core.trading.MarketSession;
import com.investmentresearch.core.trading.TradingContext;
import com.investmentresearch.core.trading.TradingRules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型每轮思考前读取的权威状态：账户、持仓（含 T+1 可用股数）、当前计划、成交与复盘。
 * 报价拿不到时按成本价估值，不因为上游故障阻断思考。
 */
public final class GetTradingStateTool implements ResearchTool {
    private final TradingRepository repo;
    private final QuotePort quotes;
    private final TradingContext context;
    private final ToolSchemas schemas;

    public GetTradingStateTool(
            TradingRepository repo,
            QuotePort quotes,
            TradingContext context,
            ToolSchemas schemas) {
        this.repo = repo;
        this.quotes = quotes;
        this.context = context;
        this.schemas = schemas;
    }

    @Override
    public ToolSchema schema() {
        return schemas.get("get_trading_state");
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        String date = text(arguments.get("date"));
        if (date == null) {
            date = context.tradeDate();
        }
        if (date == null) {
            date = LocalDate.now(MarketSession.ZONE).toString();
        }

        List<Position> positions = repo.positions();
        Map<String, Long> prices = quotePrices(positions);
        TradeAccount account = repo.account();
        long equity = TradingRules.equity(account.cashCents(), positions, prices);
        List<TradeOrder> orders = repo.orders(date);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", date);
        data.put("account", account(account, equity, date));
        data.put("positions", positionList(positions, prices, date, orders));
        data.put("plan", plan(repo.activePlan(date)));
        data.put("orders", orderList(orders));
        data.put("journals", journalList(repo.journals(date)));
        data.put("rules", rules());
        return ToolResult.ok(null, date, List.of(), data);
    }

    private Map<String, Long> quotePrices(List<Position> positions) {
        if (positions.isEmpty()) {
            return Map.of();
        }
        List<String> codes = new ArrayList<>();
        for (Position position : positions) {
            codes.add(position.code());
        }
        Map<String, Long> prices = new LinkedHashMap<>();
        try {
            for (Quote quote : quotes.quotes(codes)) {
                if (quote.priceCents() > 0) {
                    prices.put(quote.code(), quote.priceCents());
                }
            }
        } catch (RuntimeException error) {
            // 报价失败时退回成本价估值
        }
        return prices;
    }

    private Map<String, Object> account(TradeAccount account, long equity, String date) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cash", account.cashCents());
        map.put("cash_yuan", yuan(account.cashCents()));
        map.put("initial", account.initialCents());
        map.put("initial_yuan", yuan(account.initialCents()));
        map.put("equity", equity);
        map.put("equity_yuan", yuan(equity));
        long baseline = previousEquity(date);
        map.put("day_pnl", equity - baseline);
        map.put("day_pnl_yuan", yuan(equity - baseline));
        return map;
    }

    /** 当日盈亏基准：本日之前最近一份权益快照，没有就用初始本金。 */
    private long previousEquity(String date) {
        long baseline = TradingRules.INITIAL_CAPITAL_CENTS;
        for (EquitySnapshot snapshot : repo.equitySeries()) {
            if (snapshot.tradeDate().compareTo(date) < 0) {
                baseline = snapshot.equityCents();
            }
        }
        return baseline;
    }

    private List<Map<String, Object>> positionList(
            List<Position> positions, Map<String, Long> prices, String date, List<TradeOrder> orders) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Position position : positions) {
            int boughtToday = 0;
            for (TradeOrder order : orders) {
                if (order.side() == Side.BUY && order.code().equals(position.code())) {
                    boughtToday += order.shares();
                }
            }
            long price = prices.getOrDefault(position.code(), position.avgCostCents());
            long pnl = (price - position.avgCostCents()) * position.shares();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", position.code());
            map.put("name", position.name());
            map.put("shares", position.shares());
            map.put("available", TradingRules.sellableShares(position.shares(), boughtToday));
            map.put("avg_cost", position.avgCostCents());
            map.put("avg_cost_yuan", yuan(position.avgCostCents()));
            map.put("price", price);
            map.put("price_yuan", yuan(price));
            map.put("value", price * position.shares());
            map.put("value_yuan", yuan(price * position.shares()));
            map.put("pnl", pnl);
            map.put("pnl_yuan", yuan(pnl));
            rows.add(map);
        }
        return rows;
    }

    private Map<String, Object> plan(TradePlan plan) {
        if (plan == null) {
            return null;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", item.code());
            map.put("name", item.name());
            map.put("side", item.side().name());
            map.put("trigger_price", item.triggerPriceCents());
            map.put("trigger_price_yuan", yuan(item.triggerPriceCents()));
            map.put("shares", item.shares());
            map.put("stop_loss", item.stopLossCents());
            map.put("stop_loss_yuan", yuan(item.stopLossCents()));
            map.put("take_profit", item.takeProfitCents());
            map.put("take_profit_yuan", yuan(item.takeProfitCents()));
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

    private List<Map<String, Object>> orderList(List<TradeOrder> orders) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TradeOrder order : orders) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", order.id());
            map.put("code", order.code());
            map.put("name", order.name());
            map.put("side", order.side().name());
            map.put("price", order.priceCents());
            map.put("price_yuan", yuan(order.priceCents()));
            map.put("shares", order.shares());
            map.put("amount", order.amountCents());
            map.put("amount_yuan", yuan(order.amountCents()));
            map.put("fee", order.feeCents());
            map.put("tax", order.taxCents());
            map.put("realized_pnl", order.realizedPnlCents());
            map.put("reason", order.reason());
            map.put("quote_time", order.quoteTime());
            rows.add(map);
        }
        return rows;
    }

    private List<Map<String, Object>> journalList(List<JournalEntry> journals) {
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

    private Map<String, Object> rules() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("initial_capital_yuan", yuan(TradingRules.INITIAL_CAPITAL_CENTS));
        map.put("lot", TradingRules.LOT);
        map.put("max_positions", TradingRules.MAX_POSITIONS);
        map.put("max_single_position_pct", 20);
        map.put("commission_rate_pct", 0.025);
        map.put("min_commission_yuan", "5.00");
        map.put("stamp_tax_rate_pct", 0.1);
        map.put("t_plus_one", true);
        return map;
    }

    private static String yuan(Long cents) {
        return cents == null ? null : String.format(Locale.ROOT, "%.2f", cents / 100.0);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
