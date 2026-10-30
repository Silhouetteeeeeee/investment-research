package com.investmentresearch.data.adapter.eastmoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.persistence.ValHistoryEntity;
import com.investmentresearch.data.persistence.ValHistoryMapper;
import com.investmentresearch.data.persistence.ValPeerEntity;
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
import org.springframework.transaction.PlatformTransactionManager;

class ValuationAdapterTest {
    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private StockMapper stockMapper;
    private ValHistoryMapper historyMapper;
    private ValPeerMapper valPeerMapper;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        server = EastmoneyTestSupport.startServer();
        EastmoneyTestSupport.Db db =
                EastmoneyTestSupport.openDb(EastmoneyTestSupport.copySnapshot(tempDir));
        session = db.session();
        transactionManager = db.transactionManager();
        stockMapper = session.getMapper(StockMapper.class);
        historyMapper = session.getMapper(ValHistoryMapper.class);
        valPeerMapper = session.getMapper(ValPeerMapper.class);
    }

    @AfterEach
    void tearDown() {
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
    void valuation_fetch_parses_and_stores_columns() {
        EastmoneyTestSupport.exec(session, "delete from val_history where code = ?", "600600");
        EastmoneyTestSupport.exec(session, "delete from val_peer");

        ToolResult actual = adapter().valuation("600600");

        // 录制的最新一行：2026-10-30、PE 15.80379154、收盘 50.61、板块 016167 共 17 家
        assertThat(actual.ok()).isTrue();
        assertThat(actual.asOf()).isEqualTo("2026-10-30");
        assertThat(actual.data().get("pe_ttm")).isEqualTo(15.80379154);
        assertThat(actual.data().get("price")).isEqualTo(50.61);
        @SuppressWarnings("unchecked")
        Map<String, Object> industry = (Map<String, Object>) actual.data().get("industry");
        assertThat(industry.get("count")).isEqualTo(17);

        List<ValHistoryEntity> history =
                historyMapper.selectList(
                        Wrappers.<ValHistoryEntity>query()
                                .eq("code", "600600")
                                .orderByAsc("trade_date"));
        assertThat(history).hasSize(2140);
        ValHistoryEntity latest = history.get(history.size() - 1);
        assertThat(latest.getTradeDate()).isEqualTo("2026-10-30");
        assertThat(latest.getPeTtm()).isEqualTo(15.80379154);
        assertThat(latest.getBoardCode()).isEqualTo("016167");

        List<ValPeerEntity> peers =
                valPeerMapper.selectList(
                        Wrappers.<ValPeerEntity>query()
                                .eq("board_code", "016167")
                                .eq("trade_date", "2026-10-30"));
        assertThat(peers).hasSize(17);
        // 按市值降序存，本公司不一定排第一
        assertThat(peers).extracting(ValPeerEntity::getCode).contains("600600");
    }

    @Test
    void duplicate_history_keys_leave_existing_rows_intact() {
        long before =
                historyMapper.selectCount(
                        Wrappers.<ValHistoryEntity>query().eq("code", "600600"));

        // 同一主键插两次会撞 UNIQUE；delete+insert 必须整体回滚，旧行不能丢
        assertThatThrownBy(
                        () ->
                                support()
                                        .saveHistory(
                                                "600600",
                                                List.of(
                                                        historyRow("2026-10-30", 1.0),
                                                        historyRow("2026-10-30", 2.0))))
                .isInstanceOf(RuntimeException.class);

        assertThat(
                        historyMapper.selectCount(
                                Wrappers.<ValHistoryEntity>query().eq("code", "600600")))
                .isEqualTo(before);
        assertThat(
                        historyMapper.selectCount(
                                Wrappers.<ValHistoryEntity>query()
                                        .eq("code", "600600")
                                        .eq("trade_date", "2026-10-27")))
                .isEqualTo(1);
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
        return new ValuationAdapter(support(), stockMapper);
    }

    private ValuationSupport support() {
        return new ValuationSupport(
                historyMapper,
                valPeerMapper,
                new RestClientUpstreamClient(),
                server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                EastmoneyTestSupport.CLOCK,
                transactionManager);
    }

    private static ValHistoryEntity historyRow(String tradeDate, double peTtm) {
        ValHistoryEntity row = new ValHistoryEntity();
        row.setCode("600600");
        row.setTradeDate(tradeDate);
        row.setPeTtm(peTtm);
        row.setFetchedAt("2026-10-30 09:00:00");
        return row;
    }

    private PeerAdapter peerAdapter() {
        return new PeerAdapter(
                session.getMapper(
                        com.investmentresearch.data.persistence.CompanyPeerMapper.class),
                stockMapper,
                new RestClientUpstreamClient(),
                support(),
                server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                EastmoneyTestSupport.CLOCK);
    }
}
