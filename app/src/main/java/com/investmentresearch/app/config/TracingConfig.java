package com.investmentresearch.app.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 按需把 AgentScope 的 trace 导出到 OTLP collector。
 *
 * <p>未设置 {@code OTEL_EXPORTER_OTLP_ENDPOINT} 时保持 no-op：不建 SDK、不起导出线程，{@code
 * OtelTracingMiddleware} 拿到的就是 noop tracer。手动组装而不是用 autoconfigure，是因为后者要额外的
 * {@code opentelemetry-sdk-extension-autoconfigure}，我们只需要 traces 一条链路。
 */
@Configuration
public class TracingConfig {
    private static final String DEFAULT_SERVICE_NAME = "investment-research";

    private final Environment environment;
    private OpenTelemetrySdk sdk;

    TracingConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void start() {
        sdk = build(environment).orElse(null);
        if (sdk != null) {
            // AgentScope 的 OtelTracingMiddleware 从 GlobalOpenTelemetry 取 tracer，
            // 必须在 request 之前注册好；顺序无妨，它每次调用时才取。
            GlobalOpenTelemetry.set(sdk);
        }
    }

    @PreDestroy
    void stop() {
        // 停进程前把 BatchSpanProcessor 队列里的 span 刷出去，否则最后 5 秒的调用会丢。
        if (sdk != null) {
            sdk.close();
        }
    }

    static Optional<OpenTelemetrySdk> build(Environment env) {
        if (Boolean.parseBoolean(env.getProperty("OTEL_SDK_DISABLED", "false"))) {
            return Optional.empty();
        }
        String endpoint = env.getProperty("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }

        String serviceName = env.getProperty("OTEL_SERVICE_NAME", DEFAULT_SERVICE_NAME);
        Resource resource =
                Resource.getDefault().toBuilder().put("service.name", serviceName).build();

        OtlpHttpSpanExporter exporter =
                OtlpHttpSpanExporter.builder().setEndpoint(tracesEndpoint(endpoint)).build();
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .setSampler(sampler(env.getProperty("OTEL_TRACES_SAMPLER_ARG")))
                        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                        .build();
        return Optional.of(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
    }

    /** OTLP/HTTP 的信号地址要带 {@code /v1/traces}，但用户习惯只填 collector 根地址。 */
    static String tracesEndpoint(String endpoint) {
        String trimmed = endpoint.trim().replaceAll("/+$", "");
        return trimmed.endsWith("/v1/traces") ? trimmed : trimmed + "/v1/traces";
    }

    private static Sampler sampler(String rawRatio) {
        double ratio = 1.0;
        if (rawRatio != null && !rawRatio.isBlank()) {
            try {
                double parsed = Double.parseDouble(rawRatio.trim());
                if (parsed > 0 && parsed <= 1) {
                    ratio = parsed;
                }
            } catch (NumberFormatException ignored) {
                // 配置写坏时按全采样跑，不让一个环境变量拦住启动。
            }
        }
        return Sampler.parentBased(Sampler.traceIdRatioBased(ratio));
    }
}
