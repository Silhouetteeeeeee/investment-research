package com.investmentresearch.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SseWriterTest {
    @Test
    void frame_has_data_prefix_and_blank_line_separator() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "error");

        assertThat(SseWriter.frame(event)).isEqualTo("data: {\"type\":\"error\"}\n\n");
    }

    @Test
    void frame_keeps_chinese_unescaped() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("text", "助手尚未接入模型");

        assertThat(SseWriter.frame(event)).contains("助手尚未接入模型").doesNotContain("\\u");
    }
}
