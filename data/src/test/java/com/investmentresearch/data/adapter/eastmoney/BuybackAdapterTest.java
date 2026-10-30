package com.investmentresearch.data.adapter.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BuybackAdapterTest {
    /** 金标准里的 window_end 是生成当天 2026-10-31。 */
    private static final Clock CLOCK =
            Clock.fixed(
                    LocalDateTime.parse("2026-10-31T12:00:00")
                            .atZone(ZoneId.systemDefault())
                            .toInstant(),
                    ZoneId.systemDefault());

    @TempDir Path tempDir;

    private EastmoneyTestSupport.Db db;
    private BuybackAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        String url = EastmoneyTestSupport.copySnapshot(tempDir);
        db = EastmoneyTestSupport.openDb(url);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        adapter =
                new BuybackAdapter(
                        new ResearchCacheStore(new JdbcTemplate(dataSource)),
                        db.session().getMapper(StockMapper.class),
                        offline(),
                        "http://127.0.0.1:1",
                        CLOCK);
    }

    private static UpstreamClient offline() {
        return new UpstreamClient() {
            @Override
            public String get(String path, Map<String, String> headers) {
                throw new IllegalStateException("不应访问上游：" + path);
            }

            @Override
            public String get(String path, Charset charset, Map<String, String> headers) {
                throw new IllegalStateException("不应访问上游：" + path);
            }
        };
    }

    @Test
    void matches_golden_for_every_cached_company() throws Exception {
        EastmoneyTestSupport.exec(
                db.session(), "update company_buybacks set fetched_at = '2026-10-31 09:00:00'");
        List<Path> goldens = EastmoneyTestSupport.goldens("get_buybacks");
        assertThat(goldens).isNotEmpty();
        for (Path file : goldens) {
            String code = EastmoneyTestSupport.golden(file).path("default_company").asText();
            EastmoneyTestSupport.assertMatchesGolden(file, adapter.buybacks(code, 5));
        }
    }
}
