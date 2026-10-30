package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseWriterTest {
    private static final ObjectMapper JSON =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void frame_has_data_prefix_and_blank_line_separator() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "error");

        assertThat(SseWriter.frame(event)).isEqualTo("data: {\"type\":\"error\"}\n\n");
    }

    @Test
    void frame_keeps_chinese_unescaped() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("text", "东鹏饮料营收稳健");

        assertThat(SseWriter.frame(event)).contains("东鹏饮料营收稳健").doesNotContain("\\u");
    }

    @Test
    void frame_serialises_jsr310_types_as_iso_strings() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", Instant.parse("2026-10-30T01:02:03Z"));
        event.put("local", LocalDateTime.of(2026, 10, 30, 1, 2, 3));

        assertThat(SseWriter.frame(event))
                .contains("2026-10-30T01:02:03Z")
                .contains("2026-10-30T01:02:03");
    }

    @Test
    void send_swallows_client_disconnect_and_ignores_later_sends() {
        class BrokenEmitter extends SseEmitter {
            private int attempts;

            @Override
            public void send(Set<DataWithMediaType> data) throws IOException {
                attempts++;
                throw new IOException("client gone");
            }
        }
        BrokenEmitter emitter = new BrokenEmitter();
        SseWriter writer = new SseWriter(emitter, JSON);

        assertThatCode(() -> writer.send(Map.of("type", "text", "delta", "第一段")))
                .doesNotThrowAnyException();
        assertThatCode(() -> writer.send(Map.of("type", "text", "delta", "第二段")))
                .doesNotThrowAnyException();
        assertThatCode(writer::complete).doesNotThrowAnyException();

        assertThat(emitter.attempts).isEqualTo(1);
    }
}
