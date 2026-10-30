package com.investmentresearch.data.adapter.f10;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.CompanyProfileMapper;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompanyProfileAdapterTest {
    @TempDir Path tempDir;

    private WireMockServer server;
    private SqlSession session;
    private CompanyProfileMapper mapper;
    private String url;

    @BeforeEach
    void setUp() throws Exception {
        server = F10TestSupport.startServer();
        url = F10TestSupport.copySnapshot(tempDir);
        session = F10TestSupport.openSession(url);
        mapper = session.getMapper(CompanyProfileMapper.class);
    }

    @AfterEach
    void tearDown() {
        session.close();
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
                            clock);
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

        CompanyProfileAdapter adapter =
                new CompanyProfileAdapter(
                        mapper,
                        new RestClientUpstreamClient(),
                        server.baseUrl() + F10TestSupport.PROFILE_PATH,
                        pinned("2026-10-30"));
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
        assertThat(columns(fresh, "company_profile"))
                .isEqualTo(columns(python, "company_profile"));
        assertThat(columns(fresh, "biz_segment"))
                .isEqualTo(columns(python, "biz_segment"));
    }

    private static List<String> columns(String url, String table) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (rows.next()) {
                names.add(rows.getString("name"));
            }
        }
        return names;
    }

    private static Clock pinned(String date) {
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant(), zone);
    }
}
