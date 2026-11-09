package com.investmentresearch.data.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.investmentresearch.core.domain.screen.ScreenFinance;
import com.investmentresearch.core.domain.screen.ScreenQuote;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ScreenSnapshotStoreTest {
    @TempDir Path tempDir;

    private JdbcTemplate jdbc;
    private ScreenSnapshotStore store;

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
        store = new ScreenSnapshotStore(jdbc);
    }

    @Test
    void replacing_quotes_drops_rows_not_in_the_new_snapshot() {
        store.replaceQuotes(
                List.of(quote("600519", "贵州茅台", "白酒", 1680.0, 12.5, "2026-11-06"),
                        quote("000001", "平安银行", "银行", 11.2, 30.0, "2026-11-06")),
                "2026-11-06T15:30:00");

        store.replaceQuotes(
                List.of(quote("600519", "贵州茅台", "白酒", 1700.0, 13.0, "2026-11-07")),
                "2026-11-07T15:30:00");

        assertThat(store.loadQuotes()).singleElement().satisfies(row -> {
            assertThat(row.code()).isEqualTo("600519");
            assertThat(row.price()).isEqualTo(1700.0);
            assertThat(row.tradeDate()).isEqualTo("2026-11-07");
        });
        assertThat(jdbc.queryForObject("select count(*) from screen_quote", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void quotes_roundtrip_keeps_null_metrics_and_fetched_at() {
        ScreenQuote missingMetrics =
                new ScreenQuote(
                        "300750", "宁德时代", null, null, null, null, null, null, null, null, "2026-11-06");
        store.replaceQuotes(List.of(missingMetrics), "2026-11-06T15:30:00");

        assertThat(store.loadQuotes()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("宁德时代");
            assertThat(row.industry()).isNull();
            assertThat(row.price()).isNull();
            assertThat(row.pe()).isNull();
        });
        assertThat(jdbc.queryForObject("select fetched_at from screen_quote", String.class))
                .isEqualTo("2026-11-06T15:30:00");
    }

    @Test
    void finance_upsert_is_keyed_by_code_and_report_date() {
        store.upsertFinance(
                List.of(finance("600519", "2026-09-30", 10.0, 9.0), finance("600519", "2026-06-30", 8.0, 7.0)),
                "2026-11-07T15:30:00");

        store.upsertFinance(List.of(finance("600519", "2026-09-30", 11.5, 10.5)), "2026-11-08T09:00:00");

        assertThat(jdbc.queryForObject("select count(*) from screen_finance", Integer.class))
                .isEqualTo(2);
        assertThat(store.loadFinance())
                .extracting(ScreenFinance::reportDate)
                .containsExactly("2026-09-30", "2026-06-30");
        assertThat(store.loadFinance())
                .filteredOn(row -> row.reportDate().equals("2026-09-30"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.eps()).isEqualTo(11.5);
                    assertThat(row.netProfit()).isEqualTo(10.5);
                });
        assertThat(
                        jdbc.queryForObject(
                                "select fetched_at from screen_finance where report_date = ?",
                                String.class,
                                "2026-09-30"))
                .isEqualTo("2026-11-08T09:00:00");
    }

    @Test
    void latest_trade_date_returns_max_and_null_when_empty() {
        assertThat(store.latestTradeDate()).isNull();

        store.replaceQuotes(
                List.of(quote("600519", "贵州茅台", "白酒", 1680.0, 12.5, "2026-11-06"),
                        quote("000001", "平安银行", "银行", 11.2, 30.0, "2026-11-07")),
                "2026-11-07T15:30:00");

        assertThat(store.latestTradeDate()).isEqualTo("2026-11-07");
    }

    @Test
    void latest_fetched_at_falls_back_to_finance_and_is_null_when_empty() {
        assertThat(store.latestFetchedAt()).isNull();

        store.upsertFinance(List.of(finance("600519", "2026-09-30", 10.0, 9.0)), "2026-11-07T15:30:00");
        assertThat(store.latestFetchedAt()).isEqualTo("2026-11-07T15:30:00");

        store.replaceQuotes(
                List.of(quote("600519", "贵州茅台", "白酒", 1680.0, 12.5, "2026-11-07")),
                "2026-11-08T15:30:00");
        assertThat(store.latestFetchedAt()).isEqualTo("2026-11-08T15:30:00");
    }

    private static ScreenQuote quote(
            String code, String name, String industry, double price, double amountYi, String tradeDate) {
        return new ScreenQuote(
                code, name, industry, price, 1.2, amountYi, 0.8, 25.0, 6.0, 21000.0, tradeDate);
    }

    private static ScreenFinance finance(
            String code, String reportDate, double eps, double netProfit) {
        return new ScreenFinance(
                code, reportDate, eps, 1.0e9, netProfit, 15.0, 30.0, 12.0, 20.0, 5.5, "2026-10-25");
    }
}
