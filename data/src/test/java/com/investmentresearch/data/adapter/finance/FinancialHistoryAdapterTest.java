package com.investmentresearch.data.adapter.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.FinBalanceMapper;
import com.investmentresearch.data.persistence.FinCashflowMapper;
import com.investmentresearch.data.persistence.FinDisclosureMapper;
import com.investmentresearch.data.persistence.FinIncomeMapper;
import com.investmentresearch.data.persistence.FinMetricsMapper;
import com.investmentresearch.data.persistence.StockMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class FinancialHistoryAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDENS = Path.of("../core/src/test/resources/golden/tools");
    private static final Path SNAPSHOT =
            Path.of("../core/src/test/resources/fixtures/stock-snapshot.db");

    @TempDir Path tempDir;

    private FinancialHistoryAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        Path database = tempDir.resolve("stock.db");
        Files.copy(SNAPSHOT, database, StandardCopyOption.REPLACE_EXISTING);
        String jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/');
        // 缓存新鲜，且没有更新的披露记录，读库路径不会走网络
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "update fin_balance set fetched_at = '2026-10-31 09:00:00'");
            statement.executeUpdate(
                    "update fin_income set fetched_at = '2026-10-31 09:00:00'");
            statement.executeUpdate(
                    "update fin_cashflow set fetched_at = '2026-10-31 09:00:00'");
            statement.executeUpdate("delete from fin_disclosure");
        }

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
        SqlSessionTemplate session = new SqlSessionTemplate(factory);

        Clock clock =
                Clock.fixed(
                        LocalDate.of(2026, 10, 31)
                                .atTime(12, 0)
                                .atZone(ZoneId.systemDefault())
                                .toInstant(),
                        ZoneId.systemDefault());
        FinanceAdapter finance =
                new FinanceAdapter(
                        session.getMapper(FinBalanceMapper.class),
                        session.getMapper(FinIncomeMapper.class),
                        session.getMapper(FinCashflowMapper.class),
                        session.getMapper(FinMetricsMapper.class),
                        session.getMapper(FinDisclosureMapper.class),
                        session.getMapper(StockMapper.class),
                        new com.investmentresearch.data.upstream.UpstreamClient() {
                            @Override
                            public String get(String path, Map<String, String> headers) {
                                throw new AssertionError("不应访问上游：" + path);
                            }

                            @Override
                            public String get(
                                    String path,
                                    java.nio.charset.Charset charset,
                                    Map<String, String> headers) {
                                throw new AssertionError("不应访问上游：" + path);
                            }
                        },
                        "http://unused",
                        clock,
                        new DataSourceTransactionManager(dataSource));
        adapter = new FinancialHistoryAdapter(finance, session.getMapper(StockMapper.class));
    }

    @Test
    void matches_golden_for_default_and_five_year_windows() throws Exception {
        List<Path> goldens;
        try (Stream<Path> files = Files.list(GOLDENS)) {
            goldens =
                    files.filter(
                                    file ->
                                            file.getFileName()
                                                    .toString()
                                                    .startsWith("get_financial_history__"))
                            .sorted()
                            .toList();
        }
        assertThat(goldens).hasSize(10);
        for (Path file : goldens) {
            JsonNode golden = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String code = golden.path("default_company").asText();
            int years = golden.path("arguments").path("years").asInt(10);
            @SuppressWarnings("unchecked")
            Map<String, Object> expected = MAPPER.convertValue(golden.path("result"), Map.class);
            assertThat(json(resultMap(adapter.financialHistory(code, years))))
                    .as(file.toString())
                    .isEqualTo(json(expected));
        }
    }

    /** 与 {@code EastmoneyTestSupport.assertMatchesGolden} 同口径：只忽略 sources[].fetched_at。 */
    private static String json(Map<String, Object> result) throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");
        for (Map<String, Object> source : sources) {
            source.remove("fetched_at");
        }
        return MAPPER.writeValueAsString(result);
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
}
