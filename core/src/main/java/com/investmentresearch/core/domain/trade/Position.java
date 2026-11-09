package com.investmentresearch.core.domain.trade;

public record Position(String code, String name, int shares, long avgCostCents) {}
