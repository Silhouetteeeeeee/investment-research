package com.investmentresearch.data.adapter.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** 财报适配器的小工具，逐个对应 Python 的同名函数。 */
final class FinanceSupport {
    private FinanceSupport() {}

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

    /** {@code stock/utils.py:get_stock_prefix} 的交易所判定。 */
    static String prefix(String digits) {
        if (digits == null || digits.isEmpty()) {
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

    /** Python {@code round(value, digits)}：按二进制精确值取最近，平局取偶。 */
    static Double round(Double value, int digits) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** {@code assistant/tools.py:_yi}：元 → 亿元，保留两位。 */
    static Double yi(Double value) {
        return value == null ? null : round(value / 1e8, 2);
    }

    /** {@code assistant/tools.py:_pct}；加 0.0 把 -0.0 归一成 0.0。 */
    static Double pct(Double value) {
        return value == null ? null : round(value, 1) + 0.0;
    }

    /** 15 年窗口起点（含）；Java 的 minusYears 会把 2 月 29 日落到 2 月 28 日。 */
    static String historyCutoff(LocalDate today) {
        return today.minusYears(15).toString();
    }
}
