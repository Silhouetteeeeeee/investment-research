package com.investmentresearch.core.research;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 板块轮动的纯函数与 Python {@code research.sector_rotation} 逐字段一致。
 *
 * <p>金标准由 {@code data} 的夹具喂给 Python 生成，输入与输出都存在同一个文件里。
 */
class SectorRotationParityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN = repoRoot().resolve("core/src/test/resources/golden/sector_rotation.json");

    @Test
    void sector_stats_score_and_drivers_match_the_python_golden() throws Exception {
        JsonNode golden = MAPPER.readTree(Files.readString(GOLDEN, UTF_8));
        int total = golden.path("total").asInt();
        double benchmarkToday = golden.path("benchmark_today").asDouble();

        Map<String, Double> closes = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = golden.path("benchmark_closes").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            closes.put(entry.getKey(), entry.getValue().asDouble());
        }
        Map<String, Object> benchmark = SectorRotation.makeBenchmark(closes);

        int boards = 0;
        for (JsonNode board : golden.path("boards")) {
            Map<String, Object> summary = map(board.get("summary"));
            List<Map<String, Object>> history = rows(board.get("history"));
            Map<String, Object> sector = SectorRotation.sectorStats(summary, history, benchmark, total, 1.0);

            Double chg = SectorRotation.num(sector.get("chg_pct"));
            sector.put("excess_today", chg == null ? null : SectorRotation.round(chg - benchmarkToday, 2));
            sector.put("score", SectorRotation.score(sector));
            sector.put(
                    "persistence",
                    SectorRotation.persistence(
                            sector.get("rs_days_10") instanceof Number days ? days.intValue() : null,
                            SectorRotation.num(sector.get("score"))));
            sector.put("reversal_signals", SectorRotation.reversalSignals(sector));
            sector.put("drivers", SectorRotation.drivers(sector));

            assertThat(MAPPER.readTree(MAPPER.writeValueAsString(sector)))
                    .as(board.get("summary").path("板块").asText())
                    .isEqualTo(board.get("stats"));
            boards++;
        }
        assertThat(boards).isEqualTo(4);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode node) {
        return MAPPER.convertValue(node, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(JsonNode node) {
        return new ArrayList<>(MAPPER.convertValue(node, List.class));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom) && Files.readString(pom).contains("<module>data</module>")) {
                    return dir;
                }
            } catch (IOException ignored) {
                // keep walking up
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
