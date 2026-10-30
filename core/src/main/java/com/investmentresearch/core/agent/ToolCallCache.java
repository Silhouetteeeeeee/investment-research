package com.investmentresearch.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.investmentresearch.core.domain.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-conversation session cache for identical tool calls. In memory only: a restart re-fetches.
 */
public final class ToolCallCache {

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Map<Long, Map<String, ToolResult>> conversations = new ConcurrentHashMap<>();

    public Optional<ToolResult> get(
            long conversationId, String tool, Map<String, Object> args, String company) {
        Map<String, ToolResult> cached = conversations.get(conversationId);
        if (cached == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cached.get(key(tool, args, company)));
    }

    public void put(
            long conversationId,
            String tool,
            Map<String, Object> args,
            String company,
            ToolResult result) {
        conversations
                .computeIfAbsent(conversationId, id -> new ConcurrentHashMap<>())
                .put(key(tool, args, company), result);
    }

    public void clear(long conversationId) {
        conversations.remove(conversationId);
    }

    private String key(String tool, Map<String, Object> args, String company) {
        return tool + '\u0000' + canonicalArguments(args) + '\u0000' + (company == null ? "" : company);
    }

    /** Mirrors Python's canonical arguments: company is a separate key, the rest sorted. */
    private String canonicalArguments(Map<String, Object> args) {
        Map<String, Object> rest = new TreeMap<>();
        if (args != null) {
            args.forEach(
                    (name, value) -> {
                        if (!"company".equals(name)) {
                            rest.put(name, value);
                        }
                    });
        }
        try {
            return mapper.writeValueAsString(rest);
        } catch (JsonProcessingException error) {
            // shortcut: exotic argument values fall back to toString; upgrade if args leave JSON types.
            return new LinkedHashMap<>(rest).toString();
        }
    }
}
