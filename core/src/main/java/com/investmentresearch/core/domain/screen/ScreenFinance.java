package com.investmentresearch.core.domain.screen;

/** 单一报告期的财务数据；金额单位为元，比率为百分数，缺失为 {@code null}。 */
public record ScreenFinance(
        String code,
        String reportDate,
        Double eps,
        Double revenue,
        Double netProfit,
        Double roe,
        Double grossMargin,
        Double revenueYoy,
        Double profitYoy,
        Double ocfPerShare,
        String updateDate) {}
