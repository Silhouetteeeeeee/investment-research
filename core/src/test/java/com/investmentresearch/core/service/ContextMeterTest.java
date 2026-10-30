package com.investmentresearch.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.port.ConversationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContextMeterTest {

    private final FakeRepository repository = new FakeRepository();
    private final ContextMeter meter = new ContextMeter(repository);

    @Test
    void usage_reports_the_frontend_ring_shape() {
        Map<String, Object> usage = meter.usage(1, 0);

        assertThat(usage)
                .containsEntry("tokens", 0)
                .containsEntry("budget_tokens", 55_000)
                .containsEntry("hard_tokens", 80_000)
                .containsEntry("percent", 0.0)
                .containsEntry("ratio", 0.0)
                .containsEntry("level", "normal");
    }

    @Test
    void estimate_counts_cjk_at_point_seven_and_the_rest_at_point_three() {
        assertThat(ContextMeter.estimateTokens("")).isZero();
        assertThat(ContextMeter.estimateTokens("中文")).isEqualTo(2);
        assertThat(ContextMeter.estimateTokens("abcd")).isEqualTo(2);
        assertThat(ContextMeter.estimateTokens("中文abcd")).isEqualTo(3);
    }

    @Test
    void stored_messages_and_the_draft_are_added_together() {
        repository.messages.add(new StoredMessage("user", Map.of("text", "中文中文中文中文中")));

        Map<String, Object> usage = meter.usage(1, 1_000);

        // 9 个中文字符 → int(6.3) + 1 = 7
        assertThat(usage).containsEntry("tokens", 1_007);
        assertThat(usage).containsEntry("level", "normal");
    }

    @Test
    void raw_tool_messages_from_the_assistant_turn_are_counted() {
        Map<String, Object> function =
                Map.of("name", "get_finance", "arguments", "{}");
        Map<String, Object> toolMessage =
                Map.of(
                        "role", "tool",
                        "content", "中文中文",
                        "tool_calls", List.of(Map.of("function", function)));
        Map<String, Object> assistant =
                Map.of("text", "回答", "raw", List.of(toolMessage));
        repository.messages.add(new StoredMessage("assistant", assistant));

        // 回答 2 + 工具内容 4 = 6 个中文字符；get_finance 与 {} 共 13 个其他字符
        assertThat((Integer) meter.usage(1, 0).get("tokens")).isEqualTo(9);
    }

    @Test
    void eighty_percent_is_warn_and_full_budget_is_over() {
        assertThat(meter.usage(1, 44_000)).containsEntry("level", "warn");
        // 80% 是 44000 tokens，少一个仍属正常
        assertThat(meter.usage(1, 43_999)).containsEntry("level", "normal");
        assertThat(meter.usage(1, 55_000)).containsEntry("level", "over");
    }

    @Test
    void over_budget_clamps_ratio_but_percent_stays_truthful() {
        Map<String, Object> usage = meter.usage(1, 110_000);

        assertThat(usage).containsEntry("percent", 200.0);
        assertThat(usage).containsEntry("ratio", 1.0);
        assertThat(usage).containsEntry("level", "over");
    }

    @Test
    void negative_draft_tokens_are_ignored() {
        assertThat(meter.usage(1, -10)).containsEntry("tokens", 0);
    }

    private static final class FakeRepository implements ConversationRepository {
        private final List<StoredMessage> messages = new ArrayList<>();

        @Override
        public long create(String title, String primaryCode, String primaryName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Conversation> find(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Conversation> recent(String query, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredMessage> messages(long id) {
            return messages;
        }

        @Override
        public int append(long conversationId, String role, Map<String, Object> payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateUsage(long conversationId, Map<String, Object> usage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(long id) {
            throw new UnsupportedOperationException();
        }
    }
}
