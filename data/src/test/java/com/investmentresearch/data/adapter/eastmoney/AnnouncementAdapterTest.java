package com.investmentresearch.data.adapter.eastmoney;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.data.persistence.AnnouncementEntity;
import com.investmentresearch.data.persistence.AnnouncementMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.RestClientUpstreamClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class AnnouncementAdapterTest {
    private static final String ANN_PATH = "/api/security/ann";
    private static final String FRESH_TIMESTAMP = "2026-10-30 09:00:00";
    private static final String STALE_TIMESTAMP = "2026-10-29 00:00:00";

    @TempDir Path tempDir;

    private WireMockServer server;
    private String url;
    private AnnouncementMapper mapper;
    private StockMapper stockMapper;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        try (Stream<Path> files = Files.list(EastmoneyTestSupport.FIXTURES)) {
            for (Path file :
                    files.filter(path -> path.getFileName().toString().startsWith("announcements-"))
                            .sorted()
                            .toList()) {
                server.addStubMapping(
                        Json.read(
                                Files.readString(file, StandardCharsets.UTF_8),
                                StubMapping.class));
            }
        }

        url = EastmoneyTestSupport.copySnapshot(tempDir);
        UnpooledDataSource dataSource =
                new UnpooledDataSource("org.sqlite.JDBC", url, null, null);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // SpringManagedTransactionFactory + SqlSessionTemplate：mapper 每次调用都绑定
        // 当前 Spring 事务的连接，TransactionTemplate 才真正覆盖 delete + upsert。
        configuration.setEnvironment(
                new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(StockMapper.class);
        configuration.addMapper(AnnouncementMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        mapper = session.getMapper(AnnouncementMapper.class);
        stockMapper = session.getMapper(StockMapper.class);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void announcements_match_golden_for_two_companies() throws Exception {
        for (String code : List.of("600600", "605499")) {
            // 快照里的抓取时间早于金标准的 2026-10-30，先置为新鲜，
            // 让适配器读缓存；只比较不依赖抓取时钟的字段（公告来源的 fetched_at 恒为空）。
            makeFresh(code);
            ToolResult actual = adapter().announcements(code, 180);
            assertThat(actual.ok()).as(code).isTrue();
            EastmoneyTestSupport.assertMatchesGolden(
                    EastmoneyTestSupport.goldenFor("get_announcements", code), actual);
        }
        server.verify(0, getRequestedFor(urlPathEqualTo(ANN_PATH)));
    }

    @Test
    void days_is_clamped_and_sorted_desc() {
        makeFresh("605499");
        ToolResult wide = adapter().announcements("605499", 1000);
        assertThat(wide.ok()).isTrue();
        assertThat(wide.data().get("days")).isEqualTo(365);
        assertThat(items(wide)).hasSize(20);
        // 明细与来源都截在 20 条，来源不补抓取时间
        assertThat(wide.sources()).hasSize(20);
        assertThat(wide.sources()).allMatch(source -> source.fetchedAt().isEmpty());
        assertDescending(items(wide));
        assertDescendingSources(wide);

        ToolResult narrow = adapter().announcements("605499", -5);
        assertThat(narrow.data().get("days")).isEqualTo(1);
        List<Map<String, Object>> items = items(narrow);
        assertThat(items).isNotEmpty();
        for (Map<String, Object> item : items) {
            assertThat((String) item.get("date")).isGreaterThanOrEqualTo("2026-10-29");
        }
        assertDescending(items);

        // Python `arguments.get("days") or 180`：0 和 null 都回落到 180
        assertThat(adapter().announcements("605499", 0).data().get("days")).isEqualTo(180);
        assertThat(adapter().announcements("605499", null).data().get("days")).isEqualTo(180);
    }

    @Test
    void empty_list_returns_failure_with_no_announcement_message() {
        mapper.delete(Wrappers.<AnnouncementEntity>query().eq("code", "000858"));
        server.stubFor(
                get(urlPathEqualTo(ANN_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"data\":{\"list\":[]}}")));

        ToolResult result = adapter().announcements("000858", 180);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("000858：近 180 天没有公告");
        assertThat(result.asOf()).isNull();
        assertThat(result.sources()).isEmpty();
        assertThat(result.data()).isNull();
        assertThat(count("000858")).isZero();
    }

    @Test
    void fresh_cache_with_empty_window_returns_failure() {
        // 缓存新鲜但窗口内没有公告：走 assemble 的空窗口分支，不是页 1 空。
        // days=-5 同时验证夹取后的值进了失败文案
        makeFresh("600600");
        ToolResult result = adapter().announcements("600600", -5);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("600600：近 1 天没有公告");
        server.verify(0, getRequestedFor(urlPathEqualTo(ANN_PATH)));
    }

    @Test
    void payload_fields_are_clipped_and_normalised() {
        mapper.delete(Wrappers.<AnnouncementEntity>query().eq("code", "000858"));
        String longTitle = "超长标题".repeat(20);
        server.stubFor(
                get(urlPathEqualTo(ANN_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"data\":{\"list\":["
                                                        + "{\"art_code\":\"ANLONG\",\"notice_date\":\"2026-10-29 00:00:00\",\"title_ch\":\""
                                                        + longTitle
                                                        + "\",\"columns\":[]},"
                                                        + "{\"art_code\":\"ANPREFIX\",\"notice_date\":\"2026-10-28 00:00:00\","
                                                        + "\"title_ch\":\"测试公司:关于回购股份的公告\","
                                                        + "\"codes\":[{\"short_name\":\"测试公司\"}],"
                                                        + "\"columns\":[{\"column_name\":\"股票回购\"}]},"
                                                        + "{\"art_code\":\"ANCAT\",\"notice_date\":\"2026-10-27 00:00:00\","
                                                        + "\"title_ch\":\"普通事项公告\","
                                                        + "\"columns\":[{\"column_name\":\"股权激励\"}]}"
                                                        + "]}}")));

        ToolResult result = adapter().announcements("000858", 180);

        assertThat(result.ok()).isTrue();
        List<Map<String, Object>> items = items(result);
        assertThat(items).extracting(item -> (String) item.get("title"))
                .containsExactly(longTitle, "关于回购股份的公告", "普通事项公告");
        // 60 字截断只加在 source detail，item 标题保持完整
        assertThat(result.sources().get(0).detail())
                .isEqualTo(longTitle.substring(0, 60) + "…");
        assertThat(items.get(1).get("tags")).isEqualTo(List.of("回购"));
        assertThat(items.get(2).get("tags")).isEqualTo(List.of("股权激励"));
        assertThat(items.get(2).get("level")).isEqualTo("info");

        List<AnnouncementEntity> rows =
                mapper.selectList(
                        Wrappers.<AnnouncementEntity>query().eq("code", "000858"));
        AnnouncementEntity prefix =
                rows.stream()
                        .filter(row -> "ANPREFIX".equals(row.getArtCode()))
                        .findFirst()
                        .orElseThrow();
        assertThat(prefix.getCategories()).isEqualTo("股票回购");
        assertThat(prefix.getUrl())
                .isEqualTo(
                        "https://data.eastmoney.com/notices/detail/000858/ANPREFIX.html");
    }

    @Test
    void server_error_falls_back_to_cached_rows() {
        setFetchedAt("600600", STALE_TIMESTAMP);
        server.stubFor(
                get(urlPathEqualTo(ANN_PATH)).willReturn(aResponse().withStatus(500)));

        ToolResult result = adapter().announcements("600600", 180);

        assertThat(result.ok()).isTrue();
        assertThat(result.asOf()).isEqualTo("2026-10-17");
        assertThat(result.sources()).hasSize(20);
        // 上游客户端对 5xx 重试一次才放弃，随后回退到旧缓存
        server.verify(
                2,
                getRequestedFor(urlPathEqualTo(ANN_PATH))
                        .withQueryParam("stock_list", equalTo("600600")));
    }

    @Test
    void unparseable_fetched_at_is_treated_as_stale() {
        setFetchedAt("605499", "not-a-timestamp");

        ToolResult result = adapter().announcements("605499", 180);

        assertThat(result.ok()).isTrue();
        List<AnnouncementEntity> rows =
                mapper.selectList(
                        Wrappers.<AnnouncementEntity>query().eq("code", "605499"));
        assertThat(rows).extracting(AnnouncementEntity::getFetchedAt)
                .contains("2026-10-30 12:00:00")
                .doesNotContain("not-a-timestamp");
        server.verify(
                4,
                getRequestedFor(urlPathEqualTo(ANN_PATH))
                        .withQueryParam("stock_list", equalTo("605499")));
    }

    @Test
    void malformed_date_row_is_skipped_not_fatal() {
        mapper.delete(Wrappers.<AnnouncementEntity>query().eq("code", "000858"));
        server.stubFor(
                get(urlPathEqualTo(ANN_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {"data":{"list":[
                                                  {"art_code":"AN1","notice_date":"2026-10-29 00:00:00","title_ch":"关于回购股份的公告","columns":[{"column_name":"股票回购"}]},
                                                  {"art_code":"ANEMPTY","notice_date":"","title_ch":"日期为空","columns":[]},
                                                  {"art_code":"ANNULL","title_ch":"缺日期","columns":[]},
                                                  {"art_code":"ANOLD","notice_date":"2020-01-01 00:00:00","title_ch":"保留窗口外","columns":[]},
                                                  {"art_code":"ANNOTITLE","notice_date":"2026-10-28 00:00:00","title_ch":"","columns":[]},
                                                  {"art_code":"AN2","notice_date":"2026-10-28 00:00:00","title_ch":"关于减持股份的公告","columns":[]}
                                                ]}}
                                                """)));

        ToolResult result = adapter().announcements("000858", 180);

        assertThat(result.ok()).as("坏日期行只跳过，不让整只票失败").isTrue();
        List<Map<String, Object>> items = items(result);
        assertThat(items).extracting(item -> item.get("date"))
                .containsExactly("2026-10-29", "2026-10-28");
        assertThat(items.get(0).get("tags")).isEqualTo(List.of("回购"));
        assertThat(items.get(0).get("level")).isEqualTo("info");
        assertThat(items.get(1).get("tags")).isEqualTo(List.of("减持"));
        assertThat(items.get(1).get("level")).isEqualTo("warning");

        List<AnnouncementEntity> rows =
                mapper.selectList(
                        Wrappers.<AnnouncementEntity>query()
                                .eq("code", "000858")
                                .orderByAsc("art_code"));
        assertThat(rows).extracting(AnnouncementEntity::getArtCode)
                .containsExactly("AN1", "AN2");
        assertThat(rows).extracting(AnnouncementEntity::getFetchedAt)
                .containsOnly("2026-10-30 12:00:00");
    }

    @Test
    void fresh_cache_skips_upstream_and_stale_cache_refetches_with_page_stop() {
        makeFresh("600600");
        ToolResult cached = adapter().announcements("600600", 180);
        assertThat(cached.ok()).isTrue();
        assertThat(cached.asOf()).isEqualTo("2026-10-17");
        server.verify(0, getRequestedFor(urlPathEqualTo(ANN_PATH)));

        // 超过 6 小时 → 重抓；p1 满 100 条，p2 有窗口外条目 → 翻 2 页后停
        setFetchedAt("600600", STALE_TIMESTAMP);
        ToolResult refetched = adapter().announcements("600600", 180);
        assertThat(refetched.ok()).isTrue();
        assertThat(refetched.asOf()).isEqualTo("2026-10-29");
        server.verify(
                2,
                getRequestedFor(urlPathEqualTo(ANN_PATH))
                        .withQueryParam("stock_list", equalTo("600600")));

        // 第二次调用已新鲜，不再打接口
        ToolResult again = adapter().announcements("600600", 180);
        assertThat(again.data()).isEqualTo(refetched.data());
        server.verify(
                2,
                getRequestedFor(urlPathEqualTo(ANN_PATH))
                        .withQueryParam("stock_list", equalTo("600600")));

        // 605499 第 4 页全部落在 400 天窗口外 → 停在第 4 页，不请求第 5 页
        setFetchedAt("605499", STALE_TIMESTAMP);
        ToolResult paged = adapter().announcements("605499", 180);
        assertThat(paged.ok()).isTrue();
        server.verify(
                4,
                getRequestedFor(urlPathEqualTo(ANN_PATH))
                        .withQueryParam("stock_list", equalTo("605499")));
    }

    @Test
    void failed_write_rolls_back_so_cached_rows_survive() throws Exception {
        AnnouncementEntity old = new AnnouncementEntity();
        old.setCode("000001");
        old.setArtCode("ANOLD");
        old.setNoticeDate("2020-01-01");
        old.setTitle("窗口外的旧公告");
        old.setCategories("");
        old.setUrl("");
        old.setFetchedAt(STALE_TIMESTAMP);
        mapper.insert(old);

        // 让第二个 upsert 失败，证明裁剪窗口的 delete 与插入在同一个事务里
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create trigger announcement_fail before insert on announcement"
                            + " when new.art_code = 'ANFAIL'"
                            + " begin select raise(abort, 'boom'); end");
        }
        server.stubFor(
                get(urlPathEqualTo(ANN_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {"data":{"list":[
                                                  {"art_code":"ANOK","notice_date":"2026-10-29 00:00:00","title_ch":"正常公告"},
                                                  {"art_code":"ANFAIL","notice_date":"2026-10-29 00:00:00","title_ch":"触发失败"}
                                                ]}}
                                                """)));

        ToolResult result = adapter().announcements("000001", 180);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotNull();
        List<AnnouncementEntity> rows =
                mapper.selectList(
                        Wrappers.<AnnouncementEntity>query().eq("code", "000001"));
        assertThat(rows).extracting(AnnouncementEntity::getArtCode)
                .as("失败后旧行还在、新行一条都没落库")
                .containsExactly("ANOLD");
    }

    @Test
    void migrations_create_the_same_announcement_columns_as_python() throws Exception {
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
        assertThat(columnSpecs(fresh, "announcement"))
                .isEqualTo(columnSpecs(python, "announcement"));
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

    private AnnouncementAdapter adapter() {
        return new AnnouncementAdapter(
                mapper,
                stockMapper,
                new RestClientUpstreamClient(),
                server.baseUrl() + ANN_PATH,
                EastmoneyTestSupport.CLOCK,
                transactionManager);
    }

    private void makeFresh(String code) {
        setFetchedAt(code, FRESH_TIMESTAMP);
    }

    private void setFetchedAt(String code, String fetchedAt) {
        mapper.update(
                null,
                Wrappers.<AnnouncementEntity>update()
                        .set("fetched_at", fetchedAt)
                        .eq("code", code));
    }

    private long count(String code) {
        return mapper.selectCount(Wrappers.<AnnouncementEntity>query().eq("code", code));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ToolResult result) {
        return (List<Map<String, Object>>) result.data().get("items");
    }

    private static void assertDescending(List<Map<String, Object>> items) {
        List<String> dates = new ArrayList<>();
        for (Map<String, Object> item : items) {
            dates.add((String) item.get("date"));
        }
        assertThat(dates).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /** 同一天内按 art_code 倒序；来源 id 里带着这两个排序键。 */
    private static void assertDescendingSources(ToolResult result) {
        List<String> keys = new ArrayList<>();
        for (var source : result.sources()) {
            String[] parts = source.id().split(":", 4);
            keys.add(parts[2] + ":" + parts[3]);
        }
        assertThat(keys).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
}
