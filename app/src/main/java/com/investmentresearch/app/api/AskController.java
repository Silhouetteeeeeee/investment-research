package com.investmentresearch.app.api;

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

    AskController(@Qualifier("askExecutor") Executor executor) {
        this.executor = executor;
    }

    @PostMapping(value = "/api/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody AskRequest request, HttpServletResponse response) {
        String question = request == null || request.question() == null ? "" : request.question();
        if (question.strip().isEmpty()) {
            // Must be a plain 400, not a stream that errors right after opening.
            throw new EmptyQuestionException();
        }

        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        // Without this nginx buffers the whole stream and nothing reaches the page until done.
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        // This runs off the request thread and inherits no security context; Phase 1 must
        // capture ids before this hop rather than reading SecurityContextHolder here.
        executor.execute(() -> streamPlaceholder(emitter));
        return emitter;
    }

    private static void streamPlaceholder(SseEmitter emitter) {
        SseWriter writer = new SseWriter(emitter);
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "error");
            event.put("text", "助手尚未接入模型");
            writer.send(event);
        } catch (Throwable failure) {
            // Anything escaping this task would leave the client hanging until the
            // 10-minute timeout, so end the stream with an error frame instead.
            try {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "error");
                event.put("text", "服务出错，请重试");
                writer.send(event);
            } catch (Throwable clientGone) {
                // Disconnected client: stop silently, never log the token or the question.
            }
        } finally {
            writer.complete();
        }
    }

    @ExceptionHandler(EmptyQuestionException.class)
    ResponseEntity<Map<String, String>> emptyQuestion() {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "empty question"));
    }

    record AskRequest(String question) {}

    private static final class EmptyQuestionException extends RuntimeException {}
}
