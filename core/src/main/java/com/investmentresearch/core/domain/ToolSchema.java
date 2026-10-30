package com.investmentresearch.core.domain;

import java.util.Map;

/** One OpenAI function schema exported by the Python tool layer. */
public record ToolSchema(String name, String description, Map<String, Object> parameters) {}
