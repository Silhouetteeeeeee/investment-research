package com.investmentresearch.app.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.agent.AgentProperties;
import com.investmentresearch.core.agent.AskResult;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM Judge：按 {@code eval/judge-prompt.md} 给回答打四项分。分数只写进报告，不参与 pass/fail——
 * 模型评审有噪声，低分样本留给人工复核（报告里 verdict=review）。
 */
final class AnswerJudge {
    static final List<String> DIMENSIONS =
            List.of("grounding", "coverage", "reasoning", "clarity");

    private final Model model;
    private final ObjectMapper json;
    private final String template;

    AnswerJudge(AgentProperties properties, ObjectMapper json, Path repoRoot) throws Exception {
        this.json = json;
        this.model =
                ModelRegistry.resolve(
                        properties.model(),
                        ModelCreationContext.builder()
                                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                                .build());
        this.template =
                Files.readString(
                        repoRoot.resolve("eval/judge-prompt.md"), StandardCharsets.UTF_8);
    }

    Map<String, Object> judge(JsonNode question, AskResult answer, List<?> facts) {
        String prompt =
                template.replace("{{question}}", questionText(question))
                        .replace("{{answer}}", answer.text())
                        .replace("{{evidence}}", evidence(question, answer, facts));
        Msg message =
                Msg.builder().role(MsgRole.USER).name("judge").textContent(prompt).build();
        // 流式响应按 delta 到达，blockLast() 只剩收尾的空块，必须把每块文本拼起来
        String text =
                model.stream(
                                List.of(message),
                                List.of(),
                                GenerateOptions.builder().temperature(0.0).build())
                        .flatMapIterable(
                                response ->
                                        response.getContent() == null
                                                ? List.<ContentBlock>of()
                                                : response.getContent())
                        .filter(TextBlock.class::isInstance)
                        .map(block -> ((TextBlock) block).getText())
                        .filter(part -> part != null && !part.isEmpty())
                        .collect(Collectors.joining())
                        .block();

        return score(extract(json, text));
    }

    /** 模型偶尔会包一层 markdown 或加一句说明，取第一个 { 到最后一个 } 之间的内容。 */
    static JsonNode extract(ObjectMapper json, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("judge 没有返回内容");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("judge 输出不是 JSON：" + abbreviate(text));
        }
        try {
            return json.readTree(text.substring(start, end + 1));
        } catch (Exception error) {
            throw new IllegalStateException("judge 输出不是 JSON：" + abbreviate(text), error);
        }
    }

    /** 总分和判定由我们算，不信任模型自己的算术。 */
    static Map<String, Object> score(JsonNode scored) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String dimension : DIMENSIONS) {
            JsonNode value = scored.path("scores").path(dimension);
            if (!value.isInt() || value.asInt() < 1 || value.asInt() > 5) {
                throw new IllegalStateException(
                        "judge 返回的 " + dimension + " 不是 1-5 的整数：" + value);
            }
            scores.put(dimension, value.asInt());
        }
        double total =
                Math.round(
                                scores.values().stream().mapToInt(Integer::intValue).average()
                                        .orElse(0)
                                        * 100)
                        / 100.0;
        boolean pass = total >= 4.0 && scores.values().stream().allMatch(score -> score >= 3);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scores", scores);
        result.put("total", total);
        result.put("verdict", pass ? "pass" : "review");
        result.put("notes", scored.path("notes").asText(""));
        return result;
    }

    private String evidence(JsonNode question, AskResult answer, List<?> facts) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("company", question.path("name").asText() + "（" + question.path("code").asText() + "）");
        evidence.put("expected_skill", question.path("skill").asText());
        evidence.put("issues", answer.issues());
        evidence.put(
                "tools",
                answer.trace().stream()
                        .map(
                                step -> {
                                    Map<String, Object> tool = new LinkedHashMap<>();
                                    tool.put("name", step.name());
                                    tool.put("ok", step.ok());
                                    tool.put("error", step.error());
                                    return tool;
                                })
                        .toList());
        evidence.put("key_facts", facts);
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence);
        } catch (Exception error) {
            return String.valueOf(evidence);
        }
    }

    private static String questionText(JsonNode question) {
        return question.path("question").asText();
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
