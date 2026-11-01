package com.investmentresearch.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.investmentresearch.core.citation.CitationCheck;
import com.investmentresearch.core.citation.CitationValidator;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.domain.TraceStep;
import com.investmentresearch.core.run.RunRecorder;
import com.investmentresearch.core.run.RunRecord;
import com.investmentresearch.core.service.QuestionRouter;
import com.investmentresearch.core.tool.ToolRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** AgentScope harness assembly plus the per-run cache/citation/trace bookkeeping. */
public final class AgentEngine implements AssistantEngine {

    private static final String USER_ID = "default";
    private static final String COMPANY_KEY = "defaultCompany";
    private static final String SYSTEM_PROMPT = loadSystemPrompt();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CANONICAL_JSON =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Python {@code UNCACHED_TOOLS}：时效性或一次性的数据不能跨问题复用。 */
    private static final Set<String> UNCACHED_TOOLS =
            Set.of(
                    "load_skill",
                    "resolve_company",
                    "get_valuation",
                    "get_announcements",
                    "get_earnings_forecasts",
                    "get_market_overview",
                    "get_news_events",
                    "get_sector_rotation",
                    "screen_candidates",
                    "build_strategy_plan",
                    "save_daily_judgment",
                    "review_research_reports",
                    "list_research_memory",
                    "read_research_memory",
                    "append_research_record",
                    "update_research_record",
                    "record_conclusion_change",
                    "web_search");

    private final ToolRegistry tools;
    private final AgentProperties properties;
    private final ToolCallCache cache = new ToolCallCache();
    private final CitationValidator citations = new CitationValidator();
    private final JsonFileAgentStateStore stateStore;
    private final Set<String> toolNames;
    private final Map<String, Object> toolSchemas;
    private final HarnessAgent agent;

    public AgentEngine(
            ToolRegistry tools, AgentProperties properties, Supplier<Model> modelSupplier) {
        this.tools = tools;
        this.properties = properties;
        this.stateStore = new JsonFileAgentStateStore(properties.dataDir().resolve("sessions"));
        this.toolNames =
                tools.schemas().stream()
                        .map(ToolSchema::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.toolSchemas = toolSchemasPayload();
        this.agent = buildAgent(modelSupplier.get());
    }

    @Override
    public AskResult ask(
            long conversationId,
            String code,
            String name,
            String question,
            StreamListener listener) {
        RunRecorder recorder =
                new RunRecorder(
                        // Python 用本地时间（datetime.now()），UTC 会让时间线差 8 小时
                        Clock.systemDefaultZone(),
                        properties.model(),
                        properties.maxTurns(),
                        toolSchemas);
        recorder.start(conversationId, question);
        // Python api.py:71 —— 大盘问题不绑公司，否则工具会把「MARKET」当成公司名去解析
        String defaultCompany = QuestionRouter.MARKET_CODE.equals(code) ? "" : code;
        RunState state = new RunState(conversationId, defaultCompany, listener, recorder);
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId(USER_ID)
                        .sessionId(Long.toString(conversationId))
                        .put(COMPANY_KEY, defaultCompany == null ? "" : defaultCompany)
                        .put(RunState.class, state)
                        .build();

        List<String> issues = new ArrayList<>();
        try {
            AtomicReference<Msg> finalMessage = new AtomicReference<>();
            AtomicReference<String> streamed = new AtomicReference<>("");
            Map<String, Long> toolStartedAt = new ConcurrentHashMap<>();

            Msg userMessage =
                    Msg.builder().role(MsgRole.USER).name("user").textContent(question).build();

            agent.streamEvents(userMessage, context)
                    .doOnNext(
                            event ->
                                    handleEvent(
                                            event,
                                            state,
                                            listener,
                                            finalMessage,
                                            streamed,
                                            toolStartedAt,
                                            issues))
                    .blockLast();

            Msg result = finalMessage.get();
            String text = result == null ? "" : textOf(result);
            if (text.isEmpty() && !streamed.get().isEmpty()) {
                // 模型没给最终消息时，至少保住已经推给页面的正文
                text = streamed.get();
            }

            String stopReason =
                    result == null || text.isBlank() ? "rounds_exhausted" : "answered";
            if (state.exceeded.get()) {
                if (!text.isBlank()) {
                    issues.add("工具轮次用尽后直接基于已有资料作答");
                } else {
                    issues.add("研究轮次用尽（" + state.maxIters.get() + " 轮）");
                    stopReason = "rounds_exhausted";
                }
            }

            CitationCheck check = citations.validate(text, state.sources);
            issues.addAll(check.issues());
            recorder.finish(stopReason, null);
            return new AskResult(
                    check.text(),
                    check.citations(),
                    List.copyOf(state.trace),
                    List.copyOf(issues),
                    recordedRun(recorder, issues),
                    List.copyOf(state.raw));
        } catch (RuntimeException error) {
            // SSE 超时会中断工作线程，Reactor 停止后会恢复中断标记；先清掉，
            // 否则后面的运行记录落盘（NIO）会被 ClosedByInterruptException 打断。
            Thread.interrupted();
            if (error instanceof EngineFailure failure) {
                throw failure;
            }
            String message = error.getMessage();
            if (message == null || message.isBlank()) {
                message = error.getClass().getSimpleName();
            }
            // 运行记录先收尾再抛，前端失败消息也能展开时间线（agent.py:720-723）
            recorder.finish("error", message);
            // 不再自己加前缀：AskService 会拼"研究失败："，加两层就成"研究失败：回答生成失败：…"
            throw new EngineFailure(message, recordedRun(recorder, issues), error);
        }
    }

    private static RunRecord recordedRun(RunRecorder recorder, List<String> issues) {
        Map<String, Object> payload = recorder.payload();
        @SuppressWarnings("unchecked")
        List<Object> runIssues = (List<Object>) payload.get("issues");
        runIssues.addAll(issues);
        return new RunRecord((String) payload.get("run_id"), payload);
    }

    @Override
    public void deleteSession(long conversationId) {
        String sessionId = Long.toString(conversationId);
        agent.clearStateCache(USER_ID, sessionId);
        stateStore.delete(USER_ID, sessionId);
        cache.clear(conversationId);
    }

    private void handleEvent(
            AgentEvent event,
            RunState state,
            StreamListener listener,
            AtomicReference<Msg> finalMessage,
            AtomicReference<String> streamed,
            Map<String, Long> toolStartedAt,
            List<String> issues) {
        AgentEventType type = event.getType();
        switch (type) {
            case MODEL_CALL_START -> {
                ModelCallStartEvent call = (ModelCallStartEvent) event;
                boolean forced = state.forcedNext.getAndSet(false);
                if (!forced) {
                    state.round.incrementAndGet();
                }
                // 强制作答那一轮 Python 记 max_rounds+1 且不计入轮数（agent.py:742）
                int round = state.round.get() + (forced ? 1 : 0);
                state.modelCalls.put(
                        call.getReplyId(), new ModelCall(round, forced, System.nanoTime()));
            }
            case MODEL_CALL_END -> {
                ModelCallEndEvent end = (ModelCallEndEvent) event;
                ModelCall call = state.modelCalls.remove(end.getReplyId());
                if (call != null) {
                    ChatUsage usage = end.getUsage();
                    state.recorder.modelStage(
                            call.round(),
                            elapsed(call.startedNanos()),
                            null,
                            usage == null ? null : usage.getInputTokens(),
                            usage == null ? null : usage.getOutputTokens(),
                            null,
                            usage == null ? "estimate" : "api",
                            call.forced());
                }
            }
            case TEXT_BLOCK_DELTA -> {
                TextBlockDeltaEvent delta = (TextBlockDeltaEvent) event;
                String text = delta.getDelta();
                if (text != null && !text.isEmpty()) {
                    streamed.set(streamed.get() + text);
                    listener.text(text);
                }
            }
            case TOOL_CALL_START -> {
                ToolCallStartEvent call = (ToolCallStartEvent) event;
                listener.discard();
                toolStartedAt.put(
                        toolKey(call.getToolCallId(), call.getToolCallName()), System.nanoTime());
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent result = (ToolResultEndEvent) event;
                String name = result.getToolCallName();
                String key = toolKey(result.getToolCallId(), name);
                boolean ok = result.getState() == ToolResultState.SUCCESS;
                long durationMs = elapsed(toolStartedAt.remove(key));
                if (name != null && toolNames.contains(name)) {
                    // 参数校验失败时 ToolRegistry 不会执行，补一条失败记录留在时间线上
                    if (!state.recordedToolCalls.remove(key)) {
                        String error = ok ? null : "工具调用失败：参数校验未通过或工具未执行";
                        state.addTrace(
                                name,
                                Map.of(),
                                ok,
                                null,
                                null,
                                error,
                                List.of(),
                                false,
                                durationMs);
                        state.recorder.toolStage(
                                Math.max(1, state.round.get()),
                                name,
                                Map.of(),
                                durationMs,
                                ok,
                                false,
                                error,
                                null,
                                null,
                                List.of(),
                                0);
                        listener.tool(new ToolEvent(name, null, ok, false, durationMs));
                    }
                } else if (name != null) {
                    // skill 加载和未知工具不经过 ToolRegistry，事件里也只有成败没有参数
                    String error =
                            ok || SkillLoadTool.TOOL_NAME.equals(name) ? null : "未知工具：" + name;
                    state.addTrace(
                            name,
                            Map.of(),
                            ok,
                            null,
                            null,
                            error,
                            List.of(),
                            false,
                            durationMs);
                    state.recorder.toolStage(
                            Math.max(1, state.round.get()),
                            name,
                            Map.of(),
                            durationMs,
                            ok,
                            false,
                            error,
                            null,
                            null,
                            List.of(),
                            0);
                    listener.tool(new ToolEvent(name, null, ok, false, durationMs));
                }
            }
            case AGENT_RESULT -> finalMessage.set(((AgentResultEvent) event).getResult());
            case EXCEED_MAX_ITERS -> {
                ExceedMaxItersEvent exceeded = (ExceedMaxItersEvent) event;
                state.exceeded.set(true);
                state.maxIters.set(exceeded.getMaxIters());
                // 下一轮模型调用是 AgentScope 的强制作答，不算研究轮次
                state.forcedNext.set(true);
            }
            default -> {
                // AGENT_START/END、文本块边界等事件不需要转给页面
            }
        }
    }

    private HarnessAgent buildAgent(Model model) {
        Toolkit toolkit = new Toolkit();
        for (ToolSchema schema : tools.schemas()) {
            toolkit.registerAgentTool(new CachedTool(schema));
        }

        HarnessAgent built =
                HarnessAgent.builder()
                        .name("research-assistant")
                        .description("股票基本面研究助手")
                        .sysPrompt(SYSTEM_PROMPT)
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(properties.maxTurns())
                        .generateOptions(GenerateOptions.builder().temperature(0.3).build())
                        .skillRepository(new FileSystemSkillRepository(properties.skillsDir()))
                        .workspace(properties.dataDir().resolve("workspace"))
                        .stateStore(stateStore)
                        .compaction(CompactionConfig.builder().triggerTokens(80_000).build())
                        .disableToolResultEviction()
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableWebTools()
                        .disableMemoryTools()
                        .disableMemoryHooks()
                        .disableTranscript()
                        .disableWorkspaceContext()
                        .disableAtPathExpansion()
                        .disableSubagents()
                        .disableDefaultWorkspaceSkills()
                        .disableDynamicSkills()
                        .disableToolsConfig()
                        .enableAgentTracingLog(false)
                        .middleware(new CompanyPromptMiddleware(properties.maxTurns()))
                        // 没有注册 OTel SDK 时 tracer 是 noop，这里常驻不需要开关。
                        .middleware(new OtelTracingMiddleware())
                        .build();

        // harness 还会挂异步工具；本节只暴露 registry 工具和 skill 加载
        Set<String> keep = new HashSet<>(toolNames);
        keep.add(SkillLoadTool.TOOL_NAME);
        for (String name : Set.copyOf(built.getToolkit().getToolNames())) {
            if (!keep.contains(name)) {
                built.getToolkit().removeTool(name);
            }
        }
        return built;
    }

    private static String textOf(Msg message) {
        String text = message.getTextContent();
        return text == null ? "" : text;
    }

    private static long elapsed(Long startedAt) {
        return startedAt == null
                ? 0
                : Math.max(0, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private static String toolKey(String toolCallId, String name) {
        return toolCallId == null || toolCallId.isEmpty() ? String.valueOf(name) : toolCallId;
    }

    /** Python {@code _new_run()} 的 tool_schemas：整份 schema 加一个稳定哈希。 */
    private Map<String, Object> toolSchemasPayload() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolSchema schema : tools.schemas()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", schema.name());
            function.put("description", schema.description());
            function.put("parameters", schema.parameters() == null ? Map.of() : schema.parameters());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "function");
            item.put("function", function);
            list.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hash", textHash(toCanonicalJson(list)));
        payload.put("tools", list);
        return payload;
    }

    private static String toCanonicalJson(Object value) {
        try {
            return CANONICAL_JSON.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalStateException("工具 schema 序列化失败", error);
        }
    }

    private static String textHash(String text) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", error);
        }
    }

    private static String loadSystemPrompt() {
        try (InputStream stream =
                AgentEngine.class.getResourceAsStream("/prompts/system.md")) {
            if (stream == null) {
                throw new IllegalStateException("找不到 prompts/system.md");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("读取 prompts/system.md 失败", error);
        }
    }

    private final class CachedTool implements AgentTool {
        private final ToolSchema schema;

        private CachedTool(ToolSchema schema) {
            this.schema = schema;
        }

        @Override
        public String getName() {
            return schema.name();
        }

        @Override
        public String getDescription() {
            return schema.description();
        }

        @Override
        public Map<String, Object> getParameters() {
            return schema.parameters() == null ? Map.of() : schema.parameters();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> execute(param));
        }

        private ToolResultBlock execute(ToolCallParam param) {
            Map<String, Object> arguments =
                    param.getInput() == null ? Map.of() : param.getInput();
            RuntimeContext context = param.getRuntimeContext();
            RunState state = context == null ? null : context.get(RunState.class);
            if (state == null) {
                throw new IllegalStateException("缺少运行上下文");
            }

            String company = state.companyOf(arguments);
            long startedNanos = System.nanoTime();
            boolean cacheable = !UNCACHED_TOOLS.contains(getName());
            Optional<ToolResult> hit =
                    cacheable
                            ? cache.get(state.conversationId, getName(), arguments, company)
                            : Optional.empty();

            ToolResult result;
            if (hit.isPresent()) {
                result = hit.get().asCached();
            } else {
                result = tools.run(getName(), arguments, state.defaultCompany);
                if (cacheable && result.ok()) {
                    cache.put(state.conversationId, getName(), arguments, company, result);
                    String normalized = result.company() == null ? "" : result.company();
                    if (!normalized.equals(company)) {
                        cache.put(
                                state.conversationId,
                                getName(),
                                arguments,
                                normalized,
                                result);
                    }
                }
            }

            long durationMs =
                    Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
            String callId = param.getToolUseBlock().getId();
            state.recordedToolCalls.add(toolKey(callId, getName()));
            state.record(callId, getName(), arguments, result, durationMs);
            return ToolResultBlock.of(
                            callId,
                            getName(),
                            TextBlock.builder().text(result.toMessageJson()).build())
                    // 默认状态是 RUNNING，必须显式收尾，否则 ReAct 循环会等下去
                    .withState(ToolResultState.SUCCESS);
        }
    }

    private record ModelCall(int round, boolean forced, long startedNanos) {}

    private static final class RunState {
        private final long conversationId;
        private final String defaultCompany;
        private final StreamListener listener;
        private final RunRecorder recorder;
        private final AtomicInteger round = new AtomicInteger();
        private final AtomicBoolean forcedNext = new AtomicBoolean();
        private final AtomicBoolean exceeded = new AtomicBoolean();
        private final AtomicInteger maxIters = new AtomicInteger();
        private final Map<String, ModelCall> modelCalls = new ConcurrentHashMap<>();
        private final Set<String> recordedToolCalls = ConcurrentHashMap.newKeySet();
        private final Map<String, Source> sources = Collections.synchronizedMap(new LinkedHashMap<>());
        private final List<TraceStep> trace = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> raw = new CopyOnWriteArrayList<>();

        private RunState(
                long conversationId,
                String defaultCompany,
                StreamListener listener,
                RunRecorder recorder) {
            this.conversationId = conversationId;
            this.defaultCompany = defaultCompany == null ? "" : defaultCompany;
            this.listener = listener;
            this.recorder = recorder;
        }

        private String companyOf(Map<String, Object> arguments) {
            Object explicit = arguments.get("company");
            if (explicit != null && !String.valueOf(explicit).isBlank()) {
                return String.valueOf(explicit);
            }
            return defaultCompany;
        }

        private void record(
                String callId,
                String name,
                Map<String, Object> arguments,
                ToolResult result,
                long durationMs) {
            for (Source source : result.sources()) {
                sources.putIfAbsent(source.id(), source);
            }
            List<String> sourceIds = result.sources().stream().map(Source::id).toList();
            addTrace(
                    name,
                    arguments,
                    result.ok(),
                    result.company(),
                    result.asOf(),
                    result.error(),
                    sourceIds,
                    result.cached(),
                    durationMs);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "tool");
            message.put("tool_call_id", callId == null ? "" : callId);
            // 并行工具调用时 trace 与 raw 的追加顺序不保证一致；带上名字才能可靠关联
            message.put("name", name);
            message.put("content", result.toMessageJson());
            raw.add(message);
            recorder.toolStage(
                    Math.max(1, round.get()),
                    name,
                    new LinkedHashMap<>(arguments),
                    durationMs,
                    result.ok(),
                    result.cached(),
                    result.error(),
                    result.company(),
                    result.asOf(),
                    sourceIds,
                    dataChars(result));
            listener.tool(
                    new ToolEvent(
                            name,
                            result.company(),
                            result.ok(),
                            result.cached(),
                            durationMs));
        }

        private void addTrace(
                String name,
                Map<String, Object> arguments,
                boolean ok,
                String company,
                String asOf,
                String error,
                List<String> sourceIds,
                boolean cached,
                long durationMs) {
            trace.add(
                    new TraceStep(
                            Math.max(1, round.get()),
                            name,
                            new LinkedHashMap<>(arguments),
                            ok,
                            company,
                            asOf,
                            error,
                            sourceIds,
                            cached,
                            durationMs));
        }

        private static int dataChars(ToolResult result) {
            if (result.data() == null) {
                return 0;
            }
            try {
                return JSON.writeValueAsString(result.data()).length();
            } catch (IOException error) {
                return 0;
            }
        }
    }

    private static final class CompanyPromptMiddleware implements MiddlewareBase {
        private final int maxTurns;

        private CompanyPromptMiddleware(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        @Override
        public int order() {
            // 排在 harness 的 skill 目录后面，让默认公司成为系统提示的最后一段
            return 100;
        }

        @Override
        public Mono<String> onSystemPrompt(
                Agent agent, RuntimeContext context, String systemPrompt) {
            String company =
                    context == null ? null : context.get(COMPANY_KEY, String.class);
            String text =
                    systemPrompt
                            + "\n\n当前默认公司："
                            + (company == null || company.isBlank()
                                    ? "未指定（大盘/市场问题不需要公司）"
                                    : company);
            text +=
                    "\n\n本轮工具调用最多 "
                            + maxTurns
                            + " 轮。临近上限时先保留一轮作答；"
                            + "研究档案的记录一次批量写入，不要为了补字段反复重试。";
            return Mono.just(text);
        }
    }
}
