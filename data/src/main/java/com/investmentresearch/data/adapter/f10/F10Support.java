package com.investmentresearch.data.adapter.f10;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** F10 两个适配器共用的小工具，逐个对应 Python 的同名函数。 */
final class F10Support {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private F10Support() {}

    /** {@code stock/db_service.py:normalize_stock_code}。 */
    static String normalize(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (Character.isDigit(code.charAt(i))) {
                digits.append(code.charAt(i));
            }
        }
        while (digits.length() > 0 && digits.length() < 6) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }

    /** {@code stock/utils.py:get_stock_prefix} 的纯数字分支。 */
    static String prefix(String digits) {
        if (digits.isEmpty()) {
            return "UNKNOWN";
        }
        if (digits.startsWith("600")
                || digits.startsWith("601")
                || digits.startsWith("603")
                || digits.startsWith("605")
                || digits.startsWith("688")) {
            return "SH";
        }
        if (digits.startsWith("000")
                || digits.startsWith("001")
                || digits.startsWith("002")
                || digits.startsWith("003")
                || digits.startsWith("300")
                || digits.startsWith("301")) {
            return "SZ";
        }
        if (digits.startsWith("920") || digits.startsWith("4") || digits.startsWith("8")) {
            return "BJ";
        }
        return "UNKNOWN";
    }

    /** Python {@code _text}：去空白，空串与 "-" 视为缺失。 */
    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText().strip();
        return text.isEmpty() || text.equals("-") ? null : text;
    }

    /** Python {@code _num}：null、"-"、"" 与非数字都返回 null。 */
    static Double num(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String text = node.asText().strip();
        if (text.isEmpty() || text.equals("-")) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** Python {@code _int}：先按浮点解析再取整。 */
    static Integer integer(JsonNode node) {
        Double number = num(node);
        return number == null ? null : (int) (double) number;
    }

    /** Python {@code str(value)} 用于 f-string 时的 None 表现。 */
    static String py(Object value) {
        return value == null ? "None" : String.valueOf(value);
    }

    static String timestamp(Clock clock) {
        return LocalDateTime.now(clock).format(TIMESTAMP);
    }

    static String datePart(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        return timestamp.length() <= 10 ? timestamp : timestamp.substring(0, 10);
    }

    /** Python {@code round(value, digits)}：按二进制精确值取最近，平局取偶。 */
    static double round(double value, int digits) {
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** {@code assistant/tools.py:_yi}。 */
    static Double yi(Double value) {
        return value == null ? null : round(value / 1e8, 2);
    }

    /** {@code assistant/tools.py:_pct}；加 0.0 把 -0.0 归一成 0.0。 */
    static Double pct(Double value) {
        return value == null ? null : round(value, 1) + 0.0;
    }

    /** {@code assistant/company_profile.py:_clip}。 */
    static String clip(String text, int maxChars) {
        if (text == null || text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        String clipped = text.substring(0, text.offsetByCodePoints(0, maxChars));
        while (!clipped.isEmpty() && "，、；： ".indexOf(clipped.charAt(clipped.length() - 1)) >= 0) {
            clipped = clipped.substring(0, clipped.length() - 1);
        }
        return clipped + "…";
    }
}
