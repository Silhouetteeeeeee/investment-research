package com.investmentresearch.core.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** {@code assistant/research_memory.py} 里与库无关的校验、编号与去重规则。 */
public final class MemoryRules {
    public static final List<String> SECTIONS =
            List.of("conclusions", "facts", "hypotheses", "changes", "questions");
    public static final List<String> VERIFICATION_STATUSES = List.of("已核验", "待核验");
    public static final List<String> HYPOTHESIS_STATUSES =
            List.of("待验证", "部分支持", "已证实", "已证伪");
    public static final List<String> CONFIDENCE_LEVELS = List.of("低", "中", "高");
    public static final List<String> CONCLUSION_STATUSES = List.of("有效", "已推翻");
    public static final List<String> IMPORTANCE_LEVELS = List.of("高", "中", "低");
    public static final List<String> QUESTION_STATUSES =
            List.of("待处理", "调查中", "已解决", "暂时无法解决");
    public static final List<String> CHANGE_OPERATIONS = List.of("补充", "修正", "推翻", "维持");
    public static final List<String> OPEN_QUESTION_STATUSES = List.of("待处理", "调查中");

    static final Pattern CODE = Pattern.compile("\\d{6}");
    public static final Pattern FACT_ID = Pattern.compile("F\\d{3,}");
    public static final Pattern HYPOTHESIS_ID = Pattern.compile("H\\d{3,}");
    public static final Pattern EVIDENCE_ID = Pattern.compile("[FH]\\d{3,}");
    private static final int MAX_FIELD_BYTES = 20 * 1024;
    private static final int MAX_RECORD_BYTES = 50 * 1024;
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MemoryRules() {}

    public static String code(Object value) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (!CODE.matcher(text).matches()) {
            throw new MemoryException("公司代码必须是六位数字");
        }
        return text;
    }

    public static String text(Object value, String field, boolean required) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (required && text.isEmpty()) {
            throw new MemoryException("缺少 " + field);
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FIELD_BYTES) {
            throw new MemoryException(field + " 超过 20KB");
        }
        return text;
    }

    public static List<Object> jsonArray(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new MemoryException(field + " 必须是数组");
        }
        return new ArrayList<>(items);
    }

    public static String enumOf(Object value, String field, List<String> allowed) {
        return enumOf(value, field, allowed, null);
    }

    /** {@code default_} 为 null 时缺值报错；空字符串不是缺值。 */
    public static String enumOf(
            Object value, String field, List<String> allowed, String default_) {
        String text = text(value, field, false);
        if (text.isEmpty()) {
            if (default_ == null) {
                throw new MemoryException("缺少 " + field);
            }
            return default_;
        }
        if (!allowed.contains(text)) {
            throw new MemoryException(field + " 只允许 " + String.join("/", allowed));
        }
        return text;
    }

    public static List<String> refIds(Object value, String field, Pattern pattern) {
        List<String> result = new ArrayList<>();
        for (Object item : jsonArray(value, field)) {
            String text = item == null ? "" : String.valueOf(item).strip();
            if (!pattern.matcher(text).matches()) {
                throw new MemoryException(field + " 含非法编号：" + text);
            }
            result.add(text);
        }
        return result;
    }

    public static Map<String, Object> validateFact(Map<String, Object> record) {
        if (record == null) {
            throw new MemoryException("record 必须是对象");
        }
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("subject", text(record.get("subject"), "subject", false));
        fact.put("fact_type", text(record.get("fact_type"), "fact_type", true));
        fact.put("content", text(record.get("content"), "content", true));
        fact.put("metrics", metrics(record.get("metrics")));
        fact.put("report_period", text(record.get("report_period"), "report_period", true));
        String publishedAt = text(record.get("published_at"), "published_at", false);
        fact.put("published_at", publishedAt.isEmpty() ? null : publishedAt);
        fact.put("source_id", text(record.get("source_id"), "source_id", true));
        fact.put("source_label", text(record.get("source_label"), "source_label", false));
        fact.put("source_locator", text(record.get("source_locator"), "source_locator", false));
        fact.put("source_url", text(record.get("source_url"), "source_url", false));
        String status = text(record.get("verification_status"), "verification_status", false);
        if (status.isEmpty()) {
            status = "已核验";
        }
        if (!VERIFICATION_STATUSES.contains(status)) {
            throw new MemoryException("verification_status 只允许 已核验/待核验");
        }
        fact.put("verification_status", status);
        recordSize(fact);
        return fact;
    }

    private static List<Map<String, Object>> metrics(Object value) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Object> items = jsonArray(value, "metrics");
        for (int index = 0; index < items.size(); index++) {
            if (!(items.get(index) instanceof Map<?, ?> raw)) {
                throw new MemoryException("metrics[" + index + "] 必须是对象");
            }
            String name = text(raw.get("name"), "metrics[" + index + "].name", true);
            String unit = text(raw.get("unit"), "metrics[" + index + "].unit", false);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            if (raw.containsKey("value") && raw.get("value") != null) {
                item.put("value", raw.get("value"));
            }
            if (!unit.isEmpty()) {
                item.put("unit", unit);
            }
            if (raw.get("yoy") != null) {
                item.put("yoy", raw.get("yoy"));
            }
            metrics.add(item);
        }
        return metrics;
    }

    public static Map<String, Object> validateHypothesis(Map<String, Object> record) {
        if (record == null) {
            throw new MemoryException("record 必须是对象");
        }
        Map<String, Object> hypothesis = new LinkedHashMap<>();
        hypothesis.put("statement", text(record.get("statement"), "statement", true));
        hypothesis.put(
                "supporting_fact_ids",
                refIds(record.get("supporting_fact_ids"), "supporting_fact_ids", FACT_ID));
        hypothesis.put("assumptions", text(record.get("assumptions"), "assumptions", false));
        hypothesis.put(
                "confidence",
                enumOf(record.get("confidence"), "confidence", CONFIDENCE_LEVELS));
        hypothesis.put(
                "verification_method",
                text(record.get("verification_method"), "verification_method", true));
        hypothesis.put(
                "falsification_condition",
                text(
                        record.get("falsification_condition"),
                        "证伪条件（falsification_condition）",
                        true));
        hypothesis.put("status", enumOf(record.get("status"), "status", HYPOTHESIS_STATUSES));
        String nextReview = text(record.get("next_review_date"), "next_review_date", false);
        hypothesis.put("next_review_date", nextReview.isEmpty() ? null : nextReview);
        if (((List<?>) hypothesis.get("supporting_fact_ids")).isEmpty()) {
            throw new MemoryException("假设必须引用至少一条事实");
        }
        recordSize(hypothesis);
        return hypothesis;
    }

    public static Map<String, Object> validateQuestion(Map<String, Object> record) {
        if (record == null) {
            throw new MemoryException("record 必须是对象");
        }
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("question", text(record.get("question"), "question", true));
        question.put("importance", enumOf(record.get("importance"), "importance", IMPORTANCE_LEVELS));
        question.put("known_facts", refIds(record.get("known_facts"), "known_facts", FACT_ID));
        question.put(
                "missing_evidence", text(record.get("missing_evidence"), "missing_evidence", true));
        question.put("next_action", text(record.get("next_action"), "next_action", true));
        question.put(
                "resolution_criteria",
                text(record.get("resolution_criteria"), "resolution_criteria", true));
        question.put(
                "related_hypothesis_ids",
                refIds(
                        record.get("related_hypothesis_ids"),
                        "related_hypothesis_ids",
                        HYPOTHESIS_ID));
        question.put("status", enumOf(record.get("status"), "status", QUESTION_STATUSES));
        question.put(
                "resolution_note", text(record.get("resolution_note"), "resolution_note", false));
        recordSize(question);
        return question;
    }

    public static Map<String, Object> validateConclusion(Map<String, Object> record) {
        if (record == null) {
            throw new MemoryException("record 必须是对象");
        }
        Map<String, Object> conclusion = new LinkedHashMap<>();
        conclusion.put("title", text(record.get("title"), "title", true));
        conclusion.put("statement", text(record.get("statement"), "statement", true));
        conclusion.put(
                "status",
                enumOf(record.get("status"), "status", CONCLUSION_STATUSES, "有效"));
        conclusion.put(
                "confidence",
                enumOf(record.get("confidence"), "confidence", CONFIDENCE_LEVELS, ""));
        conclusion.put(
                "based_on_fact_ids",
                refIds(record.get("based_on_fact_ids"), "based_on_fact_ids", FACT_ID));
        conclusion.put(
                "based_on_hypothesis_ids",
                refIds(
                        record.get("based_on_hypothesis_ids"),
                        "based_on_hypothesis_ids",
                        HYPOTHESIS_ID));
        recordSize(conclusion);
        return conclusion;
    }

    public static String dedupeKey(Map<String, Object> fact) {
        String factType = normalizeContent((String) fact.get("fact_type"));
        String reportPeriod = normalizeContent((String) fact.get("report_period"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) fact.get("metrics");
        String raw;
        if (!metrics.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> item : metrics) {
                parts.add(
                        String.join(
                                "|",
                                normalizeContent(String.valueOf(item.getOrDefault("name", ""))),
                                normalizeMetricValue(item.getOrDefault("value", "")),
                                normalizeContent(String.valueOf(item.getOrDefault("unit", "")))));
            }
            parts.sort(String::compareTo);
            raw = String.join("|", factType, reportPeriod, String.join("|", parts));
        } else {
            raw =
                    String.join(
                            "|",
                            factType,
                            reportPeriod,
                            normalizeContent((String) fact.get("content")));
        }
        return sha1(raw);
    }

    static String normalizeContent(String value) {
        return WHITESPACE
                .matcher(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .replaceAll("")
                .toLowerCase();
    }

    static String normalizeMetricValue(Object value) {
        if (value instanceof Boolean) {
            return normalizeContent(String.valueOf(value));
        }
        if (value instanceof Number) {
            try {
                return plain(new BigDecimal(value.toString()));
            } catch (NumberFormatException error) {
                return normalizeContent(String.valueOf(value));
            }
        }
        String text = String.valueOf(value);
        try {
            return plain(new BigDecimal(text.strip().replace(",", "")));
        } catch (NumberFormatException error) {
            return normalizeContent(text);
        }
    }

    private static String plain(BigDecimal number) {
        return number.stripTrailingZeros().toPlainString();
    }

    static void recordSize(Object record) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(record);
        } catch (JsonProcessingException error) {
            throw new MemoryException("记录无法序列化：" + error.getOriginalMessage());
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
            throw new MemoryException("单条记录超过 50KB");
        }
    }

    private static String sha1(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
