package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ShareholderPort;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** 东方财富 F10 股东结构，照抄 {@code stock/shareholders.py} 与 {@code assistant/tools.py:_shareholders}。 */
@Repository
public class ShareholderAdapter implements ShareholderPort {
    static final String ENDPOINT =
            "https://emweb.securities.eastmoney.com/PC_HSF10/ShareholderResearch/PageAjax";
    static final String PAGE_URL =
            "https://emweb.securities.eastmoney.com/PC_HSF10/ShareholderResearch/Index"
                    + "?type=web&code=%s";
    static final double MAX_AGE_HOURS = 24;
    /** 缓存固定保留最多十期，调用方要几期再切片。 */
    static final int MAX_PERIODS = 10;

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final Clock clock;

    @Autowired
    public ShareholderAdapter(
            ResearchCacheStore store, StockMapper stockMapper, UpstreamClient upstream) {
        this(store, stockMapper, upstream, Clock.systemDefaultZone());
    }

    ShareholderAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            Clock clock) {
        this.store = store;
        this.stockMapper = stockMapper;
        this.upstream = upstream;
        this.clock = clock;
    }

    @Override
    public ToolResult shareholders(String rawCode, int periods) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.SHAREHOLDERS,
                            code,
                            null,
                            MAX_AGE_HOURS,
                            clock,
                            () -> fetch(code));
            return build(code, ensured, periods);
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult build(String code, DatacenterSupport.CacheResult ensured, int periods) {
        Map<String, Object> cached = DatacenterSupport.plain(ensured.data());
        List<Map<String, Object>> top10 = holderRows(cached.get("top10"), false);
        List<Map<String, Object>> freeTop = holderRows(cached.get("free_float_top10"), true);
        if (top10.isEmpty() && freeTop.isEmpty()) {
            return ToolResult.failure(code, code + "：没有股东结构数据");
        }

        List<Map<String, Object>> history =
                countRows(cached.get("holder_count"), Math.min(Math.max(periods, 1), MAX_PERIODS));
        String name = stockMapper.selectNameByCode(code);
        String asOf = DatacenterSupport.text(cached.get("as_of"));
        if (asOf == null) {
            asOf = "";
        }

        Map<String, Object> controller = null;
        if (cached.get("controller") instanceof Map<?, ?> raw) {
            String holder = DatacenterSupport.text(raw.get("name"));
            if (holder != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", holder);
                item.put("ratio", DatacenterSupport.pct(raw.get("ratio")));
                controller = item;
            }
        }

        Map<String, Object> count = new LinkedHashMap<>();
        count.put("latest", history.isEmpty() ? null : history.get(history.size() - 1));
        count.put("history", history);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("top10", top10.size());
        counts.put("free_float_top10", freeTop.size());
        counts.put("holder_periods", history.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("stale", ensured.stale());
        data.put("controller", controller);
        data.put("top10", top10);
        data.put("free_float_top10", freeTop);
        data.put("holder_count", count);
        data.put("counts", counts);

        String shownName = name == null || name.isEmpty() ? code : name;
        Source source =
                new Source(
                        asOf.isEmpty()
                                ? "shareholders:" + code
                                : "shareholders:" + code + ":" + asOf,
                        "股东结构",
                        (shownName + " " + asOf).strip(),
                        asOf,
                        ensured.fetchedAt() == null ? "" : ensured.fetchedAt(),
                        PAGE_URL.formatted(prefix(code) + code));
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    // ---------------------------------------------------------------- 整形

    /** {@code assistant/tools.py:_holder_row}。 */
    private static List<Map<String, Object>> holderRows(Object raw, boolean freeFloat) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : castList(raw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", item.get("rank"));
            row.put("name", item.get("name"));
            row.put("share_type", item.get("share_type"));
            row.put("shares_wan", DatacenterSupport.wan(item.get("shares")));
            row.put("ratio", DatacenterSupport.pct(item.get("ratio")));
            row.put("change_wan", DatacenterSupport.wan(item.get("change")));
            row.put("change_text", item.get("change_text"));
            row.put("change_ratio", DatacenterSupport.pct(item.get("change_ratio")));
            if (freeFloat) {
                row.put("holder_type", item.get("holder_type"));
            }
            rows.add(row);
        }
        return rows;
    }

    /** {@code _apply_periods} + {@code assistant/tools.py:_holder_count_row}。 */
    private static List<Map<String, Object>> countRows(Object holderCount, int periods) {
        Object raw = holderCount instanceof Map<?, ?> map ? map.get("history") : null;
        List<Map<String, Object>> all = castList(raw);
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> item : all.subList(Math.max(0, all.size() - periods), all.size())) {
            Double avg = DatacenterSupport.number(item.get("avg_free_shares"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", item.get("period"));
            row.put("holders", item.get("holders"));
            row.put("holders_change_pct", DatacenterSupport.pct(item.get("holders_change_pct")));
            row.put("avg_free_shares", avg == null ? null : (int) Math.rint(avg));
            row.put(
                    "avg_free_shares_change_pct",
                    DatacenterSupport.pct(item.get("avg_free_shares_change_pct")));
            row.put("focus", item.get("focus"));
            row.put("top10_ratio", DatacenterSupport.pct(item.get("top10_ratio")));
            row.put(
                    "free_float_top10_ratio",
                    DatacenterSupport.pct(item.get("free_float_top10_ratio")));
            history.add(row);
        }
        return history;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    // ---------------------------------------------------------------- 取数

    /** {@code stock/utils.py:get_stock_prefix}：只在这里需要前缀与纯数字。 */
    static String prefix(String digits) {
        if (digits.isEmpty()) {
            return "UNKNOWN";
        }
        if (digits.startsWith("600")
                || digits.startsWith("601")
                || digits.startsWith("603")
                || digits.startsWith("605")
                || digits.startsWith("688")) {
            return "SH";
        }
        if (digits.startsWith("000")
                || digits.startsWith("001")
                || digits.startsWith("002")
                || digits.startsWith("003")
                || digits.startsWith("300")
                || digits.startsWith("301")) {
            return "SZ";
        }
        if (digits.startsWith("920") || digits.startsWith("4") || digits.startsWith("8")) {
            return "BJ";
        }
        return "UNKNOWN";
    }

    private JsonNode fetch(String code) {
        String prefix = prefix(code);
        if (!(prefix.equals("SH") || prefix.equals("SZ") || prefix.equals("BJ"))) {
            throw new IllegalStateException(code + "：无法判断交易所");
        }
        String body;
        try {
            body =
                    upstream.get(
                            ENDPOINT + "?code=" + prefix + code,
                            Map.of("User-Agent", "Mozilla/5.0"));
        } catch (RuntimeException error) {
            throw new IllegalStateException("获取股东结构失败：" + error.getMessage(), error);
        }
        JsonNode payload;
        try {
            payload = EastmoneySupport.MAPPER.readTree(body);
        } catch (Exception error) {
            throw new IllegalStateException("获取股东结构失败：" + error.getMessage(), error);
        }
        return fromPayload(payload, code, MAX_PERIODS);
    }

    /** {@code stock/shareholders.py:shareholders_from_payload}。 */
    static JsonNode fromPayload(JsonNode payload, String code, int periods) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalStateException(code + "：股东结构接口返回格式异常");
        }
        List<Map<String, Object>> top10 = topHolders(payload.get("sdgd"), false);
        List<Map<String, Object>> freeTop = topHolders(payload.get("sdltgd"), true);
        if (top10.isEmpty() && freeTop.isEmpty()) {
            throw new IllegalStateException(code + "：没有取到股东结构数据");
        }
        String asOf =
                (top10.isEmpty() ? freeTop.get(0) : top10.get(0)).get("period").toString();
        List<Map<String, Object>> history = holderHistory(payload.get("gdrs"), periods);

        Map<String, Object> controller = null;
        for (JsonNode row : payload.path("sjkzr")) {
            if (!row.isObject()) {
                continue;
            }
            String name = EastmoneySupport.text(row.get("HOLDER_NAME"));
            if (name != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("ratio", EastmoneySupport.num(row.get("HOLD_RATIO")));
                controller = item;
                break;
            }
        }

        Map<String, Object> holder = new LinkedHashMap<>();
        holder.put("latest", history.isEmpty() ? null : history.get(history.size() - 1));
        holder.put("history", history);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("top10", top10.size());
        counts.put("free_float_top10", freeTop.size());
        counts.put("holder_periods", history.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("as_of", asOf);
        out.put("controller", controller);
        out.put("top10", top10);
        out.put("free_float_top10", freeTop);
        out.put("holder_count", holder);
        out.put("counts", counts);
        return EastmoneySupport.MAPPER.valueToTree(out);
    }

    /** {@code stock/shareholders.py:_top_holders}。 */
    private static List<Map<String, Object>> topHolders(JsonNode rows, boolean freeFloat) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (JsonNode row : rows == null ? List.<JsonNode>of() : rows) {
            if (!row.isObject()) {
                continue;
            }
            String period = date(row.get("END_DATE"));
            String name = EastmoneySupport.text(row.get("HOLDER_NAME"));
            if (period == null || name == null) {
                continue;
            }
            String changeText = EastmoneySupport.text(row.get("HOLD_NUM_CHANGE"));
            Double change = DatacenterSupport.number(changeText);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("period", period);
            item.put("rank", intOrNull(row.get("HOLDER_RANK")));
            item.put("name", name);
            item.put("share_type", EastmoneySupport.text(row.get("SHARES_TYPE")));
            item.put("shares", EastmoneySupport.num(row.get("HOLD_NUM")));
            item.put(
                    "ratio",
                    EastmoneySupport.num(
                            row.get(freeFloat ? "FREE_HOLDNUM_RATIO" : "HOLD_NUM_RATIO")));
            item.put("change", change);
            item.put("change_text", change != null ? null : changeText);
            item.put("change_ratio", EastmoneySupport.num(row.get("CHANGE_RATIO")));
            if (freeFloat) {
                item.put("holder_type", EastmoneySupport.text(row.get("HOLDER_TYPE")));
            }
            normalized.add(item);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        String latest =
                normalized.stream()
                        .map(item -> String.valueOf(item.get("period")))
                        .max(String::compareTo)
                        .orElse("");
        List<Map<String, Object>> latestRows = new ArrayList<>();
        normalized.stream()
                .filter(item -> latest.equals(item.get("period")))
                .sorted(
                        Comparator.comparingInt(
                                item -> item.get("rank") == null ? 999 : (int) item.get("rank")))
                .forEach(latestRows::add);
        return latestRows.size() > 10 ? latestRows.subList(0, 10) : latestRows;
    }

    /** {@code stock/shareholders.py:_holder_history}。 */
    private static List<Map<String, Object>> holderHistory(JsonNode rows, int periods) {
        Map<String, Map<String, Object>> byPeriod = new TreeMap<>();
        for (JsonNode row : rows == null ? List.<JsonNode>of() : rows) {
            if (!row.isObject()) {
                continue;
            }
            String period = date(row.get("END_DATE"));
            Integer holders = intOrNull(row.get("HOLDER_TOTAL_NUM"));
            if (period == null || holders == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("period", period);
            item.put("holders", holders);
            item.put("holders_change_pct", EastmoneySupport.num(row.get("TOTAL_NUM_RATIO")));
            item.put("avg_free_shares", EastmoneySupport.num(row.get("AVG_FREE_SHARES")));
            item.put(
                    "avg_free_shares_change_pct",
                    EastmoneySupport.num(row.get("AVG_FREESHARES_RATIO")));
            item.put("focus", EastmoneySupport.text(row.get("HOLD_FOCUS")));
            item.put("top10_ratio", EastmoneySupport.num(row.get("HOLD_RATIO_TOTAL")));
            item.put(
                    "free_float_top10_ratio",
                    EastmoneySupport.num(row.get("FREEHOLD_RATIO_TOTAL")));
            byPeriod.put(period, item);
        }
        List<Map<String, Object>> ordered = new ArrayList<>(byPeriod.values());
        return ordered.size() > periods
                ? ordered.subList(ordered.size() - periods, ordered.size())
                : ordered;
    }

    private static String date(JsonNode node) {
        String text = EastmoneySupport.text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }

    private static Integer intOrNull(JsonNode node) {
        Double value = EastmoneySupport.num(node);
        return value == null ? null : (int) (double) value;
    }
}
