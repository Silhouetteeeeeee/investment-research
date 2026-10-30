package com.investmentresearch.data.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ResearchCacheStoreTest {
    @TempDir Path tempDir;

    private ResearchCacheStore store;

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
        store = new ResearchCacheStore(new JdbcTemplate(dataSource));
    }

    @Test
    void code_only_table_round_trips_and_overwrites() {
        assertThat(store.read(ResearchCache.DIVIDENDS, "600519")).isEmpty();

        store.write(ResearchCache.DIVIDENDS, "600519", "{\"a\":1}", "2026-10-30T10:00:00");
        assertThat(store.read(ResearchCache.DIVIDENDS, "600519"))
                .hasValueSatisfying(
                        row -> {
                            assertThat(row.data()).isEqualTo("{\"a\":1}");
                            assertThat(row.fetchedAt()).isEqualTo("2026-10-30T10:00:00");
                        });

        store.write(ResearchCache.DIVIDENDS, "600519", "{\"a\":2}", "2026-10-31T10:00:00");
        assertThat(store.read(ResearchCache.DIVIDENDS, "600519").orElseThrow().data())
                .isEqualTo("{\"a\":2}");
    }

    @Test
    void keyed_table_keeps_one_row_per_key() {
        store.write(ResearchCache.RESEARCH, "600519", 1, "{\"y\":1}", "t1");
        store.write(ResearchCache.RESEARCH, "600519", 3, "{\"y\":3}", "t3");

        assertThat(store.read(ResearchCache.RESEARCH, "600519", 1).orElseThrow().data())
                .isEqualTo("{\"y\":1}");
        assertThat(store.read(ResearchCache.RESEARCH, "600519", 3).orElseThrow().data())
                .isEqualTo("{\"y\":3}");
        assertThat(store.read(ResearchCache.REPORTS, "600519", 30)).isEmpty();

        store.write(ResearchCache.RESEARCH, "600519", 1, "{\"y\":9}", "t9");
        assertThat(store.read(ResearchCache.RESEARCH, "600519", 1).orElseThrow().data())
                .isEqualTo("{\"y\":9}");
    }

    @Test
    void notes_are_keyed_by_code_and_note_key() {
        store.writeNote("600519", "2026-08-01-调研", "正文一", "t1");
        store.writeNote("600519", "2026-09-01-调研", "正文二", "t2");

        assertThat(store.readNote("600519", "2026-08-01-调研").orElseThrow().data())
                .isEqualTo("正文一");
        assertThat(store.readNote("600519", "2026-09-01-调研").orElseThrow().fetchedAt())
                .isEqualTo("t2");
        assertThat(store.readNote("600519", "不存在")).isEmpty();
    }
}
