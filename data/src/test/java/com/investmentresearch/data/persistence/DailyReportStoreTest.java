package com.investmentresearch.data.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DailyReportStoreTest {
    @TempDir Path tempDir;

    private JdbcTemplate jdbc;
    private DailyReportStore store;

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
        store = new DailyReportStore(jdbc, clock("2026-10-31T07:00:00Z"));
    }

    @Test
    void saving_the_same_day_twice_keeps_one_row_with_the_later_payload() {
        Map<String, Object> first = report("2026-10-30", "强势");
        Map<String, Object> saved = store.save(first);
        assertThat(saved).containsEntry("replaced", false).containsEntry("trade_date", "2026-10-30");

        store = new DailyReportStore(jdbc, clock("2026-10-31T08:00:00Z"));
        Map<String, Object> second = report("2026-10-30", "弱势");
        assertThat(store.save(second)).containsEntry("replaced", true);

        assertThat(jdbc.queryForObject("select count(*) from daily_reports", Integer.class)).isEqualTo(1);
        assertThat(store.load(10)).singleElement().satisfies(row -> {
            assertThat(row.get("market")).isEqualTo(Map.of("state", "弱势"));
            assertThat(row.get("saved_at")).isEqualTo("2026-10-31 16:00:00");
        });
    }

    @Test
    void reports_come_back_newest_first_with_dates_reinserted() {
        store.save(report("2026-10-28", "震荡"));
        store.save(report("2026-10-30", "强势"));

        assertThat(store.load(10)).extracting(row -> row.get("trade_date"))
                .containsExactly("2026-10-30", "2026-10-28");
        assertThat(store.load(1)).hasSize(1);
    }

    @Test
    void a_report_without_trade_date_is_rejected() {
        assertThatThrownBy(() -> store.save(new LinkedHashMap<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trade_date");
    }

    @Test
    void a_broken_payload_row_is_skipped() {
        store.save(report("2026-10-30", "强势"));
        jdbc.update("insert into daily_reports (trade_date, saved_at, payload) values (?, ?, ?)",
                "2026-10-29", "2026-10-29 16:00:00", "not json");

        assertThat(store.load(10)).extracting(row -> row.get("trade_date"))
                .containsExactly("2026-10-30");
    }

    private static Map<String, Object> report(String tradeDate, String state) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("trade_date", tradeDate);
        report.put("market", Map.of("state", state));
        report.put("candidates", java.util.List.of());
        return report;
    }

    private static Clock clock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai"));
    }
}
