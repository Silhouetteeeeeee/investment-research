package com.investmentresearch.data.adapter.eastmoney;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.data.persistence.ResearchCacheStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DividendAdapterTest {
    @TempDir Path tempDir;

    private EastmoneyTestSupport.Db db;
    private DividendAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        String url = EastmoneyTestSupport.copySnapshot(tempDir);
        db = EastmoneyTestSupport.openDb(url);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        adapter =
                new DividendAdapter(
                        new ResearchCacheStore(new JdbcTemplate(dataSource)),
                        db.session().getMapper(com.investmentresearch.data.persistence.FinIncomeMapper.class),
                        db.session().getMapper(com.investmentresearch.data.persistence.StockMapper.class),
                        new com.investmentresearch.data.upstream.UpstreamClient() {
                            @Override
                            public String get(
                                    String path, java.util.Map<String, String> headers) {
                                throw new IllegalStateException("不应访问上游：" + path);
                            }

                            @Override
                            public String get(
                                    String path,
                                    java.nio.charset.Charset charset,
                                    java.util.Map<String, String> headers) {
                                throw new IllegalStateException("不应访问上游：" + path);
                            }
                        },
                        "http://127.0.0.1:1",
                        EastmoneyTestSupport.CLOCK);
    }

    @Test
    void matches_golden_for_every_cached_company() throws Exception {
        EastmoneyTestSupport.exec(
                db.session(),
                "update company_dividends set fetched_at = '2026-10-30 09:00:00'");
        List<Path> goldens = EastmoneyTestSupport.goldens("get_dividends");
        assertThat(goldens).isNotEmpty();
        for (Path file : goldens) {
            String code = EastmoneyTestSupport.golden(file).path("default_company").asText();
            EastmoneyTestSupport.assertMatchesGolden(file, adapter.dividends(code, 10));
        }
    }

    @Test
    void missing_cache_falls_back_to_upstream_error() {
        var result = adapter.dividends("600519", 10);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("分红");
    }

    @Test
    void row_without_bonus_and_transfer_leaves_share_bonus_empty() throws Exception {
        // 京东方的实盘数据：三季报只有方案文本，送股与转增都为 null，曾在这里拆箱崩掉
        var row =
                EastmoneyTestSupport.MAPPER.readTree(
                        "{\"REPORT_DATE\":\"2026-09-30 00:00:00\","
                                + "\"IMPL_PLAN_PROFILE\":\"不分配不转增\"}");

        var event = DividendAdapter.normalize(row);

        assertThat(event.get("share_bonus_per_10")).isNull();
        assertThat(event.get("cash_per_10")).isNull();
    }
}
