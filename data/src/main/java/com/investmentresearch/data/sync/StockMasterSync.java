package com.investmentresearch.data.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.StockRef;
import com.investmentresearch.data.persistence.StockEntity;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import com.investmentresearch.data.upstream.UpstreamException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 全 A 主表刷新：东财 clist 一页 100 条，页数由首页 total 推出；空页/不足一页也作为兜底终点。
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
    private final TransactionTemplate transactionTemplate;

    public StockMasterSync(
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            @Value("${app.stock-list-url:https://push2test.eastmoney.com/api/qt/clist/get}")
                    String endpoint,
            PlatformTransactionManager transactionManager) {
        this.stockMapper = stockMapper;
        this.upstreamClient = upstreamClient;
        this.endpoint = endpoint;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void refresh() {
        List<StockRef> stocks = new ArrayList<>();
        int pages = Integer.MAX_VALUE;
        for (int page = 1; page <= pages; page++) {
            JsonNode data = fetchPage(page);
            JsonNode diff = data.path("diff");
            if (page == 1 && data.path("total").isNumber()) {
                pages = (int) ((data.path("total").asLong() + PAGE_SIZE - 1) / PAGE_SIZE);
            }
            if (!diff.isArray() || diff.isEmpty()) {
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
        // 事务只包写库，避免翻页耗时占用 Hikari 连接
        transactionTemplate.executeWithoutResult(
                status -> {
                    for (StockRef stock : stocks) {
                        StockEntity row = new StockEntity();
                        row.setCode(stock.code());
                        row.setName(stock.name());
                        row.setRefreshedAt(now);
                        stockMapper.insertOrUpdate(row);
                    }
                });
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
            JsonNode root = MAPPER.readTree(upstreamClient.get(url, Map.of()));
            // 空 body / {"rc":102} / 截断 JSON 都不是“空页”，必须按上游失败处理：
            // 否则 cooldown 不会生效，且翻页中途的半张表会被提交
            JsonNode data = root == null ? null : root.get("data");
            if (root == null
                    || !root.isObject()
                    || data == null
                    || !data.isObject()
                    || !data.path("diff").isArray()) {
                throw new UpstreamException(0, url);
            }
            return data;
        } catch (JsonProcessingException e) {
            // WAF/HTML/截断响应按上游失败处理，让调用方按“空表”降级而不是抛 500
            throw new UpstreamException(0, url, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
