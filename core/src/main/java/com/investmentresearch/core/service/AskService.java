package com.investmentresearch.core.service;

import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.EngineFailure;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.agent.ToolEvent;
import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.TraceStep;
import com.investmentresearch.core.port.ConversationRepository;
import com.investmentresearch.core.port.RunStore;
import com.investmentresearch.core.run.RunRecord;
import com.investmentresearch.core.run.RunSummary;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of {@code assistant/api.py:stream_answer}: route, persist, stream, save the run. */
public final class AskService {
    static final String UNKNOWN_COMPANY_WARNING = "没认出你问的是哪家公司，请在问题里带上公司名或六位代码。";
    static final String MARKET_WARNING = "暂时不支持大盘问题，请指定公司。";

    private static final long METER_INTERVAL_MS = 400;
    private static final int TITLE_LIMIT = 30;
    private static final int PAGE_ISSUE_LIMIT = 2;

    private final ConversationRepository repository;
    private final AssistantEngine engine;
    private final QuestionRouter router;
    private final ContextMeter meter;
    private final RunStore runStore;
    private final Clock clock;

    public AskService(
            ConversationRepository repository,
            AssistantEngine engine,
            QuestionRouter router,
            ContextMeter meter,
            RunStore runStore) {
        this(repository, engine, router, meter, runStore, Clock.systemUTC());
    }

    public AskService(
            ConversationRepository repository,
            AssistantEngine engine,
            QuestionRouter router,
            ContextMeter meter,
            RunStore runStore,
            Clock clock) {
        this.repository = repository;
        this.engine = engine;
        this.router = router;
        this.meter = meter;
        this.runStore = runStore;
        this.clock = clock;
    }

    public void ask(Long conversationId, String question, EventSink sink) {
        Sink out = new Sink(sink);
        Long current = conversationId != null && conversationId > 0 ? conversationId : null;
        Conversation meta = current == null ? null : repository.find(current).orElse(null);
        String currentCode = meta == null ? null : meta.primaryCode();
        String currentName = meta == null ? "" : nameOf(meta);

        Route route = router.route(currentCode, currentName, question);
        String notice = "";
        if (route.reset()) {
            current = null;
            if (currentCode != null && !currentCode.isEmpty()) {
                notice = "已切换到 " + route.name() + "（" + route.code() + "），开始新对话。";
            }
        }
        if (route.code() == null || route.code().isEmpty()) {
            out.send(event("type", "warning", "text", UNKNOWN_COMPANY_WARNING));
            out.complete();
            return;
        }
        if (QuestionRouter.MARKET_CODE.equals(route.code())) {
            // 阶段 1 没有大盘工具；不建会话，也不下发 state（见 README 差异说明）
            out.send(event("type", "warning", "text", MARKET_WARNING));
            out.complete();
            return;
        }

        if (current == null) {
            current =
                    repository.create(
                            buildTitle(route.name(), question), route.code(), route.name());
        }
        long cid = current;
        out.send(
                event(
                        "type", "state",
                        "conversation_id", cid,
                        "code", route.code(),
                        "name", route.name(),
                        "notice", notice));
        // 初始占用不含刚提交的问题：Python 的 meter 读 agent 历史，新增用户消息尚未进入
        sendMeter(out, cid, 0);
        repository.append(cid, "user", event("text", question));

        Draft draft = new Draft();
        long[] lastMeter = {clock.millis() - METER_INTERVAL_MS};
        StreamListener listener =
                new StreamListener() {
                    @Override
                    public void text(String delta) {
                        String value = delta == null ? "" : delta;
                        draft.text.append(value);
                        out.send(event("type", "text", "delta", value));
                        long now = clock.millis();
                        if (now - lastMeter[0] >= METER_INTERVAL_MS) {
                            lastMeter[0] = now;
                            sendMeter(out, cid, ContextMeter.estimateTokens(draft.text.toString()));
                        }
                    }

                    @Override
                    public void discard() {
                        draft.text.setLength(0);
                        out.send(event("type", "discard"));
                        sendMeter(out, cid, 0);
                    }

                    @Override
                    public void tool(ToolEvent tool) {
                        out.send(
                                event(
                                        "type", "tool",
                                        "name", tool.name(),
                                        "company", tool.company(),
                                        "ok", tool.ok(),
                                        "cached", tool.cached(),
                                        "duration_ms", tool.durationMs()));
                        sendMeter(out, cid, 0);
                    }
                };

        AskResult answer;
        try {
            answer = engine.ask(cid, route.code(), route.name(), question, listener);
        } catch (RuntimeException error) {
            String text = "研究失败：" + errorText(error);
            // 引擎失败也带着已经跑完的那次运行记录（agent.py:563,720-723），
            // 页面靠 message.run.id 才打得开这条失败消息的时间线
            Map<String, Object> run =
                    writeRun(error instanceof EngineFailure failure ? failure.run() : null);
            repository.append(cid, "error", event("text", text, "run", run));
            out.send(
                    event(
                            "type", "error",
                            "text", text,
                            "conversation_id", cid,
                            "run", run));
            out.complete();
            return;
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        for (Source source : answer.sources() == null ? List.<Source>of() : answer.sources()) {
            sources.add(sourceMap(source));
        }
        List<Map<String, Object>> trace = new ArrayList<>();
        for (TraceStep step : answer.trace() == null ? List.<TraceStep>of() : answer.trace()) {
            trace.add(traceMap(step));
        }
        List<String> issues = pageIssues(answer.issues());
        Map<String, Object> run = writeRun(answer.run());

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("text", answer.text() == null ? "" : answer.text());
        message.put("sources", sources);
        message.put("trace", trace);
        message.put("issues", issues);
        message.put("run", run);

        Map<String, Object> persisted = new LinkedHashMap<>(message);
        persisted.put("raw", answer.raw() == null ? List.of() : answer.raw());
        repository.append(cid, "assistant", persisted);

        Map<String, Object> usage = meter.usage(cid, 0);
        repository.updateUsage(cid, usage);
        out.send(
                event(
                        "type", "done",
                        "conversation_id", cid,
                        "title", buildTitle(route.name(), question),
                        "message", message,
                        "usage", usage));
        out.complete();
    }

    private Map<String, Object> writeRun(RunRecord run) {
        if (run == null || run.payload() == null) {
            return null;
        }
        try {
            RunSummary summary = runStore.write(run.payload());
            if (summary == null) {
                return null;
            }
            return event("id", summary.id(), "file_bytes", summary.fileBytes());
        } catch (RuntimeException error) {
            // 原文写失败只影响详情页，回答照常返回（assistant/api.py:96-105）
            return null;
        }
    }

    private void sendMeter(Sink out, long conversationId, int draftTokens) {
        Map<String, Object> usage;
        try {
            usage = meter.usage(conversationId, draftTokens);
        } catch (RuntimeException error) {
            // 所有调用点都吞掉 meter 失败；Python 只包了节流那一处（api.py:139-144），此处更保守
            return;
        }
        out.send(event("type", "meter", "usage", usage));
    }

    /** Python slices {@code [:2]} first, then drops empty strings ({@code api.py:197-200}). */
    private static List<String> pageIssues(List<String> issues) {
        List<String> page = new ArrayList<>();
        if (issues == null) {
            return page;
        }
        int limit = Math.min(PAGE_ISSUE_LIMIT, issues.size());
        for (int i = 0; i < limit; i++) {
            String issue = issues.get(i);
            if (issue != null && !issue.isEmpty()) {
                page.add(issue);
            }
        }
        return page;
    }

    private static Map<String, Object> sourceMap(Source source) {
        return event(
                "id", source.id(),
                "label", source.label(),
                "detail", source.detail(),
                "as_of", source.asOf(),
                "fetched_at", source.fetchedAt(),
                "url", source.url() == null ? "" : source.url());
    }

    private static Map<String, Object> traceMap(TraceStep step) {
        return event(
                "round", step.round(),
                "name", step.name(),
                "arguments", step.arguments() == null ? Map.of() : step.arguments(),
                "ok", step.ok(),
                "company", step.company(),
                "as_of", step.asOf(),
                "error", step.error(),
                "sources", step.sources() == null ? List.of() : step.sources(),
                "cached", step.cached(),
                "duration_ms", step.durationMs());
    }

    private static String nameOf(Conversation conversation) {
        String name = conversation.primaryName();
        if (name == null || name.isEmpty()) {
            return conversation.primaryCode();
        }
        return name;
    }

    /** {@code assistant/store.py:build_title}. */
    static String buildTitle(String name, String question) {
        String text = question == null ? "" : question.strip();
        if (text.codePointCount(0, text.length()) > TITLE_LIMIT) {
            text = text.substring(0, text.offsetByCodePoints(0, TITLE_LIMIT)) + "…";
        }
        String value = name == null ? "" : name.strip();
        return value.isEmpty() ? text : value + " · " + text;
    }

    /** Python's {@code f"{error}"}: an exception without a message contributes nothing. */
    private static String errorText(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? "" : message;
    }

    private static Map<String, Object> event(Object... pairs) {
        Map<String, Object> event = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            event.put((String) pairs[i], pairs[i + 1]);
        }
        return event;
    }

    /** A client that disconnects mid-stream must not abort the answer or its persistence. */
    private static final class Sink {
        private final EventSink delegate;
        private boolean dead;

        Sink(EventSink delegate) {
            this.delegate = delegate;
        }

        void send(Map<String, Object> event) {
            if (dead) {
                return;
            }
            try {
                delegate.send(event);
            } catch (RuntimeException error) {
                dead = true;
            }
        }

        void complete() {
            if (dead) {
                return;
            }
            try {
                delegate.complete();
            } catch (RuntimeException error) {
                dead = true;
            }
        }
    }

    private static final class Draft {
        private final StringBuilder text = new StringBuilder();
    }
}
