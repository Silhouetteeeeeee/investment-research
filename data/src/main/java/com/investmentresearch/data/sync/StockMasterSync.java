package com.investmentresearch.data.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.data.persistence.StockEntity;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全 A 主表刷新：东财 clist 一页 100 条，按 pn 翻页直到空页或不足一页。
 * 先抓完再写库，上游中途失败时表保持原样（空表也保持空）。
 */
@Component
public class StockMasterSync {
    static final int PAGE_SIZE = 100;
    static final String FIELDS = "f12,f14";
    static final String FS = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StockMapper stockMapper;
    private final UpstreamClient upstreamClient;
    private final String endpoint;

    public StockMasterSync(
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            @Value("${app.stock-list-url:https://82.push2.eastmoney.com/api/qt/clist/get}")
                    String endpoint) {
        this.stockMapper = stockMapper;
        this.upstreamClient = upstreamClient;
        this.endpoint = endpoint;
    }

    @Scheduled(cron = "0 0 6 * * *")
    @Transactional
    public void refresh() {
        List<StockRef> stocks = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode diff = fetchPage(page);
            if (diff == null || !diff.isArray() || diff.isEmpty()) {
                break;
            }
            for (JsonNode item : diff) {
                String code = item.path("f12").asText("").trim();
                String name = item.path("f14").asText("").trim();
                if (!code.isEmpty() && !name.isEmpty()) {
                    stocks.add(new StockRef(code, name));
                }
            }
            if (diff.size() < PAGE_SIZE) {
                break;
            }
        }

        String now = LocalDateTime.now().format(TIMESTAMP);
        for (StockRef stock : stocks) {
            StockEntity row = new StockEntity();
            row.setCode(stock.code());
            row.setName(stock.name());
            row.setRefreshedAt(now);
            stockMapper.insertOrUpdate(row);
        }
    }

    private JsonNode fetchPage(int page) {
        String url =
                endpoint
                        + "?pn="
                        + page
                        + "&pz="
                        + PAGE_SIZE
                        + "&po=1&np=1&fltt=2&invt=2&fid=f3"
                        + "&fs="
                        + encode(FS)
                        + "&fields="
                        + encode(FIELDS);
        try {
            return MAPPER.readTree(upstreamClient.get(url, Map.of())).path("data").path("diff");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stock list JSON from " + endpoint, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
