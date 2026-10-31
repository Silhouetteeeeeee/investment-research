package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 个股日线（腾讯 {@code fqkline}，前复权）：一根是 {@code [日期, 开, 收, 高, 低, 成交量(手)]}。
 * 东财 push2his 容易限流，日 K 面板优先走这里。
 */
final class TencentStockDailySource {
    // 实测 800 根稳定；1000 起会被压回 640，5000 以上直接 param error
    private static final int MAX_BARS = 800;
    private static final String URL =
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=%s,day,,,%d,qfq";

    private final UpstreamClient client;

    TencentStockDailySource(UpstreamClient client) {
        this.client = client;
    }

    /** {@code code} 是 6 位代码；返回按日期升序的前复权日线。 */
    List<Map<String, Object>> rows(String code, int days) {
        String tencentCode = prefixed(code);
        int count = Math.min(Math.max(1, days), MAX_BARS);
        String body =
                client.get(
                        String.format(URL, tencentCode, count),
                        Map.of("User-Agent", MarketSupport.USER_AGENT, "Referer", "https://gu.qq.com/"));
        JsonNode root;
        try {
            root = MarketSupport.MAPPER.readTree(body);
        } catch (IOException error) {
            throw new MarketSupport.MarketException("腾讯日线响应不是 JSON");
        }
        if (root.path("code").asInt(-1) != 0) {
            throw new MarketSupport.MarketException(
                    "腾讯日线返回错误：" + root.path("msg").asText("未知原因"));
        }
        JsonNode node = root.path("data").path(tencentCode);
        JsonNode bars = node.path("qfqday");
        if (!bars.isArray()) {
            bars = node.path("day");
        }
        if (!bars.isArray()) {
            throw new MarketSupport.MarketException("腾讯日线接口返回格式异常");
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
                            // 腾讯的成交量是手，与东财源统一成股
                            "volume", volume == null ? null : volume * 100));
        }
        if (rows.isEmpty()) {
            throw new MarketSupport.MarketException("腾讯日线接口返回空数据");
        }
        return rows.size() <= count ? rows : new ArrayList<>(rows.subList(rows.size() - count, rows.size()));
    }

    /** 腾讯用 sh/sz 前缀；北交所归到 sz 取不到时会由东财兜底。 */
    private static String prefixed(String code) {
        return (code.startsWith("6") || code.startsWith("9") ? "sh" : "sz") + code;
    }
}
