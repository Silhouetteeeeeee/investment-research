package com.investmentresearch.core.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Unified tool result; failures are values, never exceptions. */
public record ToolResult(
        boolean ok,
        String company,
        String asOf,
        List<Source> sources,
        Map<String, Object> data,
        String error,
        boolean cached) {

    public static final String CACHED_NOTE = "本次会话已取过相同参数的数据，未重新请求";

    public static ToolResult ok(
            String company, String asOf, List<Source> sources, Map<String, Object> data) {
        return new ToolResult(true, company, asOf, sources, data, null, false);
    }

    public static ToolResult failure(String company, String error) {
        return new ToolResult(false, company, null, List.of(), null, error, false);
    }

    /** Python 的失败结果可以带 data（如复盘、存档），消息里仍只有 error。 */
    public static ToolResult failure(String company, String error, Map<String, Object> data) {
        return new ToolResult(false, company, null, List.of(), data, error, false);
    }

    /** Session cache hit: same fields, only the flag changes; the note is written by the serializer. */
    public ToolResult asCached() {
        return new ToolResult(ok, company, asOf, sources, data, error, true);
    }

    /**
     * Same bytes as Python {@code ToolResult.to_message()}, i.e. {@code json.dumps(message,
     * ensure_ascii=False)}: default separators, insertion order, non-ASCII left raw.
     */
    public String toMessageJson() {
        StringBuilder out = new StringBuilder("{");
        out.append("\"ok\": ").append(ok);
        out.append(", \"sources\": [");
        List<Source> items = sources == null ? List.of() : sources;
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            appendSource(out, items.get(i));
        }
        out.append(']');
        if (cached) {
            out.append(", \"cached\": true, \"note\": ").append(quote(CACHED_NOTE));
        }
        if (company != null) {
            out.append(", \"company\": ").append(quote(company));
        }
        if (asOf != null) {
            out.append(", \"as_of\": ").append(quote(asOf));
        }
        if (data != null) {
            out.append(", \"data\": ").append(writeValue(data));
        }
        if (error != null) {
            out.append(", \"error\": ").append(quote(error));
        }
        return out.append('}').toString();
    }

    private static void appendSource(StringBuilder out, Source source) {
        out.append("{\"id\": ").append(quote(source.id()));
        out.append(", \"label\": ").append(quote(source.label()));
        out.append(", \"detail\": ").append(quote(source.detail()));
        out.append(", \"as_of\": ").append(quote(source.asOf()));
        out.append(", \"fetched_at\": ").append(quote(source.fetchedAt()));
        out.append(", \"url\": ").append(quote(source.url()));
        out.append('}');
    }

    private static String writeValue(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            out.append(quote(text));
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof Double number) {
            out.append(pythonFloat(number));
        } else if (value instanceof Float number) {
            out.append(pythonFloat(number.doubleValue()));
        } else if (value instanceof BigDecimal number) {
            out.append(number.toPlainString());
        } else if (value instanceof Character character) {
            out.append(quote(character.toString()));
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append(quote(pythonKey(entry.getKey()))).append(": ");
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else if (value instanceof Object[] array) {
            out.append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                writeValue(out, array[i]);
            }
            out.append(']');
        } else {
            out.append(quote(value.toString()));
        }
    }

    private static String pythonKey(Object key) {
        if (key == null) {
            return "null";
        }
        if (key instanceof Boolean flag) {
            return flag ? "true" : "false";
        }
        if (key instanceof Double number) {
            return pythonFloat(number);
        }
        return String.valueOf(key);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append("\\u%04x".formatted((int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Python's {@code repr(float)}: shortest round-trip digits, fixed notation while {@code -4 <
     * decpt <= 16}, otherwise scientific with a two-digit exponent.
     *
     * <p>shortcut: Java prints subnormal {@code Double.MIN_VALUE} as {@code 4.9E-324} where Python
     * prints {@code 5e-324}; upgrade to a shortest-digits formatter if such values ever reach a
     * golden.
     */
    private static String pythonFloat(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "Infinity";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Infinity";
        }
        boolean negative = Double.doubleToRawLongBits(value) < 0;
        String raw = Double.toString(Math.abs(value));
        int exponentIndex = raw.indexOf('E');
        String mantissa = exponentIndex >= 0 ? raw.substring(0, exponentIndex) : raw;
        int exponent = exponentIndex >= 0 ? Integer.parseInt(raw.substring(exponentIndex + 1)) : 0;
        int dot = mantissa.indexOf('.');
        String digits = dot < 0 ? mantissa : mantissa.substring(0, dot) + mantissa.substring(dot + 1);
        int decpt = (dot < 0 ? mantissa.length() : dot) + exponent;
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
            decpt--;
        }
        int end = digits.length();
        while (end > start + 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(start, end);

        String body;
        if (decpt > -4 && decpt <= 16) {
            if (decpt <= 0) {
                body = "0." + "0".repeat(-decpt) + digits;
            } else if (decpt >= digits.length()) {
                body = digits + "0".repeat(decpt - digits.length()) + ".0";
            } else {
                body = digits.substring(0, decpt) + "." + digits.substring(decpt);
            }
        } else {
            String head = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            int exp = decpt - 1;
            String expText = Integer.toString(Math.abs(exp));
            if (expText.length() < 2) {
                expText = "0" + expText;
            }
            body = head + "e" + (exp < 0 ? "-" : "+") + expText;
        }
        return (negative ? "-" : "") + body;
    }
}
