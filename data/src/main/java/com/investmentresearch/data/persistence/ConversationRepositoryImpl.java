package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.Conversation;
import com.investmentresearch.core.domain.StoredMessage;
import com.investmentresearch.core.port.ConversationRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationRepositoryImpl implements ConversationRepository {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public ConversationRepositoryImpl(
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public long create(String title, String primaryCode, String primaryName) {
        String now = now();
        ConversationEntity row = new ConversationEntity();
        row.setTitle(title);
        row.setPrimaryCode(primaryCode);
        row.setPrimaryName(primaryName);
        row.setCompanies(writeJson(List.of(primaryCode == null ? "" : primaryCode)));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        conversationMapper.insert(row);
        return row.getId();
    }

    @Override
    public Optional<Conversation> find(long id) {
        return Optional.ofNullable(conversationMapper.findRow(id)).map(this::toConversation);
    }

    @Override
    public List<Conversation> recent(String query, int limit) {
        String normalized = query == null ? "" : query.trim();
        return conversationMapper.recent(normalized, limit).stream()
                .map(this::toConversation)
                .toList();
    }

    @Override
    public List<StoredMessage> messages(long id) {
        return messageMapper.listByConversation(id).stream()
                .map(row -> new StoredMessage(row.getRole(), readMap(row.getPayloadJson())))
                .toList();
    }

    @Override
    @Transactional
    public int append(long conversationId, String role, Map<String, Object> payload) {
        String now = now();
        int seq = messageMapper.maxSeq(conversationId) + 1;
        MessageEntity row = new MessageEntity();
        row.setConversationId(conversationId);
        row.setSeq(seq);
        row.setRole(role);
        row.setPayloadJson(writeJson(payload));
        row.setCreatedAt(now);
        messageMapper.insertMessage(row);
        conversationMapper.update(
                null,
                Wrappers.<ConversationEntity>update()
                        .eq("id", conversationId)
                        .set("updated_at", now));
        return seq;
    }

    @Override
    public void updateUsage(long conversationId, Map<String, Object> usage) {
        conversationMapper.update(
                null,
                Wrappers.<ConversationEntity>update()
                        .eq("id", conversationId)
                        .set("usage_json", usage == null ? null : writeJson(usage)));
    }

    @Override
    @Transactional
    public void delete(long id) {
        messageMapper.deleteByConversation(id);
        conversationMapper.deleteById(id);
    }

    private Conversation toConversation(ConversationEntity row) {
        return new Conversation(
                row.getId(),
                row.getTitle(),
                row.getPrimaryCode(),
                row.getPrimaryName(),
                readList(row.getCompanies()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getMessageCount() == null ? 0 : row.getMessageCount(),
                row.getUsageJson() == null ? Map.of() : readMap(row.getUsageJson()));
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid companies JSON: " + json, e);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid payload JSON: " + json, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize to JSON", e);
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }
}
