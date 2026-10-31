package com.investmentresearch.app.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 题库自身的正确性：数量、id 唯一、公司/工具/skill 都真实存在，且 facts 与冻结的 golden 一致。
 * 不调用模型、不联网，固定随 {@code mvn verify} 跑。
 */
class QuestionBankTest {
    private static final Path REPO = Path.of("..");
    private static final Path BANK = REPO.resolve("eval/questions.json");
    private static final Path GOLDENS = REPO.resolve("core/src/test/resources/golden/tools");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> COMPANIES =
            Set.of("000858", "600600", "601318", "601606", "605499");
    private static JsonNode bank;

    @BeforeAll
    static void load() throws Exception {
        bank = JSON.readTree(BANK.toFile());
    }

    @Test
    void has_twenty_questions_four_per_company_and_four_skills() {
        JsonNode questions = bank.path("questions");
        assertThat(questions.size()).isEqualTo(20);

        Set<String> ids = new HashSet<>();
        Map<String, Integer> perCompany = new HashMap<>();
        Set<String> skills = new HashSet<>();
        for (JsonNode question : questions) {
            String id = question.path("id").asText();
            assertThat(ids.add(id)).as("重复的题号 %s", id).isTrue();
            assertThat(question.path("question").asText()).isNotBlank();
            assertThat(COMPANIES).contains(question.path("code").asText());
            perCompany.merge(question.path("code").asText(), 1, Integer::sum);
            skills.add(question.path("skill").asText());
        }

        assertThat(perCompany).containsOnlyKeys(COMPANIES);
        assertThat(perCompany.values()).allMatch(count -> count == 4);
        assertThat(skills)
                .containsExactlyInAnyOrder(
                        "business_model", "operating_history", "valuation", "financial_safety");
    }

    @Test
    void every_skill_exists_with_matching_frontmatter() throws Exception {
        for (JsonNode question : bank.path("questions")) {
            String skill = question.path("skill").asText();
            Path file = REPO.resolve("skills").resolve(skill).resolve("SKILL.md");
            assertThat(file).as("skill %s 不存在", skill).exists();
            assertThat(Files.readString(file, StandardCharsets.UTF_8))
                    .as("skill %s 的 frontmatter name 与目录不一致", skill)
                    .contains("name: " + skill);
        }
    }

    @Test
    void every_required_tool_is_registered() throws Exception {
        JsonNode schemas =
                JSON.readTree(
                        REPO.resolve("core/src/main/resources/tool-schemas.json").toFile());
        for (JsonNode question : bank.path("questions")) {
            List<JsonNode> tools = iterable(question.path("required_tools_any"));
            assertThat(tools).as("%s 没有期望工具", question.path("id").asText()).isNotEmpty();
            for (JsonNode tool : tools) {
                assertThat(schemas.has(tool.asText()))
                        .as("%s 期望的工具 %s 未注册", question.path("id").asText(), tool.asText())
                        .isTrue();
            }
        }
    }

    @Test
    void every_fact_matches_the_frozen_golden() throws Exception {
        String asOf = bank.path("as_of").asText();
        for (JsonNode question : bank.path("questions")) {
            for (JsonNode fact : iterable(question.path("facts"))) {
                Path golden = goldenFor(fact.path("tool").asText(), question.path("code").asText());
                JsonNode file = JSON.readTree(golden.toFile());
                assertThat(file.path("generated_on").asText())
                        .as("%s 的金标准不是 %s 生成的", golden.getFileName(), asOf)
                        .isEqualTo(asOf);

                String field = fact.path("field").asText();
                JsonNode actual = file.path("result").path("data").path(field);
                assertThat(actual.isMissingNode())
                        .as("%s 里没有字段 %s", golden.getFileName(), field)
                        .isFalse();
                JsonNode expected = fact.path("value");
                if (expected.isTextual()) {
                    assertThat(actual.asText())
                            .as("%s/%s", golden.getFileName(), field)
                            .isEqualTo(expected.asText());
                } else {
                    assertThat(actual.asDouble())
                            .as("%s/%s", golden.getFileName(), field)
                            .isEqualTo(expected.asDouble());
                }
            }
        }
    }

    /** 金标准文件名带参数哈希；同一工具+公司有多个文件时说明题库没有钉死参数。 */
    private static Path goldenFor(String tool, String code) throws Exception {
        String prefix = tool + "__" + code + "__";
        try (Stream<Path> files = Files.list(GOLDENS)) {
            List<Path> matches =
                    files.filter(
                                    file ->
                                            file.getFileName().toString().startsWith(prefix)
                                                    && file.getFileName()
                                                            .toString()
                                                            .endsWith(".json"))
                            .toList();
            assertThat(matches)
                    .as("工具 %s 与公司 %s 的金标准应该唯一", tool, code)
                    .hasSize(1);
            return matches.get(0);
        }
    }

    private static List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(items::add);
        }
        return items;
    }
}
