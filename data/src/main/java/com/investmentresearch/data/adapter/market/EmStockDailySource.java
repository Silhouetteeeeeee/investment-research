package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 个股日线（{@code ak.stock_zh_a_daily}）：东方财富的 {@code push2his} K 线，
 * 一根是 {@code 日期,开,收,高,低,成交量(手),成交额(元),换手率(%)}。
 */
final class EmStockDailySource {
    private static final String URL =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=%s"
                    + "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57,f61"
                    + "&klt=101&fqt=%d&lmt=%d&end=20500101";

    private final UpstreamClient client;

    EmStockDailySource(UpstreamClient client) {
        this.client = client;
    }

    /** 不复权，筛选与复盘的口径。 */
    List<Map<String, Object>> rows(String code, int days) {
        return rows(code, days, 0);
    }

    /**
     * {@code code} 是新浪式的 {@code sh600000}；返回按日期升序的日线。
     * {@code fqt}：0 不复权、1 前复权、2 后复权。
     */
    List<Map<String, Object>> rows(String code, int days, int fqt) {
        String body =
                client.get(
                        String.format(URL, secid(code), fqt, Math.max(1, days)),
                        Map.of("User-Agent", MarketSupport.USER_AGENT, "Referer", "https://quote.eastmoney.com/"));
        JsonNode klines;
        try {
            klines = MarketSupport.MAPPER.readTree(body).path("data").path("klines");
        } catch (IOException error) {
            throw new MarketSupport.MarketException("日线响应不是 JSON");
        }
        if (!klines.isArray()) {
            throw new MarketSupport.MarketException("日线接口返回格式异常");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode node : klines) {
            String[] cells = node.asText("").split(",");
            if (cells.length < 8) {
                continue;
            }
            Double close = MarketSupport.number(cells[2]);
            if (close == null) {
                continue;
            }
            Double turnover = MarketSupport.number(cells[7]);
            rows.add(
                    MarketSupport.row(
                            "date", cells[0],
                            "open", MarketSupport.number(cells[1]),
                            "close", close,
                            "high", MarketSupport.number(cells[3]),
                            "low", MarketSupport.number(cells[4]),
                            // 东财的成交量是手，与快照的股统一
                            "volume", scale(MarketSupport.number(cells[5]), 100),
                            "amount", MarketSupport.number(cells[6]),
                            // 换手率按小数保存，与 akshare 的 turnover 列一致
                            "turnover", turnover == null ? null : turnover / 100));
        }
        if (rows.isEmpty()) {
            throw new MarketSupport.MarketException("日线接口返回空数据");
        }
        return rows;
    }

    /** 沪市用 1，深市与北交所都用 0。 */
    private static String secid(String code) {
        String value = code == null ? "" : code.strip().toLowerCase();
        if (value.startsWith("sh")) {
            return "1." + value.substring(2);
        }
        return "0." + (value.length() > 2 ? value.substring(2) : value);
    }

    private static Double scale(Double value, double factor) {
        return value == null ? null : value * factor;
    }
}
