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
import com.investmentresearch.core.port.MarketPort;
import com.investmentresearch.core.research.MarketState;
import com.investmentresearch.core.tool.MarketOverviewTool;
import com.investmentresearch.core.tool.ToolSchemas;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 六个上游源各解析一段原始响应；{@code classify} 与 Python 金标准逐字段一致；故障按段隔离。
 *
 * <p>夹具由 {@code tools/fixtures/record.py} 录制，金标准由 Python 的 {@code classify_market} 生成。
 */
class MarketAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURES = repoRoot().resolve("data/src/test/resources/wiremock/market");
    private static final Path GOLDEN =
            repoRoot().resolve("core/src/test/resources/golden/market_state.json");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 与夹具同一交易日的 15:30（北京时间）：as_of 在 15:00，session 应该按收盘判断。 */
    private static final Clock TRADE_DAY = Clock.fixed(Instant.parse("2026-10-30T07:30:00Z"), SHANGHAI);
    /** 次日周六：夹具时间与今天不同，session 应该按最近交易日判断。 */
    private static final Clock SATURDAY = Clock.fixed(Instant.parse("2026-10-31T02:00:00Z"), SHANGHAI);

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
    void sina_index_spot_parses_all_seven_indices() {
        List<Map<String, Object>> rows = new SinaIndexSource(client).rows();

        assertThat(rows).hasSize(7);
        Map<String, Object> sh = rows.get(0);
        assertThat(sh)
                .containsEntry("代码", "sh000001")
                .containsEntry("名称", "上证指数")
                .containsEntry("最新价", 3599.02)
                .containsEntry("昨收", 3651.43)
                .containsEntry("涨跌额", -52.42)
                .containsEntry("涨跌幅", -1.44)
                .containsEntry("成交额", 764227190865.0)
                .containsEntry("成交量", 497973940.0);
    }

    @Test
    void tencent_daily_parses_four_benchmarks_and_converts_lots_to_shares() {
        Map<String, List<Map<String, Object>>> daily = new TencentTrendSource(client).fetch().daily();

        assertThat(daily).containsOnlyKeys("sh000001", "sz399001", "sz399006", "sh000300");
        List<Map<String, Object>> bars = daily.get("sh000001");
        assertThat(bars).hasSize(80);
        assertThat(bars.get(79))
                .containsEntry("date", "2026-10-30")
                .containsEntry("close", 3599.02)
                // 腾讯的成交量是手，与快照里的股统一成股再算量能
                .containsEntry("volume", 49797394000.0);
    }

    @Test
    void legu_breadth_parses_counts_and_the_trade_clock() {
        LeguBreadthSource.Breadth breadth = new LeguBreadthSource(client).fetch();

        assertThat(breadth.asOf()).isEqualTo("2026-10-30 15:00:00");
        Map<String, Object> byItem = breadth.byItem();
        assertThat(byItem)
                .containsEntry("上涨", "854")
                .containsEntry("下跌", "4290")
                .containsEntry("涨停", "49")
                .containsEntry("跌停", "93")
                .containsEntry("真实涨停", "44")
                .containsEntry("活跃度", "16.35%");
    }

    @Test
    void ths_sector_pages_parse_ninety_industries() {
        List<Map<String, Object>> rows = new ThsSectorSource(client).rows();

        assertThat(rows).hasSize(90);
        assertThat(rows.get(0))
                .containsEntry("板块", "白酒")
                .containsEntry("涨跌幅", 5.28)
                .containsEntry("总成交额", 244.7)
                .containsEntry("净流入", 25.35)
                .containsEntry("上涨家数", 18.0)
                .containsEntry("领涨股", "皇台酒业")
                .containsEntry("领涨股-涨跌幅", 10.01);
    }

    @Test
    void limit_up_pool_parses_streaks_and_industries() {
        List<Map<String, Object>> rows = new LimitUpSource(client).rows("20261030");

        assertThat(rows).hasSize(47);
        assertThat(rows.get(0)).containsEntry("代码", "603703").containsEntry("连板数", 2.0);
        assertThat(rows.get(2)).containsEntry("连板数", 3.0);
    }

    @Test
    void cls_news_parses_titles_and_shanghai_timestamps() {
        List<Map<String, Object>> rows = new ClsNewsSource(client).rows();

        assertThat(rows).hasSize(20);
        assertThat(rows.get(0)).containsKeys("标题", "发布日期", "发布时间");
        assertThat(rows.get(0).get("发布日期").toString()).startsWith("2026-10-3");
    }

    @Test
    void classify_matches_the_python_golden() throws Exception {
        JsonNode golden = MAPPER.readTree(Files.readString(GOLDEN, UTF_8));

        Map<String, Object> classified =
                MarketState.classify(MAPPER.convertValue(golden.get("overview"), Map.class));

        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(classified)))
                .isEqualTo(golden.get("classified"));
    }

    @Test
    void overview_reports_every_segment_and_takes_as_of_from_the_fixture() {
        ToolResult result = new MarketAdapter(client, TRADE_DAY).overview();

        assertThat(result.ok()).isTrue();
        assertThat(result.asOf()).isEqualTo("2026-10-30 15:00:00");
        Map<String, Object> data = result.data();
        assertThat(data)
                .containsKeys(
                        "as_of",
                        "session",
                        "indices",
                        "turnover",
                        "breadth",
                        "trend",
                        "sectors",
                        "limit_up",
                        "news",
                        "errors",
                        "state");
        assertThat(map(data.get("errors"))).isEmpty();
        assertThat(rows(data.get("indices"))).hasSize(7);
        assertThat(list(data.get("trend"))).hasSize(4);
        assertThat(data.get("sectors")).isNotNull();
        assertThat(map(data.get("state")).get("state"))
                .isIn("强势", "震荡", "弱势", "极端风险");
        assertThat(result.sources()).hasSize(7);
        assertThat(result.sources().get(0).id()).isEqualTo("market:index:20261030150000");
    }

    @Test
    void session_state_uses_the_injected_clock_while_as_of_stays_on_the_fixture() {
        Map<String, Object> closed =
                new MarketAdapter(client, TRADE_DAY).overview().data();
        Map<String, Object> stale =
                new MarketAdapter(client, SATURDAY).overview().data();

        assertThat(closed.get("as_of")).isEqualTo("2026-10-30 15:00:00");
        assertThat(stale.get("as_of")).isEqualTo("2026-10-30 15:00:00");
        // Python 用 <= 收盘时刻判断，15:00:00 整点仍算盘中（进度 1.0），收盘是 15:01 之后
        assertThat(map(closed.get("session")))
                .containsEntry("state", "盘中")
                .containsEntry("progress", 1.0);
        assertThat(map(stale.get("session"))).containsEntry("state", "最近交易日");
    }

    @Test
    void a_failing_source_is_reported_and_the_rest_survives() throws Exception {
        startStubbed(fixture -> !fixture.toString().contains("ths-sectors"));

        ToolResult result = new MarketAdapter(client, TRADE_DAY).overview();

        assertThat(result.ok()).isTrue();
        assertThat(map(result.data().get("errors"))).containsOnlyKeys("sectors");
        assertThat(result.data().get("indices")).isNotNull();
        assertThat(result.data().get("sectors")).isNull();
    }

    @Test
    void every_source_down_is_a_failed_result() throws Exception {
        startStubbed(fixture -> false);

        ToolResult result = new MarketAdapter(client, TRADE_DAY).overview();

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).startsWith("大盘数据获取失败");
    }

    @Test
    void market_overview_tool_delegates_to_the_port() {
        MarketPort port = new MarketAdapter(client, TRADE_DAY);
        MarketOverviewTool tool = new MarketOverviewTool(port, ToolSchemas.loadDefault());

        assertThat(tool.schema().name()).isEqualTo("get_market_overview");
        assertThat(tool.execute(Map.of(), null).data()).isEqualTo(port.overview().data());
    }

    private void startStubbed(Predicate<Path> keep) throws Exception {
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

    private static List<?> list(Object value) {
        return (List<?>) value;
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
