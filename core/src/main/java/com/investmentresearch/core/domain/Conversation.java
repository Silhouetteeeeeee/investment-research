package com.investmentresearch.core.domain;

import java.util.List;
import java.util.Map;

/** A stored conversation and its summary metadata. */
public record Conversation(
        long id,
        String title,
        String primaryCode,
        String primaryName,
        List<String> companies,
        String createdAt,
        String updatedAt,
        int messageCount,
        Map<String, Object> usage) {}
