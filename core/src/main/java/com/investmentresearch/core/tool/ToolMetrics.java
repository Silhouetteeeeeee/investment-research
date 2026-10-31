package com.investmentresearch.core.tool;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tool execution stats since process start. In memory only: a restart clears them, which
 * matches the single-instance setup. Cached calls never reach {@link ToolRegistry#run}, so they
 * do not pollute the latency samples.
 */
public final class ToolMetrics {
    /** Percentiles look at the most recent samples; the counters still cover all calls. */
    private static final int MAX_SAMPLES = 500;

    private final Map<String, Stats> tools = new ConcurrentHashMap<>();

    public void record(String name, long durationMs, boolean ok) {
        tools.computeIfAbsent(name, key -> new Stats()).add(durationMs, ok);
    }

    /** One row per tool that has executed at least once, ordered by name. */
    public List<ToolStat> snapshot() {
        return tools.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .sorted(Comparator.comparing(ToolStat::name))
                .toList();
    }

    public record ToolStat(
            String name,
            long count,
            long successes,
            double successRate,
            long lastMs,
            long avgMs,
            long p95Ms,
            long p99Ms) {}

    private static final class Stats {
        private long count;
        private long successes;
        private long lastMs;
        private long totalMs;
        private final ArrayDeque<Long> samples = new ArrayDeque<>(MAX_SAMPLES);

        synchronized void add(long durationMs, boolean ok) {
            long ms = Math.max(0, durationMs);
            count++;
            if (ok) {
                successes++;
            }
            lastMs = ms;
            totalMs += ms;
            if (samples.size() == MAX_SAMPLES) {
                samples.removeFirst();
            }
            samples.addLast(ms);
        }

        synchronized ToolStat snapshot(String name) {
            long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
            long avg = count == 0 ? 0 : Math.round((double) totalMs / count);
            return new ToolStat(
                    name,
                    count,
                    successes,
                    count == 0 ? 0 : (double) successes / count,
                    lastMs,
                    avg,
                    percentile(sorted, 0.95),
                    percentile(sorted, 0.99));
        }

        /** Nearest-rank percentile; an empty sample set reads as zero. */
        private static long percentile(long[] sorted, double p) {
            if (sorted.length == 0) {
                return 0;
            }
            int rank = (int) Math.ceil(p * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(rank, sorted.length - 1))];
        }
    }
}
