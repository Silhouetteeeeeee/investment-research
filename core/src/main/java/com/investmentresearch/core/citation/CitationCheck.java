package com.investmentresearch.core.citation;

import com.investmentresearch.core.domain.Source;
import java.util.List;

/** Result of cleaning citation markers: rewritten text, cited sources in first-seen order, issues. */
public record CitationCheck(String text, List<Source> citations, List<String> issues) {}
