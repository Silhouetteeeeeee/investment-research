package com.investmentresearch.app.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.app.Application;
import com.investmentresearch.core.agent.AgentProperties;
import com.investmentresearch.core.agent.AskResult;
import com.investmentresearch.core.agent.AssistantEngine;
import com.investmentresearch.core.agent.StreamListener;
import com.investmentresearch.core.agent.ToolEvent;
import com.investmentresearch.core.domain.TraceStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 题库执行器：按 {@code eval/questions.json} 逐题调用真实模型，用 trace 断言 skill、工具和关键事实，
 * 报告写到 {@code eval/results/<agent_version>-<prompt_version>.json}。类名不带 Test，默认不跑：
 *
 * <pre>
 * LIVE_EVAL=1 ./mvnw -pl app -am test -Dtest=QuestionBankEval -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * 调试时可加 {@code -Deval.limit=3} 只跑前几题。跑之前最好先跑 {@code QuestionBankTest}，
 * 免得把题库错误算到模型头上。
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.api-token=live-eval-token",
            // surefire 的工作目录是模块目录 app/，skills 与题库在仓库根
            "APP_SKILLS_DIR=../skills",
            "spring.web.resources.static-locations=classpath:/test-web/",
            "management.server.port=0"
        })
@EnabledIfEnvironmentVariable(named = "LIVE_EVAL", matches = "1")
class QuestionBankEval {
    private static final Path REPO = Path.of("..");
    private static final Path DATA_DIR = Path.of("target/eval-data");
    private static final Path SESSION_DIR = DATA_DIR.resolve("sessions/default");
    private static final Path RESULTS = REPO.resolve("eval/results");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("APP_DATA_DIR", DATA_DIR::toString);
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:sqlite:file:target/live-eval.db?busy_timeout=5000");
    }

    @Autowired AssistantEngine engine;
    @Autowired AgentProperties properties;
    @Autowired ObjectMapper json;

    @Test
    @Timeout(3600)
    void every_question_loads_its_skill_and_gets_its_facts_right() throws Exception {
        assumeTrue(
                System.getenv("DEEPSEEK_API_KEY") != null
                        && !System.getenv("DEEPSEEK_API_KEY").isBlank(),
                "DEEPSEEK_API_KEY 未设置，跳过真实评测");

        JsonNode bank = json.readTree(REPO.resolve("eval/questions.json").toFile());
        VersionStamp stamp =
                VersionStamp.capture(REPO, REPO.resolve("eval/questions.json"));
        List<JsonNode> questions = new ArrayList<>();
        bank.path("questions").forEach(questions::add);
        int limit = Math.min(Integer.getInteger("eval.limit", questions.size()), questions.size());

        AnswerJudge judge = new AnswerJudge(properties, json, REPO);
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        long started = System.nanoTime();
        for (int i = 0; i < limit; i++) {
            JsonNode question = questions.get(i);
            Map<String, Object> result = runOne(question, i, judge);
            results.add(result);
            if (!Boolean.TRUE.equals(result.get("passed"))) {
                failures.add(question.path("id").asText() + ": " + result.get("failures"));
            }
            System.out.printf(
                    "eval: %s %s -> %s (judge=%s)%n",
                    question.path("id").asText(),
                    question.path("skill").asText(),
                    Boolean.TRUE.equals(result.get("passed")) ? "PASS" : "FAIL",
                    judgeVerdict(result));
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        Path report = writeReport(bank, stamp, results, limit, elapsedMs);
        long passed = results.size() - failures.size();
        System.out.println(
                "eval report: "
                        + report.toAbsolutePath()
                        + " ("
                        + passed
                        + "/"
                        + results.size()
                        + " passed)");

        // CI 用阈值门禁；本地默认仍然要求零失败
        Double minPassRate = doubleProperty("eval.min-pass-rate");
        Double minJudgeAverage = doubleProperty("eval.min-judge-average");
        if (minPassRate == null) {
            assertThat(failures).as("评测失败的题目见报告 %s", report).isEmpty();
        } else {
            double passRate = (double) passed / results.size();
            assertThat(passRate)
                    .as("通过率 %.2f 低于阈值 %.2f（报告 %s）", passRate, minPassRate, report)
                    .isGreaterThanOrEqualTo(minPassRate);
        }
        if (minJudgeAverage != null) {
            assertThat(judgeAverage(results))
                    .as("Judge 均分低于阈值 %.2f（报告 %s）", minJudgeAverage, report)
                    .isNotNull()
                    .isGreaterThanOrEqualTo(minJudgeAverage);
        }
    }

    private static Double doubleProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : Double.parseDouble(value);
    }

    private Map<String, Object> runOne(JsonNode question, int index, AnswerJudge judge)
            throws Exception {
        String code = question.path("code").asText();
        String name = question.path("name").asText();
        String skill = question.path("skill").asText();
        String text = question.path("question").asText();
        List<String> requiredTools = strings(question.path("required_tools_any"));
        // 每题新会话，避免上一题的 skill 加载记录混进来
        long conversationId = Math.abs(System.nanoTime()) + index;
        List<String> failures = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", question.path("id").asText());
        item.put("code", code);
        item.put("name", name);
        item.put("skill", skill);
        item.put("question", text);

        long started = System.nanoTime();
        AskResult answer;
        try {
            answer =
                    engine.ask(
                            conversationId,
                            code,
                            name,
                            text,
                            new StreamListener() {
                                @Override
                                public void text(String delta) {}

                                @Override
                                public void discard() {}

                                @Override
                                public void tool(ToolEvent event) {}
                            });
        } catch (RuntimeException error) {
            failures.add("engine: " + error.getMessage());
            item.put("passed", false);
            item.put("failures", failures);
            item.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
            return item;
        }
        item.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
        item.put("conversation_id", conversationId);
        item.put("run_id", answer.run().runId());
        Object stopReason = answer.run().payload().get("stop_reason");
        item.put("stop_reason", stopReason);
        item.put("answer", answer.text());
        item.put("issues", answer.issues());

        if (!"answered".equals(stopReason) || answer.text().isBlank()) {
            failures.add("stop_reason=" + stopReason + "，回答为空=" + answer.text().isBlank());
        }
        List<String> loaded = loadedSkills(conversationId);
        item.put("loaded_skills", loaded);
        if (loaded.stream()
                .noneMatch(id -> id.equals(skill) || id.startsWith(skill + "_"))) {
            failures.add("期望 skill " + skill + "，实际 " + loaded);
        }

        List<TraceStep> trace = answer.trace();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (TraceStep step : trace) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", step.name());
            tool.put("ok", step.ok());
            tool.put("arguments", step.arguments());
            tool.put("error", step.error());
            tools.add(tool);
        }
        item.put("tools", tools);

        if (trace.stream().noneMatch(step -> step.ok() && requiredTools.contains(step.name()))) {
            failures.add("期望调用 " + requiredTools + " 之一，实际 " + names(trace));
        }
        // 可选工具失败只记警告，必修工具失败或参数错误才算错
        List<String> callerErrors =
                trace.stream()
                        .filter(step -> !step.ok() && isCallerError(step.error()))
                        .map(step -> step.name() + ": " + step.error())
                        .toList();
        List<String> upstreamFailures =
                trace.stream()
                        .filter(step -> !step.ok() && !isCallerError(step.error()))
                        .map(step -> step.name() + ": " + step.error())
                        .toList();
        List<String> requiredFailed =
                trace.stream()
                        .filter(step -> !step.ok() && requiredTools.contains(step.name()))
                        .map(step -> step.name() + ": " + step.error())
                        .toList();
        if (!callerErrors.isEmpty()) {
            failures.add("工具调用参数错误 " + callerErrors);
        }
        if (!requiredFailed.isEmpty()) {
            failures.add("必修工具执行失败 " + requiredFailed);
        }
        item.put("tool_errors", callerErrors);
        item.put("tool_warnings", upstreamFailures);
        item.put("facts", checkFacts(question.path("facts"), answer.raw(), failures));

        try {
            item.put("judge", judge.judge(question, answer, (List<?>) item.get("facts")));
        } catch (RuntimeException error) {
            Map<String, Object> judgeError = new LinkedHashMap<>();
            judgeError.put("error", error.getMessage());
            item.put("judge", judgeError);
        }

        if (answer.issues().contains("unknown_citation")) {
            failures.add("引用了不存在的来源");
        }

        item.put("passed", failures.isEmpty());
        item.put("failures", failures);
        return item;
    }

    /**
     * 事实核对：raw 的 content 是工具结果 JSON，AgentEngine 会给每条 raw 带上工具名。
     * 并行调用时 trace 与 raw 顺序不保证一致，所以按名字找；同一工具多次调用只要有一次匹配就算过。
     */
    private List<Map<String, Object>> checkFacts(
            JsonNode facts, List<Map<String, Object>> raw, List<String> failures)
            throws Exception {
        List<Map<String, Object>> checked = new ArrayList<>();
        for (JsonNode fact : facts) {
            String tool = fact.path("tool").asText();
            String field = fact.path("field").asText();
            JsonNode expected = fact.path("value");
            boolean matched = false;
            String actual = null;
            for (Map<String, Object> message : raw) {
                if (!tool.equals(message.get("name"))) {
                    continue;
                }
                Object content = message.get("content");
                if (content == null) {
                    continue;
                }
                JsonNode value = json.readTree(String.valueOf(content)).path("data").path(field);
                if (value.isMissingNode() || value.isNull()) {
                    continue;
                }
                actual = value.asText();
                if (expected.isTextual() ? expected.asText().equals(value.asText())
                        : expected.asDouble() == value.asDouble()) {
                    matched = true;
                    break;
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", tool);
            item.put("field", field);
            item.put("expected", expected.asText());
            item.put("actual", actual);
            item.put("matched", matched);
            checked.add(item);
            if (!matched) {
                failures.add("事实不符 " + tool + "." + field + " 期望 " + expected.asText() + " 实际 " + actual);
            }
        }
        return checked;
    }

    private static Double judgeAverage(List<Map<String, Object>> results) {
        List<Double> totals = new ArrayList<>();
        for (Map<String, Object> item : results) {
            if (item.get("judge") instanceof Map<?, ?> judge
                    && judge.get("total") instanceof Number total) {
                totals.add(total.doubleValue());
            }
        }
        if (totals.isEmpty()) {
            return null;
        }
        return Math.round(
                        totals.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100)
                / 100.0;
    }

    private static long judgeReviews(List<Map<String, Object>> results) {
        return results.stream()
                .filter(
                        item ->
                                item.get("judge") instanceof Map<?, ?> judge
                                        && "review".equals(judge.get("verdict")))
                .count();
    }

    private static String judgeVerdict(Map<String, Object> item) {
        if (item.get("judge") instanceof Map<?, ?> judge) {
            if (judge.get("verdict") == null) {
                return "error";
            }
            return judge.get("verdict") + " " + judge.get("total");
        }
        return "-";
    }

    private static long count(List<Map<String, Object>> results, String key) {
        return results.stream()
                .mapToLong(
                        item -> item.get(key) instanceof List<?> list ? list.size() : 0)
                .sum();
    }

    private Path writeReport(
            JsonNode bank,
            VersionStamp stamp,
            List<Map<String, Object>> results,
            int limit,
            long elapsedMs)
            throws Exception {
        long passed = results.stream().filter(item -> Boolean.TRUE.equals(item.get("passed"))).count();
        long errors = count(results, "tool_errors");
        long warnings = count(results, "tool_warnings");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generated_at", Instant.now().toString());
        report.put("as_of", bank.path("as_of").asText());
        report.put("model_version", properties.model());
        report.put("dataset_questions", bank.path("questions").size());
        report.put("executed", limit);
        report.put("passed", passed);
        report.put("failed", results.size() - passed);
        report.put("tool_errors", errors);
        report.put("tool_warnings", warnings);
        report.put("judge_average", judgeAverage(results));
        report.put("judge_reviews", judgeReviews(results));
        report.put(
                "judge_prompt_version",
                VersionStamp.hashFile(REPO.resolve("eval/judge-prompt.md")));
        report.put("elapsed_ms", elapsedMs);
        report.putAll(stamp.asMap());
        report.put("questions", results);

        Files.createDirectories(RESULTS);
        Path file = RESULTS.resolve(fileName(stamp));
        Map<String, Object> comparison = comparison(stamp, file, results);
        report.putAll(comparison);
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        if (comparison.get("delta") instanceof Map<?, ?> delta) {
            System.out.printf(
                    "eval compare vs %s: passed %s->%s, judge %s->%s, regressions=%s%n",
                    comparison.get("baseline") instanceof Map<?, ?> base
                            ? base.get("file")
                            : "?",
                    delta.get("passed_before"),
                    delta.get("passed_after"),
                    delta.get("judge_average_before"),
                    delta.get("judge_average_after"),
                    delta.get("regressions"));
        }
        return file;
    }

    /** 文件名带上模型和题库版本，换模型/换题库不会互相覆盖，才能做历史对比。 */
    private String fileName(VersionStamp stamp) {
        return stamp.agentVersion()
                + "-"
                + stamp.promptVersion()
                + "-"
                + properties.model().replaceAll("[^A-Za-z0-9._-]", "_")
                + "-"
                + stamp.datasetVersion()
                + ".json";
    }

    /**
     * 找同题库、不同版本配置的上一份报告。同一配置重跑只覆盖自己，不拿来对比（那只是随机波动）。
     */
    private Map<String, Object> comparison(
            VersionStamp stamp, Path self, List<Map<String, Object>> results) throws IOException {
        if (!Files.isDirectory(RESULTS)) {
            return Map.of();
        }
        Path baseline;
        try (Stream<Path> files = Files.list(RESULTS)) {
            baseline =
                    files.filter(file -> file.getFileName().toString().endsWith(".json"))
                            .filter(file -> !file.equals(self))
                            .filter(file -> sameDataset(file, stamp.datasetVersion()))
                            .filter(file -> !sameIdentity(file, stamp))
                            .max(Comparator.comparingLong(QuestionBankEval::lastModified))
                            .orElse(null);
        }
        if (baseline == null) {
            return Map.of();
        }
        JsonNode old = json.readTree(baseline.toFile());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("file", baseline.getFileName().toString());
        for (String key :
                List.of(
                        "generated_at",
                        "agent_version",
                        "prompt_version",
                        "model_version",
                        "skills_version",
                        "tools_version",
                        "executed",
                        "passed",
                        "judge_average")) {
            if (old.has(key)) {
                info.put(key, json.convertValue(old.get(key), Object.class));
            }
        }
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("baseline", info);
        comparison.put("delta", compare(old, results));
        return comparison;
    }

    private boolean sameDataset(Path file, String datasetVersion) {
        try {
            return datasetVersion.equals(json.readTree(file.toFile()).path("dataset_version").asText());
        } catch (IOException error) {
            return false;
        }
    }

    private boolean sameIdentity(Path file, VersionStamp stamp) {
        try {
            JsonNode old = json.readTree(file.toFile());
            return stamp.agentVersion().equals(old.path("agent_version").asText())
                    && stamp.promptVersion().equals(old.path("prompt_version").asText())
                    && stamp.skillsVersion().equals(old.path("skills_version").asText())
                    && stamp.toolsVersion().equals(old.path("tools_version").asText())
                    && properties.model().equals(old.path("model_version").asText());
        } catch (IOException error) {
            return false;
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException error) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * 只在两边都有的题目上比较通过数，避免 partial 运行（eval.limit）被当成退步。
     * 总分差值直接取报告头部的 judge_average；某项 judge 缺失时该字段留 null。
     */
    static Map<String, Object> compare(
            JsonNode baseline, List<Map<String, Object>> current) {
        Map<String, JsonNode> before = new LinkedHashMap<>();
        baseline.path("questions").forEach(question -> before.put(question.path("id").asText(), question));

        int matched = 0;
        int passedBefore = 0;
        int passedNow = 0;
        List<String> regressions = new ArrayList<>();
        List<String> improvements = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        for (Map<String, Object> item : current) {
            String id = String.valueOf(item.get("id"));
            JsonNode old = before.get(id);
            if (old == null) {
                continue;
            }
            matched++;
            boolean wasPassed = old.path("passed").asBoolean();
            boolean isPassed = Boolean.TRUE.equals(item.get("passed"));
            if (wasPassed) {
                passedBefore++;
            }
            if (isPassed) {
                passedNow++;
            }
            Double judgeBefore = judgeTotal(old.path("judge"));
            Double judgeNow = judgeTotal(item.get("judge"));
            if (wasPassed != isPassed || !Objects.equals(judgeBefore, judgeNow)) {
                if (wasPassed && !isPassed) {
                    regressions.add(id);
                }
                if (!wasPassed && isPassed) {
                    improvements.add(id);
                }
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("id", id);
                change.put("passed_before", wasPassed);
                change.put("passed_after", isPassed);
                change.put("judge_before", judgeBefore);
                change.put("judge_after", judgeNow);
                changed.add(change);
            }
        }

        Double averageBefore =
                baseline.hasNonNull("judge_average") ? baseline.get("judge_average").asDouble() : null;
        Double averageNow = judgeAverage(current);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("matched", matched);
        delta.put("passed_before", passedBefore);
        delta.put("passed_after", passedNow);
        delta.put(
                "judge_average_before", averageBefore);
        delta.put("judge_average_after", averageNow);
        delta.put(
                "judge_average_change",
                averageBefore == null || averageNow == null
                        ? null
                        : Math.round((averageNow - averageBefore) * 100) / 100.0);
        delta.put("regressions", regressions);
        delta.put("improvements", improvements);
        delta.put("changed", changed);
        return delta;
    }

    private static Double judgeTotal(JsonNode judge) {
        return judge.path("total").isNumber() ? judge.path("total").asDouble() : null;
    }

    private static Double judgeTotal(Object judge) {
        if (judge instanceof Map<?, ?> map && map.get("total") instanceof Number total) {
            return total.doubleValue();
        }
        return null;
    }

    /** 工具执行前的参数/业务校验失败算模型的调用错误；上游报错才算环境警告。 */
    private static boolean isCallerError(String error) {
        if (error == null) {
            return false;
        }
        return error.contains("参数校验")
                || error.contains("校验未通过")
                || error.contains("不是合法")
                || error.contains("未知工具")
                || error.contains("缺少")
                || error.contains("必须是")
                || error.contains("新旧结论");
    }

    /** AgentScope 把会话状态写成 JSON，skill 调用参数里的 skillId 是唯一能确认「加载了哪个」的地方。 */
    private List<String> loadedSkills(long conversationId) throws Exception {
        Path file = SESSION_DIR.resolve(Long.toString(conversationId)).resolve("agent_state.json");
        List<String> ids = new ArrayList<>();
        if (Files.exists(file)) {
            collectSkillIds(json.readTree(file.toFile()), ids);
        }
        return ids;
    }

    private static void collectSkillIds(JsonNode node, List<String> ids) {
        if (node.isObject()) {
            JsonNode skillId = node.get("skillId");
            if (skillId != null && skillId.isTextual() && !ids.contains(skillId.asText())) {
                ids.add(skillId.asText());
            }
            node.fields().forEachRemaining(field -> collectSkillIds(field.getValue(), ids));
        } else if (node.isArray()) {
            node.forEach(child -> collectSkillIds(child, ids));
        }
    }

    private static List<String> names(List<TraceStep> trace) {
        return trace.stream().map(TraceStep::name).toList();
    }

    private static List<String> strings(JsonNode array) {
        List<String> items = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> items.add(item.asText()));
        }
        return items;
    }
}
