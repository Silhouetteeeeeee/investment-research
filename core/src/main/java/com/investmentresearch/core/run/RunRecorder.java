package com.investmentresearch.core.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Port of Python's run record builder: {@code assistant/agent.py:778-804}. */
public final class RunRecorder {
    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    private final Clock clock;
    private final String model;
    private final int maxRounds;
    private final Map<String, Object> toolSchemas;

    private final Map<String, Object> payload = new LinkedHashMap<>();
    private final List<Map<String, Object>> stages = new ArrayList<>();
    private Instant startedAt;

    public RunRecorder(Clock clock, String model, int maxRounds, Map<String, Object> toolSchemas) {
        this.clock = clock;
        this.model = model;
        this.maxRounds = maxRounds;
        this.toolSchemas = toolSchemas == null ? Map.of() : toolSchemas;
    }

    public void start(long conversationId, String question) {
        Instant now = clock.instant();
        startedAt = now;
        payload.clear();
        stages.clear();
        payload.put("version", 1);
        payload.put("run_id", RUN_ID_TIME.format(local(now)) + "-" + hex8());
        payload.put("conversation_id", conversationId);
        payload.put("question", question);
        payload.put("model", model);
        payload.put("started_at", TIMESTAMP.format(local(now)));
        payload.put("finished_at", null);
        payload.put("total_ms", 0L);
        payload.put("rounds", 0);
        payload.put("max_rounds", maxRounds);
        payload.put("stop_reason", "error");
        payload.put("error", null);
        payload.put("issues", new ArrayList<>());
        payload.put("stages", stages);
        payload.put("prompts", new ArrayList<>());
        payload.put("skills", new ArrayList<>());
        payload.put("tool_schemas", toolSchemas);
        payload.put("calls", new ArrayList<>());
    }

    public void modelStage(
            int round,
            long durationMs,
            Integer ttftMs,
            Integer promptTokens,
            Integer completionTokens,
            String finishReason,
            String usageSource,
            boolean forced) {
        Map<String, Object> stage = stage("model", durationMs, round);
        stage.put("forced", forced);
        stage.put("ttft_ms", ttftMs);
        stage.put("prompt_tokens", promptTokens);
        stage.put("completion_tokens", completionTokens);
        stage.put("finish_reason", finishReason);
        stage.put("usage_source", usageSource);
    }

    public void toolStage(
            int round,
            String name,
            Map<String, Object> arguments,
            long durationMs,
            boolean ok,
            boolean cached,
            String error,
            String company,
            String asOf,
            List<String> sources,
            int dataChars) {
        Map<String, Object> stage = stage("tool", durationMs, round);
        stage.put("name", name);
        stage.put("arguments", arguments);
        stage.put("ok", ok);
        stage.put("cached", cached);
        stage.put("error", error);
        stage.put("company", company);
        stage.put("as_of", asOf);
        stage.put("sources", sources == null ? List.of() : sources);
        stage.put("data_chars", dataChars);
    }

    public void finish(String stopReason, String error) {
        requireStarted();
        Instant now = clock.instant();
        payload.put("stop_reason", stopReason);
        if (error != null) {
            payload.put("error", error);
        }
        payload.put("finished_at", TIMESTAMP.format(local(now)));
        payload.put("total_ms", (int) Math.max(0L, Duration.between(startedAt, now).toMillis()));
    }

    public Map<String, Object> payload() {
        return payload;
    }

    /** 调用方在阶段结束时登记；{@code start_ms} 由结束时刻和耗时倒推。 */
    private Map<String, Object> stage(String kind, long durationMs, int round) {
        requireStarted();
        long endMs = Math.max(0L, Duration.between(startedAt, clock.instant()).toMillis());
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("kind", kind);
        stage.put("start_ms", (int) Math.max(0L, endMs - durationMs));
        stage.put("duration_ms", (int) durationMs);
        stage.put("round", round);
        stages.add(stage);
        payload.put("rounds", Math.max((Integer) payload.get("rounds"), round));
        return stage;
    }

    private void requireStarted() {
        if (startedAt == null) {
            throw new IllegalStateException("start() must be called before recording stages");
        }
    }

    private LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone());
    }

    private static String hex8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
