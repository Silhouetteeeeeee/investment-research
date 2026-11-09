package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.domain.trade.ItemStatus;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.domain.trade.Side;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.QuotePort;
import com.investmentresearch.core.port.TradingRepository;
import com.investmentresearch.core.trading.PlanDraft;
import com.investmentresearch.core.trading.TradingContext;
import com.investmentresearch.core.trading.TradingRules;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 计划的可信边界：模型给的价格、数量、止损止盈都在这里对照真实账户和实时报价校验，
 * 通过后只暂存在上下文里，等 Agent 整体成功才由 TradingService 落库。
 */
public final class SaveTradePlanTool implements ResearchTool {
    /** 买入价允许偏离参考价的幅度；超出说明模型引用的是过期行情。 */
    private static final double MAX_PRICE_DRIFT = 0.21;

    private final TradingContext context;
    private final TradingRepository repo;
    private final CompanyDirectory directory;
    private final QuotePort quotes;
    private final ToolSchemas schemas;

    public SaveTradePlanTool(
            TradingContext context,
            TradingRepository repo,
            CompanyDirectory directory,
            QuotePort quotes,
            ToolSchemas schemas) {
        this.context = context;
        this.repo = repo;
        this.directory = directory;
        this.quotes = quotes;
        this.schemas = schemas;
    }

    @Override
    public ToolSchema schema() {
        return schemas.get("save_trade_plan");
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
        try {
            PlanDraft draft = validate(arguments);
            context.stage(draft);
            return ToolResult.ok(
                    null,
                    null,
                    List.of(),
                    Map.of(
                            "accepted", true,
                            "items", draft.items().size(),
                            "notes", draft.notes()));
        } catch (BadPlan error) {
            return ToolResult.failure(null, error.getMessage());
        } catch (RuntimeException error) {
            return ToolResult.failure(null, "计划校验失败：" + error.getMessage());
        }
    }

    private PlanDraft validate(Map<String, Object> args) {
        String tradeDate = context.tradeDate();
        if (tradeDate == null) {
            throw new BadPlan("当前不在交易阶段，不能保存计划");
        }
        if (!tradeDate.equals(text(args.get("trade_date")))) {
            throw new BadPlan("trade_date 与当前交易日不一致");
        }
        TradingPhase phase = context.phase();
        String phaseText = text(args.get("phase"));
        if (phase == null || phaseText == null || !phaseText.equalsIgnoreCase(phase.dbValue())) {
            throw new BadPlan("phase 与当前阶段不一致");
        }
        if (phase == TradingPhase.CLOSE) {
            throw new BadPlan("收盘阶段只做复盘，不产生新计划");
        }
        for (TradePlan existing : repo.plans(tradeDate)) {
            if (existing.phase() == phase) {
                throw new BadPlan("该阶段今天已经保存过计划，不再重复保存");
            }
        }

        List<PlanDraft.Item> items = parseItems(args.get("items"));
        Set<String> seen = new HashSet<>();
        for (PlanDraft.Item item : items) {
            if (!seen.add(item.code() + "|" + item.side())) {
                throw new BadPlan(item.code() + " 的同一方向在计划里出现了两次");
            }
        }

        List<Position> positions = repo.positions();
        Map<String, Quote> quotesByCode = fetchQuotes(items, positions);
        checkBuyPrices(items, quotesByCode);
        checkBuyCapacity(items, positions, quotesByCode);
        checkMiddayCodes(phase, tradeDate, items);
        checkHoldingsCovered(items, positions);

        String notes = text(args.get("notes"));
        return new PlanDraft(notes == null ? "" : notes, items);
    }

    private List<PlanDraft.Item> parseItems(Object raw) {
        List<PlanDraft.Item> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        if (!(raw instanceof List<?> list)) {
            throw new BadPlan("items 必须是数组");
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new BadPlan("items 的每一项都必须是对象");
            }
            items.add(parseItem(map));
        }
        return items;
    }

    private PlanDraft.Item parseItem(Map<?, ?> map) {
        String code = text(map.get("code"));
        if (code == null || !code.matches("\\d{6}")) {
            throw new BadPlan("股票代码必须是 6 位数字");
        }
        StockRef ref =
                directory
                        .byCode(code)
                        .orElseThrow(() -> new BadPlan("未知股票代码：" + code));
        String sideText = text(map.get("side"));
        Side side = sideOf(sideText);
        if (side == null) {
            throw new BadPlan(code + " 的 side 只能是 BUY 或 SELL");
        }
        int shares = integer(map.get("shares"), code + " 的 shares");
        if (shares <= 0 || !TradingRules.isLot(shares)) {
            throw new BadPlan(code + " 的 shares 必须是 100 的整数倍");
        }
        Long trigger = cents(map.get("trigger_price"), code + " 的 trigger_price");
        Long stop = cents(map.get("stop_loss"), code + " 的 stop_loss");
        Long take = cents(map.get("take_profit"), code + " 的 take_profit");
        String reason = text(map.get("reason"));
        if (reason == null || reason.isEmpty()) {
            throw new BadPlan(code + " 没有写 reason，计划不接受无理由的交易");
        }
        if (side == Side.BUY) {
            if (trigger == null) {
                throw new BadPlan(code + " 的买入计划必须给 trigger_price");
            }
            if (stop == null || take == null) {
                throw new BadPlan(code + " 的买入计划必须同时给 stop_loss 和 take_profit");
            }
            if (stop >= trigger || take <= trigger) {
                throw new BadPlan(code + " 必须满足 stop_loss < trigger_price < take_profit");
            }
        }
        String name = text(map.get("name"));
        return new PlanDraft.Item(
                code,
                name == null || name.isEmpty() ? ref.name() : name,
                side,
                trigger,
                shares,
                stop,
                take,
                reason);
    }

    private Map<String, Quote> fetchQuotes(List<PlanDraft.Item> items, List<Position> positions) {
        Set<String> codes = new LinkedHashSet<>();
        for (PlanDraft.Item item : items) {
            codes.add(item.code());
        }
        for (Position position : positions) {
            codes.add(position.code());
        }
        if (codes.isEmpty()) {
            return Map.of();
        }
        List<Quote> rows;
        try {
            rows = quotes.quotes(List.copyOf(codes));
        } catch (RuntimeException error) {
            throw new BadPlan("实时报价取不到，无法校验计划：" + error.getMessage());
        }
        Map<String, Quote> byCode = new LinkedHashMap<>();
        for (Quote quote : rows) {
            byCode.put(quote.code(), quote);
        }
        return byCode;
    }

    private void checkBuyPrices(List<PlanDraft.Item> items, Map<String, Quote> quotesByCode) {
        for (PlanDraft.Item item : items) {
            if (item.side() != Side.BUY) {
                continue;
            }
            long reference = referencePrice(quotesByCode.get(item.code()));
            if (reference <= 0) {
                throw new BadPlan(item.code() + " 没有有效参考价（停牌或行情缺失），不能挂买入计划");
            }
            long drift = Math.abs(item.triggerPriceCents() - reference);
            if (drift > reference * MAX_PRICE_DRIFT) {
                throw new BadPlan(
                        item.code() + " 的买入价偏离参考价超过 21%，模型可能用了过期行情");
            }
        }
    }

    private void checkBuyCapacity(
            List<PlanDraft.Item> items, List<Position> positions, Map<String, Quote> quotesByCode) {
        long cash = repo.account().cashCents();
        long cost = 0;
        for (PlanDraft.Item item : items) {
            if (item.side() == Side.BUY) {
                long amount = item.triggerPriceCents() * item.shares();
                cost += amount + TradingRules.commission(amount);
            }
        }
        if (cost > cash) {
            throw new BadPlan("计划买入需要 " + cost + " 分，可用现金只有 " + cash + " 分");
        }

        Set<String> after = new LinkedHashSet<>();
        for (Position position : positions) {
            after.add(position.code());
        }
        for (PlanDraft.Item item : items) {
            if (item.side() == Side.BUY) {
                after.add(item.code());
            }
        }
        if (after.size() > TradingRules.MAX_POSITIONS) {
            throw new BadPlan("执行后会有 " + after.size() + " 只持仓，超过 " + TradingRules.MAX_POSITIONS + " 只上限");
        }

        Map<String, Long> prices = new LinkedHashMap<>();
        for (Quote quote : quotesByCode.values()) {
            long price = referencePrice(quote);
            if (price > 0) {
                prices.put(quote.code(), price);
            }
        }
        long equity = TradingRules.equity(cash, positions, prices);
        for (PlanDraft.Item item : items) {
            if (item.side() != Side.BUY) {
                continue;
            }
            long sharesAfter = item.shares();
            for (Position position : positions) {
                if (position.code().equals(item.code())) {
                    sharesAfter += position.shares();
                }
            }
            if (sharesAfter * item.triggerPriceCents() > TradingRules.MAX_SINGLE_POSITION * equity) {
                throw new BadPlan(item.code() + " 的计划持仓超过单票 20% 上限");
            }
        }
    }

    private void checkMiddayCodes(
            TradingPhase phase, String tradeDate, List<PlanDraft.Item> items) {
        if (phase != TradingPhase.MIDDAY) {
            return;
        }
        Set<String> allowed = new HashSet<>();
        TradePlan active = repo.activePlan(tradeDate);
        if (active != null) {
            for (PlanItem planItem : active.items()) {
                if (planItem.side() == Side.BUY && planItem.status() == ItemStatus.PENDING) {
                    allowed.add(planItem.code());
                }
            }
        }
        for (PlanDraft.Item item : items) {
            if (item.side() == Side.BUY && !allowed.contains(item.code())) {
                throw new BadPlan(
                        item.code() + " 不在早盘计划的待买清单里，midday 不能临时新增买入标的");
            }
        }
    }

    private void checkHoldingsCovered(List<PlanDraft.Item> items, List<Position> positions) {
        Set<String> sellCodes = new HashSet<>();
        for (PlanDraft.Item item : items) {
            if (item.side() == Side.SELL) {
                sellCodes.add(item.code());
            }
        }
        for (Position position : positions) {
            if (!sellCodes.contains(position.code())) {
                throw new BadPlan("持仓 " + position.code() + " 必须在计划里有卖出项，标明止盈止损或继续持有");
            }
        }
    }

    private static long referencePrice(Quote quote) {
        if (quote == null) {
            return 0;
        }
        return quote.priceCents() > 0 ? quote.priceCents() : quote.prevCloseCents();
    }

    private static Side sideOf(String text) {
        if (text == null) {
            return null;
        }
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "BUY" -> Side.BUY;
            case "SELL" -> Side.SELL;
            default -> null;
        };
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** 价格以分为单位；缺省为 null，给了就必须为正。 */
    private static Long cents(Object value, String field) {
        if (value == null) {
            return null;
        }
        long cents;
        try {
            cents =
                    value instanceof Number number
                            ? number.longValue()
                            : Long.parseLong(value.toString().trim());
        } catch (NumberFormatException error) {
            throw new BadPlan(field + " 不是有效整数");
        }
        if (cents <= 0) {
            throw new BadPlan(field + " 必须大于 0");
        }
        return cents;
    }

    private static int integer(Object value, String field) {
        if (value == null) {
            throw new BadPlan(field + " 缺失");
        }
        try {
            return value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException error) {
            throw new BadPlan(field + " 不是有效整数");
        }
    }

    /** 校验失败；消息会原样回给模型，让它自己纠正。 */
    private static final class BadPlan extends RuntimeException {
        private BadPlan(String message) {
            super(message);
        }
    }
}
