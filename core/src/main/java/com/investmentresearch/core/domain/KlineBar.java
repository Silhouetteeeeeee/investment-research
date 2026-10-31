package com.investmentresearch.core.domain;

/** 一根日线；日 K 面板用前复权价，成交量单位是股。 */
public record KlineBar(
        String date,
        Double open,
        Double close,
        Double high,
        Double low,
        Double volume,
        Double amount) {}
