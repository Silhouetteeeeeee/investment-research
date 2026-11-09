package com.investmentresearch.core.domain.screen;

/** 排序键；{@code order} 缺省为 {@code desc}。 */
public record ScreenSort(String field, String order) {}
