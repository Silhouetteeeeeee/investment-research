package com.investmentresearch.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.port.ConversationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContextMeterTest {

    private final FakeRepository repository = new FakeRepository();
    private final ContextMeter meter = new ContextMeter(repository);

    @Test
    void usage_reports_the_frontend_ring_shape_including_the_fixed_cost() {
        Map<String, Object> usage = meter.usage(1, 0);

        // 空会话也含 system prompt + schema 的固定成本，以及 4*(0+2) 的消息开销
        assertThat(usage)
                .containsEntry("tokens", ContextMeter.fixedTokens() + 8)
                .containsEntry("budget_tokens", 55_000)
                .containsEntry("hard_tokens", 80_000)
                .containsEntry("level", "normal")
                .containsKeys("percent", "ratio");
        assertThat((Double) usage.get("percent")).isGreaterThan(0.0);
        assertThat(ContextMeter.fixedTokens()).isGreaterThan(10_000);
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
        String text = "中文中文中文中文中";
        repository.messages.add(new StoredMessage("user", Map.of("text", text)));

        Map<String, Object> usage = meter.usage(1, 1_000);

        int expected =
                ContextMeter.fixedTokens()
                        + ContextMeter.estimateTokens(text)
                        + 4 * (1 + 2)
                        + 1_000;
        assertThat(usage).containsEntry("tokens", expected);
    }

    @Test
    void raw_tool_content_is_counted_for_the_engine_shape() {
        Map<String, Object> rawTool =
                Map.of(
                        "role", "tool",
                        "tool_call_id", "call-1",
                        "content", "中文中文");
        repository.messages.add(
                new StoredMessage("assistant", Map.of("text", "回答", "raw", List.of(rawTool))));

        int expected =
                ContextMeter.fixedTokens()
                        + ContextMeter.estimateTokens("回答")
                        + ContextMeter.estimateTokens("中文中文")
                        + 4 * (2 + 2);
        assertThat(meter.usage(1, 0)).containsEntry("tokens", expected);
    }

    @Test
    void tool_result_maps_without_a_content_field_are_still_counted() {
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("ok", true);
        toolResult.put("company", "605499");
        toolResult.put("as_of", "2025-09-30");
        toolResult.put("data", Map.of("revenue", "中文中文中文"));
        toolResult.put("cached", false);
        repository.messages.add(
                new StoredMessage(
                        "assistant", Map.of("text", "回答", "raw", List.of(toolResult))));

        // 没有 content 时整条工具结果被序列化，所以一定高于只有固定成本和消息开销的下限
        assertThat((Integer) meter.usage(1, 0).get("tokens"))
                .isGreaterThan(ContextMeter.fixedTokens() + 4 * (2 + 2));
    }

    @Test
    void eighty_percent_is_warn_and_full_budget_is_over() {
        int base = (Integer) meter.usage(1, 0).get("tokens");

        assertThat(meter.usage(1, 44_000 - base))
                .containsEntry("tokens", 44_000)
                .containsEntry("level", "warn");
        // 80% 是 44000 tokens，少一个仍属正常
        assertThat(meter.usage(1, 43_999 - base))
                .containsEntry("tokens", 43_999)
                .containsEntry("level", "normal");
        assertThat(meter.usage(1, 55_000 - base))
                .containsEntry("tokens", 55_000)
                .containsEntry("level", "over");
    }

    @Test
    void over_budget_clamps_ratio_but_percent_stays_truthful() {
        int base = (Integer) meter.usage(1, 0).get("tokens");

        Map<String, Object> usage = meter.usage(1, 110_000 - base);

        assertThat(usage)
                .containsEntry("tokens", 110_000)
                .containsEntry("percent", 200.0)
                .containsEntry("ratio", 1.0)
                .containsEntry("level", "over");
    }

    @Test
    void negative_draft_tokens_are_ignored() {
        int base = (Integer) meter.usage(1, 0).get("tokens");

        assertThat(meter.usage(1, -10)).containsEntry("tokens", base);
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
