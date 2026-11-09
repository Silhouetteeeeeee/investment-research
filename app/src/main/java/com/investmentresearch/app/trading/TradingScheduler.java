package com.investmentresearch.app.trading;

import com.investmentresearch.app.config.TradingProperties;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.agent.ToolEvent;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.TradingRepository;
import com.investmentresearch.core.trading.MarketSession;
import com.investmentresearch.core.trading.TradingDayGate;
import com.investmentresearch.core.trading.TradingExecutor;
import com.investmentresearch.core.trading.TradingService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 交易 Agent 的唯一驱动：交易日门禁 → 盘前/午盘/收盘阶段串行执行 → 盘中按计划轮询成交。
 * 非交易日不写任何交易表；阶段失败只落 ERROR，不进下一阶段。
 */
public final class TradingScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(TradingScheduler.class);
    private static final LocalTime HOLIDAY_CUTOFF = LocalTime.of(9, 45);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    private final TradingService service;
    private final TradingExecutor executor;
    private final TradingDayGate gate;
    private final TradingRepository repo;
    private final TradingBroadcaster broadcaster;
    private final TradingProperties props;
    private final Clock clock;

    private final AtomicBoolean phaseRunning = new AtomicBoolean();
    private final AtomicReference<String> premarketRetriedDate = new AtomicReference<>();
    private final ExecutorService phaseExecutor =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "trading-phase");
                        thread.setDaemon(true);
                        return thread;
                    });

    // 连续报价失败 3 次后退避到 60 秒，恢复后回到正常间隔
    private int consecutiveQuoteErrors;
    private volatile long nextTickAtMillis;

    public TradingScheduler(
            TradingService service,
            TradingExecutor executor,
            TradingDayGate gate,
            TradingRepository repo,
            TradingBroadcaster broadcaster,
            TradingProperties props,
            Clock clock) {
        this.service = service;
        this.executor = executor;
        this.gate = gate;
        this.repo = repo;
        this.broadcaster = broadcaster;
        this.props = props;
        this.clock = clock;
    }

    /** 09:25–09:45 探针窗口：确认交易日才跑盘前；09:45 仍确认不了就判休市。 */
    @Scheduled(cron = "0 25,30,35,40,45 9 * * MON-FRI", zone = "Asia/Shanghai")
    public void preMarketWindow() {
        if (!props.enabled()) {
            return;
        }
        LocalDate today = gate.today();
        TradingDayGate.Status status = gate.status(today);
        if (status == TradingDayGate.Status.UNCONFIRMED) {
            // status() 会缓存未确认结果；每个窗口必须重新探测，一次抖动不能把整天判成休市
            TradingDayGate.Status probed = gate.probe(today);
            if (probed != null) {
                status = probed;
            }
        }
        // 探针只在 09:25–09:45 跑，留一行日志才能确认它真的在跑
        LOG.info("交易日探测 {}：{}", today, status);
        if (status == TradingDayGate.Status.HOLIDAY) {
            return;
        }
        LocalTime now = now();
        if (status == TradingDayGate.Status.UNCONFIRMED) {
            if (!now.isBefore(HOLIDAY_CUTOFF)) {
                gate.markHoliday(today);
                LOG.info("今日 {} 未能确认是交易日，按休市处理", today);
            }
            return;
        }
        String date = today.toString();
        PhaseRecord existing = repo.phase(date, TradingPhase.PRE_MARKET);
        if (existing == null) {
            runPhase(date, TradingPhase.PRE_MARKET);
            return;
        }
        if ("OK".equals(existing.status()) || "RUNNING".equals(existing.status())) {
            return;
        }
        // 盘前失败每天只在 09:45 重试一次；进程跨天运行时标志要跟着日期走
        if (!now.isBefore(HOLIDAY_CUTOFF) && !date.equals(premarketRetriedDate.get())) {
            premarketRetriedDate.set(date);
            runPhase(date, TradingPhase.PRE_MARKET);
        }
    }

    @Scheduled(cron = "0 35 11 * * MON-FRI", zone = "Asia/Shanghai")
    public void midday() {
        if (!tradingDay()) {
            return;
        }
        runPhase(date(), TradingPhase.MIDDAY);
    }

    /** 收盘先过期未成交项，再让模型复盘（权益快照由 TradingService 在调用前写入）。 */
    @Scheduled(cron = "0 5 15 * * MON-FRI", zone = "Asia/Shanghai")
    public void close() {
        if (!tradingDay()) {
            return;
        }
        String date = date();
        repo.expirePendingItems(date);
        runPhase(date, TradingPhase.CLOSE);
    }

    @Scheduled(fixedDelayString = "#{${app.trading.poll-seconds:10} * 1000}")
    public void tick() {
        if (!props.enabled()) {
            return;
        }
        if (clock.millis() < nextTickAtMillis) {
            return;
        }
        if (!MarketSession.inContinuousTrading(now())) {
            return;
        }
        LocalDate today = gate.today();
        if (gate.status(today) != TradingDayGate.Status.TRADING) {
            return;
        }
        TradingExecutor.TickResult result;
        try {
            result = executor.tick(today.toString());
        } catch (RuntimeException error) {
            publish(error("盘中轮询失败：" + message(error)));
            return;
        }
        if (result == null) {
            return;
        }
        if (result.error() != null) {
            if (++consecutiveQuoteErrors >= 3) {
                nextTickAtMillis = clock.millis() + 60_000L;
            }
        } else {
            consecutiveQuoteErrors = 0;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "monitor");
        event.put("checked_at", result.checkedAt());
        event.put("quotes", result.quotes());
        event.put("executed", result.executed());
        event.put("error", result.error());
        publish(event);
    }

    /** 启动补跑：09:25–14:30 内、确认是交易日且当天还没有计划，才补一次盘前。 */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        repo.markRunningPhasesError("应用重启中断");
        if (!props.enabled()) {
            return;
        }
        LocalTime now = now();
        if (!MarketSession.inCatchUpWindow(now)) {
            return;
        }
        LocalDate today = gate.today();
        if (gate.status(today) != TradingDayGate.Status.TRADING) {
            return;
        }
        String date = today.toString();
        PhaseRecord phase = repo.phase(date, TradingPhase.PRE_MARKET);
        // 只有「已有计划」或「盘前已经跑成」才不补跑；失败留下的 ERROR 行不能挡住当天唯一的计划
        if (repo.activePlan(date) != null
                || (phase != null
                        && ("OK".equals(phase.status()) || "RUNNING".equals(phase.status())))) {
            return;
        }
        runPhase(date, TradingPhase.PRE_MARKET);
    }

    private boolean tradingDay() {
        return props.enabled() && gate.status(gate.today()) == TradingDayGate.Status.TRADING;
    }

    /** 阶段串行：同一时刻只允许一个阶段，超时取消并记 ERROR。 */
    private void runPhase(String date, TradingPhase phase) {
        if (!phaseRunning.compareAndSet(false, true)) {
            LOG.info("{} {} 阶段正在运行，跳过本次触发", date, phase.dbValue());
            return;
        }
        Future<?> future =
                phaseExecutor.submit(
                        () -> {
                            publish(phaseEvent("started", date, phase, "RUNNING"));
                            try {
                                service.runPhase(date, phase, listener());
                            } catch (RuntimeException error) {
                                LOG.warn("{} {} 阶段异常退出", date, phase.dbValue(), error);
                            } finally {
                                PhaseRecord done = repo.phase(date, phase);
                                publish(
                                        phaseEvent(
                                                "finished",
                                                date,
                                                phase,
                                                done == null ? "UNKNOWN" : done.status()));
                            }
                            return null;
                        });
        try {
            future.get(props.phaseTimeoutMinutes(), TimeUnit.MINUTES);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            savePhaseError(date, phase, "阶段超时");
        } catch (ExecutionException error) {
            savePhaseError(date, phase, message(error.getCause()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            savePhaseError(date, phase, "阶段被中断");
        } finally {
            phaseRunning.set(false);
        }
    }

    private void savePhaseError(String date, TradingPhase phase, String error) {
        PhaseRecord existing = repo.phase(date, phase);
        repo.savePhase(
                new PhaseRecord(
                        0,
                        date,
                        phase,
                        "ERROR",
                        existing == null ? timestamp() : existing.startedAt(),
                        timestamp(),
                        existing == null ? null : existing.runId(),
                        existing == null ? null : existing.payloadJson(),
                        error));
        publish(phaseEvent("finished", date, phase, "ERROR"));
    }

    private StreamListener listener() {
        return new StreamListener() {
            @Override
            public void text(String delta) {
                publish(delta("text", delta));
            }

            @Override
            public void discard() {
                // 丢弃的内容不入页面
            }

            @Override
            public void tool(ToolEvent event) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", "tool");
                map.put("name", event.name());
                map.put("ok", event.ok());
                map.put("cached", event.cached());
                map.put("duration_ms", event.durationMs());
                publish(map);
            }

            @Override
            public void thinking(String delta) {
                publish(delta("thinking", delta));
            }
        };
    }

    private Map<String, Object> phaseEvent(
            String event, String date, TradingPhase phase, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "phase");
        map.put("event", event);
        map.put("date", date);
        map.put("phase", phase.dbValue());
        map.put("status", status);
        return map;
    }

    private static Map<String, Object> delta(String type, String delta) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("delta", delta == null ? "" : delta);
        return map;
    }

    private static Map<String, Object> error(String text) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "error");
        map.put("text", text);
        return map;
    }

    private void publish(Map<String, Object> event) {
        try {
            broadcaster.publish(event);
        } catch (RuntimeException error) {
            LOG.warn("交易 SSE 广播失败", error);
        }
    }

    private String date() {
        return gate.today().toString();
    }

    private LocalTime now() {
        return LocalDateTime.now(clock).toLocalTime();
    }

    private String timestamp() {
        return STAMP.format(LocalDateTime.now(clock));
    }

    private static String message(Throwable error) {
        return error == null || error.getMessage() == null ? "未知错误" : error.getMessage();
    }
}
