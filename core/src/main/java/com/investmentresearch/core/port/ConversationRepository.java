package com.investmentresearch.core.port;

import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.StoredMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence port for conversations and their replayable messages. */
public interface ConversationRepository {
    long create(String title, String primaryCode, String primaryName);

    Optional<Conversation> find(long id);

    List<Conversation> recent(String query, int limit);

    List<StoredMessage> messages(long id);

    int append(long conversationId, String role, Map<String, Object> payload);

    void updateUsage(long conversationId, Map<String, Object> usage);

    void delete(long id);
}
