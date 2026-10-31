package com.investmentresearch.core.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 阶段 3 的筛选打分、策略方案与复盘，金标准由 Python 侧用同一份输入生成。 */
class ResearchScoringTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};

    @Test
    void screening_matches_the_python_golden() {
        JsonNode golden = golden("screening");
        List<Map<String, Object>> rows = rows(golden.get("rows"));
        Map<String, List<Map<String, Object>>> histories = group(golden.get("histories"));

        Map<String, Object> shortlisted = Screening.shortlist(rows, 1.0, Screening.DEFAULT_MIN_PRICE);
        assertThat(tree(shortlisted.get("picked"))).isEqualTo(golden.get("picked"));
        assertThat(tree(shortlisted.get("stats"))).isEqualTo(golden.get("stats"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> picked = (List<Map<String, Object>>) shortlisted.get("picked");
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : picked) {
            byCode.put(row.get("代码").toString().toLowerCase(), row);
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (JsonNode expected : golden.get("stats_all")) {
            String code = expected.get("code").asText();
            Map<String, Object> item =
                    Screening.stockStats(byCode.get(code), histories.getOrDefault(code, List.of()));
            item.put("score", Screening.scoreStock(item));
            item.put("reasons", Screening.reasons(item));
            item.put("risks", Screening.risks(item));
            item.put("valuation", null);
            assertThat(tree(item)).as(code).isEqualTo(expected);
            if (item.get("score") != null) {
                candidates.add(item);
            }
        }

        candidates.sort(
                Comparator.comparingDouble((Map<String, Object> item) -> SectorRotation.num(item.get("score")))
                        .thenComparingDouble(
                                item -> {
                                    Double amount = SectorRotation.num(item.get("spot_amount_yi"));
                                    return amount == null ? 0 : amount;
                                })
                        .reversed());
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> item : Screening.diversify(candidates, Screening.DEFAULT_LIMIT, Screening.MAX_PER_BOARD)) {
            top.add(Screening.publicView(item));
        }
        assertThat(tree(top)).isEqualTo(golden.get("top"));
    }

    @Test
    void strategy_plan_matches_the_python_golden() {
        for (JsonNode scenario : golden("strategy")) {
            String name = scenario.get("name").asText();
            Map<String, Object> plan =
                    Strategy.buildPlan(
                            map(scenario.get("profile")),
                            map(scenario.get("market")),
                            scenario.get("holdings").isNull() ? null : rows(scenario.get("holdings")));
            assertThat(tree(plan)).as(name).isEqualTo(scenario.get("expected"));
        }
    }

    @Test
    void review_rows_match_the_python_golden() {
        JsonNode golden = golden("review");
        Map<String, String> errors = new LinkedHashMap<>();
        golden.get("errors").fields().forEachRemaining(entry -> errors.put(entry.getKey(), entry.getValue().asText()));
        Map<String, Object> result =
                Review.build(
                        rows(golden.get("reports")),
                        Review.series(rows(golden.get("benchmark"))),
                        group(golden.get("stock_rows")),
                        group(golden.get("sector_rows")),
                        golden.get("horizon").asInt(),
                        golden.get("as_of").asText(),
                        errors);
        assertThat(tree(result.get("reports"))).isEqualTo(golden.get("rows"));
        assertThat(tree(result.get("summary"))).isEqualTo(golden.get("summary"));
    }

    private static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    private static Map<String, Object> map(JsonNode node) {
        return MAPPER.convertValue(node, MAP);
    }

    private static List<Map<String, Object>> rows(JsonNode node) {
        return MAPPER.convertValue(node, ROWS);
    }

    private static Map<String, List<Map<String, Object>>> group(JsonNode node) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> out.put(entry.getKey(), rows(entry.getValue())));
        return out;
    }

    private static JsonNode golden(String name) {
        String resource = "/golden/" + name + ".json";
        try (InputStream in = ResearchScoringTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少 " + resource);
            }
            return MAPPER.readTree(in);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
