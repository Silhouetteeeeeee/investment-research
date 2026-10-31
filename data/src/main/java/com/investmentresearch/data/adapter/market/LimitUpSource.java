package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 东方财富涨停股池（{@code ak.stock_zt_pool_em}）：只取连板高度与行业分布要用的字段。 */
final class LimitUpSource {
    private static final String URL =
            "https://push2ex.eastmoney.com/getTopicZTPool"
                    + "?ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt&Pageindex=0&pagesize=10000"
                    + "&sort=fbt:asc&date=";

    private final UpstreamClient client;

    LimitUpSource(UpstreamClient client) {
        this.client = client;
    }

    /** 停牌或非交易日时接口的 {@code data} 是 null，Python 同样返回空表。 */
    List<Map<String, Object>> rows(String date) {
        JsonNode root;
        try {
            root = MarketSupport.MAPPER.readTree(client.get(URL + date, Map.of()));
        } catch (IOException error) {
            throw new MarketSupport.MarketException("涨停池响应不是 JSON");
        }
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : data.path("pool")) {
            rows.add(
                    MarketSupport.row(
                            "代码", item.path("c").asText(""),
                            "名称", item.path("n").asText(""),
                            "涨跌幅", MarketSupport.number(item.path("zdp").asText(null)),
                            "连板数", MarketSupport.number(item.path("lbc").asText(null)),
                            "所属行业", item.path("hybk").asText("")));
        }
        return rows;
    }
}
