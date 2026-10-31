package com.investmentresearch.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.tool.ToolMetrics.ToolStat;
import org.junit.jupiter.api.Test;

class ToolMetricsTest {

    @Test
    void empty_metrics_snapshot_is_empty() {
        assertThat(new ToolMetrics().snapshot()).isEmpty();
    }

    @Test
    void counts_successes_and_nearest_rank_percentiles() {
        ToolMetrics metrics = new ToolMetrics();
        for (int ms = 1; ms <= 10; ms++) {
            metrics.record("get_finance", ms, ms <= 8);
        }

        ToolStat stat = metrics.snapshot().get(0);

        assertThat(stat.name()).isEqualTo("get_finance");
        assertThat(stat.count()).isEqualTo(10);
        assertThat(stat.successes()).isEqualTo(8);
        assertThat(stat.successRate()).isEqualTo(0.8);
        assertThat(stat.lastMs()).isEqualTo(10);
        assertThat(stat.avgMs()).isEqualTo(6);
        assertThat(stat.p95Ms()).isEqualTo(10);
        assertThat(stat.p99Ms()).isEqualTo(10);
    }

    @Test
    void percentiles_use_only_the_most_recent_samples() {
        ToolMetrics metrics = new ToolMetrics();
        for (int ms = 0; ms < 600; ms++) {
            metrics.record("screen_candidates", ms, true);
        }

        ToolStat stat = metrics.snapshot().get(0);

        // 计数与均值覆盖全部调用，百分位只看最近 500 个样本（100..599）。
        assertThat(stat.count()).isEqualTo(600);
        assertThat(stat.avgMs()).isEqualTo(300);
        assertThat(stat.p95Ms()).isEqualTo(574);
        assertThat(stat.p99Ms()).isEqualTo(594);
    }

    @Test
    void negative_durations_are_clamped_to_zero() {
        ToolMetrics metrics = new ToolMetrics();
        metrics.record("get_finance", -5, true);

        ToolStat stat = metrics.snapshot().get(0);

        assertThat(stat.lastMs()).isZero();
        assertThat(stat.avgMs()).isZero();
    }

    @Test
    void snapshot_is_sorted_by_tool_name() {
        ToolMetrics metrics = new ToolMetrics();
        metrics.record("get_peers", 1, true);
        metrics.record("get_finance", 1, true);

        assertThat(metrics.snapshot()).extracting(ToolStat::name)
                .containsExactly("get_finance", "get_peers");
    }
}
