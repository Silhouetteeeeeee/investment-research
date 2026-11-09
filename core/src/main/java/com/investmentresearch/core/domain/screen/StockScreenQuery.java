package com.investmentresearch.core.domain.screen;

import java.util.List;

/** 参数化筛选请求；{@code limit} 由纯逻辑钳到 1..50。 */
public record StockScreenQuery(List<ScreenFilter> filters, List<ScreenSort> sort, int limit) {}
