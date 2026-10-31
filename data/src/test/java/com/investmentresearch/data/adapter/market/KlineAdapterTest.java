package com.investmentresearch.data.adapter.market;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.KlineBar;
import com.investmentresearch.data.persistence.KlineDailyStore;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 日 K 取数：腾讯优先、东财兜底、落库后当天不再请求。 */
class KlineAdapterTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 15:00 收盘前后的两个时刻。 */
    private static final Clock MORNING = Clock.fixed(Instant.parse("2026-10-31T02:00:00Z"), SHANGHAI);
    private static final Clock AFTER_CLOSE =
            Clock.fixed(Instant.parse("2026-10-31T08:00:00Z"), SHANGHAI);

    @TempDir Path tempDir;

    private JdbcTemplate jdbc;

    private final List<String> urls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String url =
                "jdbc:sqlite:"
                        + tempDir.resolve("app.db").toAbsolutePath().toString().replace('\\', '/');
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/sqlite")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void tencent_is_primary_and_result_is_cached() {
        KlineAdapter adapter = adapter(url -> url.contains("gtimg") ? TENCENT : EASTMONEY, MORNING);

        List<KlineBar> bars = adapter.daily("600519", 120);

        assertThat(urls).singleElement().asString().contains("param=sh600519,day,,,500,qfq");
        assertThat(bars).hasSize(2);
        KlineBar first = bars.get(0);
        assertThat(first.date()).isEqualTo("2026-10-30");
        assertThat(first.open()).isEqualTo(10.0);
        assertThat(first.close()).isEqualTo(10.5);
        assertThat(first.high()).isEqualTo(10.8);
        assertThat(first.low()).isEqualTo(9.9);
        // 腾讯成交量是手，缓存统一成股
        assertThat(first.volume()).isEqualTo(1_234_500.0);
        assertThat(first.amount()).isNull();

        // 同一天再取直接读库，不再打上游
        adapter.daily("600519", 120);
        assertThat(urls).hasSize(1);
        assertThat(storeAt(MORNING).load("600519", 10)).hasSize(2);
    }

    @Test
    void eastmoney_fallback_uses_qfq() {
        KlineAdapter adapter =
                adapter(
                        url -> {
                            if (url.contains("gtimg")) {
                                throw new IllegalStateException("腾讯挂了");
                            }
                            return EASTMONEY;
                        },
                        MORNING);

        List<KlineBar> bars = adapter.daily("600519", 120);

        assertThat(urls).hasSize(2);
        assertThat(urls.get(1)).contains("fqt=1&").contains("secid=1.600519");
        assertThat(bars).hasSize(2);
        assertThat(bars.get(1).volume()).isEqualTo(2_345_600.0);
    }

    @Test
    void cache_is_refreshed_after_close() {
        adapter(url -> TENCENT, MORNING).daily("600519", 120);
        assertThat(urls).hasSize(1);

        KlineAdapter afterClose = adapter(url -> TENCENT_UPDATED, AFTER_CLOSE);
        List<KlineBar> bars = afterClose.daily("600519", 120);

        assertThat(urls).hasSize(2);
        assertThat(bars.get(1).close()).isEqualTo(11.2);
    }

    @Test
    void stale_cache_is_served_when_both_sources_fail() {
        adapter(url -> TENCENT, MORNING).daily("600519", 120);

        KlineAdapter broken =
                adapter(
                        url -> {
                            throw new IllegalStateException("上游全挂");
                        },
                        AFTER_CLOSE);

        List<KlineBar> bars = broken.daily("600519", 120);
        assertThat(bars).hasSize(2);
        assertThat(bars.get(1).close()).isEqualTo(10.2);
    }

    private KlineAdapter adapter(Function<String, String> responder, Clock clock) {
        UpstreamClient client =
                new UpstreamClient() {
                    @Override
                    public String get(String url, Map<String, String> headers) {
                        return get(url, UTF_8, headers);
                    }

                    @Override
                    public String get(String url, Charset charset, Map<String, String> headers) {
                        urls.add(url);
                        return responder.apply(url);
                    }
                };
        return new KlineAdapter(client, storeAt(clock), clock);
    }

    private KlineDailyStore storeAt(Clock clock) {
        return new KlineDailyStore(jdbc, clock);
    }

    private static final String TENCENT =
            "{\"code\":0,\"msg\":\"\",\"data\":{\"sh600519\":{\"qfqday\":["
                    + "[\"2026-10-30\",\"10.0\",\"10.5\",\"10.8\",\"9.9\",\"12345\"],"
                    + "[\"2026-10-31\",\"10.5\",\"10.2\",\"10.6\",\"10.1\",\"23456\"]]}}}";

    private static final String TENCENT_UPDATED =
            "{\"code\":0,\"msg\":\"\",\"data\":{\"sh600519\":{\"qfqday\":["
                    + "[\"2026-10-30\",\"10.0\",\"10.5\",\"10.8\",\"9.9\",\"12345\"],"
                    + "[\"2026-10-31\",\"10.5\",\"11.2\",\"11.3\",\"10.1\",\"23456\"]]}}}";

    private static final String EASTMONEY =
            "{\"data\":{\"klines\":["
                    + "\"2026-10-30,10.0,10.5,10.8,9.9,12345,13000000.0,1.23\","
                    + "\"2026-10-31,10.5,10.2,10.6,10.1,23456,24000000.0,2.34\"]}}";
}
