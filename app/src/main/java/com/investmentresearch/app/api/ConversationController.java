package com.investmentresearch.app.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.port.ConversationRepository;
import com.investmentresearch.core.port.RunStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code assistant/api.py:249-375}: read and delete conversations, runs, evidence. */
@RestController
public class ConversationController {
    private static final int RECENT_LIMIT = 50;
    // runlog 的 id 规则：字母数字与 _-，不接受路径分隔符与 ..（assistant/runlog.py:23）
    private static final Pattern RUN_ID = Pattern.compile("[0-9A-Za-z_-]{8,64}");

    private final ConversationRepository repository;
    // 运行原文与 Agent 会话的 Bean 要等 /api/ask 接入（Task 20）才出现在上下文里；
    // 缺失时按「不存在」处理，接口照常可用，避免启动被未完成的一步拖死。
    private final ObjectProvider<RunStore> runStores;
    private final ObjectProvider<AssistantEngine> engines;
    private final ObjectMapper mapper;

    ConversationController(
            ConversationRepository repository,
            ObjectProvider<RunStore> runStores,
            ObjectProvider<AssistantEngine> engines,
            ObjectMapper mapper) {
        this.repository = repository;
        this.runStores = runStores;
        this.engines = engines;
        this.mapper = mapper;
    }

    @GetMapping("/api/conversations")
    public List<Map<String, Object>> list(
            @RequestParam(name = "query", defaultValue = "") String query) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Conversation item : repository.recent(query == null ? "" : query, RECENT_LIMIT)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.id());
            row.put("title", item.title());
            row.put("name", nameOf(item));
            row.put("code", codeOf(item));
            row.put("count", item.messageCount());
            row.put("updated", item.updatedAt());
            items.add(row);
        }
        return items;
    }

    @GetMapping("/api/conversations/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable("id") long id) {
        Optional<Conversation> found = repository.find(id);
        if (found.isEmpty()) {
            return notFound();
        }
        Conversation meta = found.get();
        List<Map<String, Object>> messages = new ArrayList<>();
        for (StoredMessage message : repository.messages(id)) {
            messages.add(cleanMessage(message));
        }
        Map<String, Object> usage = meta.usage();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("title", meta.title());
        body.put("code", codeOf(meta));
        body.put("name", nameOf(meta));
        body.put("messages", messages);
        // Python 打开历史时重算 agent 占用；Java 直接回放上一次落库的 usage（Task 18）
        body.put("usage", usage == null || usage.isEmpty() ? null : usage);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/conversations/{id}/evidence")
    public ResponseEntity<Map<String, Object>> evidence(
            @PathVariable("id") long id,
            @RequestParam(name = "source_id", required = false) String sourceId) {
        String needle = sourceId == null ? "" : sourceId.strip();
        if (needle.isEmpty()) {
            return jsonError(400, "missing source_id");
        }
        if (repository.find(id).isEmpty()) {
            return notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_id", needle);
        body.put("evidence", evidenceFor(id, needle));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/conversations/{id}/runs/{runId}")
    public ResponseEntity<Map<String, Object>> runDetail(
            @PathVariable("id") long id, @PathVariable("runId") String runId) {
        if (repository.find(id).isEmpty()) {
            return notFound();
        }
        // 先校验 id 再看引用：路径穿越在碰存储之前就被挡掉（assistant/api.py:345-361）
        if (!validRunId(runId) || !referenced(id, runId)) {
            return notFound();
        }
        RunStore store = runStores.getIfAvailable();
        if (store == null) {
            return notFound();
        }
        Optional<Map<String, Object>> record = store.read(runId);
        if (record.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(record.get());
    }

    @PostMapping("/api/conversations/{id}/delete")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        List<String> runIds = new ArrayList<>();
        for (StoredMessage message : repository.messages(id)) {
            Object run = message.payload().get("run");
            if (run instanceof Map<?, ?> record && record.get("id") != null) {
                String runId = String.valueOf(record.get("id"));
                if (!runId.isEmpty()) {
                    runIds.add(runId);
                }
            }
        }
        // 顺序照 Python：消息 → 运行原文 → Agent 状态（assistant/api.py:364-375）
        repository.delete(id);
        RunStore store = runStores.getIfAvailable();
        if (store != null) {
            store.deleteAll(runIds);
        }
        AssistantEngine engine = engines.getIfAvailable();
        if (engine != null) {
            engine.deleteSession(id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return body;
    }

    /** {@code assistant/api.py:293-308}: raw 不外泄，只留页面会读的字段。 */
    private static Map<String, Object> cleanMessage(StoredMessage message) {
        Map<String, Object> payload = message.payload();
        String role = roleOf(message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", role);
        Object text = payload.get("text");
        out.put("text", text == null ? "" : text);
        Object run = payload.get("run");
        if (run instanceof Map<?, ?>) {
            out.put("run", run);
        }
        if ("assistant".equals(role)) {
            out.put("sources", listOrEmpty(payload.get("sources")));
            Object trace = payload.get("trace");
            if (trace instanceof List<?> steps) {
                out.put("trace", steps);
                out.put("trace_count", steps.size());
            } else {
                out.put("trace", List.of());
                out.put("trace_count", traceCount(trace));
            }
            out.put("issues", listOrEmpty(payload.get("issues")));
        }
        return out;
    }

    /**
     * {@code assistant/api.py:265-290}. Python 的 raw 是 {@code {role:"tool", content:"<json>"}}
     * 的历史消息；Java 落库的 raw 已经是工具结果本身，两种形态都认，统一按 sources 命中。
     */
    private Map<String, Object> evidenceFor(long id, String sourceId) {
        Map<String, Object> found = null;
        for (StoredMessage message : repository.messages(id)) {
            if (!"assistant".equals(roleOf(message))) {
                continue;
            }
            if (!(message.payload().get("raw") instanceof List<?> items)) {
                continue;
            }
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry) {
                    Map<String, Object> content = toolContent(entry);
                    if (content != null && hasSource(content, sourceId)) {
                        found = content;
                    }
                }
            }
        }
        return found;
    }

    private Map<String, Object> toolContent(Map<?, ?> entry) {
        if ("tool".equals(entry.get("role"))) {
            Object content = entry.get("content");
            if (content instanceof Map<?, ?> map) {
                return toStringMap(map);
            }
            if (content instanceof String text) {
                try {
                    return mapper.readValue(
                            text, new TypeReference<Map<String, Object>>() {});
                } catch (IOException error) {
                    return null;
                }
            }
            return null;
        }
        // Java 的 raw 项没有 role，本身就是 {"ok", "sources", "data", ...}
        if (!entry.containsKey("role") && entry.containsKey("sources")) {
            return toStringMap(entry);
        }
        return null;
    }

    private static boolean hasSource(Map<String, Object> content, String sourceId) {
        if (!(content.get("sources") instanceof List<?> sources)) {
            return false;
        }
        for (Object source : sources) {
            if (source instanceof Map<?, ?> item && sourceId.equals(string(item.get("id")))) {
                return true;
            }
        }
        return false;
    }

    private boolean referenced(long id, String runId) {
        for (StoredMessage message : repository.messages(id)) {
            Object run = message.payload().get("run");
            if (run instanceof Map<?, ?> record && runId.equals(string(record.get("id")))) {
                return true;
            }
        }
        return false;
    }

    /** Python 先合并 {@code {"role": row_role, **payload}}，payload 里的 role 优先。 */
    private static String roleOf(StoredMessage message) {
        Map<String, Object> payload = message.payload();
        return payload.containsKey("role")
                ? string(payload.get("role"))
                : message.role() == null ? "" : message.role();
    }

    private static String nameOf(Conversation item) {
        if (item.primaryName() != null && !item.primaryName().isEmpty()) {
            return item.primaryName();
        }
        return codeOf(item);
    }

    private static String codeOf(Conversation item) {
        return item.primaryCode() == null ? "" : item.primaryCode();
    }

    private static Object listOrEmpty(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static int traceCount(Object trace) {
        if (trace instanceof Number number) {
            return number.intValue();
        }
        if (trace == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(trace).trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static boolean validRunId(String runId) {
        return runId != null && RUN_ID.matcher(runId).matches() && !runId.contains("..");
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> toStringMap(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return jsonError(404, "not found");
    }

    private static ResponseEntity<Map<String, Object>> jsonError(int status, String error) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }
}
