package com.investmentresearch.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.investmentresearch.core.domain.ToolResult;
    import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Per-conversation session cache for identical tool calls. In memory only: a restart re-fetches.
 *
 * <p>Bounded so a long-lived process cannot grow without limit: each conversation keeps at most
 * {@value #DEFAULT_MAX_ENTRIES_PER_CONVERSATION} results (LRU), the process keeps at most
 * {@value #DEFAULT_MAX_CONVERSATIONS} conversations (LRU), and entries expire after 30 minutes.
 */
public final class ToolCallCache {
    static final int DEFAULT_MAX_ENTRIES_PER_CONVERSATION = 200;
    static final int DEFAULT_MAX_CONVERSATIONS = 500;
    static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final int maxEntriesPerConversation;
    private final int maxConversations;
    private final Duration ttl;
    private final Clock clock;
    /** Access-order at both levels, so eviction drops the least recently used entry/conversation. */
    private final Map<Long, Map<String, Entry>> conversations =
            new LinkedHashMap<>(16, 0.75f, true);

    public ToolCallCache() {
        this(
                DEFAULT_MAX_ENTRIES_PER_CONVERSATION,
                DEFAULT_MAX_CONVERSATIONS,
                DEFAULT_TTL,
                Clock.systemUTC());
    }

    ToolCallCache(int maxEntriesPerConversation, int maxConversations, Duration ttl, Clock clock) {
        this.maxEntriesPerConversation = maxEntriesPerConversation;
        this.maxConversations = maxConversations;
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized Optional<ToolResult> get(
            long conversationId, String tool, Map<String, Object> args, String company) {
        Map<String, Entry> cached = conversations.get(conversationId);
        if (cached == null) {
            return Optional.empty();
        }
        String key = key(tool, args, company);
        Entry entry = cached.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.cachedAt().plus(ttl))) {
            cached.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.result());
    }

    public synchronized void put(
            long conversationId,
            String tool,
            Map<String, Object> args,
            String company,
            ToolResult result) {
        Map<String, Entry> cached = conversations.get(conversationId);
        if (cached == null) {
            if (conversations.size() >= maxConversations) {
                Iterator<Long> oldest = conversations.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            cached =
                    new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                            return size() > maxEntriesPerConversation;
                        }
                    };
            conversations.put(conversationId, cached);
        }
        cached.put(key(tool, args, company), new Entry(result, clock.instant()));
    }

    public synchronized void clear(long conversationId) {
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

    private record Entry(ToolResult result, Instant cachedAt) {}
}
