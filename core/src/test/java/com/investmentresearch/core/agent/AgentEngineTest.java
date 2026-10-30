package com.investmentresearch.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
import com.investmentresearch.core.run.FileRunStore;
import com.investmentresearch.core.run.RunSummary;
import com.investmentresearch.core.tool.ResearchTool;
import com.investmentresearch.core.tool.ToolRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentEngineTest {

    @TempDir Path root;

    private Path skillsDir;
    private Path dataDir;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = realSkillsDir();
        dataDir = Files.createDirectories(root.resolve("data"));
    }

    @Test
    void engine_streams_text_and_tool_events_in_order() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("营收 1 亿[[profile:605499]]。"));
        FakeProfileTool tool = new FakeProfileTool();
        RecordingListener listener = new RecordingListener();

        AskResult result = engine(model, tool).ask(1, "605499", "东鹏饮料", "靠什么赚钱", listener);

        assertThat(result.text()).isEqualTo("营收 1 亿[[profile:605499]]。");
        assertThat(listener.text()).isEqualTo(result.text());
        assertThat(listener.tools).hasSize(1);
        assertThat(listener.tools.get(0).name()).isEqualTo("get_company_profile");
        assertThat(listener.tools.get(0).ok()).isTrue();
        assertThat(listener.tools.get(0).cached()).isFalse();

        int toolIndex = listener.events.indexOf("tool:get_company_profile");
        int firstText =
                listener.events.stream()
                        .filter(event -> event.startsWith("text:"))
                        .findFirst()
                        .map(listener.events::indexOf)
                        .orElse(-1);
        assertThat(toolIndex).isGreaterThanOrEqualTo(0);
        assertThat(firstText).isGreaterThan(toolIndex);

        assertThat(result.trace()).hasSize(1);
        assertThat(result.trace().get(0).round()).isEqualTo(1);
        assertThat(result.trace().get(0).name()).isEqualTo("get_company_profile");
        assertThat(result.trace().get(0).sources()).containsExactly("profile:605499");
        assertThat(result.sources()).extracting(Source::id).containsExactly("profile:605499");
        assertThat(result.raw()).hasSize(1);
        assertThat(result.raw().get(0)).containsEntry("role", "tool");
        assertThat(result.raw().get(0).get("tool_call_id"))
                .asString()
                .startsWith("call_get_company_profile_");
        assertThat((String) result.raw().get(0).get("content"))
                .contains("\"ok\": true")
                .contains("\"sources\"");
        assertThat(result.run().runId()).isNotBlank();

        String systemPrompt = allText(model.calls().get(0));
        assertThat(systemPrompt).contains("当前默认公司：605499");
        assertThat(systemPrompt).contains("本轮工具调用最多 10 轮");
    }

    @Test
    void run_payload_can_be_persisted_with_model_and_tool_stages() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("营收 1 亿[[profile:605499]]。"));
        AskResult result =
                engine(model, new FakeProfileTool())
                        .ask(1, "605499", "东鹏饮料", "靠什么赚钱", new RecordingListener());

        RunSummary summary = new FileRunStore(root.resolve("runs")).write(result.run().payload());

        assertThat(summary.id()).isEqualTo(result.run().runId());
        Map<String, Object> payload = result.run().payload();
        assertThat(payload)
                .containsKeys(
                        "run_id",
                        "model",
                        "max_rounds",
                        "error",
                        "stages",
                        "prompts",
                        "skills",
                        "tool_schemas",
                        "calls");
        assertThat(payload.get("model")).isEqualTo("scripted");
        assertThat(payload.get("max_rounds")).isEqualTo(10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages =
                (List<Map<String, Object>>) payload.get("stages");
        assertThat(stages).isNotEmpty();
        assertThat(stages).extracting(stage -> stage.get("kind")).contains("model", "tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) payload.get("tool_schemas");
        assertThat(schemas).containsKeys("hash", "tools");
    }

    @Test
    void engine_loads_skill_before_first_tool_call() throws Exception {
        String skillId;
        try (FileSystemSkillRepository repository = new FileSystemSkillRepository(skillsDir)) {
            skillId = repository.getAllSkills().get(0).getSkillId();
        }
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall(
                                SkillLoadTool.TOOL_NAME,
                                Map.of("skillId", skillId, "path", "SKILL.md")),
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("done[[profile:605499]]"));
        RecordingListener listener = new RecordingListener();

        AskResult result =
                engine(model, new FakeProfileTool())
                        .ask(1, "605499", "东鹏饮料", "靠什么赚钱", listener);

        assertThat(result.text()).isEqualTo("done[[profile:605499]]");
        assertThat(listener.tools).extracting(ToolEvent::name)
                .containsExactly(SkillLoadTool.TOOL_NAME, "get_company_profile");
        assertThat(listener.tools.get(0).ok()).isTrue();
        assertThat(allText(model.calls().get(0))).contains("business_model");
        assertThat(toolResultText(model.calls().get(1)))
                .contains("第一问：这家公司究竟靠什么赚钱？");
    }

    @Test
    void unknown_citation_in_answer_becomes_question_mark() {
        ScriptedModel model = new ScriptedModel(ScriptedModel.text("营收 1 亿[[nope]]。"));

        AskResult result =
                engine(model).ask(1, "605499", "东鹏饮料", "靠什么赚钱", new RecordingListener());

        assertThat(result.text()).isEqualTo("营收 1 亿[?]。");
        assertThat(result.sources()).isEmpty();
        assertThat(result.issues()).contains("unknown_citation");
    }

    @Test
    void second_ask_reuses_same_session_state() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.text("第一轮回答"), ScriptedModel.text("第二轮回答"));
        AgentEngine engine = engine(model);

        engine.ask(1, "605499", "东鹏饮料", "第一个问题", new RecordingListener());
        AskResult second =
                engine.ask(1, "605499", "东鹏饮料", "第二个问题", new RecordingListener());

        assertThat(model.calls()).hasSize(2);
        assertThat(allText(model.calls().get(1))).contains("第一轮回答");
        assertThat(second.text()).isEqualTo("第二轮回答");
    }

    @Test
    void identical_tool_call_in_same_conversation_is_cached() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("first[[profile:605499]]"),
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("second[[profile:605499]]"));
        FakeProfileTool tool = new FakeProfileTool();
        RecordingListener secondListener = new RecordingListener();
        AgentEngine engine = engine(model, tool);

        engine.ask(1, "605499", "东鹏饮料", "第一个问题", new RecordingListener());
        AskResult second = engine.ask(1, "605499", "东鹏饮料", "第二个问题", secondListener);

        assertThat(tool.runs.get()).isEqualTo(1);
        assertThat(second.trace().get(0).cached()).isTrue();
        assertThat(secondListener.tools).hasSize(1);
        assertThat(secondListener.tools.get(0).cached()).isTrue();
    }

    @Test
    void uncached_tool_is_never_served_from_cache() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("get_valuation", Map.of()),
                        ScriptedModel.text("first[[valuation:605499]]"),
                        ScriptedModel.toolCall("get_valuation", Map.of()),
                        ScriptedModel.text("second[[valuation:605499]]"));
        FakeValuationTool tool = new FakeValuationTool();
        RecordingListener secondListener = new RecordingListener();
        AgentEngine engine = engine(model, tool);

        engine.ask(1, "605499", "东鹏饮料", "第一个问题", new RecordingListener());
        AskResult second = engine.ask(1, "605499", "东鹏饮料", "第二个问题", secondListener);

        assertThat(tool.runs.get()).isEqualTo(2);
        assertThat(second.trace().get(0).cached()).isFalse();
        assertThat(secondListener.tools).hasSize(1);
        assertThat(secondListener.tools.get(0).cached()).isFalse();
    }

    @Test
    void deleteSession_removes_state_file_and_cache() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("first[[profile:605499]]"),
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("second[[profile:605499]]"),
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("third[[profile:605499]]"));
        FakeProfileTool tool = new FakeProfileTool();
        AgentEngine engine = engine(model, tool);
        JsonFileAgentStateStore probe =
                new JsonFileAgentStateStore(dataDir.resolve("sessions"));

        engine.ask(7, "605499", "东鹏饮料", "第一个问题", new RecordingListener());
        assertThat(probe.exists("default", "7")).isTrue();

        engine.deleteSession(7);

        assertThat(probe.exists("default", "7")).isFalse();
        engine.ask(7, "605499", "东鹏饮料", "第三个问题", new RecordingListener());
        assertThat(tool.runs.get()).isEqualTo(2);
    }

    @Test
    void unknown_tool_call_is_reported_and_does_not_abort() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("no_such_tool", Map.of()),
                        ScriptedModel.text("recovered"));
        RecordingListener listener = new RecordingListener();

        AskResult result =
                engine(model).ask(1, "605499", "东鹏饮料", "靠什么赚钱", listener);

        assertThat(result.text()).isEqualTo("recovered");
        assertThat(listener.tools).hasSize(1);
        assertThat(listener.tools.get(0).name()).isEqualTo("no_such_tool");
        assertThat(listener.tools.get(0).ok()).isFalse();
        assertThat(result.trace()).hasSize(1);
        assertThat(result.trace().get(0).ok()).isFalse();
        assertThat(result.trace().get(0).error()).isEqualTo("未知工具：no_such_tool");
    }

    @Test
    void round_exhaustion_does_not_count_the_forced_answer_round() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("资料有限，先给结论[[profile:605499]]"));
        RecordingListener listener = new RecordingListener();

        AskResult result =
                engine(1, model, new FakeProfileTool())
                        .ask(1, "605499", "东鹏饮料", "靠什么赚钱", listener);

        assertThat(result.text()).isEqualTo("资料有限，先给结论[[profile:605499]]");
        assertThat(result.issues()).contains("工具轮次用尽后直接基于已有资料作答");
        assertThat(result.issues()).doesNotContain("研究轮次用尽（1 轮）");
        assertThat(result.run().payload().get("rounds")).isEqualTo(1);
        assertThat(result.run().payload().get("stop_reason")).isEqualTo("answered");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages =
                (List<Map<String, Object>>) result.run().payload().get("stages");
        assertThat(
                        stages.stream()
                                .filter(stage -> "model".equals(stage.get("kind")))
                                .map(stage -> stage.get("forced")))
                .containsExactly(false, true);
    }

    @Test
    void tool_exception_is_reported_and_run_continues() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("get_company_profile", Map.of()),
                        ScriptedModel.text("recovered"));
        RecordingListener listener = new RecordingListener();

        AskResult result =
                engine(model, new ThrowingTool())
                        .ask(1, "605499", "东鹏饮料", "靠什么赚钱", listener);

        assertThat(result.text()).isEqualTo("recovered");
        assertThat(listener.tools).hasSize(1);
        assertThat(listener.tools.get(0).ok()).isFalse();
        assertThat(result.trace()).hasSize(1);
        assertThat(result.trace().get(0).ok()).isFalse();
        assertThat(result.trace().get(0).error()).isNotBlank();
    }

    @Test
    void schema_rejected_tool_call_still_gets_a_failed_trace_step() {
        ScriptedModel model =
                new ScriptedModel(
                        ScriptedModel.toolCall("get_finance", Map.of()),
                        ScriptedModel.text("recovered"));
        RecordingListener listener = new RecordingListener();

        AskResult result =
                engine(model, new StrictFinanceTool())
                        .ask(1, "605499", "东鹏饮料", "靠什么赚钱", listener);

        assertThat(result.text()).isEqualTo("recovered");
        assertThat(listener.tools).hasSize(1);
        assertThat(listener.tools.get(0).name()).isEqualTo("get_finance");
        assertThat(listener.tools.get(0).ok()).isFalse();
        assertThat(result.trace()).hasSize(1);
        assertThat(result.trace().get(0).name()).isEqualTo("get_finance");
        assertThat(result.trace().get(0).ok()).isFalse();
        assertThat(result.trace().get(0).error()).isNotBlank();
    }

    @Test
    void engine_failure_still_carries_a_finished_error_run() {
        // 第一次模型调用先取数，第二次调用没有脚本可用 -> 运行中途抛错
        ScriptedModel model =
                new ScriptedModel(ScriptedModel.toolCall("get_company_profile", Map.of()));
        EngineFailure failure = null;

        try {
            engine(model, new FakeProfileTool())
                    .ask(1, "605499", "东鹏饮料", "靠什么赚钱", new RecordingListener());
        } catch (EngineFailure error) {
            failure = error;
        }

        assertThat(failure).isNotNull();
        Map<String, Object> payload = failure.run().payload();
        assertThat(payload.get("stop_reason")).isEqualTo("error");
        assertThat((String) payload.get("error")).isNotBlank();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages =
                (List<Map<String, Object>>) payload.get("stages");
        assertThat(stages).isNotEmpty();
    }

    private AgentEngine engine(Model model, ResearchTool... tools) {
        return engine(10, model, tools);
    }

    private AgentEngine engine(int maxTurns, Model model, ResearchTool... tools) {
        return new AgentEngine(
                new ToolRegistry(List.of(tools)),
                new AgentProperties("scripted", skillsDir, dataDir, maxTurns),
                () -> model);
    }

    private static Path realSkillsDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(cwd.resolve("skills"), cwd.resolve("..").resolve("skills"))) {
            if (Files.isRegularFile(candidate.resolve("business_model").resolve("SKILL.md"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("找不到仓库里的 skills/business_model/SKILL.md");
    }

    private static String allText(List<Msg> messages) {
        return messages.stream()
                .map(Msg::getTextContent)
                .filter(text -> text != null)
                .collect(Collectors.joining("\n"));
    }

    private static String toolResultText(List<Msg> messages) {
        return messages.stream()
                .flatMap(
                        message ->
                                message.getContentBlocks(
                                                io.agentscope.core.message.ToolResultBlock.class)
                                        .stream())
                .flatMap(block -> block.getOutput().stream())
                .filter(io.agentscope.core.message.TextBlock.class::isInstance)
                .map(io.agentscope.core.message.TextBlock.class::cast)
                .map(io.agentscope.core.message.TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    private static final class RecordingListener implements StreamListener {
        private final List<String> events = new ArrayList<>();
        private final List<ToolEvent> tools = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void text(String delta) {
            text.append(delta);
            events.add("text:" + delta);
        }

        @Override
        public void discard() {
            events.add("discard");
        }

        @Override
        public void tool(ToolEvent event) {
            tools.add(event);
            events.add("tool:" + event.name());
        }

        private String text() {
            return text.toString();
        }
    }

    private static final class FakeProfileTool implements ResearchTool {
        private final AtomicInteger runs = new AtomicInteger();
        private final ToolSchema schema =
                new ToolSchema(
                        "get_company_profile",
                        "公司概况",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("company", Map.of("type", "string"))));

        @Override
        public ToolSchema schema() {
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
            runs.incrementAndGet();
            return ToolResult.ok(
                    defaultCompany,
                    "2026-06-30",
                    List.of(
                            new Source(
                                    "profile:605499",
                                    "公司概况",
                                    "主营",
                                    "2026-06-30",
                                    "2026-06-30T10:00:00Z")),
                    Map.of("name", "东鹏饮料"));
        }
    }

    private static final class FakeValuationTool implements ResearchTool {
        private final AtomicInteger runs = new AtomicInteger();
        private final ToolSchema schema =
                new ToolSchema(
                        "get_valuation",
                        "估值",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("company", Map.of("type", "string"))));

        @Override
        public ToolSchema schema() {
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
            runs.incrementAndGet();
            return ToolResult.ok(
                    defaultCompany,
                    "2026-06-30",
                    List.of(
                            new Source(
                                    "valuation:605499",
                                    "估值",
                                    "PE",
                                    "2026-06-30",
                                    "2026-06-30T10:00:00Z")),
                    Map.of("pe", 25));
        }
    }

    private static final class ThrowingTool implements ResearchTool {
        private final ToolSchema schema =
                new ToolSchema(
                        "get_company_profile",
                        "公司概况",
                        Map.of("type", "object", "properties", Map.of()));

        @Override
        public ToolSchema schema() {
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
            throw new IllegalStateException("上游超时");
        }
    }

    private static final class StrictFinanceTool implements ResearchTool {
        private final ToolSchema schema =
                new ToolSchema(
                        "get_finance",
                        "财务数据",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("period", Map.of("type", "string")),
                                "required",
                                List.of("period")));

        @Override
        public ToolSchema schema() {
            return schema;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments, String defaultCompany) {
            throw new AssertionError("schema 校验失败时不应执行工具");
        }
    }
}
