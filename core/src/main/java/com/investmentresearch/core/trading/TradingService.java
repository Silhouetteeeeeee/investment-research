package com.investmentresearch.core.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.EngineFailure;
import com.investmentresearch.core.agent.StreamListener;
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
import com.investmentresearch.core.port.TradingRepository;
import com.investmentresearch.core.run.RunRecord;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个交易阶段的唯一编排入口：设置上下文 → 组装提示词 → 跑模型 → 提交暂存计划 → 落阶段记录。
 * 模型失败或后置条件不满足时只记 ERROR，绝不落半成品计划。
 */
public final class TradingService {
    public static final String MARKET_CODE = "MARKET";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TradingRepository repo;
    private final AssistantEngine engine;
    private final TradingContext context;
    private final QuoteCache quotes;
    private final Clock clock;
    private final TradingExperience experience;

    public TradingService(
            TradingRepository repo,
            AssistantEngine engine,
            TradingContext context,
            QuoteCache quotes,
            Clock clock,
            TradingExperience experience) {
        this.repo = repo;
        this.engine = engine;
        this.context = context;
        this.quotes = quotes;
        this.clock = clock;
        this.experience = experience;
    }

    /** 负数会话 id：-(yyyyMMdd)，避免和聊天会话撞号。 */
    public static long sessionId(String tradeDate) {
        return -Long.parseLong(tradeDate.replace("-", ""));
    }

    public void runPhase(String tradeDate, TradingPhase phase, StreamListener listener) {
        context.begin(tradeDate, phase);
        String startedAt = now();
        repo.savePhase(
                new PhaseRecord(0, tradeDate, phase, "RUNNING", startedAt, null, null, null, null));
        if (phase == TradingPhase.PRE_MARKET) {
            // 每天盘前从干净的会话开始，不继承昨天的上下文
            engine.deleteSession(sessionId(tradeDate));
        }
        if (phase == TradingPhase.CLOSE) {
            writeEquity(tradeDate);
        }

        AskResult answer = null;
        try {
            String prompt = PhasePrompt.userMessage(repo, phase, tradeDate, lessons());
            answer = engine.ask(sessionId(tradeDate), MARKET_CODE, "大盘", prompt, listener);

            PlanDraft staged = context.stagedPlan();
            if (phase == TradingPhase.PRE_MARKET && staged == null) {
                throw new IllegalStateException("盘前阶段没有提交交易计划，今天不能开盘");
            }
            // 后置条件先检查再落库：阶段失败不能留下半成品，午盘失败也不能顶掉早盘计划
            if (phase != TradingPhase.PRE_MARKET && !journalWritten(tradeDate, phase)) {
                throw new IllegalStateException(
                        PhasePrompt.label(phase) + "阶段没有写复盘");
            }
            if (staged != null) {
                commitPlan(tradeDate, phase, staged, answer.run());
            }
            savePhase(tradeDate, phase, "OK", startedAt, answer.run(), payload(answer), null);
        } catch (EngineFailure failure) {
            savePhase(
                    tradeDate,
                    phase,
                    "ERROR",
                    startedAt,
                    failure.run(),
                    failurePayload(failure),
                    failure.getMessage());
        } catch (RuntimeException error) {
            savePhase(
                    tradeDate,
                    phase,
                    "ERROR",
                    startedAt,
                    answer == null ? null : answer.run(),
                    answer == null ? failurePayload(null) : payload(answer),
                    error.getMessage());
        } finally {
            context.clear();
        }
    }

    /** §7.5：成功后在一个事务里替换旧计划，并继承未触发的 auto 止损止盈。 */
    private void commitPlan(
            String tradeDate, TradingPhase phase, PlanDraft draft, RunRecord run) {
        repo.transaction(
                () -> {
                    TradePlan old = repo.activePlan(tradeDate);
                    int version = 1;
                    for (TradePlan plan : repo.plans(tradeDate)) {
                        version = Math.max(version, plan.version() + 1);
                    }
                    if (old != null) {
                        repo.updatePlanStatus(old.id(), "SUPERSEDED");
                    }
                    long planId =
                            repo.insertPlan(
                                    new TradePlan(
                                            0,
                                            tradeDate,
                                            phase,
                                            version,
                                            draft.notes(),
                                            run == null ? null : run.runId(),
                                            "ACTIVE",
                                            List.of()));
                    Set<String> sellCodes = new HashSet<>();
                    for (PlanDraft.Item item : draft.items()) {
                        if (item.side() == Side.SELL) {
                            sellCodes.add(item.code());
                        }
                        repo.insertPlanItem(
                                new PlanItem(
                                        0,
                                        planId,
                                        item.code(),
                                        item.name(),
                                        item.side(),
                                        item.triggerPriceCents(),
                                        item.shares(),
                                        item.stopLossCents(),
                                        item.takeProfitCents(),
                                        item.reason(),
                                        ItemStatus.PENDING,
                                        ItemSource.AGENT,
                                        0,
                                        null,
                                        null,
                                        ""));
                    }
                    if (old != null) {
                        repo.cancelPendingAgentItems(old.id());
                        for (PlanItem auto : repo.pendingAutoSellItems(old.id())) {
                            if (sellCodes.add(auto.code()) && auto.side() == Side.SELL) {
                                repo.insertPlanItem(
                                        new PlanItem(
                                                0,
                                                planId,
                                                auto.code(),
                                                auto.name(),
                                                auto.side(),
                                                auto.triggerPriceCents(),
                                                auto.shares(),
                                                auto.stopLossCents(),
                                                auto.takeProfitCents(),
                                                auto.reason(),
                                                ItemStatus.PENDING,
                                                ItemSource.AUTO,
                                                0,
                                                null,
                                                null,
                                                ""));
                            }
                            repo.updatePlanItem(copy(auto, ItemStatus.CANCELLED));
                        }
                    }
                    return true;
                });
    }

    /** 经验文件读不出来时按「暂无经验」继续，不能让一份文档的 IO 失败挡住当天交易。 */
    private String lessons() {
        try {
            return experience.read();
        } catch (RuntimeException error) {
            return "";
        }
    }

    /** 收盘复盘前写权益快照；价格优先用最近一次 tick 的报价，缺价按成本估值。 */
    private void writeEquity(String tradeDate) {
        List<Position> positions = repo.positions();
        Map<String, Long> prices = new LinkedHashMap<>();
        long marketValue = 0;
        for (Position position : positions) {
            Quote quote = quotes.byCode(position.code());
            long price =
                    quote != null && quote.priceCents() > 0
                            ? quote.priceCents()
                            : position.avgCostCents();
            prices.put(position.code(), price);
            marketValue += price * position.shares();
        }
        TradeAccount account = repo.account();
        long equity = account.cashCents() + marketValue;
        repo.saveEquity(
                new EquitySnapshot(
                        tradeDate,
                        account.cashCents(),
                        marketValue,
                        equity,
                        equity - previousEquity(tradeDate),
                        prices));
    }

    private long previousEquity(String tradeDate) {
        long baseline = TradingRules.INITIAL_CAPITAL_CENTS;
        for (EquitySnapshot snapshot : repo.equitySeries()) {
            if (snapshot.tradeDate().compareTo(tradeDate) < 0) {
                baseline = snapshot.equityCents();
            }
        }
        return baseline;
    }

    private boolean journalWritten(String tradeDate, TradingPhase phase) {
        for (JournalEntry journal : repo.journals(tradeDate)) {
            if (journal.phase() == phase) {
                return true;
            }
        }
        return false;
    }

    private void savePhase(
            String tradeDate,
            TradingPhase phase,
            String status,
            String startedAt,
            RunRecord run,
            String payloadJson,
            String error) {
        repo.savePhase(
                new PhaseRecord(
                        0,
                        tradeDate,
                        phase,
                        status,
                        startedAt,
                        now(),
                        run == null ? null : run.runId(),
                        payloadJson,
                        error));
    }

    private static String payload(AskResult answer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", answer.text());
        payload.put("sources", answer.sources());
        payload.put("trace", answer.trace());
        payload.put("issues", answer.issues());
        payload.put("raw", answer.raw());
        payload.put("run", answer.run());
        payload.put("thinking", thinking(answer.run()));
        return toJson(payload);
    }

    private static String failurePayload(EngineFailure failure) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", "");
        payload.put("sources", failure == null ? List.of() : failure.sources());
        payload.put("trace", failure == null ? List.of() : failure.trace());
        payload.put("issues", List.of());
        payload.put("raw", failure == null ? List.of() : failure.raw());
        payload.put("run", failure == null ? null : failure.run());
        payload.put("thinking", failure == null ? "" : thinking(failure.run()));
        return toJson(payload);
    }

    private static Object thinking(RunRecord run) {
        if (run == null || run.payload() == null) {
            return "";
        }
        return run.payload().getOrDefault("thinking", "");
    }

    private static PlanItem copy(PlanItem item, ItemStatus status) {
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
                item.filledShares(),
                item.filledPriceCents(),
                item.orderId(),
                item.note());
    }

    private String now() {
        return STAMP.format(LocalDateTime.now(clock));
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }
}
