package com.investmentresearch.core.citation;

import com.investmentresearch.core.domain.Source;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of Python's {@code assistant.citations.validate_citations}. */
public final class CitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[\\[([^\\[\\]]+)\\]\\]");
    // Python's \d is Unicode decimal digits (category Nd), not just ASCII.
    private static final Pattern DIGIT = Pattern.compile("\\p{Nd}");

    private static final String UNKNOWN_CITATION = "unknown_citation";
    private static final String UNCITED_NUMBERS = "回答里有数字，但没有任何有效引用";

    public CitationCheck validate(String text, Map<String, Source> registry) {
        List<Source> used = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> issues = new ArrayList<>();

        Matcher matcher = CITATION.matcher(text == null ? "" : text);
        StringBuilder clean = new StringBuilder();
        while (matcher.find()) {
            String sourceId = strip(matcher.group(1));
            Source source = registry.get(sourceId);
            if (source == null) {
                issues.add(UNKNOWN_CITATION);
                matcher.appendReplacement(clean, Matcher.quoteReplacement("[?]"));
            } else {
                if (seen.add(sourceId)) {
                    used.add(source);
                }
                // Keep the matched text verbatim, including any inner whitespace.
                matcher.appendReplacement(clean, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(clean);

        if (used.isEmpty() && DIGIT.matcher(clean).find()) {
            issues.add(UNCITED_NUMBERS);
        }
        return new CitationCheck(clean.toString(), List.copyOf(used), List.copyOf(issues));
    }

    /** Mirrors Python str.strip(): NBSP and NEL are whitespace there, but not for String.strip(). */
    private static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isPythonWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isPythonWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isPythonWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }
}
