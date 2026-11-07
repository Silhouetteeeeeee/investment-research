package com.investmentresearch.core.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.EngineFailure;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.agent.ToolEvent;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.TraceStep;
import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.ItemSource;
import com.investmentresearch.core.domain.trade.ItemStatus;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Quote;
import com.investmentresearch.core.domain.trade.Side;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.run.RunRecord;
import com.investmentresearch.core.tool.TradingToolTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TradingServiceTest {
    private static final String DATE = "2026-11-07";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant MORNING = Instant.parse("2026-11-07T01:30:00Z"); // 09:30

    private TradingToolTestSupport.InMemoryRepo repo;
    private TradingContext context;
    private QuoteCache cache;
    private ScriptedEngine engine;
    private TradingService service;

    @BeforeEach
    void setUp() {
        repo = new TradingToolTestSupport.InMemoryRepo();
        context = new TradingContext();
        cache = new QuoteCache();
        engine = new ScriptedEngine();
        service = new TradingService(repo, engine, context, cache, new FixedClock(MORNING));
    }

    @Test
    void successful_phase_commits_staged_plan_and_persists_payload() {
        PlanDraft draft = new PlanDraft(
                "今日只做防守",
                List.of(new PlanDraft.Item("600000", "浦发银行", Side.BUY, 1_000L, 100, 900L,
                        1_200L, "好公司好价格")));
        engine.behavior =
                question -> {
                    assertThat(question).contains(DATE).contains("盘前").contains("账户");
                    context.stage(draft);
                    return answer("计划已写入", "run-1");
                };

        service.runPhase(DATE, TradingPhase.PRE_MARKET, listener());

        TradePlan plan = repo.activePlan(DATE);
        assertThat(plan).isNotNull();
        assertThat(plan.version()).isEqualTo(1);
        assertThat(plan.status()).isEqualTo("ACTIVE");
        assertThat(plan.phase()).isEqualTo(TradingPhase.PRE_MARKET);
        assertThat(plan.notes()).isEqualTo("今日只做防守");
        assertThat(plan.items()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo("600000");
            assertThat(item.side()).isEqualTo(Side.BUY);
            assertThat(item.source()).isEqualTo(ItemSource.AGENT);
            assertThat(item.status()).isEqualTo(ItemStatus.PENDING);
        });
        assertThat(engine.deletedSessions).contains(TradingService.sessionId(DATE));

        PhaseRecord phase = repo.phase(DATE, TradingPhase.PRE_MARKET);
        assertThat(phase.status()).isEqualTo("OK");
        assertThat(phase.runId()).isEqualTo("run-1");
        assertThat(phase.payloadJson())
                .contains("\"answer\":\"计划已写入\"")
                .contains("\"thinking\":\"先想\"");
    }

    @Test
    void failed_phase_does_not_commit_staged_plan() {
        engine.behavior =
                question -> {
                    context.stage(new PlanDraft("不该落库", List.of()));
                    throw new EngineFailure(
                            "模型超时",
                            new RunRecord("run-9", Map.of("thinking", "想到一半")),
                            new RuntimeException("boom"),
                            List.of(Map.of("tool", "screen_candidates")),
                            List.of(source()),
                            List.of(trace()));
                };

        service.runPhase(DATE, TradingPhase.PRE_MARKET, listener());

        assertThat(repo.activePlan(DATE)).isNull();
        assertThat(repo.plans(DATE)).isEmpty();
        PhaseRecord phase = repo.phase(DATE, TradingPhase.PRE_MARKET);
        assertThat(phase.status()).isEqualTo("ERROR");
        assertThat(phase.error()).isEqualTo("模型超时");
    }

    @Test
    void phase_without_staged_plan_is_error() {
        engine.behavior = question -> answer("我只聊天", "run-2");

        service.runPhase(DATE, TradingPhase.PRE_MARKET, listener());

        assertThat(repo.activePlan(DATE)).isNull();
        PhaseRecord preMarket = repo.phase(DATE, TradingPhase.PRE_MARKET);
        assertThat(preMarket.status()).isEqualTo("ERROR");
        assertThat(preMarket.error()).contains("交易计划");

        // 午盘不要求改计划，但必须写复盘（§7.6）
        service.runPhase(DATE, TradingPhase.MIDDAY, listener());
        PhaseRecord midday = repo.phase(DATE, TradingPhase.MIDDAY);
        assertThat(midday.status()).isEqualTo("ERROR");
        assertThat(midday.error()).contains("复盘");
    }

    @Test
    void midday_plan_supersedes_and_inherits_auto_sells() {
        long oldId = repo.insertPlan(
                new TradePlan(0, DATE, TradingPhase.PRE_MARKET, 1, "旧计划", "run-0", "ACTIVE",
                        List.of()));
        repo.insertPlanItem(new PlanItem(0, oldId, "600000", "浦发银行", Side.BUY, 1_000L, 100,
                900L, 1_200L, "早盘买入", ItemStatus.PENDING, ItemSource.AGENT, 0, null, null, ""));
        repo.insertPlanItem(new PlanItem(0, oldId, "600519", "贵州茅台", Side.SELL, null, 100,
                900L, 1_200L, "自动止损", ItemStatus.PENDING, ItemSource.AUTO, 0, null, null, ""));
        repo.insertPlanItem(new PlanItem(0, oldId, "600000", "浦发银行", Side.SELL, null, 100,
                900L, 1_200L, "自动止损", ItemStatus.PENDING, ItemSource.AUTO, 0, null, null, ""));

        engine.behavior =
                question -> {
                    context.stage(new PlanDraft("午盘维持", List.of(
                            new PlanDraft.Item("600000", "浦发银行", Side.SELL, null, 100, 900L,
                                    1_200L, "持仓复盘"))));
                    repo.saveJournal(new JournalEntry(0, DATE, TradingPhase.MIDDAY, "上午复盘",
                            "run-2"));
                    return answer("维持原判", "run-2");
                };

        service.runPhase(DATE, TradingPhase.MIDDAY, listener());

        TradePlan old = repo.plan(oldId);
        assertThat(old.status()).isEqualTo("SUPERSEDED");
        assertThat(old.items()).allSatisfy(item -> assertThat(item.status())
                .isEqualTo(ItemStatus.CANCELLED));

        TradePlan plan = repo.activePlan(DATE);
        assertThat(plan.version()).isEqualTo(2);
        assertThat(plan.phase()).isEqualTo(TradingPhase.MIDDAY);
        assertThat(plan.items())
                .extracting(item -> item.code() + "|" + item.side() + "|" + item.source())
                .containsExactlyInAnyOrder(
                        "600000|SELL|AGENT", "600519|SELL|AUTO");
        assertThat(repo.phase(DATE, TradingPhase.MIDDAY).status()).isEqualTo("OK");
    }

    @Test
    void midday_without_journal_does_not_supersede_the_morning_plan() {
        // 复盘没写成时整个阶段算失败；暂存的午盘计划不能顶掉早盘的唯一有效计划
        long oldId = repo.insertPlan(
                new TradePlan(0, DATE, TradingPhase.PRE_MARKET, 1, "旧计划", "run-0", "ACTIVE",
                        List.of()));
        engine.behavior =
                question -> {
                    context.stage(new PlanDraft("不该生效", List.of()));
                    return answer("忘了写复盘", "run-2");
                };

        service.runPhase(DATE, TradingPhase.MIDDAY, listener());

        assertThat(repo.phase(DATE, TradingPhase.MIDDAY).status()).isEqualTo("ERROR");
        TradePlan plan = repo.activePlan(DATE);
        assertThat(plan.id()).isEqualTo(oldId);
        assertThat(plan.status()).isEqualTo("ACTIVE");
        assertThat(repo.plans(DATE)).hasSize(1);
    }

    @Test
    void pnl_snapshot_written_before_close_journal() {
        repo.saveAccount(new TradeAccount(100_000_000L, 90_000_000L));
        repo.savePosition(new Position("600000", "浦发银行", 200, 1_000L));
        cache.update(List.of(quote("600000", 1_200L)));
        engine.behavior = question -> answer("没有写复盘", "run-3");

        service.runPhase(DATE, TradingPhase.CLOSE, listener());

        EquitySnapshot snapshot = repo.equity(DATE);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.equityCents()).isEqualTo(90_000_000L + 200 * 1_200L);
        assertThat(snapshot.dayPnlCents())
                .isEqualTo(snapshot.equityCents() - TradingRules.INITIAL_CAPITAL_CENTS);
        assertThat(repo.phase(DATE, TradingPhase.CLOSE).status()).isEqualTo("ERROR");

        engine.behavior =
                question -> {
                    repo.saveJournal(new JournalEntry(0, DATE, TradingPhase.CLOSE, "收盘复盘",
                            "run-4"));
                    return answer("今日复盘", "run-4");
                };
        service.runPhase(DATE, TradingPhase.CLOSE, listener());
        assertThat(repo.phase(DATE, TradingPhase.CLOSE).status()).isEqualTo("OK");
    }

    @Test
    void failure_payload_keeps_partial_raw_and_trace() {
        engine.behavior =
                question -> {
                    throw new EngineFailure(
                            "上游 429",
                            new RunRecord("run-7", Map.of("thinking", "先看账户")),
                            new RuntimeException("too many requests"),
                            List.of(Map.of("tool", "get_trading_state")),
                            List.of(source()),
                            List.of(trace()));
                };

        service.runPhase(DATE, TradingPhase.MIDDAY, listener());

        PhaseRecord phase = repo.phase(DATE, TradingPhase.MIDDAY);
        assertThat(phase.status()).isEqualTo("ERROR");
        assertThat(phase.payloadJson())
                .contains("get_trading_state")
                .contains("profile:600000")
                .contains("run-7")
                .contains("先看账户");
    }

    private static AskResult answer(String text, String runId) {
        return new AskResult(
                text,
                List.of(source()),
                List.of(trace()),
                List.of(),
                new RunRecord(runId, Map.of("thinking", "先想")),
                List.of(Map.of("tool", "get_trading_state")));
    }

    private static Source source() {
        return new Source("profile:600000", "公司档案", "贵州茅台", DATE, "10:00:00");
    }

    private static TraceStep trace() {
        return new TraceStep(1, "get_trading_state", Map.of(), true, "MARKET", DATE, null,
                List.of(), false, 12L);
    }

    private static Quote quote(String code, long priceCents) {
        return new Quote(code, "浦发银行", priceCents, 980L, 990L, 1_020L, 970L, DATE, "10:00:00", 0L);
    }

    private static StreamListener listener() {
        return new StreamListener() {
            @Override
            public void text(String delta) {}

            @Override
            public void discard() {}

            @Override
            public void tool(ToolEvent event) {}
        };
    }

    private static final class ScriptedEngine implements AssistantEngine {
        private final List<String> prompts = new ArrayList<>();
        private final List<Long> deletedSessions = new ArrayList<>();
        private Function<String, AskResult> behavior = question -> answer("空", "run-0");

        @Override
        public AskResult ask(
                long conversationId, String code, String name, String question,
                StreamListener listener) {
            prompts.add(question);
            return behavior.apply(question);
        }

        @Override
        public void deleteSession(long conversationId) {
            deletedSessions.add(conversationId);
        }
    }

    private static final class FixedClock extends Clock {
        private final Instant instant;

        private FixedClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            // 测试里只需要固定时间；保留方法与 TradingExecutorTest 一致
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
}
