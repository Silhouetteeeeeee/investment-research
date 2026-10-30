package com.investmentresearch.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.port.ConversationRepository;
import com.investmentresearch.core.tool.ToolSchemas;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Context-ring numbers for the page. Mirrors {@code assistant/agent.py:_estimate}: the system
 * prompt and generated tool schemas are a fixed cost, the conversation is estimated from its
 * messages, and each message adds a small overhead. {@code draftTokens} is the streamed answer.
 */
public final class ContextMeter {
    static final int BUDGET_TOKENS = 55_000;
    static final int HARD_TOKENS = 80_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FIXED_TOKENS = computeFixedTokens();

    private final ConversationRepository repository;

    public ContextMeter(ConversationRepository repository) {
        this.repository = repository;
    }

    /** {@code assistant/agent.py:277-301}; thresholds match the frontend's ring. */
    public Map<String, Object> usage(long conversationId, int draftTokens) {
        List<StoredMessage> messages = repository.messages(conversationId);
        int tokens =
                FIXED_TOKENS
                        + messageTokens(messages)
                        + 4 * (messageUnits(messages) + 2)
                        + Math.max(0, draftTokens);
        double share = tokens / (double) BUDGET_TOKENS;
        String level = share >= 1 ? "over" : share >= 0.8 ? "warn" : "normal";

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("tokens", tokens);
        usage.put("budget_tokens", BUDGET_TOKENS);
        usage.put("hard_tokens", HARD_TOKENS);
        usage.put("percent", Math.round(share * 1000.0) / 10.0);
        usage.put("ratio", Math.round(Math.min(share, 1.0) * 10000.0) / 10000.0);
        usage.put("level", level);
        return usage;
    }

    static int fixedTokens() {
        return FIXED_TOKENS;
    }

    /** The same resources the engine's prompt and registry consume, so a prompt edit moves this. */
    private static int computeFixedTokens() {
        // 解析一次，确保运行中的引擎和这里读的是同一份 schema 资源
        ToolSchemas.loadDefault();
        return estimateTokens(resource("/prompts/system.md"))
                + estimateTokens(resource("/tool-schemas.json"));
    }

    private static String resource(String path) {
        try (InputStream stream = ContextMeter.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("缺少 classpath 资源：" + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("读取 classpath 资源失败：" + path, error);
        }
    }

    private static int messageTokens(List<StoredMessage> messages) {
        int tokens = 0;
        for (StoredMessage message : messages) {
            tokens += estimateTokens(messageText(message.payload()));
        }
        return tokens;
    }

    /** Python's {@code len(self._history)}: each stored message plus each replayable raw message. */
    private static int messageUnits(List<StoredMessage> messages) {
        int units = messages.size();
        for (StoredMessage message : messages) {
            Object raw = message.payload() == null ? null : message.payload().get("raw");
            if (raw instanceof List<?> items) {
                units += items.size();
            }
        }
        return units;
    }

    /** Mirrors {@code assistant/agent.py:_message_text}; raw items may be text or tool-result maps. */
    private static String messageText(Map<String, Object> payload) {
        if (payload == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        append(text, payload.get("text"));
        append(text, payload.get("content"));
        Object raw = payload.get("raw");
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                // 新 engine 的 raw 是 {role, tool_call_id, content}；旧的是 ToolResult map，整体序列化
                if (item instanceof Map<?, ?> message
                        && message.get("content") instanceof String content) {
                    text.append(content);
                } else if (item != null) {
                    text.append(serialize(item));
                }
            }
        }
        return text.toString();
    }

    private static String serialize(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            return String.valueOf(value);
        }
    }

    private static void append(StringBuilder text, Object value) {
        if (value instanceof String string) {
            text.append(string);
        }
    }

    /** {@code assistant/agent.py:1106-1116}: CJK 0.7, the rest 0.3, +1, deliberately high. */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int length = text.codePointCount(0, text.length());
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            if ((code >= 0x3400 && code <= 0x9FFF) || (code >= 0xF900 && code <= 0xFAFF)) {
                cjk++;
            }
            i += Character.charCount(code);
        }
        int other = length - cjk;
        return (int) (cjk * 0.7 + other * 0.3) + 1;
    }
}
