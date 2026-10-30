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

class SegmentAdapterTest {
    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private BizSegmentMapper mapper;
    private StockMapper stockMapper;

    @BeforeEach
    void setUp() throws Exception {
        server = F10TestSupport.startServer();
        String url = F10TestSupport.copySnapshot(tempDir);
        session = F10TestSupport.openSession(url);
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
                clock);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> slices(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("slices");
    }
}
