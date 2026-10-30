package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ResearchPort;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** 东财机构调研，照抄 {@code stock/research.py} 与 {@code assistant/tools.py:_institutional_research}。 */
@Repository
public class ResearchAdapter implements ResearchPort {
    static final String REPORT_NAME = "RPT_ORG_SURVEY";
    static final String PAGE_URL = "https://data.eastmoney.com/jgdy/";
    static final double MAX_AGE_HOURS = 24;
    static final int MAX_EVENTS = 20;
    static final int MAX_INSTITUTIONS = 20;
    static final int MAX_CONTENT_CHARS = 4000;
    /** Python {@code _get_page} 的 {@code columns}：不含 CONTENT。 */
    static final String COLUMNS =
            "SECURITY_CODE,SECURITY_NAME_ABBR,NOTICE_DATE,RECEIVE_START_DATE,RECEIVE_PLACE,"
                    + "RECEIVE_WAY_EXPLAIN,RECEPTIONIST,RECEIVE_OBJECT,ORG_TYPE,INVESTIGATORS,SUM,NUM";

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public ResearchAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(store, stockMapper, upstream, endpoint, Clock.systemDefaultZone());
    }

    ResearchAdapter(
            ResearchCacheStore store,
            StockMapper stockMapper,
            UpstreamClient upstream,
            String endpoint,
            Clock clock) {
        this.store = store;
        this.stockMapper = stockMapper;
        this.upstream = upstream;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override
    public ToolResult research(String rawCode, int years) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        int window = Math.min(Math.max(years, 1), 10);
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.RESEARCH,
                            code,
                            window,
                            MAX_AGE_HOURS,
                            clock,
                            () -> fetch(code, window));
            return build(code, ensured, window);
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult build(String code, DatacenterSupport.CacheResult ensured, int years) {
        List<Map<String, Object>> all =
                group(DatacenterSupport.plainList(ensured.data().path("events")));

        LocalDate today = LocalDate.parse(DatacenterSupport.today(clock));
        String start = (today.getYear() - years + 1) + "-01-01";
        String end = today.toString();
        List<Map<String, Object>> window = new ArrayList<>();
        for (Map<String, Object> event : all) {
            String eventDate = String.valueOf(event.get("date"));
            if (start.compareTo(eventDate) <= 0 && eventDate.compareTo(end) <= 0) {
                window.add(event);
            }
        }
        Map<String, Object> latest = window.isEmpty() ? (all.isEmpty() ? null : all.get(0)) : window.get(0);

        String asOf = null;
        for (Map<String, Object> event : all) {
            String noticeDate = String.valueOf(event.get("notice_date"));
            if (asOf == null || noticeDate.compareTo(asOf) > 0) {
                asOf = noticeDate;
            }
        }

        String name = stockMapper.selectNameByCode(code);
        if (name == null || name.isEmpty()) {
            name = null;
            for (Map<String, Object> event : all) {
                if (event.get("security_name") != null) {
                    name = String.valueOf(event.get("security_name"));
                    break;
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("window_start", start);
        data.put("window_end", end);
        data.put("years", years);
        data.put("has_research", !all.isEmpty());
        data.put("latest", latest == null ? null : eventView(latest));
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> event : window.subList(0, Math.min(MAX_EVENTS, window.size()))) {
            events.add(eventView(event));
        }
        data.put("events", events);
        data.put("annual", annual(window));
        data.put("org_types", orgTypeTotals(window));
        data.put("events_truncated", Math.max(0, window.size() - MAX_EVENTS));
        data.put("rows_truncated", ensured.data().path("truncated").asBoolean(false));
        data.put("counts", countsView(counts(window, latest, today)));
        data.put("notes", notes(all, window, latest, years));
        data.put("stale", ensured.stale());

        String shownAsOf = asOf == null ? "" : asOf;
        String shownName = name == null || name.isEmpty() ? code : name;
        Source source =
                new Source(
                        shownAsOf.isEmpty() ? "research:" + code : "research:" + code + ":" + shownAsOf,
                        "机构调研",
                        (shownName + " 机构调研 " + shownAsOf).strip(),
                        shownAsOf,
                        ensured.fetchedAt() == null ? "" : ensured.fetchedAt(),
                        PAGE_URL);
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    // ---------------------------------------------------------------- 取数

    private JsonNode fetch(String code, int years) {
        String start = (LocalDate.now(clock).getYear() - years + 1) + "-01-01";
        String filter = "(SECURITY_CODE=\"" + code + "\")(NOTICE_DATE>='" + start + "')";
        // 列清单刻意不带 CONTENT：正文很长，只给最近一场单独取一次。
        EastmoneySupport.PagedRows paged =
                EastmoneySupport.fetchPaged(
                        upstream, endpoint, REPORT_NAME, filter, "NOTICE_DATE:-1", COLUMNS, 80, 30, "机构调研");
        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode row : paged.rows()) {
            Map<String, Object> event = normalize(row);
            if (event != null) {
                events.add(event);
            }
        }
        if (!events.isEmpty()) {
            Map<String, Object> first = events.get(0);
            String content =
                    DatacenterSupport.fetchContent(
                            upstream,
                            endpoint,
                            REPORT_NAME,
                            "(SECURITY_CODE=\""
                                    + code
                                    + "\")(NOTICE_DATE='"
                                    + first.get("notice_date")
                                    + "')(RECEIVE_START_DATE='"
                                    + first.get("date")
                                    + "')");
            if (content != null && !content.isEmpty()) {
                first.put(
                        "content",
                        content.substring(0, Math.min(MAX_CONTENT_CHARS, content.length())));
            }
        }
        ObjectNode payload = EastmoneySupport.MAPPER.createObjectNode();
        payload.put("code", code);
        payload.put("years", years);
        ArrayNode array = payload.putArray("events");
        for (Map<String, Object> event : events) {
            array.add(EastmoneySupport.MAPPER.valueToTree(event));
        }
        payload.put("count", array.size());
        payload.put("truncated", paged.truncated());
        return payload;
    }

    /** {@code stock/research.py:_normalize_row}。 */
    private static Map<String, Object> normalize(JsonNode row) {
        String noticeDate = date(row.get("NOTICE_DATE"));
        String receiveDate = date(row.get("RECEIVE_START_DATE"));
        if (receiveDate == null) {
            receiveDate = noticeDate;
        }
        if (noticeDate == null || receiveDate == null) {
            return null;
        }
        String place = EastmoneySupport.text(row.get("RECEIVE_PLACE"));
        String content = EastmoneySupport.text(row.get("CONTENT"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("date", receiveDate);
        event.put("notice_date", noticeDate);
        event.put("place", place);
        event.put("ways", EastmoneySupport.text(row.get("RECEIVE_WAY_EXPLAIN")));
        event.put("receptionists", EastmoneySupport.text(row.get("RECEPTIONIST")));
        event.put("institution", EastmoneySupport.text(row.get("RECEIVE_OBJECT")));
        String orgType = EastmoneySupport.text(row.get("ORG_TYPE"));
        event.put("org_type", orgType == null ? "未分类" : orgType);
        event.put("investigators", EastmoneySupport.text(row.get("INVESTIGATORS")));
        Double total = EastmoneySupport.num(row.get("SUM"));
        event.put("institution_total", total == null ? null : (int) (double) total);
        event.put(
                "content",
                content == null
                        ? null
                        : content.substring(0, Math.min(MAX_CONTENT_CHARS, content.length())));
        event.put("security_name", EastmoneySupport.text(row.get("SECURITY_NAME_ABBR")));
        event.put("key", receiveDate + "|" + noticeDate + "|" + (place == null ? "" : place));
        return event;
    }

    // ---------------------------------------------------------------- 归并

    /** {@code stock/research.py:_group}。 */
    private static List<Map<String, Object>> group(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String eventDate = DatacenterSupport.text(row.get("date"));
            String noticeDate = DatacenterSupport.text(row.get("notice_date"));
            if (eventDate == null || noticeDate == null) {
                continue;
            }
            String place = DatacenterSupport.text(row.get("place"));
            String key = eventDate + "|" + noticeDate + "|" + (place == null ? "" : place);
            Map<String, Object> event = groups.get(key);
            if (event == null) {
                event = new LinkedHashMap<>();
                event.put("key", key);
                event.put("date", eventDate);
                event.put("notice_date", noticeDate);
                event.put("place", place);
                event.put("ways", new ArrayList<String>());
                event.put("receptionists", new ArrayList<String>());
                event.put("institutions", new LinkedHashMap<String, String>());
                event.put("totals", new ArrayList<Integer>());
                event.put("content", null);
                event.put("security_name", null);
                groups.put(key, event);
            }
            String institution = DatacenterSupport.text(row.get("institution"));
            @SuppressWarnings("unchecked")
            Map<String, String> institutions =
                    (Map<String, String>) event.get("institutions");
            if (institution != null && !institutions.containsKey(institution)) {
                String orgType = DatacenterSupport.text(row.get("org_type"));
                institutions.put(institution, orgType == null ? "未分类" : orgType);
            }
            @SuppressWarnings("unchecked")
            List<String> ways = (List<String>) event.get("ways");
            for (String way : split(row.get("ways"))) {
                if (!ways.contains(way)) {
                    ways.add(way);
                }
            }
            @SuppressWarnings("unchecked")
            List<String> receptionists = (List<String>) event.get("receptionists");
            for (String person : split(row.get("receptionists"))) {
                if (!receptionists.contains(person)) {
                    receptionists.add(person);
                }
            }
            Object total = row.get("institution_total");
            if (total instanceof Integer value && value > 0) {
                @SuppressWarnings("unchecked")
                List<Integer> totals = (List<Integer>) event.get("totals");
                totals.add(value);
            }
            if (event.get("content") == null && row.get("content") != null) {
                event.put("content", row.get("content"));
            }
            if (event.get("security_name") == null && row.get("security_name") != null) {
                event.put("security_name", row.get("security_name"));
            }
        }

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> event : groups.values()) {
            @SuppressWarnings("unchecked")
            Map<String, String> institutions = (Map<String, String>) event.remove("institutions");
            @SuppressWarnings("unchecked")
            List<Integer> totals = (List<Integer>) event.remove("totals");
            event.put("institutions", new ArrayList<>(institutions.keySet()));
            event.put("disclosed_count", institutions.size());
            event.put(
                    "institution_count",
                    totals.isEmpty() ? institutions.size() : totals.stream().mapToInt(Integer::intValue).max().getAsInt());
            event.put("org_types", typeRows(institutions.values()));
            events.add(event);
        }
        events.sort(
                (left, right) -> {
                    int compared =
                            String.valueOf(right.get("date"))
                                    .compareTo(String.valueOf(left.get("date")));
                    if (compared != 0) {
                        return compared;
                    }
                    return String.valueOf(right.get("notice_date"))
                            .compareTo(String.valueOf(left.get("notice_date")));
                });
        return events;
    }

    private static List<Map<String, Object>> typeRows(Iterable<String> types) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : types) {
            counts.merge(name, 1, Integer::sum);
        }
        return sortedTypeRows(counts);
    }

    private static List<Map<String, Object>> orgTypeTotals(List<Map<String, Object>> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) event.getOrDefault("org_types", List.of());
            for (Map<String, Object> row : rows) {
                String name = String.valueOf(row.get("type"));
                counts.merge(name, DatacenterSupport.intOf(row.get("count")), Integer::sum);
            }
        }
        return sortedTypeRows(counts);
    }

    private static List<Map<String, Object>> sortedTypeRows(Map<String, Integer> counts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", entry.getKey());
            row.put("count", entry.getValue());
            rows.add(row);
        }
        rows.sort(
                (left, right) -> {
                    int compared =
                            Integer.compare(
                                    DatacenterSupport.intOf(right.get("count")),
                                    DatacenterSupport.intOf(left.get("count")));
                    if (compared != 0) {
                        return compared;
                    }
                    return String.valueOf(left.get("type"))
                            .compareTo(String.valueOf(right.get("type")));
                });
        return rows;
    }

    private static List<Map<String, Object>> annual(List<Map<String, Object>> events) {
        Map<Integer, List<Map<String, Object>>> byYear = new TreeMap<>();
        for (Map<String, Object> event : events) {
            int year = Integer.parseInt(String.valueOf(event.get("date")).substring(0, 4));
            byYear.computeIfAbsent(year, key -> new ArrayList<>()).add(event);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byYear.entrySet()) {
            int visits = 0;
            for (Map<String, Object> event : entry.getValue()) {
                visits += DatacenterSupport.intOf(event.get("institution_count"));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", entry.getKey());
            row.put("events", entry.getValue().size());
            row.put("institution_visits", visits);
            rows.add(row);
        }
        return rows;
    }

    /** 直接产出 {@code assistant/tools.py:_research_counts} 的形状。 */
    private static Map<String, Object> counts(
            List<Map<String, Object>> window, Map<String, Object> latest, LocalDate today) {
        String cutoff = today.minusDays(365).toString();
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> event : window) {
            if (String.valueOf(event.get("date")).compareTo(cutoff) >= 0) {
                recent.add(event);
            }
        }
        int visits = 0;
        for (Map<String, Object> event : window) {
            visits += DatacenterSupport.intOf(event.get("institution_count"));
        }
        int visitsRecent = 0;
        for (Map<String, Object> event : recent) {
            visitsRecent += DatacenterSupport.intOf(event.get("institution_count"));
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("events", window.size());
        counts.put("institution_visits", visits);
        counts.put("latest_date", latest == null ? null : latest.get("date"));
        counts.put("latest_notice_date", latest == null ? null : latest.get("notice_date"));
        counts.put(
                "latest_institution_count", latest == null ? null : latest.get("institution_count"));
        counts.put("latest_place", latest == null ? null : latest.get("place"));
        counts.put("events_12m", recent.size());
        counts.put("institution_visits_12m", visitsRecent);
        if (latest == null) {
            counts.put("events", 0);
            counts.put("institution_visits", 0);
            counts.put("events_12m", 0);
            counts.put("institution_visits_12m", 0);
        }
        return counts;
    }

    private static List<String> notes(
            List<Map<String, Object>> all,
            List<Map<String, Object>> window,
            Map<String, Object> latest,
            int years) {
        List<String> notes = new ArrayList<>();
        if (all.isEmpty() || latest == null) {
            notes.add("没有取到机构调研记录，可能公司未披露或数据源未收录");
            return notes;
        }
        if (window.isEmpty()) {
            notes.add("近" + years + "年没有已披露的机构调研；最近一次为" + latest.get("date"));
            notes.add("调研记录只含已披露的接待，未披露的无法统计");
            return notes;
        }
        notes.add("调研纪要是公司整理披露的交流记录，问答可能经过筛选，不代表承诺");
        notes.add("机构家数按披露的接待对象统计，同一机构多次调研会重复计入");
        notes.add("调研热度不等于基本面，需要看问答内容与后续经营数据");
        notes.add("只含已披露的调研记录，未披露的接待无法统计");
        return notes;
    }

    // ---------------------------------------------------------------- 整形

    /** {@code assistant/tools.py:_research_event}。 */
    private static Map<String, Object> eventView(Map<String, Object> item) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orgTypes =
                (List<Map<String, Object>>) item.getOrDefault("org_types", List.of());
        List<Map<String, Object>> orgViews = new ArrayList<>();
        for (Map<String, Object> row : orgTypes) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("type", row.get("type"));
            view.put("count", row.get("count"));
            orgViews.add(view);
        }
        @SuppressWarnings("unchecked")
        List<String> institutions = (List<String>) item.getOrDefault("institutions", List.of());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", item.get("date"));
        out.put("notice_date", item.get("notice_date"));
        out.put("place", item.get("place"));
        out.put("ways", item.getOrDefault("ways", List.of()));
        out.put("receptionists", item.getOrDefault("receptionists", List.of()));
        out.put("institution_count", item.get("institution_count"));
        out.put("disclosed_count", item.get("disclosed_count"));
        out.put("org_types", orgViews);
        out.put("institutions", institutions.subList(0, Math.min(MAX_INSTITUTIONS, institutions.size())));
        out.put("content", emptyToNull(clip(DatacenterSupport.text(item.get("content")), 600)));
        return out;
    }

    private static Map<String, Object> countsView(Map<String, Object> item) {
        return new LinkedHashMap<>(item);
    }

    // ---------------------------------------------------------------- 小工具

    private static List<String> split(Object value) {
        String text = DatacenterSupport.text(value);
        if (text == null) {
            return List.of();
        }
        text = text.replace("，", ",").replace(";", ",").replace("；", ",");
        List<String> items = new ArrayList<>();
        for (String item : text.split(",")) {
            String trimmed = item.strip();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private static String clip(String text, int maxChars) {
        String value = text == null ? "" : text.strip();
        if (value.length() <= maxChars) {
            return value;
        }
        String head = value.substring(0, maxChars).replaceAll("[，、；： ]+$", "");
        return head + "…";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String date(JsonNode node) {
        String text = EastmoneySupport.text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }
}
