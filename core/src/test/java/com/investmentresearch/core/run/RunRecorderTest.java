package com.investmentresearch.core.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunRecorderTest {
    private final MutableClock clock =
            new MutableClock(Instant.parse("2026-10-30T06:58:03.262221Z"), ZoneId.of("UTC"));

    @Test
    void stage_order_follows_events() {
        RunRecorder recorder = recorder();
        recorder.start(17, "研究一下");

        clock.advance(Duration.ofMillis(5_063));
        recorder.modelStage(1, 5_063, null, 1_000, 50, "tool_calls", "api", false);
        clock.advance(Duration.ofMillis(40));
        recorder.toolStage(
                1,
                "load_skill_through_path",
                Map.of("name", "business_model"),
                40,
                true,
                false,
                null,
                null,
                null,
                List.of("skill:business_model"),
                1_437);
        clock.advance(Duration.ofMillis(2_000));
        recorder.modelStage(2, 2_000, 120, 900, 30, "stop", "estimate", false);

        List<Map<String, Object>> stages = stages(recorder.payload());
        assertThat(stages)
                .extracting(stage -> stage.get("kind"))
                .containsExactly("model", "tool", "model");
        assertThat(stages)
                .extracting(stage -> (Integer) stage.get("start_ms"))
                .containsExactly(0, 5_063, 5_103);
        assertThat(stages)
                .extracting(stage -> (Integer) stage.get("duration_ms"))
                .containsExactly(5_063, 40, 2_000);
        assertThat(recorder.payload().get("rounds")).isEqualTo(2);
    }

    @Test
    void forced_answer_does_not_raise_rounds() {
        RunRecorder recorder = recorder();
        recorder.start(17, "研究一下");

        recorder.modelStage(10, 100, null, 10, 5, "tool_calls", "api", false);
        recorder.modelStage(11, 100, null, 10, 5, "stop", "api", true);

        assertThat(recorder.payload().get("rounds")).isEqualTo(10);
    }

    @Test
    void finish_keeps_a_non_null_error() {
        RunRecorder recorder = recorder();
        recorder.start(17, "研究一下");

        recorder.finish("error", "上游挂了");

        assertThat(recorder.payload().get("error")).isEqualTo("上游挂了");
        assertThat(recorder.payload().get("stop_reason")).isEqualTo("error");
    }

    @Test
    void null_usage_source_falls_back_to_estimate() {
        RunRecorder recorder = recorder();
        recorder.start(17, "研究一下");

        recorder.modelStage(1, 100, null, null, null, null, null, false);

        assertThat(stages(recorder.payload()).get(0).get("usage_source"))
                .isEqualTo("estimate");
    }

    @Test
    void finish_sets_stop_reason_total_ms_and_finished_at() {
        RunRecorder recorder = recorder();
        recorder.start(17, "研究一下");
        clock.advance(Duration.ofMillis(64_687));

        recorder.finish("answered", null);

        Map<String, Object> payload = recorder.payload();
        assertThat(payload.get("version")).isEqualTo(1);
        assertThat((String) payload.get("run_id"))
                .matches("\\d{8}T\\d{6}-[0-9a-f]{8}");
        assertThat(payload.get("started_at")).isEqualTo("2026-10-30T06:58:03.262221");
        assertThat(payload.get("finished_at")).isEqualTo("2026-10-30T06:59:07.949221");
        assertThat(payload.get("total_ms")).isEqualTo(64_687);
        assertThat(payload.get("stop_reason")).isEqualTo("answered");
        assertThat(payload.get("error")).isNull();
        assertThat(payload.get("conversation_id")).isEqualTo(17L);
        assertThat(payload.get("model")).isEqualTo("deepseek-flash");
        assertThat(payload.get("max_rounds")).isEqualTo(10);
        assertThat(payload.get("issues")).isEqualTo(List.of());
        assertThat(payload.get("stages")).isEqualTo(List.of());
        assertThat(payload.get("prompts")).isEqualTo(List.of());
        assertThat(payload.get("skills")).isEqualTo(List.of());
        assertThat(payload.get("calls")).isEqualTo(List.of());
    }

    @Test
    void tool_stage_records_source_ids_and_data_chars() {
        RunRecorder recorder = recorder();
        recorder.start(17, "研究一下");

        recorder.toolStage(
                2,
                "get_finance",
                Map.of("code", "605499"),
                320,
                true,
                true,
                null,
                "605499",
                "2026-06-30",
                List.of("finance:605499:2026-06-30", "finance:605499:2025-12-31"),
                18_234);

        Map<String, Object> stage = stages(recorder.payload()).get(0);
        assertThat(stage.get("kind")).isEqualTo("tool");
        assertThat(stage.get("round")).isEqualTo(2);
        assertThat(stage.get("name")).isEqualTo("get_finance");
        assertThat(stage.get("arguments")).isEqualTo(Map.of("code", "605499"));
        assertThat(stage.get("duration_ms")).isEqualTo(320);
        assertThat(stage.get("ok")).isEqualTo(true);
        assertThat(stage.get("cached")).isEqualTo(true);
        assertThat(stage.get("company")).isEqualTo("605499");
        assertThat(stage.get("as_of")).isEqualTo("2026-06-30");
        assertThat(stage.get("sources"))
                .isEqualTo(List.of("finance:605499:2026-06-30", "finance:605499:2025-12-31"));
        assertThat(stage.get("data_chars")).isEqualTo(18_234);
    }

    @Test
    void missing_ttft_is_null_not_zero() {
        RunRecorder recorder = recorder();
        recorder.start(17, "研究一下");

        recorder.modelStage(1, 100, null, 10, 5, "stop", "estimate", false);

        Map<String, Object> stage = stages(recorder.payload()).get(0);
        assertThat(stage).containsKey("ttft_ms");
        assertThat(stage.get("ttft_ms")).isNull();
        assertThat(stage.get("usage_source")).isEqualTo("estimate");
    }

    private RunRecorder recorder() {
        return new RunRecorder(clock, "deepseek-flash", 10, Map.of());
    }

    private static List<Map<String, Object>> stages(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("stages");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
