package com.investmentresearch.data.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.tool.ResolveCompanyTool;
import com.investmentresearch.data.persistence.StockEntity;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.sync.StockMasterSync;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StockDirectoryAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLIST_PATH = "/api/qt/clist/get";

    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private StockMapper mapper;
    private StockMasterSync sync;
    private StockDirectoryAdapter adapter;
    private ResolveCompanyTool tool;

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        Path mapping = Path.of("src/test/resources/wiremock/eastmoney/stock-list-page1.json");
        server.addStubMapping(
                Json.read(
                        Files.readString(mapping, StandardCharsets.UTF_8), StubMapping.class));
        server.stubFor(
                get(urlPathEqualTo(CLIST_PATH))
                        .withQueryParam("pn", equalTo("2"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"rc\": 0, \"data\": {\"diff\": []}}")));

        String url =
                "jdbc:sqlite:"
                        + tempDir.resolve("app.db").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration/sqlite")
                .load()
                .migrate();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO));
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.setEnvironment(
                new Environment(
                        "test",
                        new JdbcTransactionFactory(),
                        new UnpooledDataSource("org.sqlite.JDBC", url, null, null)));
        configuration.addMapper(StockMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        mapper = session.getMapper(StockMapper.class);

        UpstreamClient client = new RestClientUpstreamClient();
        sync = new StockMasterSync(mapper, client, server.baseUrl() + CLIST_PATH);
        adapter = new StockDirectoryAdapter(mapper, sync);
        tool = new ResolveCompanyTool(adapter);
    }

    @AfterEach
    void tearDown() {
        session.close();
        server.stop();
    }

    @Test
    void resolves_six_digit_code_and_sh_prefix_and_suffix_form() {
        for (String value : List.of("605499", "SH605499", "sh605499", "605499.SH")) {
            ToolResult result = tool.execute(Map.of("name_or_code", value), null);

            assertThat(result.ok()).as(value).isTrue();
            assertThat(result.company()).as(value).isEqualTo("605499");
            assertThat(result.data())
                    .as(value)
                    .containsEntry("code", "605499")
                    .containsEntry("name", "东鹏饮料");
        }
    }

    @Test
    void resolves_exact_name() {
        ToolResult result = tool.execute(Map.of("name_or_code", "东鹏饮料"), null);

        assertThat(result.ok()).isTrue();
        assertThat(result.company()).isEqualTo("605499");
        assertThat(result.data()).containsEntry("name", "东鹏饮料");
    }

    @Test
    void unknown_name_returns_ok_false_with_no_candidates() {
        ToolResult result = tool.execute(Map.of("name_or_code", "不存在的公司"), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("没有找到「不存在的公司」");
        assertThat(result.data()).isNull();
    }

    @Test
    void master_table_is_loaded_once_when_empty() {
        assertThat(adapter.all()).hasSize(100);
        assertThat(mapper.selectCount(null)).isEqualTo(100);

        assertThat(adapter.byName("东鹏饮料")).contains(new StockRef("605499", "东鹏饮料"));
        assertThat(adapter.all()).hasSize(100);

        server.verify(
                1,
                getRequestedFor(urlPathEqualTo(CLIST_PATH)).withQueryParam("pn", equalTo("1")));
    }

    @Test
    void refresh_is_idempotent() {
        sync.refresh();
        long first = mapper.selectCount(null);
        sync.refresh();

        assertThat(first).isEqualTo(100);
        assertThat(mapper.selectCount(null)).isEqualTo(first);
    }

    @Test
    void three_resolve_company_goldens_match_field_by_field() throws Exception {
        seedFromSnapshot();

        Path goldenDirectory = Path.of("../core/src/test/resources/golden/tools");
        Map<String, String> messages =
                MAPPER.readValue(
                        Files.readString(
                                Path.of("../core/src/test/resources/golden/tool-messages.json"),
                                StandardCharsets.UTF_8),
                        new TypeReference<Map<String, String>>() {});

        List<Path> goldens;
        try (Stream<Path> files = Files.list(goldenDirectory)) {
            goldens =
                    files.filter(
                                    file ->
                                            file.getFileName()
                                                    .toString()
                                                    .startsWith("resolve_company__"))
                            .sorted()
                            .toList();
        }
        assertThat(goldens).hasSize(3);

        for (Path file : goldens) {
            JsonNode golden = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String query = golden.path("arguments").path("name_or_code").asText();
            ToolResult actual = tool.execute(Map.of("name_or_code", query), null);
            JsonNode expected = golden.path("result");
            String label = file.toString();

            assertThat(actual.ok()).as(label).isEqualTo(expected.path("ok").asBoolean());
            assertThat(actual.company()).as(label).isEqualTo(text(expected.get("company")));
            assertThat(actual.asOf()).as(label).isEqualTo(text(expected.get("as_of")));
            assertThat(actual.sources()).as(label).isEmpty();
            assertThat(actual.data())
                    .as(label)
                    .isEqualTo(MAPPER.convertValue(expected.get("data"), Object.class));
            assertThat(actual.error()).as(label).isEqualTo(text(expected.get("error")));
            assertThat(actual.cached()).as(label).isEqualTo(expected.path("cached").asBoolean());
            // 与 Python to_message() 的整串比对：除 fetched_at 外没有字段被忽略，这里 sources 为空
            assertThat(actual.toMessageJson())
                    .as(label)
                    .isEqualTo(messages.get(file.getFileName().toString()));
        }
    }

    private void seedFromSnapshot() throws Exception {
        Path snapshot =
                Path.of("../core/src/test/resources/fixtures/stock-snapshot.db")
                        .toAbsolutePath();
        String url = "jdbc:sqlite:" + snapshot.toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT code, name FROM stock")) {
            while (rows.next()) {
                StockEntity row = new StockEntity();
                row.setCode(rows.getString("code"));
                row.setName(rows.getString("name"));
                row.setRefreshedAt("2026-10-30 00:00:00");
                mapper.insert(row);
            }
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
