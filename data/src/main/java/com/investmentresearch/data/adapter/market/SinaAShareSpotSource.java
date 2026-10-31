package com.investmentresearch.data.adapter.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全市场 A 股快照（{@code ak.stock_zh_a_spot}）：新浪的 {@code Market_Center.getHQNodeData} 分页，
 * 一页 100 只、按成交额倒序，没有 cookie，也不用跑解密脚本。
 */
final class SinaAShareSpotSource {
    private static final String URL =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php"
                    + "/Market_Center.getHQNodeData?page=%d&num=100&sort=amount&asc=0&node=hs_a&symbol=&_s_r_a=page";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 80;

    private final UpstreamClient client;

    SinaAShareSpotSource(UpstreamClient client) {
        this.client = client;
    }

    List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<Map<String, Object>> items = parse(String.format(URL, page));
            rows.addAll(items);
            // 最后一页不足一页，说明已经到底
            if (items.size() < PAGE_SIZE) {
                break;
            }
        }
        if (rows.isEmpty()) {
            throw new MarketSupport.MarketException("全市场快照为空");
        }
        return rows;
    }

    private List<Map<String, Object>> parse(String url) {
        JsonNode root;
        try {
            root = MarketSupport.MAPPER.readTree(client.get(url, Map.of("User-Agent", MarketSupport.USER_AGENT)));
        } catch (IOException error) {
            throw new MarketSupport.MarketException("全市场快照不是 JSON");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : root) {
            String symbol = item.path("symbol").asText("").strip().toLowerCase();
            if (symbol.isEmpty()) {
                continue;
            }
            rows.add(
                    MarketSupport.row(
                            "代码", symbol,
                            "名称", item.path("name").asText("").strip(),
                            "最新价", MarketSupport.number(item.path("trade").asText(null)),
                            "涨跌幅", MarketSupport.number(item.path("changepercent").asText(null)),
                            "成交额", MarketSupport.number(item.path("amount").asText(null)),
                            "流通市值", MarketSupport.number(item.path("nmc").asText(null)),
                            "换手率", MarketSupport.number(item.path("turnoverratio").asText(null))));
        }
        return rows;
    }
}
