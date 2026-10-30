package com.investmentresearch.data.adapter.finance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.FinBalanceEntity;
import com.investmentresearch.data.persistence.FinBalanceMapper;
import com.investmentresearch.data.persistence.FinCashflowEntity;
import com.investmentresearch.data.persistence.FinCashflowMapper;
import com.investmentresearch.data.persistence.FinIncomeEntity;
import com.investmentresearch.data.persistence.FinIncomeMapper;
import com.investmentresearch.data.persistence.FinMetricsEntity;
import com.investmentresearch.data.persistence.FinMetricsMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FinanceAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SINCE_PATH =
            "/cn/api/openapi.php/CompanyFinanceService.getFinanceReport2022";
    private static final Path WIREMOCK = Path.of("src/test/resources/wiremock/sina");
    private static final Path GOLDENS = Path.of("../core/src/test/resources/golden/tools");
    private static final Path SNAPSHOT =
            Path.of("../core/src/test/resources/fixtures/stock-snapshot.db");
    private static final String CUTOFF = "2011-10-30";

    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private FinBalanceMapper balanceMapper;
    private FinIncomeMapper incomeMapper;
    private FinCashflowMapper cashflowMapper;
    private FinMetricsMapper metricsMapper;
    private FinanceAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        try (Stream<Path> files = Files.list(WIREMOCK)) {
            for (Path file : files.sorted().toList()) {
                server.addStubMapping(
                        Json.read(
                                Files.readString(file, StandardCharsets.UTF_8),
                                StubMapping.class));
            }
        }

        Path database = tempDir.resolve("stock.db");
        Files.copy(SNAPSHOT, database, StandardCopyOption.REPLACE_EXISTING);
        String url =
                "jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/');
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(
                new Environment(
                        "test",
                        new JdbcTransactionFactory(),
                        new UnpooledDataSource("org.sqlite.JDBC", url, null, null)));
        configuration.addMapper(StockMapper.class);
        configuration.addMapper(FinBalanceMapper.class);
        configuration.addMapper(FinIncomeMapper.class);
        configuration.addMapper(FinCashflowMapper.class);
        configuration.addMapper(FinMetricsMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        balanceMapper = session.getMapper(FinBalanceMapper.class);
        incomeMapper = session.getMapper(FinIncomeMapper.class);
        cashflowMapper = session.getMapper(FinCashflowMapper.class);
        metricsMapper = session.getMapper(FinMetricsMapper.class);
        StockMapper stockMapper = session.getMapper(StockMapper.class);

        Clock clock =
                Clock.fixed(
                        LocalDate.of(2026, 10, 30)
                                .atStartOfDay()
                                .atZone(ZoneId.systemDefault())
                                .toInstant(),
                        ZoneId.systemDefault());
        adapter =
                new FinanceAdapter(
                        balanceMapper,
                        incomeMapper,
                        cashflowMapper,
                        metricsMapper,
                        stockMapper,
                        new RestClientUpstreamClient(),
                        server.baseUrl() + SINCE_PATH,
                        clock);
    }

    @AfterEach
    void tearDown() {
        session.close();
        server.stop();
    }

    @Test
    void finance_matches_golden_for_all_five_companies() throws Exception {
        List<Path> goldens;
        try (Stream<Path> files = Files.list(GOLDENS)) {
            goldens =
                    files.filter(
                                    file ->
                                            file.getFileName()
                                                    .toString()
                                                    .startsWith("get_finance__"))
                            .sorted()
                            .toList();
        }
        assertThat(goldens).hasSize(5);

        for (Path file : goldens) {
            JsonNode golden = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String code = golden.path("default_company").asText();
            ToolResult actual = adapter.finance(code);

            Map<String, Object> expected =
                    MAPPER.convertValue(golden.path("result"), Map.class);
            assertApprox(file.toString(), expected, resultMap(actual));
        }
    }

    @Test
    void report_with_missing_cashflow_is_partial_not_crash() throws Exception {
        server.resetAll();
        register("finance-600600-fzb.json");
        register("finance-600600-lrb.json");
        server.stubFor(
                get(urlPathEqualTo(SINCE_PATH))
                        .withQueryParam("source", com.github.tomakehurst.wiremock.client.WireMock.equalTo("llb"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"result\":{\"status\":{\"code\":0},"
                                                        + "\"data\":{\"report_date\":[],\"report_list\":{}}}}")));

        adapter.refresh("600600");

        assertThat(balanceMapper.selectCount(Wrappers.<FinBalanceEntity>query().eq("code", "600600")))
                .isPositive();
        assertThat(incomeMapper.selectCount(Wrappers.<FinIncomeEntity>query().eq("code", "600600")))
                .isPositive();
        assertThat(cashflowMapper.selectCount(Wrappers.<FinCashflowEntity>query().eq("code", "600600")))
                .isZero();

        ToolResult result = adapter.finance("600600");
        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> cashflowDetail = (Map<String, Object>) result.data().get("cashflow_detail");
        assertThat(cashflowDetail).isNotEmpty().allSatisfy((key, value) -> assertThat(value).isNull());
    }

    @Test
    void upstream_returns_html_returns_ok_false() {
        deleteCode("600600");
        server.resetAll();
        server.stubFor(
                get(urlPathEqualTo(SINCE_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/html")
                                        .withBody("<html>WAF</html>")));

        ToolResult result = adapter.finance("600600");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("失败");
        assertThat(result.data()).isNull();
    }

    @Test
    void refreshed_rows_are_upserted_not_duplicated() {
        adapter.refresh("600600");
        long balances = countBalance("600600");
        long incomes = countIncome("600600");
        long cashflows = countCashflow("600600");
        long metrics = countMetrics("600600");

        adapter.refresh("600600");

        assertThat(balances).isPositive();
        assertThat(countBalance("600600")).isEqualTo(balances);
        assertThat(countIncome("600600")).isEqualTo(incomes);
        assertThat(countCashflow("600600")).isEqualTo(cashflows);
        assertThat(countMetrics("600600")).isEqualTo(metrics);
        // 15 年窗口：夹具里的老报表必须被切掉
        assertThat(balanceMapper.selectList(Wrappers.<FinBalanceEntity>query().eq("code", "600600")))
                .allSatisfy(row -> assertThat(row.getReportDate()).isGreaterThanOrEqualTo(CUTOFF));
    }

    @Test
    void refreshed_metrics_match_python_snapshot_for_overlapping_periods() {
        Map<String, Map<String, Object>> before = new LinkedHashMap<>();
        for (FinMetricsEntity row :
                metricsMapper.selectList(Wrappers.<FinMetricsEntity>query().eq("code", "600600"))) {
            before.put(row.getReportDate(), MAPPER.convertValue(row, ROW));
        }

        adapter.refresh("600600");

        int compared = 0;
        for (FinMetricsEntity row :
                metricsMapper.selectList(Wrappers.<FinMetricsEntity>query().eq("code", "600600"))) {
            Map<String, Object> expected = before.get(row.getReportDate());
            if (expected == null) {
                continue;
            }
            compared++;
            Map<String, Object> actual = MAPPER.convertValue(row, ROW);
            for (String field : expected.keySet()) {
                if (field.equals("code")
                        || field.equals("report_date")
                        || field.equals("computedAt")) {
                    continue;
                }
                assertApprox(
                        row.getReportDate() + "." + field, expected.get(field), actual.get(field));
            }
        }
        assertThat(compared).isPositive();
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<
                    LinkedHashMap<String, Object>>
            ROW = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private void register(String fixture) throws IOException {
        Path file = WIREMOCK.resolve(fixture);
        server.addStubMapping(
                Json.read(Files.readString(file, StandardCharsets.UTF_8), StubMapping.class));
    }

    private void deleteCode(String code) {
        balanceMapper.delete(Wrappers.<FinBalanceEntity>query().eq("code", code));
        incomeMapper.delete(Wrappers.<FinIncomeEntity>query().eq("code", code));
        cashflowMapper.delete(Wrappers.<FinCashflowEntity>query().eq("code", code));
        metricsMapper.delete(Wrappers.<FinMetricsEntity>query().eq("code", code));
    }

    private long countBalance(String code) {
        return balanceMapper.selectCount(Wrappers.<FinBalanceEntity>query().eq("code", code));
    }

    private long countIncome(String code) {
        return incomeMapper.selectCount(Wrappers.<FinIncomeEntity>query().eq("code", code));
    }

    private long countCashflow(String code) {
        return cashflowMapper.selectCount(Wrappers.<FinCashflowEntity>query().eq("code", code));
    }

    private long countMetrics(String code) {
        return metricsMapper.selectCount(Wrappers.<FinMetricsEntity>query().eq("code", code));
    }

    private static Map<String, Object> resultMap(ToolResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("company", result.company());
        out.put("as_of", result.asOf());
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Source source : result.sources()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", source.id());
            item.put("label", source.label());
            item.put("detail", source.detail());
            item.put("as_of", source.asOf());
            item.put("fetched_at", source.fetchedAt());
            item.put("url", source.url());
            sources.add(item);
        }
        out.put("sources", sources);
        out.put("data", result.data());
        out.put("error", result.error());
        out.put("cached", result.cached());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void assertApprox(String label, Object expected, Object actual) {
        if (expected instanceof Number && actual instanceof Number) {
            assertThat(((Number) actual).doubleValue())
                    .as(label)
                    .isCloseTo(((Number) expected).doubleValue(), org.assertj.core.data.Offset.offset(1e-6));
        } else if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            assertThat(actualMap.keySet()).as(label).isEqualTo(expectedMap.keySet());
            for (Object key : expectedMap.keySet()) {
                assertApprox(label + "." + key, expectedMap.get(key), actualMap.get(key));
            }
        } else if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            assertThat(actualList).as(label).hasSameSizeAs(expectedList);
            for (int i = 0; i < expectedList.size(); i++) {
                assertApprox(label + "[" + i + "]", expectedList.get(i), actualList.get(i));
            }
        } else {
            assertThat(actual).as(label).isEqualTo(expected);
        }
    }
}
