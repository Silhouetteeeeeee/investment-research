package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** datacenter 适配器共用的小工具，逐个对应 Python 的 {@code stock.peers} / {@code stock.valuation}。 */
final class EastmoneySupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final double MAX_AGE_HOURS = 6;

    private EastmoneySupport() {}

    /** {@code stock/db_service.py:normalize_stock_code}。 */
    static String normalize(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (Character.isDigit(code.charAt(i))) {
                digits.append(code.charAt(i));
            }
        }
        while (digits.length() > 0 && digits.length() < 6) {
            digits.insert(0, '0');
        }
        return digits.toString();
    }

    /** Python {@code _text}：去空白，空串与 "-" 视为缺失。 */
    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText().strip();
        return text.isEmpty() || text.equals("-") ? null : text;
    }

    /** Python {@code _num}：null、"-"、"" 与非数字都返回 null。 */
    static Double num(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String text = node.asText().strip();
        if (text.isEmpty() || text.equals("-")) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /** 接口返回 {@code '2026-10-23 00:00:00'}，只保留日期部分。 */
    static String datePart(JsonNode node) {
        String text = text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }

    static String timestamp(Clock clock) {
        return LocalDateTime.now(clock).format(TIMESTAMP);
    }

    /** Python {@code round(value, digits)}：按二进制精确值取最近，平局取偶。 */
    static Double round(Double value, int digits) {
        return value == null
                ? null
                : new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** {@code assistant/tools.py:_yi}。 */
    static Double yi(Double value) {
        return value == null ? null : round(value / 1e8, 2);
    }

    /** {@code assistant/tools.py:_pct}；加 0.0 把 -0.0 归一成 0.0。 */
    static Double pct(Double value) {
        return value == null ? null : round(value, 1) + 0.0;
    }

    /** Python {@code statistics.median}：偶数个取中间两个的平均。 */
    static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    static boolean positive(Double value) {
        return value != null && value > 0;
    }

    /** Python {@code _age_hours}：取最晚的抓取时间，6 小时内算新鲜；没有可用时间戳时视为无限旧。 */
    static boolean fresh(List<String> stamps, Clock clock) {
        String newest =
                stamps.stream().filter(Objects::nonNull).max(String::compareTo).orElse(null);
        if (newest == null) {
            return false;
        }
        LocalDateTime fetched;
        try {
            fetched = LocalDateTime.parse(newest, TIMESTAMP);
        } catch (RuntimeException error) {
            return false;
        }
        Duration age = Duration.between(fetched, LocalDateTime.now(clock));
        return age.compareTo(Duration.ofHours((long) MAX_AGE_HOURS)) <= 0;
    }

    /**
     * 取一个 datacenter 报表的全部分页行，页数上限由调用方给——Python 各模块不同：
     * 机构调研 30 页、其余报表 20 页、分红 3 页、同业 5 页、单页接口 1 页。
     * 信封校验照 {@code StockMasterSync.fetchPage}：空 body、HTML/WAF、截断 JSON、
     * success 不为真、result/data 形状不对，都按上游失败抛出。
     */
    static List<JsonNode> fetchRows(
            UpstreamClient upstreamClient,
            String endpoint,
            String reportName,
            String filter,
            String sort,
            int pageSize,
            int maxPages,
            String family) {
        return fetchPaged(
                        upstreamClient,
                        endpoint,
                        reportName,
                        filter,
                        sort,
                        "ALL",
                        pageSize,
                        maxPages,
                        family)
                .rows();
    }

    /** 一页数据的取数结果；{@code truncated} 对应 Python 的 {@code while ... else}。 */
    record PagedRows(List<JsonNode> rows, boolean truncated) {}

    static PagedRows fetchPaged(
            UpstreamClient upstreamClient,
            String endpoint,
            String reportName,
            String filter,
            String sort,
            String columns,
            int pageSize,
            int maxPages,
            String family) {
        String[] sortParts = sort.split(":", 2);
        List<JsonNode> rows = new ArrayList<>();
        int page = 1;
        int pages = 1;
        boolean truncated = false;
        while (page <= maxPages) {
            String url =
                    url(
                            endpoint,
                            reportName,
                            columns,
                            filter,
                            page,
                            pageSize,
                            sortParts[0],
                            sortParts[1]);
            JsonNode root;
            try {
                root = MAPPER.readTree(upstreamClient.get(url, Map.of()));
            } catch (JsonProcessingException error) {
                throw new EastmoneyException("获取" + family + "数据失败：" + error.getMessage());
            } catch (RuntimeException error) {
                throw new EastmoneyException("获取" + family + "数据失败：" + error.getMessage());
            }
            if (root == null || !root.isObject()) {
                throw new EastmoneyException(family + "接口返回格式异常");
            }
            if (!root.path("success").asBoolean(false)) {
                throw new EastmoneyException(
                        family + "接口返回错误：" + errorMessage(root.path("message")));
            }
            JsonNode result = root.path("result");
            JsonNode data = result.path("data");
            if (!result.isObject() || !data.isArray()) {
                throw new EastmoneyException(family + "接口返回格式异常");
            }
            for (JsonNode item : data) {
                if (item.isObject()) {
                    rows.add(item);
                }
            }
            pages = intOr(result.path("pages"), 1);
            if (page >= pages || data.isEmpty()) {
                break;
            }
            page++;
            if (page > maxPages) {
                truncated = true;
            }
        }
        return new PagedRows(rows, truncated);
    }

    private static String errorMessage(JsonNode message) {
        String text = text(message);
        return text == null ? "未知错误" : text;
    }

    private static int intOr(JsonNode node, int fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText().strip());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String url(
            String endpoint,
            String reportName,
            String columns,
            String filter,
            int page,
            int pageSize,
            String sortType,
            String sortColumn) {
        return endpoint
                + "?reportName="
                + encode(reportName)
                + "&columns="
                + encode(columns)
                + "&quoteColumns=&filter="
                + encode(filter)
                + "&pageNumber="
                + page
                + "&pageSize="
                + pageSize
                + "&sortTypes="
                + encode(sortType)
                + "&sortColumns="
                + encode(sortColumn)
                + "&source=WEB&client=WEB";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 上游/解析失败统一走这里，端口层折成 {@code ToolResult.failure}。 */
    static final class EastmoneyException extends RuntimeException {
        EastmoneyException(String message) {
            super(message);
        }
    }
}
