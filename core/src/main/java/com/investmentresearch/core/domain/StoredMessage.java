package com.investmentresearch.core.domain;

import java.util.Map;

/** A single stored message, replayable back to the agent as role + payload. */
public record StoredMessage(String role, Map<String, Object> payload) {}
