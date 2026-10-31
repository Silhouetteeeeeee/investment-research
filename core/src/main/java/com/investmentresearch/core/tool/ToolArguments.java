package com.investmentresearch.core.tool;

/**
 * 工具入参的宽松解析：Python 那边全是 {@code int(arguments.get(...) or default)}，
 * 解析失败就取默认值，这里照做。
 */
final class ToolArguments {
    private ToolArguments() {}

    static int intArgument(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return (int) number.doubleValue();
        }
        String text = value.toString().trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException error) {
            try {
                return (int) Double.parseDouble(text);
            } catch (NumberFormatException again) {
                return fallback;
            }
        }
    }

    static double doubleArgument(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
