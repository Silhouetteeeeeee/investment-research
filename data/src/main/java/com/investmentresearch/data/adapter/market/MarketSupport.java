package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** 盘面取数共用的小工具，对应 Python 里的 {@code _as_float} / {@code _round} / {@code _percent}。 */
final class MarketSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private MarketSupport() {}

    /** Python {@code float(value)}：None、""、带千分位或百分号的文本都尽量认成数字。 */
    static Double number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            return Double.isNaN(decimal) ? null : decimal;
        }
        String raw = value.toString().strip().replace(",", "").replace("%", "");
        if (raw.isEmpty() || raw.equals("-") || raw.equals("--")) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** Python {@code round(value, digits)}：按二进制精确值取最近，平局取偶。 */
    static Double round(Double value, int digits) {
        return value == null
                ? null
                : new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** 按顺序拼一个行 Map；键值必须成对。 */
    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(pairs[i].toString(), pairs[i + 1]);
        }
        return row;
    }

    /** 上游取数或解析失败；由 {@link MarketAdapter} 折成 {@code errors} 里的一段。 */
    static final class MarketException extends RuntimeException {
        MarketException(String message) {
            super(message);
        }
    }
}
