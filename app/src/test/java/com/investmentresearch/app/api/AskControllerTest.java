package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.EngineFailure;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.domain.TraceStep;
import com.investmentresearch.core.port.CompanyDirectory;
import com.investmentresearch.core.port.ConversationRepository;
import com.investmentresearch.core.port.RunStore;
import com.investmentresearch.core.run.RunRecord;
import com.investmentresearch.core.run.RunSummary;
import com.investmentresearch.core.service.AskService;
import com.investmentresearch.core.service.ContextMeter;
import com.investmentresearch.core.service.QuestionRouter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(
        controllers = AskController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AskControllerTest.Harness.class)
class AskControllerTest {
    private static final String RUN_ID = "20261030T120000-abcdef12";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AskService service;
    @Autowired FakeEngine engine;
    @Autowired FakeRepository repository;
    @Autowired FakeRunStore runStore;

    @BeforeEach
    void resetFakes() {
        repository.clear();
        runStore.clear();
        engine.clear();
    }

    @Test
    void empty_question_still_returns_400_json() throws Exception {
        mvc.perform(
                        post("/api/ask")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("empty question"));
    }

    @Test
    void ask_streams_state_text_done_and_persists_assistant_message() throws Exception {
        engine.result = answer();
        engine.deltas = List.of("东鹏饮料营收稳健。");

        String body = perform("东鹏饮料的营收怎么样");

        List<JsonNode> events = frames(body);
        assertThat(types(events)).containsExactly("state", "meter", "text", "meter", "done");
        assertThat(body).contains("东鹏饮料营收稳健。").doesNotContain("\\u");

        JsonNode state = events.get(0);
        assertThat(state.get("conversation_id").asLong()).isEqualTo(1L);
        assertThat(state.get("code").asText()).isEqualTo("605499");
        assertThat(state.get("name").asText()).isEqualTo("东鹏饮料");

        JsonNode message = events.get(events.size() - 1).get("message");
        assertThat(fieldNames(message))
                .containsExactly("role", "text", "sources", "trace", "issues", "run");
        assertThat(message.get("role").asText()).isEqualTo("assistant");
        assertThat(message.get("text").asText()).isEqualTo("东鹏饮料 2025 年营收稳健。");
        assertThat(message.get("sources")).hasSize(1);
        assertThat(message.get("trace")).hasSize(1);
        // Python slices [:2] before dropping empties, so only the first non-empty issue survives.
        assertThat(message.get("issues")).hasSize(1);
        assertThat(message.get("run").get("id").asText()).isEqualTo("run-12345678");

        List<StoredMessage> stored = repository.messages(1);
        assertThat(stored).extracting(StoredMessage::role).containsExactly("user", "assistant");
        assertThat(stored.get(1).payload()).containsKey("raw");
        assertThat(runStore.written).hasSize(1);
    }

    @Test
    void sse_disconnect_does_not_abort_persistence() {
        engine.result = answer();
        SseEmitter broken =
                new SseEmitter() {
                    @Override
                    public void send(Set<DataWithMediaType> data) throws IOException {
                        throw new IOException("client gone");
                    }
                };

        SseWriter writer = new SseWriter(broken, json);

        assertThatCode(() -> service.ask(null, "东鹏饮料的营收怎么样", writer))
                .doesNotThrowAnyException();

        assertThat(repository.messages(1))
                .extracting(StoredMessage::role)
                .containsExactly("user", "assistant");
        assertThat(repository.usages).containsKey(1L);
        assertThat(runStore.written).hasSize(1);
    }

    @Test
    void engine_error_event_carries_run_summary_and_is_persisted() throws Exception {
        engine.failure =
                new EngineFailure("boom", new RunRecord(RUN_ID, Map.of("run_id", RUN_ID)), null);

        List<JsonNode> events = frames(perform("东鹏饮料的营收怎么样"));

        JsonNode error = events.get(events.size() - 1);
        assertThat(types(events)).containsExactly("state", "meter", "error");
        assertThat(error.get("text").asText()).isEqualTo("研究失败：boom");
        assertThat(error.get("conversation_id").asLong()).isEqualTo(1L);
        assertThat(error.get("run").get("id").asText()).isEqualTo(RUN_ID);

        List<StoredMessage> stored = repository.messages(1);
        assertThat(stored).extracting(StoredMessage::role).containsExactly("user", "error");
        assertThat(stored.get(1).payload())
                .containsEntry("text", "研究失败：boom")
                .containsEntry("run", Map.of("id", RUN_ID, "file_bytes", 321L));
        assertThat(runStore.written).hasSize(1);
    }

    private String perform(String question) throws Exception {
        MvcResult started =
                mvc.perform(
                                post("/api/ask")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(Map.of("question", question))))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        return mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
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

    private static List<String> types(List<JsonNode> events) {
        return events.stream().map(event -> event.get("type").asText()).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    private static AskResult answer() {
        return new AskResult(
                "东鹏饮料 2025 年营收稳健。",
                List.of(
                        new Source(
                                "fin_income:605499",
                                "利润表",
                                "营业收入 100 亿",
                                "2025-09-30",
                                "2026-10-30")),
                List.of(
                        new TraceStep(
                                1,
                                "get_finance",
                                Map.of("company", "605499"),
                                true,
                                "605499",
                                "2025-09-30",
                                null,
                                List.of("fin_income:605499"),
                                false,
                                42)),
                List.of("问题一", "", "问题二", "问题三"),
                new RunRecord("run-12345678", Map.of("run_id", "run-12345678", "version", 1)),
                List.of(Map.of("role", "tool", "content", "{}")));
    }

    static final class FakeEngine implements AssistantEngine {
        private AskResult result;
        private RuntimeException failure;
        private List<String> deltas = List.of();

        void clear() {
            result = null;
            failure = null;
            deltas = List.of();
        }

        @Override
        public AskResult ask(
                long conversationId,
                String code,
                String name,
                String question,
                StreamListener listener) {
            if (failure != null) {
                throw failure;
            }
            for (String delta : deltas) {
                listener.text(delta);
            }
            return result;
        }

        @Override
        public void deleteSession(long conversationId) {}
    }

    static final class FakeRepository implements ConversationRepository {
        private final Map<Long, Conversation> conversations = new LinkedHashMap<>();
        private final Map<Long, List<StoredMessage>> messages = new LinkedHashMap<>();
        private final Map<Long, Map<String, Object>> usages = new LinkedHashMap<>();
        private long nextId = 1;

        void clear() {
            conversations.clear();
            messages.clear();
            usages.clear();
            nextId = 1;
        }

        @Override
        public long create(String title, String primaryCode, String primaryName) {
            long id = nextId++;
            conversations.put(
                    id,
                    new Conversation(
                            id,
                            title,
                            primaryCode,
                            primaryName,
                            List.of(primaryCode == null ? "" : primaryCode),
                            "2026-10-30T00:00:00.000000",
                            "2026-10-30T00:00:00.000000",
                            0,
                            Map.of()));
            messages.put(id, new ArrayList<>());
            return id;
        }

        @Override
        public Optional<Conversation> find(long id) {
            return Optional.ofNullable(conversations.get(id));
        }

        @Override
        public List<Conversation> recent(String query, int limit) {
            return new ArrayList<>(conversations.values());
        }

        @Override
        public List<StoredMessage> messages(long id) {
            return messages.getOrDefault(id, List.of());
        }

        @Override
        public int append(long conversationId, String role, Map<String, Object> payload) {
            List<StoredMessage> rows = messages.get(conversationId);
            rows.add(new StoredMessage(role, payload));
            return rows.size();
        }

        @Override
        public void updateUsage(long conversationId, Map<String, Object> usage) {
            usages.put(conversationId, usage);
        }

        @Override
        public void delete(long id) {
            conversations.remove(id);
            messages.remove(id);
        }
    }

    static final class FakeRunStore implements RunStore {
        private final List<Map<String, Object>> written = new ArrayList<>();

        void clear() {
            written.clear();
        }

        @Override
        public RunSummary write(Map<String, Object> payload) {
            written.add(payload);
            return new RunSummary(String.valueOf(payload.get("run_id")), 321L);
        }

        @Override
        public Optional<Map<String, Object>> read(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll(List<String> runIds) {
            throw new UnsupportedOperationException();
        }
    }

    static final class FakeDirectory implements CompanyDirectory {
        private static final List<StockRef> STOCKS =
                List.of(new StockRef("605499", "东鹏饮料"), new StockRef("600519", "贵州茅台"));

        @Override
        public Optional<StockRef> byCode(String code) {
            return STOCKS.stream().filter(stock -> stock.code().equals(code)).findFirst();
        }

        @Override
        public Optional<StockRef> byName(String name) {
            return STOCKS.stream().filter(stock -> stock.name().equals(name)).findFirst();
        }

        @Override
        public List<StockRef> all() {
            return STOCKS;
        }
    }

    @TestConfiguration
    static class Harness {
        @Bean
        Executor askExecutor() {
            return Runnable::run;
        }

        @Bean
        FakeRepository repository() {
            return new FakeRepository();
        }

        @Bean
        FakeEngine engine() {
            return new FakeEngine();
        }

        @Bean
        FakeRunStore runStore() {
            return new FakeRunStore();
        }

        @Bean
        CompanyDirectory directory() {
            return new FakeDirectory();
        }

        @Bean
        QuestionRouter questionRouter(CompanyDirectory directory) {
            return new QuestionRouter(directory);
        }

        @Bean
        ContextMeter contextMeter(ConversationRepository repository) {
            return new ContextMeter(repository);
        }

        @Bean
        AskService askService(
                ConversationRepository repository,
                AssistantEngine engine,
                QuestionRouter router,
                ContextMeter meter,
                RunStore runStore) {
            return new AskService(repository, engine, router, meter, runStore);
        }
    }
}
