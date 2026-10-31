package com.investmentresearch.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallCacheTest {

    private final ToolCallCache cache = new ToolCallCache();

    @Test
    void cache_is_isolated_by_conversation() {
        cache.put(1, "get_company_profile", Map.of("company", "605499"), "605499", result());

        assertThat(cache.get(1, "get_company_profile", Map.of(), "605499")).isPresent();
        assertThat(cache.get(2, "get_company_profile", Map.of(), "605499")).isEmpty();
    }

    @Test
    void cache_key_ignores_argument_order_and_company_field_but_not_company_key() {
        cache.put(
                1,
                "get_finance",
                Map.of("company", "东鹏饮料", "period", "2026-06-30", "limit", 4),
                "东鹏饮料",
                result());

        assertThat(
                        cache.get(
                                1,
                                "get_finance",
                                Map.of("limit", 4, "period", "2026-06-30", "company", "东鹏饮料"),
                                "东鹏饮料"))
                .isPresent();
        assertThat(cache.get(1, "get_finance", Map.of("period", "2026-06-30", "limit", 4), "000858"))
                .isEmpty();
    }

    @Test
    void cache_hit_is_marked_by_as_cached_with_the_note() {
        cache.put(1, "get_company_profile", Map.of(), "605499", result());

        ToolResult hit =
                cache.get(1, "get_company_profile", Map.of(), "605499").orElseThrow().asCached();

        assertThat(hit.cached()).isTrue();
        assertThat(hit.toMessageJson())
                .contains("\"cached\": true")
                .contains(ToolResult.CACHED_NOTE);
    }

    @Test
    void clear_removes_only_the_given_conversation() {
        cache.put(1, "get_company_profile", Map.of(), "605499", result());
        cache.put(2, "get_company_profile", Map.of(), "605499", result());

        cache.clear(1);

        assertThat(cache.get(1, "get_company_profile", Map.of(), "605499")).isEmpty();
        assertThat(cache.get(2, "get_company_profile", Map.of(), "605499")).isPresent();
    }

    @Test
    void per_conversation_capacity_evicts_the_least_recently_used_entry() {
        ToolCallCache bounded = boundedCache(2, 10, new MutableClock(Instant.parse(NOW)));

        bounded.put(1, "tool_a", Map.of(), "605499", result());
        bounded.put(1, "tool_b", Map.of(), "605499", result());
        assertThat(bounded.get(1, "tool_a", Map.of(), "605499")).isPresent();
        bounded.put(1, "tool_c", Map.of(), "605499", result());

        assertThat(bounded.get(1, "tool_b", Map.of(), "605499")).isEmpty();
        assertThat(bounded.get(1, "tool_a", Map.of(), "605499")).isPresent();
        assertThat(bounded.get(1, "tool_c", Map.of(), "605499")).isPresent();
    }

    @Test
    void conversation_capacity_evicts_the_least_recently_used_conversation() {
        ToolCallCache bounded = boundedCache(10, 2, new MutableClock(Instant.parse(NOW)));

        bounded.put(1, "tool_a", Map.of(), "605499", result());
        bounded.put(2, "tool_a", Map.of(), "605499", result());
        assertThat(bounded.get(1, "tool_a", Map.of(), "605499")).isPresent();
        bounded.put(3, "tool_a", Map.of(), "605499", result());

        assertThat(bounded.get(1, "tool_a", Map.of(), "605499")).isPresent();
        assertThat(bounded.get(2, "tool_a", Map.of(), "605499")).isEmpty();
        assertThat(bounded.get(3, "tool_a", Map.of(), "605499")).isPresent();
    }

    @Test
    void entries_expire_after_ttl() {
        MutableClock clock = new MutableClock(Instant.parse(NOW));
        ToolCallCache bounded = boundedCache(10, 10, clock);

        bounded.put(1, "tool_a", Map.of(), "605499", result());
        clock.advance(Duration.ofMinutes(29));
        assertThat(bounded.get(1, "tool_a", Map.of(), "605499")).isPresent();

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(bounded.get(1, "tool_a", Map.of(), "605499")).isEmpty();
    }

    private static final String NOW = "2026-10-31T00:00:00Z";

    private static ToolCallCache boundedCache(int maxEntries, int maxConversations, Clock clock) {
        return new ToolCallCache(maxEntries, maxConversations, Duration.ofMinutes(30), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static ToolResult result() {
        return ToolResult.ok(
                "605499",
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
