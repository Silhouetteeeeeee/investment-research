package com.investmentresearch.data.adapter.eastmoney;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.CompanyPeerMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.persistence.ValHistoryMapper;
import com.investmentresearch.data.persistence.ValPeerMapper;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerAdapterTest {
    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private CompanyPeerMapper peerMapper;
    private StockMapper stockMapper;
    private ValHistoryMapper historyMapper;
    private ValPeerMapper valPeerMapper;

    @BeforeEach
    void setUp() throws Exception {
        server = EastmoneyTestSupport.startServer();
        EastmoneyTestSupport.Db db =
                EastmoneyTestSupport.openDb(EastmoneyTestSupport.copySnapshot(tempDir));
        session = db.session();
        peerMapper = session.getMapper(CompanyPeerMapper.class);
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
    void peers_match_golden_for_two_companies() throws Exception {
        for (String code : List.of("600600", "605499")) {
            EastmoneyTestSupport.makeCachesFresh(session, code);
            ToolResult actual = adapter().peers(code, 8);
            assertThat(actual.ok()).as(code).isTrue();
            EastmoneyTestSupport.assertMatchesGolden(
                    EastmoneyTestSupport.goldenFor("get_peers", code), actual);
        }
    }

    @Test
    void peer_ranking_and_median_are_stable() {
        EastmoneyTestSupport.makeCachesFresh(session, "600600");
        // 删掉缓存，强制走 WireMock 的 Q3 同业（本公司报表 3 页 + 同业 1 页）
        EastmoneyTestSupport.exec(session, "delete from company_peers where code = ?", "600600");
        ToolResult first = adapter().peers("600600", 8);
        EastmoneyTestSupport.exec(session, "delete from company_peers where code = ?", "600600");
        ToolResult second = adapter().peers("600600", 8);

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isTrue();
        assertThat(first.asOf()).isEqualTo("2026-09-30");
        assertThat(second.data()).isEqualTo(first.data());

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) first.data().get("stats");
        @SuppressWarnings("unchecked")
        Map<String, Object> revenue = (Map<String, Object>) stats.get("revenue");
        assertThat(revenue.get("median")).isNotNull();
        assertThat(revenue.get("rank")).isEqualTo(1);
        assertThat(revenue.get("count")).isEqualTo(17);

        server.verify(
                6,
                getRequestedFor(urlPathEqualTo(EastmoneyTestSupport.DATA_PATH))
                        .withQueryParam(
                                "filter", equalTo("(SECURITY_CODE=\"600600\")")));
    }

    private PeerAdapter adapter() {
        ValuationSupport valuationSupport =
                new ValuationSupport(
                        historyMapper,
                        valPeerMapper,
                        new RestClientUpstreamClient(),
                        server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                        EastmoneyTestSupport.CLOCK);
        return new PeerAdapter(
                peerMapper,
                stockMapper,
                new RestClientUpstreamClient(),
                valuationSupport,
                server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                EastmoneyTestSupport.CLOCK);
    }
}
