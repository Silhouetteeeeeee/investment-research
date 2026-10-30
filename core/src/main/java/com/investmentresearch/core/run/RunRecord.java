package com.investmentresearch.core.run;

import java.util.Map;

/** A run id plus its full payload. */
public record RunRecord(String runId, Map<String, Object> payload) {}
