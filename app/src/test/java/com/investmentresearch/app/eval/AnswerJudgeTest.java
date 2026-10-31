package com.investmentresearch.app.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswerJudgeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void extract_ignores_markdown_fences_and_chatter() {
        JsonNode scored =
                AnswerJudge.extract(
                        json,
                        "评分如下：\n```json\n"
                                + "{\"scores\":{\"grounding\":4,\"coverage\":4,"
                                + "\"reasoning\":3,\"clarity\":5},\"notes\":\"还行\"}\n```\n谢谢");

        assertThat(scored.path("scores").path("clarity").asInt()).isEqualTo(5);
    }

    @Test
    void pass_needs_average_four_and_no_dimension_below_three() {
        Map<String, Object> result =
                AnswerJudge.score(
                        json.valueToTree(
                                Map.of(
                                        "scores",
                                        Map.of(
                                                "grounding", 4,
                                                "coverage", 4,
                                                "reasoning", 3,
                                                "clarity", 5))));

        assertThat(result.get("total")).isEqualTo(4.0);
        assertThat(result.get("verdict")).isEqualTo("pass");
    }

    @Test
    void one_low_dimension_goes_to_review_even_with_high_average() {
        Map<String, Object> result =
                AnswerJudge.score(
                        json.valueToTree(
                                Map.of(
                                        "scores",
                                        Map.of(
                                                "grounding", 5,
                                                "coverage", 5,
                                                "reasoning", 2,
                                                "clarity", 5))));

        assertThat(result.get("total")).isEqualTo(4.25);
        assertThat(result.get("verdict")).isEqualTo("review");
    }

    @Test
    void score_outside_one_to_five_is_rejected() {
        JsonNode scored =
                json.valueToTree(
                        Map.of(
                                "scores",
                                Map.of(
                                        "grounding", 6,
                                        "coverage", 4,
                                        "reasoning", 4,
                                        "clarity", 4)));

        assertThatThrownBy(() -> AnswerJudge.score(scored))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grounding");
    }
}
