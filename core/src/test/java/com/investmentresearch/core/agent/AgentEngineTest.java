package com.investmentresearch.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.ToolSchema;
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
        skillsDir = Files.createDirectories(root.resolve("skills"));
        Files.createDirectories(skillsDir.resolve("business_model"));
        Files.writeString(
                skillsDir.resolve("business_model").resolve("SKILL.md"),
                """
                ---
                name: business_model
                description: 拆解商业模式
                ---

                DEMO INSTRUCTIONS
                """);
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
        assertThat(result.raw().get(0)).containsEntry("ok", true);
        assertThat(result.run().runId()).isNotBlank();

        String systemPrompt = allText(model.calls().get(0));
        assertThat(systemPrompt).contains("当前默认公司：605499");
        assertThat(systemPrompt).contains("本轮工具调用最多 10 轮");
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
        assertThat(toolResultText(model.calls().get(1))).contains("DEMO INSTRUCTIONS");
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

    private AgentEngine engine(Model model, ResearchTool... tools) {
        return new AgentEngine(
                new ToolRegistry(List.of(tools)),
                new AgentProperties("scripted", skillsDir, dataDir, 10),
                () -> model);
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
}
