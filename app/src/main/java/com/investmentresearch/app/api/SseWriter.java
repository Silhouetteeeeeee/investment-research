package com.investmentresearch.app.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investmentresearch.core.service.EventSink;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Writes the frontend's frame shape: {@code data: <json>\n\n}. */
public class SseWriter implements EventSink {
    // 测试与无参场景的默认序列化器；运行时用 Boot 的 ObjectMapper，保证与 JSON 接口同一套配置。
    private static final ObjectMapper JSON =
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    private final SseEmitter emitter;
    private final ObjectMapper json;
    private volatile boolean closed;

    public SseWriter(SseEmitter emitter, ObjectMapper json) {
        this.emitter = emitter;
        this.json = json;
    }

    @Override
    public void send(Map<String, Object> event) {
        if (closed) {
            return;
        }
        try {
            // SseEmitter.send(Object) would prepend a second "data:"; frame() already frames.
            emitter.send(
                    Set.of(
                            new DataWithMediaType(
                                    frame(json, event), MediaType.TEXT_EVENT_STREAM)));
        } catch (IOException | IllegalStateException clientGone) {
            // 客户端断开不能中断回答落库（assistant/api.py:220-225 的外层兜底）；
            // 之后的 send 直接返回，AskService 仍会走完持久化。
            closed = true;
        }
    }

    @Override
    public void complete() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            emitter.complete();
        } catch (IllegalStateException alreadyCompleted) {
            // 已结束或连接已断开，不需要再通知容器。
        }
    }

    public static String frame(Map<String, Object> event) {
        return frame(JSON, event);
    }

    private static String frame(ObjectMapper mapper, Map<String, Object> event) {
        try {
            return "data: " + mapper.writeValueAsString(event) + "\n\n";
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("SSE event is not serializable", error);
        }
    }
}
