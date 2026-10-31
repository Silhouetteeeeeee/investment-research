package com.investmentresearch.data.adapter.market;

import com.investmentresearch.data.upstream.UpstreamClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/** 乐咕乐股「赚钱效应分析」：涨跌家数、涨跌停与统计日期（{@code ak.stock_market_activity_legu}）。 */
final class LeguBreadthSource {
    static final String URL = "https://legulegu.com/stockdata/market-activity";

    private final UpstreamClient client;

    LeguBreadthSource(UpstreamClient client) {
        this.client = client;
    }

    Breadth fetch() {
        Document doc = Jsoup.parse(client.get(URL, Map.of("User-Agent", MarketSupport.USER_AGENT)));
        Element table =
                doc.select("table").stream()
                        .filter(item -> item.text().contains("上涨"))
                        .findFirst()
                        .orElse(null);
        if (table == null) {
            throw new MarketSupport.MarketException("乐咕页面结构变化，找不到涨跌家数表");
        }
        Map<String, Object> byItem = new LinkedHashMap<>();
        for (Element row : table.select("tr")) {
            Elements cells = row.select("td");
            for (int i = 0; i + 1 < cells.size(); i += 2) {
                byItem.put(cells.get(i).text().strip(), cells.get(i + 1).text().strip());
            }
        }
        String activity = doc.select(".metric-activity .metric-value").text().strip();
        if (!activity.isEmpty()) {
            byItem.put("活跃度", activity);
        }
        String asOf = doc.select(".market-activity-time").text().strip();
        if (asOf.isEmpty()) {
            asOf = doc.select(".market-activity-meta").text().strip();
        }
        if (byItem.isEmpty()) {
            throw new MarketSupport.MarketException("乐咕返回的表格为空");
        }
        byItem.put("统计日期", asOf);
        return new Breadth(asOf, byItem);
    }

    /** {@code asOf} 是页面上标的统计时刻，Python 用它当整个盘面的 {@code as_of}。 */
    record Breadth(String asOf, Map<String, Object> byItem) {}
}
