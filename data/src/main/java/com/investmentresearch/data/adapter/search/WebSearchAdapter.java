package com.investmentresearch.data.adapter.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.SearchPort;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索：百度千帆优先（每日 100 次免费），任何失败回落到 DeepSeek Search。
 *
 * <p>两家返回结构不同，这里归一成同一份 data/sources，模型不需要知道走了哪家。
 */
public final class WebSearchAdapter implements SearchPort {
    static final String BAIDU_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";
    static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/tools/search";
    // 单条正文截断，避免一次搜索占掉太多上下文；需要全文时再单独抓取。
    static final int MAX_CONTENT = 1000;
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UpstreamClient client;
    private final String baiduUrl;
    private final String deepseekUrl;
    private final String baiduKey;
    private final String deepseekKey;
    private final Clock clock;

    public WebSearchAdapter(
            UpstreamClient client, String baiduKey, String deepseekKey, Clock clock) {
        this(client, BAIDU_URL, DEEPSEEK_URL, baiduKey, deepseekKey, clock);
    }

    /** 测试用：允许把两个上游指到本地 WireMock。 */
    WebSearchAdapter(
            UpstreamClient client,
            String baiduUrl,
            String deepseekUrl,
            String baiduKey,
            String deepseekKey,
            Clock clock) {
        this.client = client;
        this.baiduUrl = baiduUrl;
        this.deepseekUrl = deepseekUrl;
        this.baiduKey = baiduKey;
        this.deepseekKey = deepseekKey;
        this.clock = clock;
    }

    @Override
    public ToolResult search(String query, int count) {
        List<String> failures = new ArrayList<>();
        if (hasText(baiduKey)) {
            try {
                return ok(query, "baidu", baiduHits(post(baiduUrl, baiduKey, baiduBody(query, count))));
            } catch (RuntimeException error) {
                failures.add("百度：" + message(error));
            }
        } else {
            failures.add("百度：未配置 BAIDU_API_KEY");
        }
        if (hasText(deepseekKey)) {
            try {
                return ok(
                        query,
                        "deepseek",
                        deepseekHits(post(deepseekUrl, deepseekKey, deepseekBody(query, count))));
            } catch (RuntimeException error) {
                failures.add("DeepSeek：" + message(error));
            }
        } else {
            failures.add("DeepSeek：未配置 DEEPSEEK_API_KEY");
        }
        return ToolResult.failure(null, "联网搜索失败（" + String.join("；", failures) + "）");
    }

    private JsonNode post(String url, String key, Map<String, Object> body) {
        String response =
                client.post(
                        url,
                        Map.of(
                                "Authorization", "Bearer " + key,
                                "Content-Type", "application/json"),
                        write(body));
        try {
            return JSON.readTree(response);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("响应不是合法 JSON", error);
        }
    }

    private static Map<String, Object> baiduBody(String query, int count) {
        return Map.of(
                "messages", List.of(Map.of("role", "user", "content", query)),
                "resource_type_filter", List.of(Map.of("type", "web", "top_k", count)));
    }

    private static Map<String, Object> deepseekBody(String query, int count) {
        // need_content=false：用 600–800 字的 summary 就够，正文另抓更贵更慢
        return Map.of("query", query, "count", count, "need_content", false);
    }

    private static List<Hit> baiduHits(JsonNode root) {
        JsonNode references = root.path("references");
        if (!references.isArray()) {
            throw new IllegalStateException("百度响应缺少 references");
        }
        List<Hit> hits = new ArrayList<>();
        for (JsonNode node : references) {
            String snippet = text(node, "snippet");
            String content = text(node, "content");
            hits.add(
                    new Hit(
                            text(node, "title"),
                            text(node, "url"),
                            snippet,
                            content.isEmpty() ? snippet : content,
                            text(node, "date"),
                            text(node, "website")));
        }
        return hits;
    }

    private static List<Hit> deepseekHits(JsonNode root) {
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            throw new IllegalStateException("DeepSeek 响应缺少 results");
        }
        List<Hit> hits = new ArrayList<>();
        for (JsonNode node : results) {
            String snippet = text(node, "snippet");
            String summary = text(node, "summary");
            hits.add(
                    new Hit(
                            text(node, "title"),
                            text(node, "url"),
                            snippet,
                            summary.isEmpty() ? snippet : summary,
                            text(node, "date"),
                            text(node, "site_name")));
        }
        return hits;
    }

    private ToolResult ok(String query, String provider, List<Hit> raw) {
        Map<String, Hit> unique = new LinkedHashMap<>();
        for (Hit hit : raw) {
            if (!hit.url().isEmpty()) {
                unique.putIfAbsent(hit.url(), hit);
            }
        }
        String asOf = LocalDate.now(clock).toString();
        String fetchedAt = LocalDateTime.now(clock).format(TIMESTAMP);
        // 引用 id 要跨多次搜索唯一：同一个 query 的结果编号稳定，不同 query 用短 hash 区分
        String prefix = "web:" + Integer.toHexString(query.hashCode()) + ":";
        List<Source> sources = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 1;
        for (Hit hit : unique.values()) {
            sources.add(
                    new Source(
                            prefix + index,
                            hit.site().isEmpty() ? "网页" : hit.site(),
                            hit.title(),
                            hit.date().isEmpty() ? asOf : hit.date(),
                            fetchedAt,
                            hit.url()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", hit.title());
            row.put("url", hit.url());
            row.put("snippet", hit.snippet());
            row.put("content", clip(hit.content()));
            row.put("date", hit.date());
            row.put("site", hit.site());
            rows.add(row);
            index++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("provider", provider);
        data.put("count", rows.size());
        data.put("results", rows);
        return ToolResult.ok(null, asOf, sources, data);
    }

    private static String clip(String value) {
        return value.length() <= MAX_CONTENT ? value : value.substring(0, MAX_CONTENT) + "…";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("请求体无法序列化", error);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Hit(
            String title, String url, String snippet, String content, String date, String site) {}
}
