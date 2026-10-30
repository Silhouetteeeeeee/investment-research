package com.investmentresearch.data.adapter.finance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.investmentresearch.data.persistence.FinDisclosureMapper;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.Base64;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class FinanceAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper SNAKE =
            new ObjectMapper()
                    .setPropertyNamingStrategy(
                            com.fasterxml.jackson.databind.PropertyNamingStrategies
                                    .SNAKE_CASE);
    private static final String SINCE_PATH =
            "/cn/api/openapi.php/CompanyFinanceService.getFinanceReport2022";
    private static final Path WIREMOCK = Path.of("src/test/resources/wiremock/sina");
    private static final Path GOLDENS = Path.of("../core/src/test/resources/golden/tools");
    private static final Path SNAPSHOT =
            Path.of("../core/src/test/resources/fixtures/stock-snapshot.db");
    private static final String CUTOFF = "2011-10-30";

    @TempDir Path tempDir;

    private WireMockServer server;
    private String jdbcUrl;
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
        jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/');
        UnpooledDataSource dataSource =
                new UnpooledDataSource("org.sqlite.JDBC", jdbcUrl, null, null);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(
                new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(StockMapper.class);
        configuration.addMapper(FinBalanceMapper.class);
        configuration.addMapper(FinIncomeMapper.class);
        configuration.addMapper(FinCashflowMapper.class);
        configuration.addMapper(FinMetricsMapper.class);
        configuration.addMapper(FinDisclosureMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = new SqlSessionTemplate(factory);
        balanceMapper = session.getMapper(FinBalanceMapper.class);
        incomeMapper = session.getMapper(FinIncomeMapper.class);
        cashflowMapper = session.getMapper(FinCashflowMapper.class);
        metricsMapper = session.getMapper(FinMetricsMapper.class);
        StockMapper stockMapper = session.getMapper(StockMapper.class);
        FinDisclosureMapper disclosureMapper = session.getMapper(FinDisclosureMapper.class);

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
                        disclosureMapper,
                        stockMapper,
                        new RestClientUpstreamClient(),
                        server.baseUrl() + SINCE_PATH,
                        clock,
                        new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void finance_matches_golden_for_all_five_companies() throws Exception {
        // 金标准录制于 2026-10-28；快照库里的 10-29 披露记录会让 600600 重抓，先清掉
        clearDisclosures();
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
    void snapshot_with_fewer_periods_than_trend_window_does_not_crash() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("report_date", "2026-09-30");

        Map<String, Object> snapshot = FinanceSnapshot.build(List.of(row), 8, "测试");

        assertThat((List<?>) snapshot.get("trend")).hasSize(1);
    }

    @Test
    void disclosed_newer_period_triggers_refresh_of_fresh_cache() {
        // 快照里 600600 缓存只到 2026-06-30，披露日历显示 09-30 已在 10-29 披露
        ToolResult result = adapter.finance("600600");

        assertThat(result.ok()).isTrue();
        assertThat(result.asOf()).isEqualTo("2026-09-30");
        assertThat(result.data().get("period")).isEqualTo("2026-09-30");
        assertThat(countMetrics("600600")).isEqualTo(60);
    }

    @Test
    void empty_table_retries_only_after_thirty_minutes() throws Exception {
        cashflowMapper.delete(Wrappers.<FinCashflowEntity>query().eq("code", "600600"));

        setFetchedAt("2026-10-29 23:55:00"); // 距固定时钟 5 分钟
        assertThat(adapter.finance("600600").ok()).isTrue();
        server.verify(0, getRequestedFor(urlPathEqualTo(SINCE_PATH)));

        setFetchedAt("2026-10-29 23:29:00"); // 31 分钟
        assertThat(adapter.finance("600600").ok()).isTrue();
        server.verify(3, getRequestedFor(urlPathEqualTo(SINCE_PATH)));
    }

    @Test
    void migrations_create_the_same_fin_columns_as_python() throws Exception {
        String fresh =
                "jdbc:sqlite:"
                        + tempDir.resolve("fresh.db").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure()
                .dataSource(fresh, null, null)
                .locations("classpath:db/migration/sqlite")
                .target("8")
                .load()
                .migrate();

        String python =
                "jdbc:sqlite:"
                        + SNAPSHOT.toAbsolutePath().toString().replace('\\', '/');
        for (String table :
                List.of(
                        "fin_balance",
                        "fin_income",
                        "fin_cashflow",
                        "fin_metrics",
                        "fin_disclosure")) {
            assertThat(columnSpecs(fresh, table)).as(table).isEqualTo(columnSpecs(python, table));
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
        Map<String, Object> before = metricRow("600600", "2026-06-30");

        adapter.refresh("600600");

        assertThat(balances).isPositive();
        assertThat(countBalance("600600")).isEqualTo(balances);
        assertThat(countIncome("600600")).isEqualTo(incomes);
        assertThat(countCashflow("600600")).isEqualTo(cashflows);
        assertThat(countMetrics("600600")).isEqualTo(metrics);
        assertThat(metricRow("600600", "2026-06-30")).isEqualTo(before);
        // 15 年窗口：夹具里的老报表必须被切掉
        assertThat(balanceMapper.selectList(Wrappers.<FinBalanceEntity>query().eq("code", "600600")))
                .allSatisfy(row -> assertThat(row.getReportDate()).isGreaterThanOrEqualTo(CUTOFF));
    }

    @Test
    void failed_refresh_rolls_back_and_keeps_previous_rows() throws Exception {
        long balances = countBalance("600600");
        long incomes = countIncome("600600");
        long cashflows = countCashflow("600600");
        long metrics = countMetrics("600600");

        server.resetAll();
        register("finance-600600-fzb.json");
        register("finance-600600-llb.json");
        server.stubFor(
                get(urlPathEqualTo(SINCE_PATH))
                        .withQueryParam("source", equalTo("lrb"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(duplicateReportDateBody())));

        assertThatThrownBy(() -> adapter.refresh("600600"))
                .isInstanceOf(RuntimeException.class);

        // 删+插同事务：第二行主键冲突后，旧数据必须原样保留
        assertThat(countBalance("600600")).isEqualTo(balances);
        assertThat(countIncome("600600")).isEqualTo(incomes);
        assertThat(countCashflow("600600")).isEqualTo(cashflows);
        assertThat(countMetrics("600600")).isEqualTo(metrics);
    }

    @Test
    void refreshed_metrics_match_python_snapshot_for_overlapping_periods() {
        Map<String, Map<String, Object>> before = new LinkedHashMap<>();
        for (FinMetricsEntity row :
                metricsMapper.selectList(Wrappers.<FinMetricsEntity>query().eq("code", "600600"))) {
            before.put(row.getReportDate(), SNAKE.convertValue(row, ROW));
        }

        adapter.refresh("600600");

        List<FinMetricsEntity> refreshed =
                metricsMapper.selectList(
                        Wrappers.<FinMetricsEntity>query().eq("code", "600600"));
        int compared = 0;
        for (FinMetricsEntity row : refreshed) {
            Map<String, Object> expected = before.get(row.getReportDate());
            if (expected == null) {
                continue;
            }
            compared++;
            Map<String, Object> actual = SNAKE.convertValue(row, ROW);
            for (String field : expected.keySet()) {
                if (field.equals("code")
                        || field.equals("report_date")
                        || field.equals("computed_at")) {
                    continue;
                }
                assertApprox(
                        row.getReportDate() + "." + field, expected.get(field), actual.get(field));
            }
        }
        assertThat(compared).isEqualTo(59);
        assertThat(refreshed).hasSize(60);

        // 2026-09-30 不在旧快照里，期望值由 Python 对同一份夹具跑 build_metrics 得到
        Map<String, Object> q3 =
                refreshed.stream()
                        .filter(row -> "2026-09-30".equals(row.getReportDate()))
                        .findFirst()
                        .map(row -> SNAKE.convertValue(row, ROW))
                        .orElseThrow();
        PYTHON_2026_Q3.forEach(
                (field, expected) ->
                        assertApprox("2026-09-30." + field, expected, q3.get(field)));
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<
                    LinkedHashMap<String, Object>>
            ROW = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private static final Map<String, Double> PYTHON_2026_Q3 =
            Map.ofEntries(
                    Map.entry("revenue", 28035229726.0),
                    Map.entry("revenue_yoy", -4.533851926943857),
                    Map.entry("revenue_sq", 8380380089.0),
                    Map.entry("revenue_sq_yoy", -5.578537415156509),
                    Map.entry("net_profit", 5163190901.0),
                    Map.entry("net_profit_yoy", -4.424916070825007),
                    Map.entry("net_profit_sq", 1176503196.0),
                    Map.entry("net_profit_sq_yoy", -17.627335680528965),
                    Map.entry("gross_margin", 44.04448940023642),
                    Map.entry("net_margin", 18.41679540871261),
                    Map.entry("rd_ratio", 0.32581307124189035),
                    Map.entry("roe", 21.410441950958933),
                    Map.entry("roa", 13.141273169324183),
                    Map.entry("debt_ratio", 36.912628692829315),
                    Map.entry("current_ratio", 1.6842136334044049),
                    Map.entry("quick_ratio", 1.5501109761814669),
                    Map.entry("ar_turnover_days", 1.480867888608647),
                    Map.entry("inventory_turnover_days", 46.175454851336134),
                    Map.entry("ocf_to_net_profit", 1.2550078794772033),
                    Map.entry("cash_to_revenue", 107.9848624601208),
                    Map.entry("operating_cash_flow", 6479845264.0),
                    Map.entry("capex", 1616523049.0),
                    Map.entry("free_cash_flow", 4863322215.0),
                    Map.entry("net_debt", -20350974112.0));

    private void clearDisclosures() throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from fin_disclosure");
        }
    }

    private void setFetchedAt(String stamp) throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "update fin_balance set fetched_at = '" + stamp + "' where code = '600600'");
            statement.executeUpdate(
                    "update fin_income set fetched_at = '" + stamp + "' where code = '600600'");
        }
    }

    private Map<String, Object> metricRow(String code, String reportDate) {
        FinMetricsEntity row =
                metricsMapper
                        .selectList(
                                Wrappers.<FinMetricsEntity>query()
                                        .eq("code", code)
                                        .eq("report_date", reportDate))
                        .get(0);
        Map<String, Object> values = SNAKE.convertValue(row, ROW);
        values.remove("computed_at");
        return values;
    }

    /** 列名、类型、非空、主键序号都要一致，只比列名漏得太多。 */
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

    private void register(String fixture) throws IOException {
        Path file = WIREMOCK.resolve(fixture);
        server.addStubMapping(
                Json.read(Files.readString(file, StandardCharsets.UTF_8), StubMapping.class));
    }

    /** 同一报告期出现两种写法，归一化后主键冲突，用来验证整次写入会回滚。 */
    private static String duplicateReportDateBody() throws IOException {
        JsonNode wire =
                MAPPER.readTree(
                        Files.readString(
                                WIREMOCK.resolve("finance-600600-lrb.json"),
                                StandardCharsets.UTF_8));
        JsonNode payload =
                MAPPER.readTree(
                        Base64.getDecoder()
                                .decode(wire.path("response").path("base64Body").asText()));
        ObjectNode data = (ObjectNode) payload.path("result").path("data");
        ObjectNode duplicate = ((ObjectNode) data.path("report_date").get(0)).deepCopy();
        duplicate.put("date_value", "2026-09-30");
        ((ArrayNode) data.path("report_date")).add(duplicate);
        ObjectNode reports = (ObjectNode) data.path("report_list");
        reports.set("2026-09-30", reports.get("20260930").deepCopy());
        return MAPPER.writeValueAsString(payload);
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
            assertThat(new ArrayList<>(actualMap.keySet()))
                    .as(label)
                    .isEqualTo(new ArrayList<>(expectedMap.keySet()));
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
