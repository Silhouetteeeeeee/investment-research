package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同花顺行业板块指数日线（{@code ak.stock_board_industry_index_ths}）。
 *
 * <p>akshare 按年取 {@code v4/line}，还需要 {@code v} cookie；{@code v6/line/.../last.js}
 * 一次给最近 140 个交易日，且不需要 cookie，正好对上 Python 的 {@code HISTORY_DAYS}。
 */
final class ThsBoardHistorySource {
    private static final String URL = "https://d.10jqka.com.cn/v6/line/bk_%s/01/last.js";

    private final UpstreamClient client;

    ThsBoardHistorySource(UpstreamClient client) {
        this.client = client;
    }

    /** 每根是 {@code 日期,开,高,低,收,成交量,成交额,,,,0}。 */
    List<Map<String, Object>> rows(String code) {
        String body = client.get(String.format(URL, code), Map.of("User-Agent", MarketSupport.USER_AGENT));
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        JsonNode node;
        try {
            node = MarketSupport.MAPPER.readTree(start < 0 || end <= start ? "{}" : body.substring(start, end + 1));
        } catch (java.io.IOException error) {
            throw new MarketSupport.MarketException("板块指数日线不是 JSON");
        }
        String data = node.path("data").asText("");
        if (data.isEmpty()) {
            throw new MarketSupport.MarketException("板块指数日线为空");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : data.split(";")) {
            String[] cells = line.split(",");
            if (cells.length < 7) {
                continue;
            }
            Double close = MarketSupport.number(cells[4]);
            if (close == null) {
                continue;
            }
            rows.add(
                    MarketSupport.row(
                            "日期", date(cells[0]),
                            "收盘价", close,
                            "成交额", MarketSupport.number(cells[6])));
        }
        if (rows.isEmpty()) {
            throw new MarketSupport.MarketException("板块指数日线没有可用收盘价");
        }
        return rows;
    }

    /** {@code 20261030} → {@code 2026-10-30}，与 Python 的日期字符串可比。 */
    private static String date(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 8) {
            return value;
        }
        return digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8);
    }
}
