package com.investmentresearch.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AskServiceTest {

    private static final List<StockRef> STOCKS =
            List.of(new StockRef("605499", "东鹏饮料"), new StockRef("600519", "贵州茅台"));

    private final FakeRepository repository = new FakeRepository();
    private final FakeEngine engine = new FakeEngine();
    private final FakeRunStore runStore = new FakeRunStore();
    private final QuestionRouter router = new QuestionRouter(new FakeDirectory(STOCKS));
    private final ContextMeter meter = new ContextMeter(repository);
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"));

    @Test
    void new_question_creates_conversation_and_streams_events_in_order() {
        engine.result = answer();
        engine.deltas = List.of("东鹏饮料营收稳健。");
        CapturingSink sink = new CapturingSink();

        service().ask(null, "东鹏饮料的营收怎么样", sink);

        Conversation created = repository.conversations.get(1L);
        assertThat(created.title()).isEqualTo("东鹏饮料 · 东鹏饮料的营收怎么样");
        assertThat(created.primaryCode()).isEqualTo("605499");
        assertThat(created.primaryName()).isEqualTo("东鹏饮料");
        assertThat(types(sink)).containsExactly("state", "meter", "text", "meter", "done");
        assertThat(sink.events.get(0))
                .containsEntry("conversation_id", 1L)
                .containsEntry("code", "605499")
                .containsEntry("name", "东鹏饮料")
                .containsEntry("notice", "");
        assertThat(sink.completes).isEqualTo(1);

        Map<String, Object> done = sink.events.get(sink.events.size() - 1);
        assertThat(done).containsEntry("conversation_id", 1L);
        assertThat(done).containsEntry("title", "东鹏饮料 · 东鹏饮料的营收怎么样");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) done.get("message");
        assertThat(message)
                .containsEntry("role", "assistant")
                .containsEntry("text", "东鹏饮料 2025 年营收稳健。");
        assertThat(message).doesNotContainKey("raw");
        // Python 先切 [:2] 再丢空串，所以第二个空 issue 之后的内容不会出现在页面上
        assertThat((List<String>) message.get("issues")).containsExactly("问题一");
        assertThat((List<?>) message.get("sources")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) ((List<?>) message.get("sources")).get(0);
        assertThat(source)
                .containsEntry("id", "fin_income:605499")
                .containsEntry("label", "利润表")
                .containsEntry("as_of", "2025-09-30")
                .containsEntry("fetched_at", "2026-10-30")
                .containsEntry("url", "");
        assertThat(message.get("run"))
                .isEqualTo(Map.of("id", "run-12345678", "file_bytes", 321L));

        List<StoredMessage> stored = repository.stored(1);
        assertThat(stored).extracting(StoredMessage::role).containsExactly("user", "assistant");
        assertThat(stored.get(0).payload()).containsEntry("text", "东鹏饮料的营收怎么样");
        assertThat(stored.get(1).payload()).containsKey("raw");
        assertThat((List<?>) stored.get(1).payload().get("raw")).hasSize(1);
        assertThat(runStore.written).hasSize(1);
    }

    @Test
    void switching_company_starts_new_conversation_with_notice() {
        seed("东鹏饮料 · 上一轮", "605499", "东鹏饮料");
        engine.result = answer();
        CapturingSink sink = new CapturingSink();

        service().ask(1L, "贵州茅台怎么样", sink);

        assertThat(repository.conversations).hasSize(2);
        assertThat(types(sink)).containsExactly("state", "meter", "done");
        assertThat(sink.events.get(0))
                .containsEntry("conversation_id", 2L)
                .containsEntry("code", "600519")
                .containsEntry("name", "贵州茅台")
                .containsEntry("notice", "已切换到 贵州茅台（600519），开始新对话。");
        assertThat(engine.seenConversationId).isEqualTo(2L);
        assertThat(engine.seenCode).isEqualTo("600519");
    }

    @Test
    void previous_conversation_is_untouched_after_switch() {
        long oldId = seed("东鹏饮料 · 上一轮", "605499", "东鹏饮料");
        repository.append(oldId, "user", new LinkedHashMap<>(Map.of("text", "旧问题")));
        List<StoredMessage> before = List.copyOf(repository.stored(oldId));
        engine.result = answer();

        service().ask(oldId, "贵州茅台怎么样", new CapturingSink());

        assertThat(repository.stored(oldId)).isEqualTo(before);
        assertThat(repository.usages).doesNotContainKey(oldId);
        assertThat(engine.seenConversationId).isEqualTo(2L);
    }

    @Test
    void unrecognised_company_emits_only_warning() {
        CapturingSink sink = new CapturingSink();

        service().ask(null, "帮我看看这个", sink);

        assertThat(types(sink)).containsExactly("warning");
        assertThat(sink.events.get(0))
                .containsEntry("text", "没认出你问的是哪家公司，请在问题里带上公司名或六位代码。");
        assertThat(repository.conversations).isEmpty();
        assertThat(engine.calls).isZero();
        assertThat(sink.completes).isEqualTo(1);
    }

    @Test
    void market_question_emits_warning_in_phase_1() {
        CapturingSink sink = new CapturingSink();

        service().ask(null, "大盘怎么样", sink);

        assertThat(types(sink)).containsExactly("warning");
        assertThat(sink.events.get(0)).containsEntry("text", "暂时不支持大盘问题，请指定公司。");
        assertThat(repository.conversations).isEmpty();
        assertThat(engine.calls).isZero();
    }

    @Test
    void engine_failure_is_persisted_as_error_message_with_run() {
        engine.failure = new IllegalStateException("boom");
        CapturingSink sink = new CapturingSink();

        service().ask(null, "东鹏饮料的营收怎么样", sink);

        assertThat(types(sink)).containsExactly("state", "meter", "error");
        Map<String, Object> error = sink.events.get(sink.events.size() - 1);
        assertThat(error)
                .containsEntry("text", "研究失败：boom")
                .containsEntry("conversation_id", 1L)
                .containsEntry("run", null);
        List<StoredMessage> stored = repository.stored(1L);
        assertThat(stored).extracting(StoredMessage::role).containsExactly("user", "error");
        assertThat(stored.get(1).payload())
                .containsEntry("text", "研究失败：boom")
                .containsEntry("run", null);
        assertThat(repository.usages).isEmpty();
        assertThat(runStore.written).isEmpty();
        assertThat(sink.completes).isEqualTo(1);
    }

    @Test
    void message_less_engine_failure_uses_the_empty_python_copy() {
        engine.failure = new IllegalStateException();
        CapturingSink sink = new CapturingSink();

        service().ask(null, "东鹏饮料的营收怎么样", sink);

        Map<String, Object> error = sink.events.get(sink.events.size() - 1);
        assertThat(error).containsEntry("text", "研究失败：");
        assertThat(repository.stored(1L).get(1).payload()).containsEntry("text", "研究失败：");
    }

    @Test
    void sink_that_throws_on_send_still_persists_answer() {
        engine.result = answer();
        ThrowingSink sink = new ThrowingSink();

        assertThatCode(() -> service().ask(null, "东鹏饮料的营收怎么样", sink))
                .doesNotThrowAnyException();

        assertThat(sink.attempts).isEqualTo(1);
        List<StoredMessage> stored = repository.stored(1L);
        assertThat(stored).extracting(StoredMessage::role).containsExactly("user", "assistant");
        assertThat(stored.get(1).payload()).containsKey("raw");
        assertThat(repository.usages).containsKey(1L);
    }

    @Test
    void usage_is_persisted_after_done() {
        engine.result = answer();
        CapturingSink sink = new CapturingSink();

        service().ask(null, "东鹏饮料的营收怎么样", sink);

        Map<String, Object> usage = repository.usages.get(1L);
        assertThat(usage)
                .containsEntry("budget_tokens", 55_000)
                .containsEntry("hard_tokens", 80_000)
                .containsKeys("tokens", "percent", "ratio", "level");
        Map<String, Object> done = sink.events.get(sink.events.size() - 1);
        assertThat(done.get("usage")).isEqualTo(usage);
    }

    @Test
    void title_truncates_by_code_points() {
        assertThat(AskService.buildTitle(null, "问".repeat(31)))
                .isEqualTo("问".repeat(30) + "…");
        // 增补平面字符在 UTF-16 里占两个单元，Python 的 len() 按一个算
        assertThat(AskService.buildTitle("东鹏饮料", "😀".repeat(31)))
                .isEqualTo("东鹏饮料 · " + "😀".repeat(30) + "…");
    }

    @Test
    void market_question_leaves_the_current_conversation_untouched() {
        long current = seed("贵州茅台 · 上一轮", "600519", "贵州茅台");
        repository.append(current, "user", new LinkedHashMap<>(Map.of("text", "旧问题")));
        List<StoredMessage> before = List.copyOf(repository.stored(current));
        CapturingSink sink = new CapturingSink();

        service().ask(current, "大盘走势怎么样", sink);

        assertThat(types(sink)).containsExactly("warning");
        assertThat(repository.conversations).hasSize(1);
        assertThat(repository.stored(current)).isEqualTo(before);
        assertThat(repository.usages).isEmpty();
        assertThat(engine.calls).isZero();
    }

    @Test
    void run_write_failure_leaves_run_null_and_still_answers() {
        runStore.fail = true;
        engine.result = answer();
        CapturingSink sink = new CapturingSink();

        service().ask(null, "东鹏饮料的营收怎么样", sink);

        @SuppressWarnings("unchecked")
        Map<String, Object> done = sink.events.get(sink.events.size() - 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) done.get("message");
        assertThat(message).containsEntry("run", null);
        assertThat(types(sink)).contains("done");
    }

    private AskService service() {
        return new AskService(repository, engine, router, meter, runStore, clock);
    }

    private long seed(String title, String code, String name) {
        return repository.create(title, code, name);
    }

    private static List<Object> types(CapturingSink sink) {
        return sink.events.stream().map(event -> event.get("type")).toList();
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

    private static final class CapturingSink implements EventSink {
        private final List<Map<String, Object>> events = new ArrayList<>();
        private int completes;

        @Override
        public void send(Map<String, Object> event) {
            events.add(event);
        }

        @Override
        public void complete() {
            completes++;
        }
    }

    private static final class ThrowingSink implements EventSink {
        private int attempts;

        @Override
        public void send(Map<String, Object> event) {
            attempts++;
            throw new IllegalStateException("client disconnected");
        }

        @Override
        public void complete() {
            throw new IllegalStateException("client disconnected");
        }
    }

    private static final class FakeEngine implements AssistantEngine {
        private AskResult result;
        private RuntimeException failure;
        private List<String> deltas = List.of();
        private long seenConversationId;
        private String seenCode;
        private String seenName;
        private String seenQuestion;
        private int calls;

        @Override
        public AskResult ask(
                long conversationId,
                String code,
                String name,
                String question,
                StreamListener listener) {
            calls++;
            seenConversationId = conversationId;
            seenCode = code;
            seenName = name;
            seenQuestion = question;
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

    private static final class FakeRunStore implements RunStore {
        private final List<Map<String, Object>> written = new ArrayList<>();
        private boolean fail;

        @Override
        public RunSummary write(Map<String, Object> payload) {
            if (fail) {
                throw new IllegalStateException("disk full");
            }
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

    private static final class FakeDirectory implements CompanyDirectory {
        private final List<StockRef> stocks;

        FakeDirectory(List<StockRef> stocks) {
            this.stocks = stocks;
        }

        @Override
        public Optional<StockRef> byCode(String code) {
            return stocks.stream().filter(stock -> stock.code().equals(code)).findFirst();
        }

        @Override
        public Optional<StockRef> byName(String name) {
            return stocks.stream().filter(stock -> stock.name().equals(name)).findFirst();
        }

        @Override
        public List<StockRef> all() {
            return stocks;
        }
    }

    private static final class FakeRepository implements ConversationRepository {
        private final Map<Long, Conversation> conversations = new LinkedHashMap<>();
        private final Map<Long, List<StoredMessage>> byConversation = new LinkedHashMap<>();
        private final Map<Long, Map<String, Object>> usages = new LinkedHashMap<>();
        private long nextId = 1;

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
            byConversation.put(id, new ArrayList<>());
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
            return byConversation.getOrDefault(id, List.of());
        }

        private List<StoredMessage> stored(long id) {
            return messages(id);
        }

        @Override
        public int append(long conversationId, String role, Map<String, Object> payload) {
            List<StoredMessage> messages = byConversation.get(conversationId);
            messages.add(new StoredMessage(role, payload));
            return messages.size();
        }

        @Override
        public void updateUsage(long conversationId, Map<String, Object> usage) {
            usages.put(conversationId, usage);
        }

        @Override
        public void delete(long id) {
            conversations.remove(id);
            byConversation.remove(id);
        }
    }
}
