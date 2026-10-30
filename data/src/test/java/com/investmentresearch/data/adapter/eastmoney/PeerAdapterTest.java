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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

class PeerAdapterTest {
    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private CompanyPeerMapper peerMapper;
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
        peerMapper = session.getMapper(CompanyPeerMapper.class);
        stockMapper = session.getMapper(StockMapper.class);
        historyMapper = session.getMapper(ValHistoryMapper.class);
        valPeerMapper = session.getMapper(ValPeerMapper.class);
    }

    @AfterEach
    void tearDown() {
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
        // 数值直接由录制的 Q3 同业行算出：中位营收 587443328.43 元（5.87 亿），本公司 280.35 亿
        assertThat(revenue.get("median")).isEqualTo(5.87);
        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) first.data().get("subject");
        assertThat(subject.get("revenue_yi")).isEqualTo(280.35);
        @SuppressWarnings("unchecked")
        Map<String, Object> industry = (Map<String, Object>) first.data().get("industry");
        assertThat(industry.get("code")).isEqualTo("BK1279");
        assertThat(industry.get("count")).isEqualTo(17);

        server.verify(
                6,
                getRequestedFor(urlPathEqualTo(EastmoneyTestSupport.DATA_PATH))
                        .withQueryParam(
                                "filter", equalTo("(SECURITY_CODE=\"600600\")")));
    }

    @Test
    void migrations_create_the_same_peers_valuation_columns_as_python() throws Exception {
        String fresh =
                "jdbc:sqlite:"
                        + tempDir.resolve("fresh.db").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure()
                .dataSource(fresh, null, null)
                .locations("classpath:db/migration/sqlite")
                .load()
                .migrate();

        String python =
                "jdbc:sqlite:"
                        + EastmoneyTestSupport.SNAPSHOT
                                .toAbsolutePath()
                                .toString()
                                .replace('\\', '/');
        for (String table : List.of("company_peers", "val_history", "val_peer")) {
            assertThat(columnSpecs(fresh, table)).as(table).isEqualTo(columnSpecs(python, table));
        }
    }

    private static List<String> columnSpecs(String url, String table) throws Exception {
        List<String> specs = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (rows.next()) {
                specs.add(
                        rows.getString("name")
                                + "|"
                                + rows.getString("type")
                                + "|notnull="
                                + rows.getInt("notnull")
                                + "|pk="
                                + rows.getInt("pk"));
            }
        }
        return specs;
    }

    private PeerAdapter adapter() {
        ValuationSupport valuationSupport =
                new ValuationSupport(
                        historyMapper,
                        valPeerMapper,
                        new RestClientUpstreamClient(),
                        server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                        EastmoneyTestSupport.CLOCK,
                        transactionManager);
        return new PeerAdapter(
                peerMapper,
                stockMapper,
                new RestClientUpstreamClient(),
                valuationSupport,
                server.baseUrl() + EastmoneyTestSupport.DATA_PATH,
                EastmoneyTestSupport.CLOCK);
    }
}
