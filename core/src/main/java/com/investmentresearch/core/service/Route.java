package com.investmentresearch.core.service;

/** Where a question should be answered: which company, and whether to start a new conversation. */
public record Route(String code, String name, boolean reset) {}
