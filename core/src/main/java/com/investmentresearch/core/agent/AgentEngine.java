package com.investmentresearch.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.citation.CitationCheck;
import com.investmentresearch.core.citation.CitationValidator;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.domain.TraceStep;
import com.investmentresearch.core.run.RunRecord;
import com.investmentresearch.core.tool.ToolRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private static final DateTimeFormatter RUN_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    private final ToolRegistry tools;
    private final AgentProperties properties;
    private final ToolCallCache cache = new ToolCallCache();
    private final CitationValidator citations = new CitationValidator();
    private final JsonFileAgentStateStore stateStore;
    private final Set<String> toolNames;
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
        this.agent = buildAgent(modelSupplier.get());
    }

    @Override
    public AskResult ask(
            long conversationId,
            String code,
            String name,
            String question,
            StreamListener listener) {
        String startedAt = Instant.now().toString();
        long startedNanos = System.nanoTime();
        RunState state = new RunState(conversationId, code, listener);
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId(USER_ID)
                        .sessionId(Long.toString(conversationId))
                        .put(COMPANY_KEY, code == null ? "" : code)
                        .put(RunState.class, state)
                        .build();

        AtomicReference<Msg> finalMessage = new AtomicReference<>();
        AtomicReference<String> streamed = new AtomicReference<>("");
        Map<String, Long> toolStartedAt = new ConcurrentHashMap<>();
        List<String> issues = new ArrayList<>();

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

        CitationCheck check = citations.validate(text, state.sources);
        issues.addAll(check.issues());
        String stopReason = result == null ? "rounds_exhausted" : "answered";
        long totalMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        RunRecord run =
                new RunRecord(
                        runId(),
                        runPayload(
                                conversationId,
                                question,
                                stopReason,
                                state,
                                issues,
                                startedAt,
                                totalMs));
        return new AskResult(
                check.text(),
                check.citations(),
                List.copyOf(state.trace),
                List.copyOf(issues),
                run,
                List.copyOf(state.raw));
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
            case MODEL_CALL_START -> state.round.incrementAndGet();
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
                toolStartedAt.put(toolKey(call.getToolCallId(), call.getToolCallName()), System.nanoTime());
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent result = (ToolResultEndEvent) event;
                String name = result.getToolCallName();
                if (name != null && !toolNames.contains(name)) {
                    // skill 加载和未知工具不经过 ToolRegistry，事件里也只有成败没有参数
                    long durationMs = elapsed(toolStartedAt.remove(toolKey(result.getToolCallId(), name)));
                    boolean ok = result.getState() == ToolResultState.SUCCESS;
                    String error =
                            ok || SkillLoadTool.TOOL_NAME.equals(name) ? null : "未知工具：" + name;
                    state.trace.add(
                            new TraceStep(
                                    Math.max(1, state.round.get()),
                                    name,
                                    Map.of(),
                                    ok,
                                    null,
                                    null,
                                    error,
                                    List.of(),
                                    false,
                                    durationMs));
                    listener.tool(new ToolEvent(name, null, ok, false, durationMs));
                }
            }
            case AGENT_RESULT -> finalMessage.set(((AgentResultEvent) event).getResult());
            case EXCEED_MAX_ITERS -> {
                ExceedMaxItersEvent exceeded = (ExceedMaxItersEvent) event;
                issues.add("研究轮次用尽（" + exceeded.getMaxIters() + " 轮）");
            }
            default -> {
                // AGENT_START/END、model/tool 的 start/end 等事件不需要转给页面
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
                        .middleware(new CompanyPromptMiddleware(properties.maxTurns()))
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

    private static String runId() {
        return RUN_STAMP.format(java.time.LocalDateTime.now())
                + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Map<String, Object> runPayload(
            long conversationId,
            String question,
            String stopReason,
            RunState state,
            List<String> issues,
            String startedAt,
            long totalMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("conversation_id", conversationId);
        payload.put("question", question);
        payload.put("rounds", state.round.get());
        payload.put("stop_reason", stopReason);
        payload.put("trace", List.copyOf(state.trace));
        payload.put("issues", List.copyOf(issues));
        payload.put("started_at", startedAt);
        payload.put("finished_at", Instant.now().toString());
        payload.put("total_ms", totalMs);
        return payload;
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

    private static Map<String, Object> parsePayload(ToolResult result) {
        try {
            return JSON.readValue(result.toMessageJson(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException error) {
            return Map.of("ok", result.ok());
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
            Optional<ToolResult> hit =
                    cache.get(state.conversationId, getName(), arguments, company);

            ToolResult result;
            if (hit.isPresent()) {
                result = hit.get().asCached();
            } else {
                result = tools.run(getName(), arguments, state.defaultCompany);
                if (result.ok()) {
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
            state.record(getName(), arguments, result, durationMs);
            return ToolResultBlock.of(
                            param.getToolUseBlock().getId(),
                            getName(),
                            TextBlock.builder().text(result.toMessageJson()).build())
                    // 默认状态是 RUNNING，必须显式收尾，否则 ReAct 循环会等下去
                    .withState(ToolResultState.SUCCESS);
        }
    }

    private static final class RunState {
        private final long conversationId;
        private final String defaultCompany;
        private final StreamListener listener;
        private final AtomicInteger round = new AtomicInteger();
        private final Map<String, Source> sources = Collections.synchronizedMap(new LinkedHashMap<>());
        private final List<TraceStep> trace = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> raw = new CopyOnWriteArrayList<>();

        private RunState(long conversationId, String defaultCompany, StreamListener listener) {
            this.conversationId = conversationId;
            this.defaultCompany = defaultCompany == null ? "" : defaultCompany;
            this.listener = listener;
        }

        private String companyOf(Map<String, Object> arguments) {
            Object explicit = arguments.get("company");
            if (explicit != null && !String.valueOf(explicit).isBlank()) {
                return String.valueOf(explicit);
            }
            return defaultCompany;
        }

        private void record(
                String name, Map<String, Object> arguments, ToolResult result, long durationMs) {
            for (Source source : result.sources()) {
                sources.putIfAbsent(source.id(), source);
            }
            trace.add(
                    new TraceStep(
                            Math.max(1, round.get()),
                            name,
                            new LinkedHashMap<>(arguments),
                            result.ok(),
                            result.company(),
                            result.asOf(),
                            result.error(),
                            result.sources().stream().map(Source::id).toList(),
                            result.cached(),
                            durationMs));
            raw.add(parsePayload(result));
            listener.tool(
                    new ToolEvent(
                            name,
                            result.company(),
                            result.ok(),
                            result.cached(),
                            durationMs));
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
