package com.investmentresearch.core.agent;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.TraceStep;
import com.investmentresearch.core.run.RunRecord;
import java.util.List;
import java.util.Map;

/** One finished question. {@code raw} holds this run's tool messages for the evidence panel. */
public record AskResult(
        String text,
        List<Source> sources,
        List<TraceStep> trace,
        List<String> issues,
        RunRecord run,
        List<Map<String, Object>> raw) {}
