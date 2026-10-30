package com.investmentresearch.data.adapter.f10;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.BizSegmentEntity;
import com.investmentresearch.data.persistence.BizSegmentMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;

class SegmentAdapterTest {
    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private BizSegmentMapper mapper;
    private StockMapper stockMapper;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        server = F10TestSupport.startServer();
        String url = F10TestSupport.copySnapshot(tempDir);
        F10TestSupport.Db db = F10TestSupport.openDb(url);
        session = db.session();
        transactionManager = db.transactionManager();
        mapper = session.getMapper(BizSegmentMapper.class);
        stockMapper = session.getMapper(StockMapper.class);
    }

    @AfterEach
    void tearDown() {
        session.close();
        server.stop();
    }

    @Test
    void segments_matches_golden_for_years_5_and_3() throws Exception {
        List<Path> goldens = F10TestSupport.goldens("get_segments");
        assertThat(goldens).hasSize(6);

        for (Path file : goldens) {
            JsonNode golden = F10TestSupport.golden(file);
            String code = golden.path("default_company").asText();
            int years = golden.path("arguments").path("years").asInt();
            mapper.delete(Wrappers.<BizSegmentEntity>query().eq("code", code));

            ToolResult actual = adapter().segments(code, years);

            F10TestSupport.assertMatchesGolden(file, actual);
        }
    }

    @Test
    void segment_history_order_and_keys_are_stable() {
        mapper.delete(Wrappers.<BizSegmentEntity>query().eq("code", "000858"));
        SegmentAdapter adapter = adapter();

        ToolResult first = adapter.segments("000858", 5);
        assertThat(first.ok()).isTrue();
        Map<String, Object> data = first.data();
        List<Map<String, Object>> slices = slices(data);
        assertThat(slices).extracting(slice -> slice.get("kind")).containsExactly("product", "region");
        assertThat(first.sources())
                .extracting(source -> source.id())
                .containsExactly(
                        "segments:000858:2026-06-30:product",
                        "segments:000858:2026-06-30:region");

        for (Map<String, Object> slice : slices) {
            List<Map<String, Object>> history = (List<Map<String, Object>>) slice.get("history");
            List<String> periods = new ArrayList<>();
            for (Map<String, Object> period : history) {
                periods.add((String) period.get("period"));
                assertThat(period.keySet())
                        .containsExactly("period", "total_income_yi", "items");
                for (Map<String, Object> item : (List<Map<String, Object>>) period.get("items")) {
                    assertThat(item.keySet())
                            .containsExactly(
                                    "name", "income_yi", "share", "yoy", "gross_margin");
                }
            }
            assertThat(periods).isSorted();
        }

        // 第二次走缓存：最新报告期已在库里，不再请求上游，输出保持逐字段一致
        ToolResult second = adapter.segments("000858", 5);
        assertThat(second.data()).isEqualTo(first.data());
        server.verify(
                1,
                getRequestedFor(urlPathEqualTo(F10TestSupport.SEGMENTS_PATH))
                        .withQueryParam("code", equalTo("000858.SZ")));
    }

    @Test
    void upstream_html_or_empty_body_returns_ok_false() {
        server.stubFor(
                get(urlPathEqualTo(F10TestSupport.SEGMENTS_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/html")
                                        .withBody("<html>WAF</html>")));
        SegmentAdapter adapter = adapter();
        ToolResult html = adapter.segments("000001", 5);
        assertThat(html.ok()).as("HTML 200").isFalse();
        assertThat(html.error()).as("HTML 200").contains("获取主营构成失败");

        server.stubFor(
                get(urlPathEqualTo(F10TestSupport.SEGMENTS_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("")));
        ToolResult empty = adapter.segments("000002", 5);
        assertThat(empty.ok()).as("empty 200").isFalse();
        assertThat(empty.data()).as("empty 200").isNull();

        // 200 但结构不对：没有 zygcfx，Python 走“没有主营构成数据”，且不写库
        server.stubFor(
                get(urlPathEqualTo(F10TestSupport.SEGMENTS_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"foo\":1}")));
        ToolResult wrongShape = adapter.segments("000012", 5);
        assertThat(wrongShape.ok()).as("wrong shape").isFalse();
        assertThat(wrongShape.error()).as("wrong shape").contains("没有主营构成数据");
        assertThat(
                        mapper.selectCount(
                                Wrappers.<BizSegmentEntity>query().eq("code", "000012")))
                .as("失败不写库")
                .isZero();
    }

    @Test
    void lagging_slice_kind_within_24_hours_does_not_refetch() {
        // 601606 地区口径停在 2025-12-31、产品已到 2026-06-30；地区两小时前刚抓过，不再打上游
        mapper.delete(Wrappers.<BizSegmentEntity>query().eq("code", "601606"));
        SegmentAdapter adapter = adapter();
        adapter.segments("601606", 5);
        setFetchedAt("601606", "2026-10-30 10:00:00");
        adapter.segments("601606", 5);
        server.verify(
                1,
                getRequestedFor(urlPathEqualTo(F10TestSupport.SEGMENTS_PATH))
                        .withQueryParam("code", equalTo("601606.SH")));
    }

    @Test
    void lagging_slice_kind_is_refetched_after_24_hours() {
        mapper.delete(Wrappers.<BizSegmentEntity>query().eq("code", "601606"));
        SegmentAdapter adapter = adapter();
        adapter.segments("601606", 5);
        setFetchedAt("601606", "2026-10-29 11:00:00");
        adapter.segments("601606", 5);
        server.verify(
                2,
                getRequestedFor(urlPathEqualTo(F10TestSupport.SEGMENTS_PATH))
                        .withQueryParam("code", equalTo("601606.SH")));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-04-29, 2025-09-30",
        "2026-04-30, 2026-03-31",
        "2026-08-30, 2026-03-31",
        "2026-08-31, 2026-06-30",
        "2026-10-30, 2026-06-30",
        "2026-10-31, 2026-09-30",
        "2027-04-29, 2026-09-30",
        "2027-04-30, 2027-03-31"
    })
    void expected_report_date_rolls_over_at_deadlines(String today, String expected) {
        assertThat(SegmentAdapter.expectedReportDate(LocalDate.parse(today)))
                .isEqualTo(LocalDate.parse(expected));
    }

    private void setFetchedAt(String code, String fetchedAt) {
        mapper.update(
                null,
                Wrappers.<BizSegmentEntity>update()
                        .set("fetched_at", fetchedAt)
                        .eq("code", code));
    }

    private SegmentAdapter adapter() {
        ZoneId zone = ZoneId.systemDefault();
        Clock clock =
                Clock.fixed(
                        LocalDate.parse("2026-10-30").atTime(12, 0).atZone(zone).toInstant(),
                        zone);
        return new SegmentAdapter(
                mapper,
                stockMapper,
                new RestClientUpstreamClient(),
                server.baseUrl() + F10TestSupport.SEGMENTS_PATH,
                clock,
                transactionManager);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> slices(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("slices");
    }
}
