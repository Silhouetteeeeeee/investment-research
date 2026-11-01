package com.investmentresearch.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TracingConfigTest {

    @Test
    void blank_endpoint_means_no_sdk() {
        assertThat(TracingConfig.build(new MockEnvironment())).isEmpty();
        assertThat(
                        TracingConfig.build(
                                new MockEnvironment()
                                        .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "  ")))
                .isEmpty();
    }

    @Test
    void disabled_flag_wins_over_endpoint() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318")
                        .withProperty("OTEL_SDK_DISABLED", "true");
        assertThat(TracingConfig.build(env)).isEmpty();
    }

    @Test
    void endpoint_builds_an_sdk() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318")
                        .withProperty("OTEL_SERVICE_NAME", "investment-research-test");
        Optional<OpenTelemetrySdk> sdk = TracingConfig.build(env);
        assertThat(sdk).isPresent();
        sdk.ifPresent(OpenTelemetrySdk::close);
    }

    @Test
    void bad_sampler_arg_falls_back_to_always_on() {
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318")
                        .withProperty("OTEL_TRACES_SAMPLER_ARG", "not-a-number");
        Optional<OpenTelemetrySdk> sdk = TracingConfig.build(env);
        assertThat(sdk).isPresent();
        sdk.ifPresent(OpenTelemetrySdk::close);
    }

    @Test
    void traces_endpoint_appends_signal_path_once() {
        assertThat(TracingConfig.tracesEndpoint("http://collector:4318"))
                .isEqualTo("http://collector:4318/v1/traces");
        assertThat(TracingConfig.tracesEndpoint("http://collector:4318/"))
                .isEqualTo("http://collector:4318/v1/traces");
        assertThat(TracingConfig.tracesEndpoint("http://collector:4318/v1/traces"))
                .isEqualTo("http://collector:4318/v1/traces");
    }
}
