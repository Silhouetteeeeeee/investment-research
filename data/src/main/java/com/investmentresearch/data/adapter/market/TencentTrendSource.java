package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯日线，替代 Python 的 {@code ak.stock_zh_index_daily()}——后者返回的响应是 JS 加密的，
 * 需要跑一遍解密脚本；腾讯同一个接口直接给 JSON。
 */
final class TencentTrendSource {
    static final List<String> CODES = List.of("sh000001", "sz399001", "sz399006", "sh000300");
    static final int BARS = 80;
    private static final String URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=";

    private final UpstreamClient client;

    TencentTrendSource(UpstreamClient client) {
        this.client = client;
    }

    /** 单只失败只记进 {@code failures}，其余宽基照常返回——与 Python 的逐只 try/except 一致。 */
    Trend fetch() {
        Map<String, List<Map<String, Object>>> daily = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (String code : CODES) {
            try {
                daily.put(code, bars(code));
            } catch (RuntimeException error) {
                failures.add(code + "：" + (error.getMessage() == null ? "" : error.getMessage()));
            }
        }
        return new Trend(daily, failures);
    }

    /** 单只指数的日线，行业轮动用它算基准收盘价。 */
    List<Map<String, Object>> barsOf(String code) {
        return bars(code);
    }

    /** 每根 K 线是 {@code [日期, 开, 收, 高, 低, 成交量(手)]}。 */
    private List<Map<String, Object>> bars(String code) {
        String body = client.get(URL + code + ",day,,," + BARS + ",qfq", Map.of());
        JsonNode node;
        try {
            node = MarketSupport.MAPPER.readTree(body).path("data").path(code);
        } catch (java.io.IOException error) {
            throw new MarketSupport.MarketException("日线响应不是 JSON");
        }
        JsonNode bars = node.path("day");
        if (!bars.isArray()) {
            bars = node.path("qfqday");
        }
        if (!bars.isArray()) {
            throw new MarketSupport.MarketException("日线接口返回格式异常");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode bar : bars) {
            Double close = MarketSupport.number(bar.path(2).asText(null));
            if (close == null) {
                continue;
            }
            Double volume = MarketSupport.number(bar.path(5).asText(null));
            rows.add(
                    MarketSupport.row(
                            "date", bar.path(0).asText(""),
                            "open", MarketSupport.number(bar.path(1).asText(null)),
                            "close", close,
                            "high", MarketSupport.number(bar.path(3).asText(null)),
                            "low", MarketSupport.number(bar.path(4).asText(null)),
                            // 腾讯的成交量是手，与新浪快照的股统一，量能才可比
                            "volume", volume == null ? null : volume * 100));
        }
        if (rows.isEmpty()) {
            throw new MarketSupport.MarketException("日线接口返回空数据");
        }
        return rows.size() <= BARS ? rows : new ArrayList<>(rows.subList(rows.size() - BARS, rows.size()));
    }

    record Trend(Map<String, List<Map<String, Object>>> daily, List<String> failures) {}
}
