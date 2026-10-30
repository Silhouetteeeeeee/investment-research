package com.investmentresearch.data.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 把录制的 WireMock mapping 真装进服务器，再让客户端按原 URL 回放并解码。 */
class RecordedFixtureTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WireMockServer server;
    private static UpstreamClient client;
    private static List<Path> mappings;

    @BeforeAll
    static void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = new RestClientUpstreamClient();
        Path root = Path.of("src/test/resources/wiremock");
        try (Stream<Path> files = Files.walk(root)) {
            mappings = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
        assertThat(mappings).isNotEmpty();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void every_recorded_mapping_replays_and_decodes() throws Exception {
        for (Path file : mappings) {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            server.addStubMapping(Json.read(json, StubMapping.class));

            JsonNode node = MAPPER.readTree(json);
            String originalUrl = node.path("metadata").path("url").asText();
            String contentType =
                    node.path("response").path("headers").path("Content-Type").asText(null);
            int expectedBytes = node.path("metadata").path("bytes").asInt();
            Charset charset = charsetOf(contentType);

            String body = client.get(localUrl(originalUrl), charset, Map.of());

            assertThat(body).as(file.toString()).isNotEmpty();
            assertThat(body.getBytes(charset))
                    .as(file + " body bytes")
                    .hasSize(expectedBytes);
            if (contentType != null && contentType.contains("json")) {
                assertThat(MAPPER.readTree(body)).as(file + " json body").isNotNull();
            }
        }
    }

    private static String localUrl(String originalUrl) {
        URI uri = URI.create(originalUrl);
        String query = uri.getRawQuery();
        return server.baseUrl() + uri.getRawPath() + (query == null ? "" : "?" + query);
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            int index = contentType.toLowerCase().indexOf("charset=");
            if (index >= 0) {
                return Charset.forName(contentType.substring(index + "charset=".length()).trim());
            }
        }
        return StandardCharsets.UTF_8;
    }
}
