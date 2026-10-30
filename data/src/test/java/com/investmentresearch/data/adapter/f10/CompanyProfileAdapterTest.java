package com.investmentresearch.data.adapter.f10;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.CompanyProfileEntity;
import com.investmentresearch.data.persistence.CompanyProfileMapper;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;

class CompanyProfileAdapterTest {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private CompanyProfileMapper mapper;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        server = F10TestSupport.startServer();
        String url = F10TestSupport.copySnapshot(tempDir);
        F10TestSupport.Db db = F10TestSupport.openDb(url);
        session = db.session();
        transactionManager = db.transactionManager();
        mapper = session.getMapper(CompanyProfileMapper.class);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void profile_matches_golden_for_all_five_companies() throws Exception {
        List<Path> goldens = F10TestSupport.goldens("get_company_profile");
        assertThat(goldens).hasSize(5);

        for (Path file : goldens) {
            JsonNode golden = F10TestSupport.golden(file);
            String code = golden.path("default_company").asText();
            // 概况的 as_of 就是抓取当天；快照里的五份缓存分别抓于不同日期，因此把时钟
            // 固定到该金标准的 as_of，再重放录制响应。source.fetched_at 的时分秒仍不同。
            Clock clock = pinned(golden.path("result").path("as_of").asText());
            mapper.deleteById(code);

            CompanyProfileAdapter adapter =
                    new CompanyProfileAdapter(
                            mapper,
                            new RestClientUpstreamClient(),
                            server.baseUrl() + F10TestSupport.PROFILE_PATH,
                            clock,
                            transactionManager);
            ToolResult actual = adapter.companyProfile(code);

            F10TestSupport.assertMatchesGolden(file, actual);
        }
    }

    @Test
    void upstream_html_or_empty_body_returns_ok_false() {
        server.stubFor(
                get(urlPathEqualTo(F10TestSupport.PROFILE_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/html")
                                        .withBody("<html>WAF</html>")));

        CompanyProfileAdapter adapter = adapter(pinned("2026-10-30"));
        ToolResult html = adapter.companyProfile("000001");
        assertThat(html.ok()).as("HTML 200").isFalse();
        assertThat(html.error()).as("HTML 200").contains("获取公司概况失败");

        server.stubFor(
                get(urlPathEqualTo(F10TestSupport.PROFILE_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("")));
        ToolResult empty = adapter.companyProfile("000002");
        assertThat(empty.ok()).as("empty 200").isFalse();
        assertThat(empty.data()).as("empty 200").isNull();

        // 200 但结构不对：jbzl 空数组，Python 走“没有取到公司概况”
        server.stubFor(
                get(urlPathEqualTo(F10TestSupport.PROFILE_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"jbzl\":[]}")));
        ToolResult wrongShape = adapter.companyProfile("000011");
        assertThat(wrongShape.ok()).as("empty jbzl").isFalse();
        assertThat(wrongShape.error()).as("empty jbzl").contains("没有取到公司概况");
        assertThat(mapper.selectById("000011")).as("失败不写库").isNull();

        // jbzl 是对象而不是数组：同样属于结构错误，不能当空处理或抛异常
        server.stubFor(
                get(urlPathEqualTo(F10TestSupport.PROFILE_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"jbzl\":{}}")));
        ToolResult objectShape = adapter.companyProfile("000013");
        assertThat(objectShape.ok()).as("jbzl object").isFalse();
        assertThat(objectShape.error()).as("jbzl object").contains("没有取到公司概况");
        assertThat(mapper.selectById("000013")).as("失败不写库").isNull();
    }

    @Test
    void profile_replace_nulls_fields_missing_upstream() {
        String code = "600600";
        mapper.deleteById(code);
        CompanyProfileEntity seed = new CompanyProfileEntity();
        seed.setCode(code);
        seed.setName("旧名");
        seed.setIndustry("旧行业");
        seed.setFetchedAt("2026-09-01 12:00:00");
        mapper.insert(seed);

        // 上游这次没给 EM2016，覆盖写入后 industry 必须是 null，而不是保留“旧行业”
        server.stubFor(
                get(urlPathEqualTo(F10TestSupport.PROFILE_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"jbzl\":[{\"SECURITY_NAME_ABBR\":\"青岛啤酒\"}]}")));

        ToolResult result = adapter(pinned("2026-10-30")).companyProfile(code);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("industry")).isNull();
        assertThat(mapper.selectById(code).getIndustry()).isNull();
    }

    @Test
    void profile_cache_expires_after_thirty_days() {
        String code = "600600";
        CompanyProfileAdapter adapter = adapter(pinned("2026-10-30"));

        mapper.deleteById(code);
        insertProfile(code, LocalDate.parse("2026-10-30").atTime(12, 0).minusDays(29));
        assertThat(adapter.companyProfile(code).ok()).as("29 天").isTrue();
        server.verify(
                0,
                getRequestedFor(urlPathEqualTo(F10TestSupport.PROFILE_PATH))
                        .withQueryParam("code", equalTo("SH" + code)));

        insertProfile(code, LocalDate.parse("2026-10-30").atTime(12, 0).minusDays(30));
        assertThat(adapter.companyProfile(code).ok()).as("30 天").isTrue();
        server.verify(
                1,
                getRequestedFor(urlPathEqualTo(F10TestSupport.PROFILE_PATH))
                        .withQueryParam("code", equalTo("SH" + code)));
    }

    @Test
    void migrations_create_the_same_f10_columns_as_python() throws Exception {
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
                        + F10TestSupport.SNAPSHOT.toAbsolutePath()
                                .toString()
                                .replace('\\', '/');
        assertThat(columnSpecs(fresh, "company_profile"))
                .isEqualTo(columnSpecs(python, "company_profile"));
        assertThat(columnSpecs(fresh, "biz_segment"))
                .isEqualTo(columnSpecs(python, "biz_segment"));
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

    private CompanyProfileAdapter adapter(Clock clock) {
        return new CompanyProfileAdapter(
                mapper,
                new RestClientUpstreamClient(),
                server.baseUrl() + F10TestSupport.PROFILE_PATH,
                clock,
                transactionManager);
    }

    private void insertProfile(String code, LocalDateTime fetchedAt) {
        CompanyProfileEntity row = new CompanyProfileEntity();
        row.setCode(code);
        row.setFetchedAt(fetchedAt.format(TIMESTAMP));
        mapper.deleteById(code);
        mapper.insert(row);
    }

    private static Clock pinned(String date) {
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant(), zone);
    }
}
