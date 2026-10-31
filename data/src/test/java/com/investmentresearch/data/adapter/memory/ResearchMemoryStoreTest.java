package com.investmentresearch.data.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.investmentresearch.core.research.MemoryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ResearchMemoryStoreTest {
    @TempDir Path tempDir;

    private ResearchMemoryStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        String url =
                "jdbc:sqlite:"
                        + tempDir.resolve("memory.db").toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration/sqlite")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        jdbc = new JdbcTemplate(dataSource);
        store = new ResearchMemoryStore(jdbc);
    }

    @Test
    void import_keeps_python_counts_business_ids_and_timestamps() throws Exception {
        assertThat(store.importLegacy(legacyFixture())).isEqualTo(34);

        assertThat(count("company_memory")).isEqualTo(5);
        assertThat(count("memory_fact")).isEqualTo(17);
        assertThat(count("memory_hypothesis")).isEqualTo(2);
        assertThat(count("memory_conclusion")).isEqualTo(3);
        assertThat(count("memory_question")).isEqualTo(6);
        assertThat(count("memory_change")).isEqualTo(1);

        Map<String, Object> detail = store.detail("000858", "", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) detail.get("facts");
        // 事实按 created_at DESC 排，最新的 F002 在前；业务 id 与时间戳必须原样保留。
        assertThat(facts.get(0))
                .containsEntry("fact_id", "F002")
                .containsEntry("source_id", "finance_history:000858:2016-2025")
                .containsEntry("created_at", "2026-10-28T10:46:32.773169");
    }

    @Test
    void importing_twice_adds_nothing() throws Exception {
        store.importLegacy(legacyFixture());

        assertThat(store.importLegacy(legacyFixture())).isZero();

        assertThat(count("memory_fact")).isEqualTo(17);
    }

    @Test
    void appending_the_same_fact_twice_keeps_one_row() {
        Map<String, Object> fact =
                Map.of(
                        "fact_type", "经营数据",
                        "content", "2026 年前三季度营收 12.3 亿元",
                        "report_period", "2026-09-30",
                        "source_id", "finance:600519:2026-09-30");

        Map<String, Object> first = store.append("600519", "facts", fact, "贵州茅台");
        Map<String, Object> second = store.append("600519", "facts", fact, "贵州茅台");

        assertThat(first).containsEntry("created", true);
        assertThat(second).containsEntry("created", false);
        assertThat(count("memory_fact")).isEqualTo(1);
    }

    @Test
    void concurrent_appends_of_the_same_fact_keep_one_row() throws Exception {
        Map<String, Object> fact =
                Map.of(
                        "fact_type", "经营数据",
                        "content", "2026 年前三季度营收 12.3 亿元",
                        "report_period", "2026-09-30",
                        "source_id", "finance:600519:2026-09-30");
        int threads = 4;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return store.append("600519", "facts", fact, "贵州茅台");
                                }));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(row -> Boolean.TRUE.equals(row.get("created"))).hasSize(1);
            assertThat(count("memory_fact")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unknown_section_and_missing_company_are_explicit() {
        assertThatThrownBy(() -> store.read("600519", "unknown", "", "", 200, 0))
                .isInstanceOf(MemoryException.class)
                .hasMessage("未知模块：unknown");
        assertThat(store.detail("600519", "", null)).isNull();
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }

    static Path legacyFixture() {
        return repoRoot().resolve("core/src/test/resources/fixtures/research-memory.db");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom)
                        && Files.readString(pom).contains("<module>data</module>")) {
                    return dir;
                }
            } catch (java.io.IOException ignored) {
                // keep walking up
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
