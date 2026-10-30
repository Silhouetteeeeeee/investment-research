package com.investmentresearch.core.service;

import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.core.port.CompanyDirectory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Port of {@code assistant/tools.py:resolve_code}: code forms, exact name, then contains candidates. */
public final class CompanyResolver {
    private static final int MAX_CANDIDATES = 5;

    private final CompanyDirectory directory;

    public CompanyResolver(CompanyDirectory directory) {
        this.directory = directory;
    }

    public ResolvedCompany resolve(String query, String fallback) {
        String text = query == null ? "" : query.trim();
        String defaultText = fallback == null ? "" : fallback.trim();
        if (text.isEmpty() && defaultText.isEmpty()) {
            return error("未指定公司：请先给出股票代码或简称", List.of());
        }

        String value = text.isEmpty() ? defaultText : text;
        String upper = value.toUpperCase(Locale.ROOT);
        String[] parsed = stockPrefix(upper);
        String prefix = parsed[0];
        String digits = parsed[1];
        // 只有整体就是代码的写法才按代码处理，避免简称里恰好有六位数字
        if (digits.length() == 6
                && (upper.equals(digits)
                        || upper.equals(prefix + digits)
                        || upper.equals(digits + "." + prefix))) {
            if (!prefix.equals("SH") && !prefix.equals("SZ") && !prefix.equals("BJ")) {
                return error("无法识别股票代码「" + value + "」", List.of());
            }
            return new ResolvedCompany(digits, null, List.of());
        }

        Optional<StockRef> exact = directory.byName(value);
        if (exact.isPresent()) {
            return new ResolvedCompany(exact.get().code(), null, List.of());
        }

        List<StockRef> candidates = new ArrayList<>();
        for (StockRef stock : directory.all()) {
            if (stock.name() != null && stock.name().contains(value)) {
                candidates.add(stock);
                if (candidates.size() == MAX_CANDIDATES) {
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            return error("没有找到「" + value + "」", List.of());
        }
        StringBuilder names = new StringBuilder();
        for (StockRef stock : candidates) {
            if (names.length() > 0) {
                names.append('、');
            }
            names.append(stock.name()).append('(').append(stock.code()).append(')');
        }
        return error("没有找到「" + value + "」，你是不是指：" + names, candidates);
    }

    private static ResolvedCompany error(String message, List<StockRef> candidates) {
        return new ResolvedCompany(null, message, candidates);
    }

    /** {@code stock/utils.py:get_stock_prefix}; prefix forms return early, plain digits infer it. */
    private static String[] stockPrefix(String code) {
        String upper = code.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("SH") || upper.startsWith("SZ") || upper.startsWith("BJ")) {
            return new String[] {upper.substring(0, 2), digits(upper.substring(2))};
        }
        if (upper.contains(".")) {
            String[] parts = upper.split("\\.");
            // Java 的 split 会把全点输入拆成空数组；Python 此时落到按简称查找
            if (parts.length > 0) {
                String suffix = parts[parts.length - 1];
                if (suffix.equals("SH") || suffix.equals("SZ") || suffix.equals("BJ")) {
                    return new String[] {suffix, digits(parts[0])};
                }
            }
        }
        String digits = digits(upper);
        if (digits.isEmpty()) {
            return new String[] {"UNKNOWN", ""};
        }
        if (startsWithAny(digits, "600", "601", "603", "605", "688")) {
            return new String[] {"SH", digits};
        }
        if (startsWithAny(digits, "000", "001", "002", "003", "300", "301")) {
            return new String[] {"SZ", digits};
        }
        if (digits.startsWith("920") || digits.startsWith("4") || digits.startsWith("8")) {
            return new String[] {"BJ", digits};
        }
        return new String[] {"UNKNOWN", digits};
    }

    private static String digits(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                out.append(value.charAt(i));
            }
        }
        return out.toString();
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
