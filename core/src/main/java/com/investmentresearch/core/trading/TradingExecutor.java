package com.investmentresearch.core.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.trade.ItemSource;
import com.investmentresearch.core.domain.trade.ItemStatus;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.domain.trade.Side;
import com.investmentresearch.core.domain.trade.TickRecord;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradeOrder;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.port.QuotePort;
import com.investmentresearch.core.port.TradingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盘中执行器：完全按早盘计划里的价格条件成交，不调用模型。
 * 幂等靠 {@code claimPendingItem} 的 UPDATE ... WHERE status='PENDING'。
 */
public final class TradingExecutor {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final long PERSIST_INTERVAL_MS = 60_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TradingRepository repo;
    private final QuotePort quotes;
    private final QuoteCache cache;
    private final Clock clock;

    public TradingExecutor(
            TradingRepository repo, QuotePort quotes, QuoteCache cache, Clock clock) {
        this.repo = repo;
        this.quotes = quotes;
        this.cache = cache;
        this.clock = clock;
    }

    /** 没有 ACTIVE 计划或不在交易时段时返回 null；其他情况总会带回一份 TickResult。 */
    public TickResult tick(String tradeDate) {
        if (!MarketSession.inContinuousTrading(LocalDateTime.now(clock).toLocalTime())) {
            return null;
        }
        TradePlan plan = repo.activePlan(tradeDate);
        if (plan == null) {
            return null;
        }

        List<PlanItem> pending =
                plan.items().stream()
                        .filter(item -> item.status() == ItemStatus.PENDING)
                        .toList();
        Set<String> codes = new LinkedHashSet<>();
        for (PlanItem item : pending) {
            codes.add(item.code());
        }
        List<Position> positions = repo.positions();
        for (Position position : positions) {
            codes.add(position.code());
        }
        String checkedAt = STAMP.format(LocalDateTime.now(clock));
        if (codes.isEmpty()) {
            return new TickResult(tradeDate, checkedAt, List.of(), List.of(), null, false);
        }

        List<Quote> rows;
        try {
            rows = quotes.quotes(List.copyOf(codes));
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? "报价失败" : error.getMessage();
            return persist(
                    new TickResult(tradeDate, checkedAt, List.of(), List.of(), message, false));
        }

        Map<String, Quote> byCode = new LinkedHashMap<>();
        for (Quote quote : rows) {
            byCode.put(quote.code(), quote);
        }
        for (Quote quote : rows) {
            if (!tradeDate.equals(quote.quoteDate())) {
                // 日期对不上宁可不交易，也不把昨天的价当今天成交
                return persist(
                        new TickResult(
                                tradeDate,
                                checkedAt,
                                rows,
                                List.of(),
                                "报价日期 " + quote.quoteDate() + " 不是 " + tradeDate + "，今日不成交",
                                false));
            }
        }
        cache.update(rows);

        List<Execution> executed = new ArrayList<>();
        for (PlanItem item : pending) {
            Quote quote = byCode.get(item.code());
            if (quote == null || !quote.tradable()) {
                continue;
            }
            Execution execution = execute(item, quote, tradeDate);
            if (execution != null) {
                executed.add(execution);
            }
        }
        return persist(new TickResult(tradeDate, checkedAt, rows, executed, null, false));
    }

    private Execution execute(PlanItem item, Quote quote, String tradeDate) {
        long price = quote.priceCents();
        if (item.side() == Side.BUY) {
            if (item.triggerPriceCents() != null
                    && !TradingRules.buyTriggered(item.triggerPriceCents(), price)) {
                return null;
            }
        } else if (!TradingRules.sellTriggered(
                item.stopLossCents(), item.takeProfitCents(), price)) {
            return null;
        }
        return repo.transaction(
                () -> {
                    if (!repo.claimPendingItem(item.id(), item.shares(), price)) {
                        return null;
                    }
                    return item.side() == Side.BUY
                            ? buy(item, quote, tradeDate)
                            : sell(item, quote, tradeDate);
                });
    }

    private Execution buy(PlanItem item, Quote quote, String tradeDate) {
        long price = quote.priceCents();
        int shares = item.shares();
        long amount = price * shares;
        long fee = TradingRules.commission(amount);
        long cost = amount + fee;

        List<Position> positions = repo.positions();
        Position held = positionOf(positions, item.code());
        long cash = repo.account().cashCents();
        if (held != null) {
            return reject(item, "已持有该股，不加仓");
        }
        if (cash < cost) {
            return reject(item, "现金不足");
        }
        if (positions.size() >= TradingRules.MAX_POSITIONS) {
            return reject(item, "持仓已达 " + TradingRules.MAX_POSITIONS + " 只上限");
        }
        long equity = equity(cash, positions, quote);
        if (amount > TradingRules.MAX_SINGLE_POSITION * equity) {
            return reject(item, "单票仓位超过 20% 上限");
        }
        repo.saveAccount(new TradeAccount(repo.account().initialCents(), cash - cost));
        repo.savePosition(
                new Position(
                        item.code(),
                        item.name(),
                        shares,
                        TradingRules.averageCost(0, 0, price, shares, fee)));
        long orderId =
                repo.insertOrder(
                        new TradeOrder(
                                0,
                                tradeDate,
                                item.id(),
                                item.code(),
                                item.name(),
                                Side.BUY,
                                price,
                                shares,
                                amount,
                                fee,
                                0L,
                                null,
                                item.reason(),
                                quote.quoteTime()));
        repo.updatePlanItem(
                copy(
                        item,
                        ItemStatus.FILLED,
                        shares,
                        price,
                        orderId,
                        item.note()));
        if (item.stopLossCents() != null || item.takeProfitCents() != null) {
            repo.insertPlanItem(
                    new PlanItem(
                            0,
                            item.planId(),
                            item.code(),
                            item.name(),
                            Side.SELL,
                            null,
                            shares,
                            item.stopLossCents(),
                            item.takeProfitCents(),
                            "买入成交后自动跟随止盈止损",
                            ItemStatus.PENDING,
                            ItemSource.AUTO,
                            0,
                            null,
                            null,
                            ""));
        }
        return new Execution(item.code(), Side.BUY, shares, price, "限价成交");
    }

    private Execution sell(PlanItem item, Quote quote, String tradeDate) {
        long price = quote.priceCents();
        Position position = positionOf(repo.positions(), item.code());
        if (position == null) {
            return reject(item, "无持仓");
        }
        int boughtToday = 0;
        for (TradeOrder order : repo.orders(tradeDate)) {
            if (order.side() == Side.BUY && order.code().equals(item.code())) {
                boughtToday += order.shares();
            }
        }
        int available = TradingRules.sellableShares(position.shares(), boughtToday);
        if (available <= 0) {
            return reject(item, "T+1：今日买入的股份今天不能卖");
        }
        int shares = Math.min(item.shares(), available);
        String note = shares < item.shares() ? "可用仅 " + available + " 股，部分成交" : "";

        long amount = price * shares;
        long fee = TradingRules.commission(amount);
        long tax = TradingRules.stampTax(amount);
        long realized =
                TradingRules.realizedPnl(price, shares, position.avgCostCents(), fee, tax);

        int remaining = position.shares() - shares;
        if (remaining == 0) {
            repo.deletePosition(position.code());
        } else {
            repo.savePosition(
                    new Position(
                            position.code(), position.name(), remaining, position.avgCostCents()));
        }
        repo.saveAccount(
                new TradeAccount(
                        repo.account().initialCents(),
                        repo.account().cashCents() + amount - fee - tax));
        long orderId =
                repo.insertOrder(
                        new TradeOrder(
                                0,
                                tradeDate,
                                item.id(),
                                item.code(),
                                item.name(),
                                Side.SELL,
                                price,
                                shares,
                                amount,
                                fee,
                                tax,
                                realized,
                                item.reason(),
                                quote.quoteTime()));
        repo.updatePlanItem(copy(item, ItemStatus.FILLED, shares, price, orderId, note));
        return new Execution(item.code(), Side.SELL, shares, price, note);
    }

    private Execution reject(PlanItem item, String reason) {
        repo.updatePlanItem(copy(item, ItemStatus.REJECTED, 0, null, null, reason));
        return null;
    }

    private long equity(long cash, List<Position> positions, Quote current) {
        Map<String, Long> prices = new LinkedHashMap<>();
        prices.put(current.code(), current.priceCents());
        return TradingRules.equity(cash, positions, prices);
    }

    private static Position positionOf(List<Position> positions, String code) {
        return positions.stream().filter(p -> p.code().equals(code)).findFirst().orElse(null);
    }

    private static PlanItem copy(
            PlanItem item,
            ItemStatus status,
            int filledShares,
            Long filledPrice,
            Long orderId,
            String note) {
        return new PlanItem(
                item.id(),
                item.planId(),
                item.code(),
                item.name(),
                item.side(),
                item.triggerPriceCents(),
                item.shares(),
                item.stopLossCents(),
                item.takeProfitCents(),
                item.reason(),
                status,
                item.source(),
                filledShares,
                filledPrice,
                orderId,
                note == null ? "" : note);
    }

    private TickResult persist(TickResult result) {
        TickRecord last = repo.latestTick(result.tradeDate());
        boolean due =
                !result.executed().isEmpty()
                        || result.error() != null
                        || last == null
                        || since(last.checkedAt()) >= PERSIST_INTERVAL_MS;
        if (!due) {
            return result;
        }
        repo.saveTick(
                new TickRecord(
                        0,
                        result.tradeDate(),
                        result.checkedAt(),
                        toJson(result.quotes()),
                        toJson(result.executed()),
                        result.error()));
        return new TickResult(
                result.tradeDate(),
                result.checkedAt(),
                result.quotes(),
                result.executed(),
                result.error(),
                true);
    }

    private long since(String checkedAt) {
        try {
            LocalDateTime then = LocalDateTime.parse(checkedAt, STAMP);
            return Math.max(
                    0,
                    Duration.between(then, LocalDateTime.now(clock)).toMillis());
        } catch (RuntimeException error) {
            return PERSIST_INTERVAL_MS;
        }
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return "[]";
        }
    }

    public record TickResult(
            String tradeDate,
            String checkedAt,
            List<Quote> quotes,
            List<Execution> executed,
            String error,
            boolean persisted) {}

    public record Execution(String code, Side side, int shares, long priceCents, String note) {}
}
