package com.investmentresearch.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.investmentresearch.app.Application;
import com.investmentresearch.core.agent.AgentEngine;
import com.investmentresearch.core.agent.AgentProperties;
import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.ScriptedModel;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.tool.ToolRegistry;
import com.investmentresearch.data.persistence.StockEntity;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.skill.runtime.HarnessSkillEntry;
import io.agentscope.harness.agent.skill.runtime.SkillCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The phase-1 acceptance test: one question through real HTTP, real SQLite, the real tool
 * adapters replayed from recordings, and a scripted model.
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=test-token",
            "DEEPSEEK_API_KEY=",
            "spring.web.resources.static-locations=classpath:/test-web/",
            "management.server.port=0"
        })
@Import(AskFlowTest.ScriptedEngineConfig.class)
class AskFlowTest {
    private static final String QUESTION = "东鹏饮料靠什么赚钱";
    private static final String MANAGEMENT_QUESTION = "东鹏饮料管理层怎么样";
    private static final String MANAGEMENT_SOURCE = "management:605499";
    private static final String MANAGEMENT_CACHE =
            "{\"as_of\":\"2026-06-30\",\"salary_year\":2025,\"count\":1,"
                    + "\"groups\":{\"directors\":{\"label\":\"董事会\",\"count\":1,"
                    + "\"members\":[{\"name\":\"林木勤\",\"duties\":[\"董事长\"],\"tenure\":\"2019\","
                    + "\"age\":59,\"education\":\"硕士\",\"is_control\":true}]}}}";
    private static final String PROFILE_SOURCE = "profile:605499";
    private static final String FINANCE_SOURCE = "finance:605499:2026-09-30";
    private static final String SEGMENTS_SOURCE = "segments:605499:2026-06-30:product";
    private static final String ANSWER =
            "东鹏饮料靠能量饮料赚钱[["
                    + PROFILE_SOURCE
                    + "]]，2026 年三季报营收保持增长[["
                    + FINANCE_SOURCE
                    + "]]，产品结构以能量饮料为主[["
                    + SEGMENTS_SOURCE
                    + "]]。";

    private static final WireMockServer UPSTREAM = startUpstream();
    // Kept out of @TempDir: Windows keeps a Hikari-opened SQLite file locked, which would
    // make JUnit's temp-directory cleanup fail. Runs and sessions still live in dataDir.
    private static final String DB = "target/askflow-" + UUID.randomUUID() + ".db";

    @TempDir static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("APP_DATA_DIR", () -> dataDir.toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:sqlite:file:" + DB + "?busy_timeout=5000");
        registry.add("APP_SKILLS_DIR", () -> repoRoot().resolve("skills").toString());
        registry.add("app.finance-url", () -> upstream("/cn/api/openapi.php/CompanyFinanceService.getFinanceReport2022"));
        registry.add("app.f10-profile-url", () -> upstream("/PC_HSF10/CompanySurvey/PageAjax"));
        registry.add("app.f10-segments-url", () -> upstream("/PC_HSF10/BusinessAnalysis/PageAjax"));
        registry.add("app.announcement-url", () -> upstream("/api/security/ann"));
        registry.add("app.datacenter-url", () -> upstream("/api/data/v1/get"));
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired StockMapper stocks;
    @Autowired ResearchCacheStore cache;

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @BeforeEach
    void seedDirectory() {
        // The app DB is freshly migrated; one row keeps routing offline. The adapters
        // themselves still fetch (and cache) from the recorded upstream responses.
        if (stocks.selectById("605499") == null) {
            StockEntity row = new StockEntity();
            row.setCode("605499");
            row.setName("东鹏饮料");
            row.setRefreshedAt("2026-10-30 00:00:00");
            stocks.insert(row);
        }
        // 同花顺高管接口没有录制文件，直接写一份新鲜缓存，让 get_management 不出网。
        cache.write(
                ResearchCache.MANAGEMENT,
                "605499",
                MANAGEMENT_CACHE,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    @Test
    void full_ask_flow_streams_events_persists_and_replays() throws Exception {
        List<JsonNode> events = ask(QUESTION);
        List<String> types = types(events);

        assertThat(types.get(0)).isEqualTo("state");
        assertThat(types.get(types.size() - 1)).isEqualTo("done");
        int firstTool = types.indexOf("tool");
        int lastTool = types.lastIndexOf("tool");
        int firstText = types.indexOf("text");
        assertThat(firstTool).isGreaterThan(0);
        assertThat(firstText).isGreaterThan(lastTool);

        JsonNode state = events.get(0);
        long id = state.get("conversation_id").asLong();
        assertThat(state.get("code").asText()).isEqualTo("605499");

        assertThat(toolNames(events))
                .containsExactly("get_company_profile", "get_finance", "get_segments");
        assertThat(events.stream().filter(event -> "tool".equals(type(event))))
                .allSatisfy(event -> assertThat(event.get("ok").asBoolean()).isTrue());

        String streamed =
                events.stream()
                        .filter(event -> "text".equals(type(event)))
                        .map(event -> event.get("delta").asText())
                        .collect(Collectors.joining());
        JsonNode done = events.get(events.size() - 1);
        JsonNode message = done.get("message");
        assertThat(done.get("conversation_id").asLong()).isEqualTo(id);
        assertThat(message.get("text").asText()).isEqualTo(streamed).contains("[[profile:605499]]");

        JsonNode detail = getJson("/api/conversations/" + id);
        assertThat(detail.get("code").asText()).isEqualTo("605499");
        assertThat(detail.get("name").asText()).isEqualTo("东鹏饮料");
        assertThat(detail.get("messages")).hasSize(2);
        assertThat(detail.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(detail.get("messages").get(0).get("text").asText()).isEqualTo(QUESTION);

        JsonNode assistant = detail.get("messages").get(1);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("text").asText()).isEqualTo(message.get("text").asText());
        assertThat(assistant.get("trace")).hasSize(3);
        assertThat(sourceIds(assistant.get("sources")))
                .contains(PROFILE_SOURCE, FINANCE_SOURCE, SEGMENTS_SOURCE);
        assertThat(assistant.get("run").get("id").asText()).isNotBlank();

        JsonNode evidence = getJson("/api/conversations/" + id + "/evidence?source_id=" + FINANCE_SOURCE);
        assertThat(evidence.get("source_id").asText()).isEqualTo(FINANCE_SOURCE);
        assertThat(evidence.get("evidence").get("data").get("period").asText())
                .isEqualTo("2026-09-30");
        assertThat(sourceIds(evidence.get("evidence").get("sources"))).contains(FINANCE_SOURCE);
    }

    @Test
    void restarted_context_can_read_back_the_conversation() throws Exception {
        long id = eventsConversationId(ask(QUESTION));

        // A brand-new application over the same data directory, as after a restart.
        try (ConfigurableApplicationContext restarted =
                new SpringApplicationBuilder(Application.class)
                        .web(WebApplicationType.SERVLET)
                        .run(
                                "--server.port=0",
                                "--management.server.port=0",
                                "--app.api-token=test-token",
                                "--DEEPSEEK_API_KEY=",
                                "--APP_DATA_DIR=" + dataDir,
                                "--spring.datasource.url=jdbc:sqlite:file:" + DB + "?busy_timeout=5000",
                                "--spring.web.resources.static-locations=classpath:/test-web/")) {
            int port =
                    ((WebServerApplicationContext) restarted).getWebServer().getPort();
            JsonNode detail =
                    getJson(
                            new TestRestTemplate(),
                            "http://127.0.0.1:" + port + "/api/conversations/" + id);

            assertThat(detail.get("id").asLong()).isEqualTo(id);
            assertThat(detail.get("code").asText()).isEqualTo("605499");
            assertThat(detail.get("messages")).hasSize(2);
            assertThat(detail.get("messages").get(0).get("text").asText()).isEqualTo(QUESTION);
            assertThat(detail.get("messages").get(1).get("text").asText()).isEqualTo(ANSWER);
            assertThat(sourceIds(detail.get("messages").get(1).get("sources")))
                    .contains(PROFILE_SOURCE, FINANCE_SOURCE, SEGMENTS_SOURCE);
        }
    }

    @Test
    void run_detail_contains_tool_stages_with_sources() throws Exception {
        List<JsonNode> events = ask(QUESTION);
        long id = eventsConversationId(events);
        String runId = events.get(events.size() - 1).get("message").get("run").get("id").asText();

        JsonNode run = getJson("/api/conversations/" + id + "/runs/" + runId);
        assertThat(run.get("run_id").asText()).isEqualTo(runId);
        assertThat(run.get("conversation_id").asLong()).isEqualTo(id);
        assertThat(run.get("question").asText()).isEqualTo(QUESTION);
        assertThat(run.get("stop_reason").asText()).isEqualTo("answered");

        List<JsonNode> toolStages = new ArrayList<>();
        for (JsonNode stage : run.get("stages")) {
            if ("tool".equals(stage.get("kind").asText())) {
                toolStages.add(stage);
            }
        }
        assertThat(toolStages)
                .extracting(stage -> stage.get("name").asText())
                .containsExactly("get_company_profile", "get_finance", "get_segments");
        for (JsonNode stage : toolStages) {
            assertThat(stage.get("ok").asBoolean()).isTrue();
            assertThat(stage.get("cached").asBoolean()).isFalse();
            assertThat(stage.get("data_chars").asInt()).isPositive();
            assertThat(stage.get("sources")).isNotEmpty();
        }
        assertThat(toolStages.get(0).get("sources").get(0).asText()).isEqualTo(PROFILE_SOURCE);
        assertThat(toolStages.get(1).get("sources").get(0).asText()).isEqualTo(FINANCE_SOURCE);
        assertThat(toolStages.get(2).get("sources").get(0).asText()).isEqualTo(SEGMENTS_SOURCE);

        long modelStages = 0;
        for (JsonNode stage : run.get("stages")) {
            if ("model".equals(stage.get("kind").asText())) {
                modelStages++;
            }
        }
        assertThat(modelStages).isEqualTo(4);
    }

    @Test
    void management_question_loads_the_skill_then_the_tool() throws Exception {
        List<JsonNode> events = ask(MANAGEMENT_QUESTION);
        assertThat(toolNames(events))
                .containsExactly("load_skill_through_path", "get_management");
        assertThat(events.stream().filter(event -> "tool".equals(type(event))))
                .allSatisfy(event -> assertThat(event.get("ok").asBoolean()).isTrue());

        long id = eventsConversationId(events);
        String runId = events.get(events.size() - 1).get("message").get("run").get("id").asText();
        JsonNode assistant = getJson("/api/conversations/" + id).get("messages").get(1);
        assertThat(traceNames(assistant.get("trace"))).contains("get_management");
        assertThat(sourceIds(assistant.get("sources"))).contains(MANAGEMENT_SOURCE);

        JsonNode run = getJson("/api/conversations/" + id + "/runs/" + runId);
        boolean managementStage = false;
        for (JsonNode stage : run.get("stages")) {
            if ("tool".equals(stage.get("kind").asText())
                    && "get_management".equals(stage.get("name").asText())) {
                managementStage = stage.get("ok").asBoolean();
            }
        }
        assertThat(managementStage).isTrue();
    }

    private List<JsonNode> ask(String question) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> response =
                rest.postForEntity(
                        "/api/ask",
                        new HttpEntity<>(Map.of("question", question), headers),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(
                        type ->
                                assertThat(type.isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                                        .isTrue());
        return frames(response.getBody());
    }

    private JsonNode getJson(String path) throws Exception {
        return getJson(rest, path);
    }

    private JsonNode getJson(TestRestTemplate client, String path) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");
        ResponseEntity<String> response =
                client.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody());
    }

    private List<JsonNode> frames(String body) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String frame : body.split("\n\n")) {
            if (frame.startsWith("data: ")) {
                events.add(json.readTree(frame.substring("data: ".length())));
            }
        }
        return events;
    }

    private static String type(JsonNode event) {
        return event.get("type").asText();
    }

    private static List<String> types(List<JsonNode> events) {
        return events.stream().map(AskFlowTest::type).toList();
    }

    private static List<String> toolNames(List<JsonNode> events) {
        return events.stream()
                .filter(event -> "tool".equals(type(event)))
                .map(event -> event.get("name").asText())
                .toList();
    }

    private static long eventsConversationId(List<JsonNode> events) {
        return events.get(0).get("conversation_id").asLong();
    }

    private static List<String> traceNames(JsonNode trace) {
        List<String> names = new ArrayList<>();
        trace.forEach(step -> names.add(step.get("name").asText()));
        return names;
    }

    private static List<String> sourceIds(JsonNode sources) {
        List<String> ids = new ArrayList<>();
        sources.forEach(source -> ids.add(source.get("id").asText()));
        return ids;
    }

    private static String upstream(String path) {
        return UPSTREAM.baseUrl() + path;
    }

    private static WireMockServer startUpstream() {
        WireMockServer server =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        Path fixtures = repoRoot().resolve("data/src/test/resources/wiremock");
        try (Stream<Path> files = Files.walk(fixtures)) {
            for (Path file :
                    files.filter(
                                    path ->
                                            path.toString().endsWith(".json")
                                                    && path.getFileName().toString().contains("605499"))
                            .sorted()
                            .toList()) {
                server.addStubMapping(
                        Json.read(
                                Files.readString(file, StandardCharsets.UTF_8),
                                StubMapping.class));
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return server;
    }

    /** Surefire's cwd is {@code app/}; walk up so IDE and reactor runs resolve the same. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    if (Files.readString(pom, StandardCharsets.UTF_8)
                            .contains("<module>data</module>")) {
                        return dir;
                    }
                } catch (IOException ignored) {
                    // keep walking up
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根 pom.xml");
    }

    @TestConfiguration
    static class ScriptedEngineConfig {
        @Bean
        @Primary
        AssistantEngine scriptedEngine(ToolRegistry tools, AgentProperties properties) {
            return new ScriptedEngine(tools, properties);
        }
    }

    /** A fresh AgentEngine per question so each test gets the same scripted reply sequence. */
    private static final class ScriptedEngine implements AssistantEngine {
        private final ToolRegistry tools;
        private final AgentProperties properties;

        private ScriptedEngine(ToolRegistry tools, AgentProperties properties) {
            this.tools = tools;
            this.properties = properties;
        }

        /** 模型从 &lt;available_skills&gt; 里读到的是 {@code <name>_<source>}，这里照抄同一个 id。 */
        private String skillId(String name) {
            SkillCatalog catalog =
                    SkillCatalog.of(
                            new FileSystemSkillRepository(properties.skillsDir())
                                    .getAllSkills().stream()
                                            .map(skill -> HarnessSkillEntry.of(skill, null))
                                            .toList());
            return catalog.ids().stream()
                    .filter(id -> id.startsWith(name + "_"))
                    .findFirst()
                    .orElse(name);
        }

        @Override
        public AskResult ask(
                long conversationId,
                String code,
                String name,
                String question,
                StreamListener listener) {
            ScriptedModel model =
                    MANAGEMENT_QUESTION.equals(question)
                            ? new ScriptedModel(
                                    ScriptedModel.toolCall(
                                            "load_skill_through_path",
                                            Map.of("skillId", skillId("management"), "path", "SKILL.md")),
                                    ScriptedModel.toolCall("get_management", Map.of()),
                                    ScriptedModel.text(
                                            "管理层信息见 [[" + MANAGEMENT_SOURCE + "]]。"))
                            : new ScriptedModel(
                                    ScriptedModel.toolCall("get_company_profile", Map.of()),
                                    ScriptedModel.toolCall("get_finance", Map.of()),
                                    ScriptedModel.toolCall("get_segments", Map.of()),
                                    ScriptedModel.text(ANSWER));
            return new AgentEngine(tools, properties, () -> model)
                    .ask(conversationId, code, name, question, listener);
        }

        @Override
        public void deleteSession(long conversationId) {}
    }
}
