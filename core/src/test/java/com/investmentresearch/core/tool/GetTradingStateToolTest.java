package com.investmentresearch.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.ItemSource;
import com.investmentresearch.core.domain.trade.ItemStatus;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.domain.trade.Side;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradeOrder;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.QuotePort;
import com.investmentresearch.core.trading.TradingContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetTradingStateToolTest {
    private static final String DATE = "2026-11-07";

    private TradingToolTestSupport.InMemoryRepo repo;
    private TradingContext context;

    @BeforeEach
    void setUp() {
        repo = new TradingToolTestSupport.InMemoryRepo();
        context = new TradingContext();
    }

    @Test
    void state_shape_includes_account_positions_plan_orders_and_journals() {
        repo.saveAccount(new TradeAccount(100_000_000L, 90_000_000L));
        repo.savePosition(new Position("600000", "浦发银行", 200, 1_000L));
        repo.insertOrder(
                new TradeOrder(0, DATE, null, "600000", "浦发银行", Side.BUY, 1_000L, 100,
                        100_000L, 500L, 0L, null, "建仓", "10:00:00"));
        repo.saveJournal(new JournalEntry(0, DATE, TradingPhase.MIDDAY, "上午复盘", "run-1"));
        long planId =
                repo.insertPlan(
                        new TradePlan(0, DATE, TradingPhase.PRE_MARKET, 1, "计划说明", "run-1",
                                "ACTIVE", List.of()));
        repo.insertPlanItem(
                new PlanItem(0, planId, "600000", "浦发银行", Side.BUY, 1_000L, 100, 900L,
                        1_200L, "好公司好价格", ItemStatus.PENDING, ItemSource.AGENT, 0, null,
                        null, ""));

        ToolResult result =
                tool(codes -> List.of(quote("600000", 1_200L))).execute(Map.of("date", DATE), null);

        assertThat(result.ok()).isTrue();
        Map<String, Object> data = result.data();
        assertThat(data).containsKeys(
                "date", "account", "positions", "plan", "orders", "journals", "rules");
        assertThat(data.get("date")).isEqualTo(DATE);
        assertThat(map(data.get("account")))
                .containsEntry("cash", 90_000_000L)
                .containsEntry("cash_yuan", "900000.00")
                .containsEntry("equity", 90_240_000L)
                .containsEntry("equity_yuan", "902400.00");

        List<Map<String, Object>> positions = maps(data.get("positions"));
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0))
                .containsEntry("code", "600000")
                .containsEntry("shares", 200)
                .containsEntry("available", 100) // T+1：今日买入 100 股不可卖
                .containsEntry("price", 1_200L)
                .containsEntry("price_yuan", "12.00")
                .containsEntry("pnl", 40_000L)
                .containsEntry("pnl_yuan", "400.00");

        Map<String, Object> plan = map(data.get("plan"));
        assertThat(plan).containsEntry("phase", "pre_market").containsEntry("status", "ACTIVE");
        assertThat(maps(plan.get("items"))).hasSize(1);

        assertThat(maps(data.get("orders"))).hasSize(1);
        assertThat(maps(data.get("journals")))
                .singleElement()
                .satisfies(journal -> assertThat(journal).containsEntry("text", "上午复盘"));
        assertThat(map(data.get("rules")))
                .containsEntry("lot", 100)
                .containsEntry("t_plus_one", true);
    }

    @Test
    void missing_quote_falls_back_to_cost_and_does_not_throw() {
        repo.savePosition(new Position("600000", "浦发银行", 100, 1_000L));

        ToolResult result =
                tool(
                                codes -> {
                                    throw new IllegalStateException("quote down");
                                })
                        .execute(Map.of("date", DATE), null);

        assertThat(result.ok()).isTrue();
        assertThat(maps(result.data().get("positions")).get(0))
                .containsEntry("price", 1_000L)
                .containsEntry("pnl", 0L);
        assertThat(map(result.data().get("account"))).containsEntry("equity", 100_100_000L);
    }

    @Test
    void day_pnl_baselines_on_the_previous_day_even_after_todays_snapshot_exists() {
        // 收盘阶段在模型复盘前就写了当日权益快照；日盈亏必须对比前一交易日，不能对比当天
        repo.saveAccount(new TradeAccount(100_000_000L, 90_000_000L));
        repo.savePosition(new Position("600000", "浦发银行", 200, 1_000L));
        repo.saveEquity(snapshot("2026-11-06", 89_000_000L));
        repo.saveEquity(snapshot(DATE, 90_240_000L));

        ToolResult result =
                tool(codes -> List.of(quote("600000", 1_200L))).execute(Map.of("date", DATE), null);

        assertThat(map(result.data().get("account")))
                .containsEntry("day_pnl", 1_240_000L)
                .containsEntry("day_pnl_yuan", "12400.00");
    }

    @Test
    void defaults_to_the_context_trade_date() {
        context.begin(DATE, TradingPhase.MIDDAY);

        ToolResult result = tool(codes -> List.of()).execute(Map.of(), null);

        assertThat(result.data().get("date")).isEqualTo(DATE);
        assertThat(result.data().get("plan")).isNull();
    }

    private GetTradingStateTool tool(QuotePort quotes) {
        return new GetTradingStateTool(repo, quotes, context, new ToolSchemas(Map.of()));
    }

    private static EquitySnapshot snapshot(String date, long equityCents) {
        return new EquitySnapshot(date, equityCents, 0L, equityCents, 0L, Map.of());
    }

    private static Quote quote(String code, long priceCents) {
        return new Quote(code, "浦发银行", priceCents, 980L, 990L, 1_020L, 970L, DATE, "10:00:00", 0L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
