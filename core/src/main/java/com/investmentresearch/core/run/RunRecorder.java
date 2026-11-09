package com.investmentresearch.core.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Set<String> promptHashes = new HashSet<>();
    private final StringBuilder thinking = new StringBuilder();
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
        promptHashes.clear();
        thinking.setLength(0);
        payload.put("version", 1);
        payload.put("run_id", RUN_ID_TIME.format(local(now)) + "-" + hex8());
        payload.put("conversation_id", conversationId);
        payload.put("question", question);
        payload.put("model", model);
        payload.put("started_at", TIMESTAMP.format(local(now)));
        payload.put("finished_at", null);
        payload.put("total_ms", 0);
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
        payload.put("thinking", "");
    }

    /** 每次模型调用都会带系统提示；同一份内容只留一次，避免 14 轮重复几十 KB。 */
    public void recordPrompt(String text) {
        requireStarted();
        String prompt = text == null ? "" : text;
        String hash = sha256(prompt).substring(0, 12);
        if (!promptHashes.add(hash)) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("hash", hash);
        item.put("chars", prompt.length());
        item.put("text", prompt);
        prompts().add(item);
    }

    /** 只记本轮新增的消息；AgentScope 每轮都会把完整历史再发一遍。 */
    public void recordCall(int round, List<Map<String, Object>> added) {
        requireStarted();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("round", round);
        item.put("added", added == null ? List.of() : added);
        calls().add(item);
    }

    public void recordThinking(String text) {
        requireStarted();
        if (text != null) {
            thinking.append(text);
        }
        payload.put("thinking", thinking.toString());
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
        // 拿不到真实用量就得标 estimate，否则页面会把估算值当成 API 统计
        stage.put("usage_source", usageSource == null ? "estimate" : usageSource);
        // 轮数只数真实模型轮：Python 强制作答那一轮不计入（agent.py:742,756-758）
        if (!forced) {
            payload.put("rounds", Math.max((Integer) payload.get("rounds"), round));
        }
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
        return stage;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> prompts() {
        return (List<Map<String, Object>>) payload.get("prompts");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> calls() {
        return (List<Map<String, Object>>) payload.get("calls");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", error);
        }
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
