package com.investmentresearch.data.adapter.market;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.research.NewsEvents;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 新闻与板块轮动的取数、纯函数与 Python 金标准一致；单个来源失败只记进 {@code errors}。
 *
 * <p>板块轮动的纯函数金标准在 {@code core} 的 {@code SectorRotationParityTest} 里比对，这里只验取数与组装。
 */
class NewsSectorAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURES = repoRoot().resolve("data/src/test/resources/wiremock/market");
    private static final Path NEWS_GOLDEN = repoRoot().resolve("core/src/test/resources/golden/news_events.json");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock TRADE_DAY = Clock.fixed(Instant.parse("2026-10-30T07:30:00Z"), SHANGHAI);

    private WireMockServer server;
    private UpstreamClient client;

    @BeforeEach
    void setUp() throws Exception {
        startStubbed(fixture -> true);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void news_events_match_the_python_golden() throws Exception {
        JsonNode golden = MAPPER.readTree(Files.readString(NEWS_GOLDEN, UTF_8));

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> row : new ClsNewsSource(client).rows()) {
            events.add(
                    NewsEvents.makeEvent(
                            row, "财联社", "news:cls:20261030120000", golden.get("today").asText(), "cls"));
        }
        for (Map<String, Object> row : new EmNewsSource(client).rows()) {
            events.add(
                    NewsEvents.makeEvent(
                            row, "东方财富", "news:em:20261030120000", golden.get("today").asText(), "em"));
        }
        events.removeIf(event -> event == null);

        List<Map<String, Object>> deduped = NewsEvents.dedupeEvents(events);
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(deduped))).isEqualTo(golden.get("events"));
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(NewsEvents.selectEvents(deduped, 12))))
                .isEqualTo(golden.get("selected"));
    }

    @Test
    void cctv_rows_strip_the_video_prefix_and_the_opening_line() {
        // 目录页的第一个 li 是整期节目，源码里跳过，所以取第二条
        String article = "https://tv.cctv.com/2026/10/30/VIDEsH52xbYIqQpLqCKmZGO4261030.shtml";
        server.stubFor(
                get(urlPathEqualTo(URI.create(article).getPath()))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/html; charset=UTF-8")
                                        .withBody(
                                                "<html><body><h3>[视频] 习近平出席中央金融工作会议</h3>"
                                                        + "<div class=\"cnt_bd\">央视网消息(新闻联播)："
                                                        + "中央金融工作会议今天在北京举行。</div></body></html>")));

        List<Map<String, Object>> rows = new CctvNewsSource(client).rows("20261030");

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0))
                .containsEntry("date", "20261030")
                .containsEntry("title", "习近平出席中央金融工作会议");
        assertThat(rows.get(0).get("content")).asString().startsWith("中央金融工作会议今天在北京举行");
    }

    @Test
    void news_adapter_assembles_every_feed_and_survives_a_missing_source() throws Exception {
        ToolResult result = new NewsAdapter(client, TRADE_DAY).news(12);

        assertThat(result.ok()).isTrue();
        Map<String, Object> data = result.data();
        assertThat(data).containsKeys("as_of", "events", "overseas", "feeds", "counts", "errors", "notes");
        assertThat(map(data.get("errors"))).isEmpty();
        assertThat(rows(data.get("events"))).hasSize(12);
        // 财联社、东财、新闻联播（今天+昨天）、海外各一条
        assertThat(rows(data.get("feeds"))).hasSize(5);
        assertThat(rows(data.get("overseas"))).hasSize(5);
        assertThat(data.get("as_of")).asString().startsWith("2026-10-31");
        assertThat(result.sources()).isNotEmpty();

        startStubbed(fixture -> !fixture.toString().contains("em-news"));
        ToolResult degraded = new NewsAdapter(client, TRADE_DAY).news(12);

        assertThat(degraded.ok()).isTrue();
        assertThat(map(degraded.data().get("errors"))).containsKey("em");
        assertThat(rows(degraded.data().get("events"))).isNotEmpty();
    }

    @Test
    void sector_adapter_builds_the_ranking_and_reports_missing_histories() {
        SectorAdapter adapter = new SectorAdapter(client, TRADE_DAY);
        ToolResult result = adapter.sectors(8);

        assertThat(result.ok()).isTrue();
        Map<String, Object> data = result.data();
        assertThat(data).containsKeys(
                "as_of", "trade_date", "session", "benchmark", "benchmark_today", "intraday", "total_sectors",
                "breadth", "leaders", "ranking", "notes", "errors");
        assertThat(data).containsEntry("total_sectors", 90).containsEntry("trade_date", "2026-10-30");
        // 对外取整到两位，与 Python 的 build_sector_rotation 一致
        assertThat(data).containsEntry("benchmark_today", -1.39);
        // 没有日线的板块算不出打分，不进排名
        assertThat(rows(data.get("ranking"))).hasSize(4);
        // 90 个板块只有 4 个录了日线，其余进 errors 而不是让整体失败
        assertThat(map(data.get("errors"))).containsKey("history");
        assertThat(result.sources()).hasSize(2);

        // 同一天同一份缓存，10 分钟内不重复打 90 个板块日线
        assertThat(adapter.sectors(8).data()).isSameAs(data);
    }

    private void startStubbed(Predicate<Path> keep) throws Exception {
        if (server != null) {
            server.stop();
        }
        server = new WireMockServer(options().dynamicPort());
        server.start();
        for (Path file : fixtures()) {
            if (!keep.test(file)) {
                continue;
            }
            server.addStubMapping(Json.read(Files.readString(file, UTF_8), StubMapping.class));
        }
        // 财联社的 last_time 与 sign 每次调用都不同，只能按路径回放
        if (keep.test(FIXTURES.resolve("cls-news.json"))) {
            server.stubFor(
                    get(urlPathEqualTo("/v1/roll/get_roll_list"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json; charset=UTF-8")
                                            .withBody(body("cls-news"))));
        }
        client = new UrlRewritingClient(new RestClientUpstreamClient(), server.baseUrl());
    }

    private static List<Path> fixtures() throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static byte[] body(String name) throws IOException {
        JsonNode mapping = MAPPER.readTree(Files.readString(FIXTURES.resolve(name + ".json"), UTF_8));
        return Base64.getDecoder().decode(mapping.path("response").path("base64Body").asText());
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    /** 把任意上游 URL 改写到本机 WireMock，路径与查询串保持原样。 */
    private record UrlRewritingClient(UpstreamClient delegate, String base) implements UpstreamClient {

        @Override
        public String get(String url, Map<String, String> headers) {
            return delegate.get(local(url), headers);
        }

        @Override
        public String get(String url, Charset charset, Map<String, String> headers) {
            return delegate.get(local(url), charset, headers);
        }

        private String local(String url) {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            return base + uri.getRawPath() + (query == null ? "" : "?" + query);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom) && Files.readString(pom).contains("<module>data</module>")) {
                    return dir;
                }
            } catch (IOException ignored) {
                // keep walking up
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
