package com.investmentresearch.core.domain.screen;

/** 单条筛选条件；数值字段用 {@code value} 比较，{@code boards} 的值为板块名或其列表。 */
public record ScreenFilter(String field, String op, Object value) {}
