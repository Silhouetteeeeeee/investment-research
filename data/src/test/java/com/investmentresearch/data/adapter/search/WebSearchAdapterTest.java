package com.investmentresearch.data.adapter.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebSearchAdapterTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC);
    private static final String BAIDU_OK =
            """
            {"references": [
              {"id": 1, "title": "茅台三季报", "url": "https://a.example/1",
               "content": "营收增长", "date": "2026-10-29", "website": "示例财经"},
              {"id": 2, "title": "白酒行业", "url": "https://a.example/2",
               "snippet": "行业摘要", "date": "", "website": ""}
            ]}
            """;
    private static final String DEEPSEEK_OK =
            """
            {"results": [
              {"title": "DeepSeek 结果", "url": "https://b.example/1",
               "summary": "摘要正文", "snippet": "短摘要",
               "date": "2026-10-30T00:00:00+00:00", "site_name": "示例站"}
            ]}
            """;

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private WebSearchAdapter adapter(String baiduKey, String deepseekKey) {
        return new WebSearchAdapter(
                new RestClientUpstreamClient(),
                server.baseUrl() + "/baidu",
                server.baseUrl() + "/deepseek",
                baiduKey,
                deepseekKey,
                CLOCK);
    }

    @Test
    void baidu_success_maps_results_and_citable_sources() {
        server.stubFor(post("/baidu").willReturn(aResponse().withStatus(200).withBody(BAIDU_OK)));
        server.stubFor(
                post("/deepseek").willReturn(aResponse().withStatus(200).withBody(DEEPSEEK_OK)));

        ToolResult result = adapter("b-key", "d-key").search("贵州茅台 三季报", 2);

        assertTrue(result.ok());
        assertEquals("baidu", result.data().get("provider"));
        assertEquals(2, ((java.util.List<?>) result.data().get("results")).size());
        assertEquals("https://a.example/1", result.sources().get(0).url());
        assertTrue(result.sources().get(0).id().startsWith("web:"));
        server.verify(
                postRequestedFor(urlEqualTo("/baidu"))
                        .withHeader("Authorization", equalTo("Bearer b-key"))
                        .withRequestBody(containing("贵州茅台 三季报")));
        server.verify(0, postRequestedFor(urlEqualTo("/deepseek")));
    }

    @Test
    void baidu_quota_error_falls_back_to_deepseek() {
        server.stubFor(post("/baidu").willReturn(aResponse().withStatus(429)));
        server.stubFor(
                post("/deepseek").willReturn(aResponse().withStatus(200).withBody(DEEPSEEK_OK)));

        ToolResult result = adapter("b-key", "d-key").search("茅台", 5);

        assertTrue(result.ok());
        assertEquals("deepseek", result.data().get("provider"));
        assertEquals("https://b.example/1", result.sources().get(0).url());
        server.verify(postRequestedFor(urlEqualTo("/deepseek")));
    }

    @Test
    void missing_baidu_key_goes_straight_to_deepseek() {
        server.stubFor(post("/baidu").willReturn(aResponse().withStatus(200).withBody(BAIDU_OK)));
        server.stubFor(
                post("/deepseek").willReturn(aResponse().withStatus(200).withBody(DEEPSEEK_OK)));

        ToolResult result = adapter(null, "d-key").search("茅台", 5);

        assertEquals("deepseek", result.data().get("provider"));
        server.verify(0, postRequestedFor(urlEqualTo("/baidu")));
    }

    @Test
    void both_providers_failing_returns_failure_without_keys() {
        server.stubFor(post("/baidu").willReturn(aResponse().withStatus(429)));
        server.stubFor(post("/deepseek").willReturn(aResponse().withStatus(500)));

        ToolResult result = adapter("b-secret", "d-secret").search("茅台", 5);

        assertFalse(result.ok());
        assertTrue(result.error().contains("百度"));
        assertTrue(result.error().contains("DeepSeek"));
        assertFalse(result.error().contains("b-secret"));
        assertFalse(result.error().contains("d-secret"));
    }

    @Test
    void no_keys_at_all_returns_actionable_failure() {
        ToolResult result = adapter(null, null).search("茅台", 5);

        assertFalse(result.ok());
        assertTrue(result.error().contains("BAIDU_API_KEY"));
        assertTrue(result.error().contains("DEEPSEEK_API_KEY"));
        server.verify(0, postRequestedFor(urlEqualTo("/baidu")));
    }
}
