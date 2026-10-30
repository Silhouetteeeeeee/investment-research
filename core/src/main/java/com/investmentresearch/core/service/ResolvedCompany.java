package com.investmentresearch.core.service;

import com.investmentresearch.core.domain.StockRef;
import java.util.List;

/** Result of resolving user text to a six-digit code: either a code or an error plus candidates. */
public record ResolvedCompany(String code, String error, List<StockRef> candidates) {}
