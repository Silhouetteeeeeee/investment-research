package com.investmentresearch.data.adapter.f10;

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
import com.investmentresearch.data.persistence.BizSegmentMapper;
import com.investmentresearch.data.persistence.CompanyProfileMapper;
import com.investmentresearch.data.persistence.StockMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** 两个 F10 适配器测试共用的夹具：快照复制、WireMock 回放、金标准比对。 */
final class F10TestSupport {
    static final String PROFILE_PATH = "/PC_HSF10/CompanySurvey/PageAjax";
    static final String SEGMENTS_PATH = "/PC_HSF10/BusinessAnalysis/PageAjax";
    private static final Path REPO_ROOT = findRepoRoot();
    static final Path FIXTURES =
            REPO_ROOT.resolve("data/src/test/resources/wiremock/eastmoney");
    static final Path GOLDENS = REPO_ROOT.resolve("core/src/test/resources/golden/tools");
    static final Path SNAPSHOT =
            REPO_ROOT.resolve("core/src/test/resources/fixtures/stock-snapshot.db");
    static final ObjectMapper MAPPER = new ObjectMapper();

    private F10TestSupport() {}

    static WireMockServer startServer() throws IOException {
        WireMockServer server = new WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options()
                        .dynamicPort());
        server.start();
        try (Stream<Path> files = Files.list(FIXTURES)) {
            for (Path file :
                    files.filter(path -> path.getFileName().toString().startsWith("f10-"))
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

    // session 是 SqlSessionTemplate：close() 会抛 UnsupportedOperationException，别在 tearDown 里关
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
        configuration.addMapper(CompanyProfileMapper.class);
        configuration.addMapper(BizSegmentMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        // SqlSessionTemplate 每次调用都开新会话，事务活动时复用 TransactionTemplate 绑定的连接
        return new Db(new SqlSessionTemplate(factory), new DataSourceTransactionManager(dataSource));
    }

    static List<Path> goldens(String tool) throws IOException {
        try (Stream<Path> files = Files.list(GOLDENS)) {
            return files.filter(path -> path.getFileName().toString().startsWith(tool + "__"))
                    .sorted()
                    .toList();
        }
    }

    static JsonNode golden(Path file) throws IOException {
        return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 与金标准逐字段比对；source 的 fetched_at 是抓取时钟，录制的秒数必然不同，是唯一忽略的字段。 */
    static void assertMatchesGolden(Path file, ToolResult actual) throws IOException {
        JsonNode golden = golden(file).path("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = MAPPER.convertValue(golden, Map.class);
        Map<String, Object> actualMap = resultMap(actual);
        stripFetchedAt(expected);
        stripFetchedAt(actualMap);
        assertThat(actualMap).as(file.toString()).isEqualTo(expected);
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

    /** 从当前目录向上找仓库根，避免依赖 surefire 把 cwd 设成哪个模块。 */
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
