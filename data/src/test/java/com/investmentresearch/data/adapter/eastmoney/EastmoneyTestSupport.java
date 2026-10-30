package com.investmentresearch.data.adapter.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.CompanyPeerMapper;
import com.investmentresearch.data.persistence.FinIncomeMapper;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.persistence.ValHistoryMapper;
import com.investmentresearch.data.persistence.ValPeerMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
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
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** 同业与估值适配器测试共用夹具：快照复制、WireMock 回放、金标准比对、缓存时间。 */
final class EastmoneyTestSupport {
    static final String DATA_PATH = "/api/data/v1/get";
    /** 与金标准的 generated_on 一致；缓存距今必须小于 6 小时。 */
    static final String PINNED_DATE = "2026-10-30";
    static final Clock CLOCK =
            Clock.fixed(
                    LocalDateTime.parse("2026-10-30T12:00:00")
                            .atZone(ZoneId.systemDefault())
                            .toInstant(),
                    ZoneId.systemDefault());
    private static final String FRESH_TIMESTAMP = "2026-10-30 09:00:00";
    private static final Path REPO_ROOT = findRepoRoot();
    static final Path FIXTURES =
            REPO_ROOT.resolve("data/src/test/resources/wiremock/eastmoney");
    static final Path GOLDENS = REPO_ROOT.resolve("core/src/test/resources/golden/tools");
    static final Path SNAPSHOT =
            REPO_ROOT.resolve("core/src/test/resources/fixtures/stock-snapshot.db");
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 金标准生成当天的时钟：窗口结束日 2026-10-31。 */
    static final Clock CLOCK_END =
            Clock.fixed(
                    LocalDateTime.parse("2026-10-31T12:00:00")
                            .atZone(ZoneId.systemDefault())
                            .toInstant(),
                    ZoneId.systemDefault());

    private EastmoneyTestSupport() {}

    /** 读缓存路径的测试不允许访问上游。 */
    static com.investmentresearch.data.upstream.UpstreamClient offline() {
        return new com.investmentresearch.data.upstream.UpstreamClient() {
            @Override
            public String get(String path, Map<String, String> headers) {
                throw new IllegalStateException("不应访问上游：" + path);
            }

            @Override
            public String get(String path, java.nio.charset.Charset charset, Map<String, String> headers) {
                throw new IllegalStateException("不应访问上游：" + path);
            }
        };
    }

    static ResearchCacheStore cacheStore(String url) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        return new ResearchCacheStore(new JdbcTemplate(dataSource));
    }

    static WireMockServer startServer() throws IOException {
        WireMockServer server =
                new WireMockServer(
                        com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                                .dynamicPort());
        server.start();
        try (Stream<Path> files = Files.list(FIXTURES)) {
            for (Path file :
                    files.filter(path -> path.getFileName().toString().startsWith("datacenter-"))
                            .sorted()
                            .toList()) {
                server.addStubMapping(
                        Json.read(
                                Files.readString(file, StandardCharsets.UTF_8),
                                StubMapping.class));
            }
        }
        return server;
    }

    static String copySnapshot(Path tempDir) throws IOException {
        Path database = tempDir.resolve("stock.db");
        Files.copy(SNAPSHOT, database, StandardCopyOption.REPLACE_EXISTING);
        return "jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/');
    }

    record Db(SqlSession session, PlatformTransactionManager transactionManager) {}

    static Db openDb(String url) {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.sqlite.JDBC", url, null, null);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // SpringManagedTransactionFactory 让 mapper 复用 TransactionTemplate 绑定的连接，
        // 否则事务包的是另一个连接，删掉事务测试也不会失败
        configuration.setEnvironment(
                new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(StockMapper.class);
        configuration.addMapper(FinIncomeMapper.class);
        configuration.addMapper(CompanyPeerMapper.class);
        configuration.addMapper(ValHistoryMapper.class);
        configuration.addMapper(ValPeerMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        // SqlSessionTemplate 每次调用都开新会话，事务活动时复用 TransactionTemplate 绑定的连接
        return new Db(new SqlSessionTemplate(factory), new DataSourceTransactionManager(dataSource));
    }

    /**
     * 金标准生成时强制刷新过缓存，快照里的 fetched_at 已经过期；把三份缓存改成本次测试的抓取时间，
     * 让适配器读缓存而不是去请求录制里没有的 Q3/10-30 之后的接口。
     */
    static void makeCachesFresh(SqlSession session, String code) {
        exec(session, "update company_peers set fetched_at = ? where code = ?", FRESH_TIMESTAMP, code);
        exec(session, "update val_history set fetched_at = ? where code = ?", FRESH_TIMESTAMP, code);
        exec(session, "update val_peer set fetched_at = ?", FRESH_TIMESTAMP);
    }

    static void exec(SqlSession session, String sql, Object... args) {
        // SqlSessionTemplate 每次调用后都会关掉自己的连接，所以这里另开一条连接直接用
        String url =
                ((UnpooledDataSource) session.getConfiguration().getEnvironment().getDataSource())
                        .getUrl();
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    static List<Path> goldens(String tool) throws IOException {
        try (Stream<Path> files = Files.list(GOLDENS)) {
            return files.filter(path -> path.getFileName().toString().startsWith(tool + "__"))
                    .sorted()
                    .toList();
        }
    }

    static Path goldenFor(String tool, String code) throws IOException {
        return goldens(tool).stream()
                .filter(path -> path.getFileName().toString().contains("__" + code + "__"))
                .findFirst()
                .orElseThrow();
    }

    static JsonNode golden(Path file) throws IOException {
        return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 与金标准逐字段比对；source 的 fetched_at 是抓取时钟，是唯一忽略的字段。 */
    static void assertMatchesGolden(Path file, ToolResult actual) throws IOException {
        JsonNode golden = golden(file).path("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = MAPPER.convertValue(golden, Map.class);
        Map<String, Object> actualMap = resultMap(actual);
        stripFetchedAt(expected);
        stripFetchedAt(actualMap);
        // Map.equals 不看键顺序，序列化后再比才能发现顺序/键集差异
        assertThat(MAPPER.writeValueAsString(actualMap))
                .as(file.toString())
                .isEqualTo(MAPPER.writeValueAsString(expected));
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
    private static void stripFetchedAt(Map<String, Object> result) {
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");
        for (Map<String, Object> source : sources) {
            source.remove("fetched_at");
        }
    }

    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom)
                        && Files.readString(pom, StandardCharsets.UTF_8)
                                .contains("<module>data</module>")) {
                    return dir;
                }
            } catch (IOException ignored) {
                // keep walking up
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
