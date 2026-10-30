package com.investmentresearch.app.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.app.config.AssistantConfig;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.service.AskService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AskController {
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private final Executor executor;
    private final AskService service;
    private final AssistantEngine engine;
    private final ObjectMapper json;

    AskController(
            @Qualifier("askExecutor") Executor executor,
            AskService service,
            AssistantEngine engine,
            ObjectMapper json) {
        this.executor = executor;
        this.service = service;
        this.engine = engine;
        this.json = json;
    }

    @PostMapping(value = "/api/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody AskRequest request, HttpServletResponse response) {
        String question =
                request == null || request.question() == null ? "" : request.question().strip();
        if (question.isEmpty()) {
            // Must be a plain 400, not a stream that errors right after opening.
            throw new EmptyQuestionException();
        }

        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        // Without this nginx buffers the whole stream and nothing reaches the page until done.
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        Long conversationId = conversationId(request.conversationId());
        // This runs off the request thread and inherits no security context; Phase 1 must
        // capture ids before this hop rather than reading SecurityContextHolder here.
        executor.execute(() -> stream(emitter, conversationId, question));
        return emitter;
    }

    private void stream(SseEmitter emitter, Long conversationId, String question) {
        SseWriter writer = new SseWriter(emitter, json);
        try {
            if (engine instanceof AssistantConfig.UnavailableEngine unavailable) {
                // 照 assistant/api.py:220-225：启动失败也回 error 事件，而不是让流空转到超时。
                // reason 只含环境变量名，不含密钥值。
                writer.send(error("助手不可用：" + unavailable.reason()));
                return;
            }
            service.ask(conversationId, question, writer);
        } catch (Throwable failure) {
            // Anything escaping the service would leave the client hanging until the
            // 10-minute timeout, so end the stream with the Python outer-except copy.
            writer.send(error("助手不可用：" + textOf(failure)));
        } finally {
            writer.complete();
        }
    }

    private static Map<String, Object> error(String text) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "error");
        event.put("text", text);
        return event;
    }

    /** Python's {@code f"{error}"}: an exception without a message contributes nothing. */
    private static String textOf(Throwable failure) {
        return failure.getMessage() == null ? "" : failure.getMessage();
    }

    /** Python {@code int(raw_cid) if raw_cid else None}: bad ids fall back to a new conversation. */
    private static Long conversationId(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @ExceptionHandler(EmptyQuestionException.class)
    ResponseEntity<Map<String, String>> emptyQuestion() {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "empty question"));
    }

    record AskRequest(
            @JsonProperty("conversation_id") Object conversationId, String question) {}

    private static final class EmptyQuestionException extends RuntimeException {}
}
