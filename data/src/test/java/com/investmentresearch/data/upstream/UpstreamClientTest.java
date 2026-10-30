package com.investmentresearch.data.upstream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UpstreamClientTest {
    private static final Charset GBK = Charset.forName("GBK");

    @RegisterExtension
    static final WireMockExtension WIREMOCK =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final UpstreamClient client = new RestClientUpstreamClient();

    @Test
    void returns_body_with_requested_charset() {
        String chinese = "营业收入：12.3 亿元，同比增长 8%";
        // 响应头故意写成 UTF-8、body 却是 GBK：客户端必须用入参 charset，不能信 header
        WIREMOCK.stubFor(
                get(urlPathEqualTo("/gbk"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                                        .withBody(chinese.getBytes(GBK))));

        String body = client.get(WIREMOCK.baseUrl() + "/gbk", GBK, Map.of());

        assertThat(body).isEqualTo(chinese);
    }

    @Test
    void retries_once_on_500_then_succeeds() {
        WIREMOCK.stubFor(
                get(urlPathEqualTo("/retry"))
                        .inScenario("retry")
                        .whenScenarioStateIs(Scenario.STARTED)
                        .willReturn(aResponse().withStatus(500))
                        .willSetStateTo("recovered"));
        WIREMOCK.stubFor(
                get(urlPathEqualTo("/retry"))
                        .inScenario("retry")
                        .whenScenarioStateIs("recovered")
                        .willReturn(aResponse().withStatus(200).withBody("ok")));

        String body = client.get(WIREMOCK.baseUrl() + "/retry", Map.of());

        assertThat(body).isEqualTo("ok");
        WIREMOCK.verify(2, getRequestedFor(urlPathEqualTo("/retry")));
    }

    @Test
    void does_not_retry_on_404_and_throws() {
        String url = WIREMOCK.baseUrl() + "/missing";
        WIREMOCK.stubFor(
                get(urlPathEqualTo("/missing"))
                        .willReturn(aResponse().withStatus(404).withBody("not found")));

        assertThatThrownBy(() -> client.get(url, Map.of()))
                .isInstanceOf(UpstreamException.class)
                .satisfies(
                        error -> {
                            UpstreamException upstream = (UpstreamException) error;
                            assertThat(upstream.status()).isEqualTo(404);
                            assertThat(upstream.url()).isEqualTo(url);
                        });
        WIREMOCK.verify(1, getRequestedFor(urlPathEqualTo("/missing")));
    }

    @Test
    void error_message_redacts_query_string() {
        String url = WIREMOCK.baseUrl() + "/missing?token=SECRET-QUERY-123";
        WIREMOCK.stubFor(get(urlPathEqualTo("/missing")).willReturn(aResponse().withStatus(404)));

        UpstreamException error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        UpstreamException.class, () -> client.get(url, Map.of()));

        assertThat(error.getMessage()).doesNotContain("SECRET-QUERY-123").doesNotContain("token");
        assertThat(error.url()).isEqualTo(url);
    }

    @Test
    void error_message_does_not_leak_headers() {
        String secret = "Bearer sk-live-SECRET-123";
        String url = WIREMOCK.baseUrl() + "/boom";
        WIREMOCK.stubFor(get(urlPathEqualTo("/boom")).willReturn(aResponse().withStatus(500)));

        UpstreamException error =
                org.junit.jupiter.api.Assertions.assertThrows(
                        UpstreamException.class,
                        () -> client.get(url, Map.of("Authorization", secret)));

        assertThat(error.status()).isEqualTo(500);
        assertThat(error.getMessage()).doesNotContain(secret).doesNotContain("Authorization");
        assertThat(stackTrace(error)).doesNotContain(secret).doesNotContain("Authorization");
    }

    @Test
    void concurrent_retries_do_not_deadlock_the_semaphore() throws Exception {
        int flights = 6;
        for (int i = 0; i < flights; i++) {
            String path = "/flight/" + i;
            WIREMOCK.stubFor(
                    get(urlPathEqualTo(path))
                            .inScenario("flight-" + i)
                            .whenScenarioStateIs(Scenario.STARTED)
                            .willReturn(aResponse().withStatus(503))
                            .willSetStateTo("recovered"));
            WIREMOCK.stubFor(
                    get(urlPathEqualTo(path))
                            .inScenario("flight-" + i)
                            .whenScenarioStateIs("recovered")
                            .willReturn(aResponse().withStatus(200).withBody("flight-" + i)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(flights);
        Duration timeout = Duration.ofSeconds(20);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < flights; i++) {
                String path = "/flight/" + i;
                results.add(pool.submit(() -> client.get(WIREMOCK.baseUrl() + path, Map.of())));
            }
            for (int i = 0; i < flights; i++) {
                assertThat(results.get(i).get(timeout.toSeconds(), TimeUnit.SECONDS))
                        .isEqualTo("flight-" + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void default_get_decodes_utf8() {
        WIREMOCK.stubFor(
                get(urlPathEqualTo("/utf8"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json; charset=utf-8")
                                        .withBody("{\"name\":\"东鹏饮料\"}"
                                                .getBytes(StandardCharsets.UTF_8))));

        assertThat(client.get(WIREMOCK.baseUrl() + "/utf8", Map.of()))
                .isEqualTo("{\"name\":\"东鹏饮料\"}");
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
