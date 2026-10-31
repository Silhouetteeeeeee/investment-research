package com.investmentresearch.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.app.Application;
import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.agent.ToolEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真实模型的 skill 路由检查：只用几个问题确认模型加载了正确的 skill，不校验回答内容。
 * 类名不带 Test，默认 {@code mvn verify} 不会跑；需要显式指定并同时提供 key：
 *
 * <pre>
 * LIVE_SMOKE=1 ./mvnw -pl app -am test -Dtest=LiveSkillSmoke -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * 「加载了哪个 skill」从 AgentScope 的会话状态文件里读（工具调用参数里的 skillId），
 * 因为 ToolEvent 不含参数。结果摘要写到 {@code app/target/live-smoke/skills-latest.json}，
 * 改 system prompt / skill 正文 / 工具后重跑，就能看出路由是否被带偏。
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=live-smoke-token",
            // surefire 的工作目录是模块目录 app/，skills 在仓库根
            "APP_SKILLS_DIR=../skills",
            "spring.web.resources.static-locations=classpath:/test-web/",
            "management.server.port=0"
        })
@EnabledIfEnvironmentVariable(named = "LIVE_SMOKE", matches = "1")
class LiveSkillSmoke {
    private static final Path DATA_DIR = Path.of("target/live-smoke-data");
    private static final Path SESSION_DIR = DATA_DIR.resolve("sessions/default");
    private static final Path SNAPSHOT = Path.of("target/live-smoke/skills-latest.json");

    private record SkillCase(String code, String name, String question, String expected) {}

    private static final List<SkillCase> CASES =
            List.of(
                    new SkillCase(
                            "605499",
                            "东鹏饮料",
                            "东鹏饮料靠什么赚钱？请拆解它的商业模式。",
                            "business_model"),
                    new SkillCase(
                            "605499", "东鹏饮料", "东鹏饮料过去五年的经营表现怎么样？", "operating_history"),
                    new SkillCase("600519", "贵州茅台", "贵州茅台现在估值贵不贵？", "valuation"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("APP_DATA_DIR", DATA_DIR::toString);
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:sqlite:file:target/live-skill-smoke.db?busy_timeout=5000");
    }

    @Autowired AssistantEngine engine;
    @Autowired ObjectMapper json;

    @Test
    @Timeout(900)
    void model_loads_the_skill_matching_the_question() throws Exception {
        assumeTrue(
                System.getenv("DEEPSEEK_API_KEY") != null
                        && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过真实链路冒烟");

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < CASES.size(); i++) {
            SkillCase testCase = CASES.get(i);
            // 每次运行用新会话，避免上一轮的 skill 加载记录混进本次结果
            long conversationId = Math.abs(System.nanoTime()) + i;
            List<ToolEvent> toolEvents = new ArrayList<>();

            long started = System.nanoTime();
            AskResult answer =
                    engine.ask(
                            conversationId,
                            testCase.code(),
                            testCase.name(),
                            testCase.question(),
                            listener(toolEvents));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            List<String> loaded = loadedSkills(conversationId);

            results.add(summary(testCase, conversationId, answer, toolEvents, loaded, elapsedMs));
            System.out.printf(
                    "live-skill: %s -> %s (%.1fs, tools=%d)%n",
                    testCase.expected(), loaded, elapsedMs / 1000.0, toolEvents.size());

            assertThat(answer.run().payload().get("stop_reason"))
                    .as("问题「%s」没有正常作答", testCase.question())
                    .isEqualTo("answered");
            assertThat(answer.text()).isNotBlank();
            assertThat(loaded)
                    .as(
                            "问题「%s」应加载 skill %s，实际加载 %s",
                            testCase.question(), testCase.expected(), loaded)
                    .anyMatch(id -> id.equals(testCase.expected()) || id.startsWith(testCase.expected() + "_"));
        }

        Files.createDirectories(SNAPSHOT.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(SNAPSHOT.toFile(), results);
        System.out.println("live-skill snapshot: " + SNAPSHOT.toAbsolutePath());
    }

    private StreamListener listener(List<ToolEvent> toolEvents) {
        return new StreamListener() {
            @Override
            public void text(String delta) {}

            @Override
            public void discard() {}

            @Override
            public void tool(ToolEvent event) {
                toolEvents.add(event);
            }
        };
    }

    /** AgentScope 把一次会话的状态写成 JSON，skill 调用参数里的 skillId 是唯一能确认「加载了哪个」的地方。 */
    private List<String> loadedSkills(long conversationId) throws Exception {
        Path file = SESSION_DIR.resolve(Long.toString(conversationId)).resolve("agent_state.json");
        List<String> ids = new ArrayList<>();
        if (!Files.exists(file)) {
            return ids;
        }
        collectSkillIds(json.readTree(file.toFile()), ids);
        return ids;
    }

    private static void collectSkillIds(JsonNode node, List<String> ids) {
        if (node.isObject()) {
            JsonNode skillId = node.get("skillId");
            if (skillId != null
                    && skillId.isTextual()
                    && !ids.contains(skillId.asText())) {
                ids.add(skillId.asText());
            }
            node.fields().forEachRemaining(field -> collectSkillIds(field.getValue(), ids));
        } else if (node.isArray()) {
            node.forEach(child -> collectSkillIds(child, ids));
        }
    }

    private static Map<String, Object> summary(
            SkillCase testCase,
            long conversationId,
            AskResult answer,
            List<ToolEvent> toolEvents,
            List<String> loaded,
            long elapsedMs) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("question", testCase.question());
        item.put("expected_skill", testCase.expected());
        item.put("loaded_skills", loaded);
        item.put("conversation_id", conversationId);
        item.put("run_id", answer.run().runId());
        item.put("stop_reason", answer.run().payload().get("stop_reason"));
        item.put("elapsed_ms", elapsedMs);
        item.put("issues", answer.issues());
        item.put(
                "tools",
                toolEvents.stream()
                        .map(
                                event -> {
                                    Map<String, Object> tool = new LinkedHashMap<>();
                                    tool.put("name", event.name());
                                    tool.put("ok", event.ok());
                                    return tool;
                                })
                        .toList());
        item.put("answer", answer.text());
        return item;
    }
}
