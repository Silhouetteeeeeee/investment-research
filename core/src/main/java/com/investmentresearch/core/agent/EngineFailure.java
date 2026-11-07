package com.investmentresearch.core.agent;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.TraceStep;
import com.investmentresearch.core.run.RunRecord;
import java.util.List;
import java.util.Map;

/**
 * A failed ask that still carries the finished error run record (agent.py:563,720-723).
 *
 * <p>交易页要求失败也保留已完成的思考与工具证据，所以运行中途已经拿到的
 * raw/sources/trace 一并带出。
 */
public final class EngineFailure extends RuntimeException {
    private final RunRecord run;
    private final List<Map<String, Object>> raw;
    private final List<Source> sources;
    private final List<TraceStep> trace;

    public EngineFailure(String message, RunRecord run, Throwable cause) {
        this(message, run, cause, List.of(), List.of(), List.of());
    }

    public EngineFailure(
            String message,
            RunRecord run,
            Throwable cause,
            List<Map<String, Object>> raw,
            List<Source> sources,
            List<TraceStep> trace) {
        super(message, cause);
        this.run = run;
        this.raw = List.copyOf(raw);
        this.sources = List.copyOf(sources);
        this.trace = List.copyOf(trace);
    }

    public RunRecord run() {
        return run;
    }

    public List<Map<String, Object>> raw() {
        return raw;
    }

    public List<Source> sources() {
        return sources;
    }

    public List<TraceStep> trace() {
        return trace;
    }
}
