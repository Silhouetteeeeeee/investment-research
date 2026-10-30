package com.investmentresearch.core.domain;

import java.util.List;
import java.util.Map;

/** One tool call in a run, mirroring the Python trace step written to the payload. */
public record TraceStep(
        int round,
        String name,
        Map<String, Object> arguments,
        boolean ok,
        String company,
        String asOf,
        String error,
        List<String> sources,
        boolean cached,
        long durationMs) {}
