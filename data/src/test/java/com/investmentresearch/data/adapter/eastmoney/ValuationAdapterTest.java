package com.investmentresearch.data.adapter.eastmoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.persistence.ValHistoryMapper;
import com.investmentresearch.data.persistence.ValPeerMapper;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import java.nio.file.Path;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValuationAdapterTest {
    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private StockMapper stockMapper;
    private ValHistoryMapper historyMapper;
    private ValPeerMapper valPeerMapper;

    @BeforeEach
    void setUp() throws Exception {
        server = EastmoneyTestSupport.startServer();
        EastmoneyTestSupport.Db db =
                EastmoneyTestSupport.openDb(EastmoneyTestSupport.copySnapshot(tempDir));
        session = db.session();
        stockMapper = session.getMapper(StockMapper.class);
        historyMapper = session.getMapper(ValHistoryMapper.class);
        valPeerMapper = session.getMapper(ValPeerMapper.class);
    }

    @AfterEach
    void tearDown() {
        session.close();
        server.stop();
    }

    @Test
    void valuation_matches_golden_for_two_companies() throws Exception {
        for (String code : List.of("600600", "605499")) {
            EastmoneyTestSupport.makeCachesFresh(session, code);
            ToolResult actual = adapter().valuation(code);
            assertThat(actual.ok()).as(code).isTrue();
            EastmoneyTestSupport.assertMatchesGolden(
                    EastmoneyTestSupport.goldenFor("get_valuation", code), actual);
        }
    }

    @Test
    void valuation_uses_previous_trade_day_when_today_missing() {
        EastmoneyTestSupport.makeCachesFresh(session, "605499");
        EastmoneyTestSupport.exec(
                session,
                "delete from val_history where code = ? and trade_date = ?",
                "605499",
                EastmoneyTestSupport.PINNED_DATE);

        ToolResult actual = adapter().valuation("605499");

        assertThat(actual.ok()).isTrue();
        assertThat(actual.asOf()).isEqualTo("2026-10-29");
        assertThat(actual.sources().get(0).id()).isEqualTo("valuation:605499:2026-10-29");
        assertThat(actual.data().get("pe_ttm")).isNotNull();
    }

    @Test
    void empty_datacenter_payload_returns_ok_false() {
        EastmoneyTestSupport.exec(session, "delete from company_peers where code = ?", "600600");
        EastmoneyTestSupport.exec(session, "delete from val_history where code = ?", "605499");

        server.stubFor(
                get(urlPathEqualTo(EastmoneyTestSupport.DATA_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/html")
                                        .withBody("<html>WAF</html>")));
        ToolResult peersHtml = peerAdapter().peers("600600", 8);
        assertThat(peersHtml.ok()).as("HTML 200").isFalse();
        assertThat(peersHtml.data()).isNull();
        assertThat(peersHtml.error()).contains("获取同业数据失败");
        ToolResult valuationHtml = adapter().valuation("605499");
        assertThat(valuationHtml.ok()).as("HTML 200").isFalse();
        assertThat(valuationHtml.error()).contains("获取估值数据失败");

        server.stubFor(
                get(urlPathEqualTo(EastmoneyTestSupport.DATA_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("")));
        ToolResult emptyPeers = peerAdapter().peers("600600", 8);
        assertThat(emptyPeers.ok()).as("empty 200").isFalse();
        assertThat(emptyPeers.data()).as("empty 200").isNull();
        ToolResult emptyValuation = adapter().valuation("605499");
        assertThat(emptyValuation.ok()).as("empty 200").isFalse();

        // 200 但 result.data 不是数组：信封校验按上游失败处理
        server.stubFor(
                get(urlPathEqualTo(EastmoneyTestSupport.DATA_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"success\": true, \"result\": {\"data\": {}}}")));
        ToolResult malformed = peerAdapter().peers("600600", 8);
        assertThat(malformed.ok()).as("bad envelope").isFalse();
        assertThat(malformed.error()).contains("接口返回格式异常");
    }

    private ValuationAdapter adapter() {
        return new ValuationAdapter(
                new ValuationSupport(
                        historyMapper,
                        valPeerMapper,
                        new RestClientUpstreamClient(),
                        server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                        EastmoneyTestSupport.CLOCK),
                stockMapper);
    }

    private PeerAdapter peerAdapter() {
        return new PeerAdapter(
                session.getMapper(
                        com.investmentresearch.data.persistence.CompanyPeerMapper.class),
                stockMapper,
                new RestClientUpstreamClient(),
                new ValuationSupport(
                        historyMapper,
                        valPeerMapper,
                        new RestClientUpstreamClient(),
                        server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                        EastmoneyTestSupport.CLOCK),
                server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                EastmoneyTestSupport.CLOCK);
    }
}
