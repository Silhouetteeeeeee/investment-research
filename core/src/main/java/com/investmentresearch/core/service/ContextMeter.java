package com.investmentresearch.core.service;

import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.port.ConversationRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Context-ring numbers for the page. The estimate is the character heuristic from
 * {@code assistant/agent.py:estimate_tokens}; Python also counts the system prompt and tool
 * schemas, but this layer only sees stored messages, so those fixed costs are omitted.
 */
public final class ContextMeter {
    static final int BUDGET_TOKENS = 55_000;
    static final int HARD_TOKENS = 80_000;

    private final ConversationRepository repository;

    public ContextMeter(ConversationRepository repository) {
        this.repository = repository;
    }

    /** {@code assistant/agent.py:277-301}: {@code draftTokens} is the streamed answer so far. */
    public Map<String, Object> usage(long conversationId, int draftTokens) {
        int tokens = storedTokens(conversationId) + Math.max(0, draftTokens);
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

    private int storedTokens(long conversationId) {
        int tokens = 0;
        List<StoredMessage> messages = repository.messages(conversationId);
        for (StoredMessage message : messages) {
            tokens += estimateTokens(messageText(message.payload()));
        }
        return tokens;
    }

    /** Mirrors {@code assistant/agent.py:_message_text}: content plus tool names and arguments. */
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
                if (!(item instanceof Map<?, ?> message)) {
                    continue;
                }
                append(text, message.get("content"));
                Object calls = message.get("tool_calls");
                if (calls instanceof List<?> list) {
                    for (Object call : list) {
                        if (call instanceof Map<?, ?> entry
                                && entry.get("function") instanceof Map<?, ?> function) {
                            append(text, function.get("name"));
                            Object arguments = function.get("arguments");
                            if (arguments != null) {
                                append(text, arguments instanceof String value
                                        ? value
                                        : String.valueOf(arguments));
                            }
                        }
                    }
                }
            }
        }
        return text.toString();
    }

    private static void append(StringBuilder text, Object value) {
        if (value instanceof String string) {
            text.append(string);
        }
    }

    /** {@code assistant/agent.py:estimate_tokens}: CJK 0.7, the rest 0.3, +1, deliberately high. */
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
