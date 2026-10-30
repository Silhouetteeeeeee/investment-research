package com.investmentresearch.app.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Writes the frontend's frame shape: {@code data: <json>\n\n}. */
public class SseWriter {
    // The frontend decodes UTF-8; escaping Chinese would still parse but bloats every frame.
    private static final ObjectMapper JSON =
            JsonMapper.builder().disable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    private final SseEmitter emitter;

    public SseWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public void send(Map<String, Object> event) throws IOException {
        // SseEmitter.send(Object) would prepend a second "data:"; frame() already frames.
        emitter.send(Set.of(new DataWithMediaType(frame(event), MediaType.TEXT_EVENT_STREAM)));
    }

    public void complete() {
        emitter.complete();
    }

    public static String frame(Map<String, Object> event) {
        try {
            return "data: " + JSON.writeValueAsString(event) + "\n\n";
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("SSE event is not serializable", error);
        }
    }
}
