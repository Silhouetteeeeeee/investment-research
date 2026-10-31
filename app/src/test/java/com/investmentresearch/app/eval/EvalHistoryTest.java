package com.investmentresearch.app.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalHistoryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reports_pass_flips_and_judge_changes() throws Exception {
        JsonNode baseline =
                json.readTree(
                        "{\"passed\":2,\"judge_average\":4.0,\"questions\":["
                                + "{\"id\":\"q01\",\"passed\":true,\"judge\":{\"total\":4.0}},"
                                + "{\"id\":\"q02\",\"passed\":true,\"judge\":{\"total\":3.5}},"
                                + "{\"id\":\"q03\",\"passed\":false,\"judge\":{\"total\":2.5}}]}");
        List<Map<String, Object>> current =
                List.of(
                        question("q01", true, 4.0),
                        question("q02", false, 3.0),
                        question("q03", true, 3.0));

        Map<String, Object> delta = QuestionBankEval.compare(baseline, current);

        assertThat(delta.get("matched")).isEqualTo(3);
        assertThat(delta.get("passed_before")).isEqualTo(2);
        assertThat(delta.get("passed_after")).isEqualTo(2);
        assertThat(delta.get("regressions")).isEqualTo(List.of("q02"));
        assertThat(delta.get("improvements")).isEqualTo(List.of("q03"));
        assertThat((List<?>) delta.get("changed")).hasSize(2);
        assertThat(delta.get("judge_average_before")).isEqualTo(4.0);
        assertThat(delta.get("judge_average_after")).isEqualTo(3.33);
        assertThat(delta.get("judge_average_change")).isEqualTo(-0.67);
    }

    @Test
    void only_questions_present_in_both_reports_are_compared() throws Exception {
        JsonNode baseline =
                json.readTree(
                        "{\"passed\":1,\"questions\":["
                                + "{\"id\":\"q01\",\"passed\":true},"
                                + "{\"id\":\"q02\",\"passed\":false}]}");
        List<Map<String, Object>> current = List.of(question("q01", false, null));

        Map<String, Object> delta = QuestionBankEval.compare(baseline, current);

        assertThat(delta.get("matched")).isEqualTo(1);
        assertThat(delta.get("passed_before")).isEqualTo(1);
        assertThat(delta.get("passed_after")).isEqualTo(0);
        assertThat(delta.get("regressions")).isEqualTo(List.of("q01"));
    }

    @Test
    void missing_judge_on_both_sides_is_not_a_change() throws Exception {
        JsonNode baseline =
                json.readTree("{\"questions\":[{\"id\":\"q01\",\"passed\":true}]}");
        List<Map<String, Object>> current = List.of(question("q01", true, null));

        Map<String, Object> delta = QuestionBankEval.compare(baseline, current);

        assertThat((List<?>) delta.get("changed")).isEmpty();
        assertThat(delta.get("judge_average_change")).isNull();
    }

    private static Map<String, Object> question(String id, boolean passed, Double judgeTotal) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("passed", passed);
        if (judgeTotal != null) {
            item.put("judge", Map.of("total", judgeTotal));
        }
        return item;
    }
}
