package com.investmentresearch.data.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Set<Path> registered = new HashSet<>();

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
            register(file);
            JsonNode node = mappingNode(file);
            String contentType =
                    node.path("response").path("headers").path("Content-Type").asText(null);
            Charset charset = charsetOf(contentType);
            int expectedBytes = node.path("metadata").path("bytes").asInt();

            String body = replay(file, charset);

            assertThat(body).as(file.toString()).isNotEmpty();
            assertThat(body.getBytes(charset))
                    .as(file + " body bytes")
                    .hasSize(expectedBytes);
            assertThat(body)
                    .as(file + " known payload marker")
                    .contains(expectedMarker(file));
            if (contentType != null && contentType.contains("json")) {
                assertThat(MAPPER.readTree(body)).as(file + " json body").isNotNull();
            }
        }
    }

    @Test
    void eastmoney_stock_list_pages_cover_all_a_shares() throws Exception {
        List<Path> pages =
                mappings.stream()
                        .filter(
                                path ->
                                        path.getFileName()
                                                .toString()
                                                .matches("stock-list-p\\d+\\.json"))
                        .toList();
        assertThat(pages).hasSize(56);

        Set<String> codes = new LinkedHashSet<>();
        for (Path page : pages) {
            register(page);
            JsonNode diff = MAPPER.readTree(replay(page, StandardCharsets.UTF_8)).path("data").path("diff");
            assertThat(diff.isArray()).as(page.toString()).isTrue();
            diff.forEach(item -> codes.add(item.path("f12").asText()));
        }
        assertThat(codes).hasSize(5563);
    }

    /** 每个 family 一个与文件内容无关的已知标记，避免用同一份文件自证。 */
    private static String expectedMarker(Path file) {
        String path = file.toString().replace('\\', '/');
        if (path.contains("/sina/finance-")) {
            return "report_list";
        }
        if (path.contains("f10-profile-")) {
            return "jbzl";
        }
        if (path.contains("f10-segments-")) {
            return "zygcfx";
        }
        if (path.contains("announcements-")) {
            return "art_code";
        }
        if (path.contains("datacenter-peers-subject-")) {
            return "REPORTDATE";
        }
        if (path.contains("datacenter-peers-")) {
            return "SECURITY_CODE";
        }
        if (path.contains("datacenter-valuation-history-")) {
            return "TRADE_DATE";
        }
        if (path.contains("datacenter-valuation-peers-")) {
            return "TOTAL_MARKET_CAP";
        }
        if (path.contains("stock-list-p")) {
            return "\"diff\"";
        }
        throw new AssertionError("未知 fixture family: " + file);
    }

    private static void register(Path file) throws IOException {
        if (!registered.add(file)) {
            return;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        server.addStubMapping(Json.read(json, StubMapping.class));
    }

    private static String replay(Path file, Charset charset) throws IOException {
        String originalUrl = mappingNode(file).path("metadata").path("url").asText();
        return client.get(localUrl(originalUrl), charset, Map.of());
    }

    private static JsonNode mappingNode(Path file) throws IOException {
        return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
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
