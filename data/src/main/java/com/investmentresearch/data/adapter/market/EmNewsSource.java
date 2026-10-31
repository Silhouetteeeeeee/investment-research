package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 东方财富 7×24 全球财经快讯（{@code ak.stock_info_global_em}）。 */
final class EmNewsSource {
    private static final String URL =
            "https://np-weblist.eastmoney.com/comm/web/getFastNewsList"
                    + "?client=web&biz=web_724&fastColumn=102&sortEnd=&pageSize=200&req_trace=1710315450384";

    private final UpstreamClient client;

    EmNewsSource(UpstreamClient client) {
        this.client = client;
    }

    List<Map<String, Object>> rows() {
        String body = client.get(URL, Map.of("User-Agent", MarketSupport.USER_AGENT));
        JsonNode list;
        try {
            list = MarketSupport.MAPPER.readTree(body).path("data").path("fastNewsList");
        } catch (java.io.IOException error) {
            throw new MarketSupport.MarketException("东财快讯不是 JSON");
        }
        if (!list.isArray()) {
            throw new MarketSupport.MarketException("东财快讯返回格式异常");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : list) {
            rows.add(
                    MarketSupport.row(
                            "标题", item.path("title").asText(""),
                            "摘要", item.path("summary").asText(""),
                            "发布时间", item.path("showTime").asText(""),
                            "链接", "https://finance.eastmoney.com/a/" + item.path("code").asText("") + ".html"));
        }
        return rows;
    }
}
