package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阶段 2 各研究工具共用的缓存与数值小工具。Python 里十三份 {@code _age_hours/_yi/_num} 是复制的，
 * 这里合成一份；口径照 {@code stock/dividends.py} 等模块。
 */
final class DatacenterSupport {
    private static final Logger LOG = LoggerFactory.getLogger(DatacenterSupport.class);

    private DatacenterSupport() {}

    /** Python {@code _age_hours}：没有或解析不了的时间戳视为无限旧。 */
    static double ageHours(String fetchedAt, Clock clock) {
        if (fetchedAt == null || fetchedAt.isBlank()) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            LocalDateTime fetched = LocalDateTime.parse(fetchedAt, EastmoneySupport.TIMESTAMP);
            return Duration.between(fetched, LocalDateTime.now(clock)).toMillis() / 3_600_000.0;
        } catch (RuntimeException error) {
            LOG.debug("缓存时间戳无法解析，按过期处理：fetched_at={}", fetchedAt);
            return Double.POSITIVE_INFINITY;
        }
    }

    record CacheResult(JsonNode data, String fetchedAt, boolean stale) {}

    /**
     * Python 的 {@code ensure_xxx}：新鲜就返回缓存，过期就抓取并落库；抓取失败但本地有缓存时
     * 返回旧数据并标记 stale，没有缓存才把异常抛给端口层。
     */
    static CacheResult ensure(
            ResearchCacheStore store,
            ResearchCache cache,
            String code,
            Integer key,
            double maxAgeHours,
            Clock clock,
            Supplier<JsonNode> fetch) {
        Optional<ResearchCacheStore.Row> cached = read(store, cache, code, key);
        if (cached.isPresent()
                && ageHours(cached.get().fetchedAt(), clock) <= maxAgeHours) {
            return new CacheResult(
                    parse(cached.get().data()), cached.get().fetchedAt(), false);
        }
        try {
            JsonNode fresh = fetch.get();
            store.write(cache, code, key, write(fresh),
                    LocalDateTime.now(clock).format(EastmoneySupport.TIMESTAMP));
            Optional<ResearchCacheStore.Row> saved = read(store, cache, code, key);
            return new CacheResult(
                    parse(saved.map(ResearchCacheStore.Row::data).orElse(fresh.toString())),
                    saved.map(ResearchCacheStore.Row::fetchedAt).orElse(null),
                    false);
        } catch (RuntimeException error) {
            if (cached.isPresent()) {
                return new CacheResult(
                        parse(cached.get().data()), cached.get().fetchedAt(), true);
            }
            throw error;
        }
    }

    /** {@code _cached_row}：行在但内容不是非空对象时按没有缓存处理。 */
    private static Optional<ResearchCacheStore.Row> read(
            ResearchCacheStore store, ResearchCache cache, String code, Integer key) {
        Optional<ResearchCacheStore.Row> row =
                key == null ? store.read(cache, code) : store.read(cache, code, key);
        return row.filter(item -> {
            JsonNode data = parse(item.data());
            return data.isObject() && !data.isEmpty();
        });
    }

    private static String write(JsonNode node) {
        try {
            return EastmoneySupport.MAPPER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new EastmoneySupport.EastmoneyException("缓存写入失败：" + error.getMessage());
        }
    }

    /** Python {@code stock/research.py:_request_content}：单独取一条纪要原文，失败就返回 null。 */
    static String fetchContent(
            UpstreamClient upstream,
            String endpoint,
            String reportName,
            String filter) {
        try {
            List<JsonNode> rows =
                    EastmoneySupport.fetchPaged(
                                    upstream,
                                    endpoint,
                                    reportName,
                                    filter,
                                    "-1",
                                    "NOTICE_DATE",
                                    "CONTENT",
                                    1,
                                    1,
                                    "调研纪要")
                            .rows();
            return rows.isEmpty() ? null : EastmoneySupport.text(rows.get(0).get("CONTENT"));
        } catch (RuntimeException error) {
            LOG.debug("调研纪要取数失败，按无数据处理", error);
            return null;
        }
    }

    static JsonNode parse(String json) {
        if (json == null) {
            return EastmoneySupport.MAPPER.createObjectNode();
        }
        try {
            return EastmoneySupport.MAPPER.readTree(json);
        } catch (Exception error) {
            LOG.debug("上游 JSON 无法解析，按空对象处理", error);
            return EastmoneySupport.MAPPER.createObjectNode();
        }
    }

    /** Python {@code round(value, digits)}：整数保持整数，浮点按银行家舍入。 */
    static Object round(Object value, int digits) {
        if (!(value instanceof Number number)) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long) {
            return value;
        }
        return EastmoneySupport.round(number.doubleValue(), digits);
    }

    /** {@code _yi}：元 → 亿元。 */
    static Double yi(Object value) {
        return number(value) == null ? null : EastmoneySupport.round(number(value) / 1e8, 2);
    }

    /** {@code _wan}：元 → 万元。 */
    static Double wan(Object value) {
        return number(value) == null ? null : EastmoneySupport.round(number(value) / 1e4, 2);
    }

    /** {@code _pct}：一位小数，-0.0 归一成 0.0。 */
    static Double pct(Object value) {
        return number(value) == null ? null : EastmoneySupport.round(number(value), 1) + 0.0;
    }

    static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return null;
    }

    static Object get(Map<String, Object> item, String key) {
        return item == null ? null : item.get(key);
    }

    /** Python {@code _text}：去空白，空串与 "-" 视为缺失。 */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() || text.equals("-") ? null : text;
    }

    static int intOf(Object value) {
        Double number = number(value);
        return number == null ? 0 : (int) (double) number;
    }

    static String today(Clock clock) {
        return LocalDateTime.now(clock).toLocalDate().toString();
    }

    /** {@code _sum_optional}：全是整数就给整数，否则按两位小数舍入。 */
    static Object sumOptional(List<Object> values) {
        List<Object> known = values.stream().filter(value -> value != null).toList();
        if (known.isEmpty()) {
            return null;
        }
        double total = 0;
        boolean allInt = true;
        for (Object value : known) {
            total += number(value);
            allInt = allInt && (value instanceof Integer || value instanceof Long);
        }
        return allInt ? (Object) (long) total : EastmoneySupport.round(total, 2);
    }

    /** Python {@code _completion}：低于下限用下限、否则用上限做分母。 */
    static Double completion(Object actual, Object low, Object high) {
        Double value = number(actual);
        if (value == null) {
            return null;
        }
        Double base = number(low);
        if (base == null || base == 0) {
            base = number(high);
        }
        if (base == null || base == 0) {
            return null;
        }
        return EastmoneySupport.round(value / base * 100.0, 1);
    }

    static boolean flag(Map<String, Object> item, String key) {
        return Boolean.TRUE.equals(item.get(key));
    }

    static List<Object> column(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> row.get(key)).toList();
    }

    /** JSON 子树 → 保序的普通 Map/List，数值保持 Python 的 int/float 形态。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> plain(JsonNode node) {
        Object value = EastmoneySupport.MAPPER.convertValue(node, Object.class);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> plainList(JsonNode node) {
        Object value = EastmoneySupport.MAPPER.convertValue(node, Object.class);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
