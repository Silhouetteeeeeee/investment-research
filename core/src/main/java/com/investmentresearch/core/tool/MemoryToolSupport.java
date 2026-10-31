package com.investmentresearch.core.tool;

import com.investmentresearch.core.domain.Source;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** {@code assistant/tools.py:1235-1280} 的模块名、必填字段与来源登记。 */
final class MemoryToolSupport {
    static final Map<String, String[]> SECTIONS =
            Map.of(
                    "facts", new String[] {"事实", "fact_id"},
                    "hypotheses", new String[] {"假设", "hypothesis_id"},
                    "conclusions", new String[] {"结论", "conclusion_id"},
                    "changes", new String[] {"变化", "change_id"},
                    "questions", new String[] {"问题", "question_id"});

    static final Map<String, List<String>> REQUIRED =
            Map.of(
                    "facts", List.of("fact_type", "content", "report_period", "source_id"),
                    "hypotheses",
                            List.of(
                                    "statement",
                                    "supporting_fact_ids",
                                    "verification_method",
                                    "falsification_condition"),
                    "conclusions", List.of("title", "statement"),
                    "questions",
                            List.of(
                                    "question",
                                    "missing_evidence",
                                    "next_action",
                                    "resolution_criteria"));

    private static final DateTimeFormatter FETCHED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MemoryToolSupport() {}

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String fetchedNow() {
        return LocalDateTime.now().format(FETCHED);
    }

    static String asOf(String section, Map<String, Object> record) {
        String value =
                switch (section) {
                    case "facts" -> firstNonEmpty(record.get("report_period"), record.get("published_at"));
                    case "hypotheses" ->
                            firstNonEmpty(record.get("next_review_date"), record.get("updated_at"));
                    case "changes" -> firstNonEmpty(record.get("trigger_date"));
                    case "questions" ->
                            firstNonEmpty(record.get("last_checked_at"), record.get("created_at"));
                    default -> firstNonEmpty(record.get("updated_at"));
                };
        return value.length() > 10 ? value.substring(0, 10) : value;
    }

    static Source source(
            String code, String section, Map<String, Object> record, String fetched) {
        String[] meta = SECTIONS.get(section);
        String recordId = text(record.get(meta[1]));
        return new Source(
                "memory:" + code + ":" + recordId,
                "研究档案",
                meta[0] + " " + recordId,
                asOf(section, record),
                fetched);
    }

    static String firstNonEmpty(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isEmpty()) {
                return String.valueOf(value);
            }
        }
        return "";
    }
}
