package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ManagementPort;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** 同花顺 F10 高管介绍，照抄 {@code stock/management.py} 与 {@code assistant/tools.py:_management}。 */
@Repository
public class ManagementAdapter implements ManagementPort {
    static final String ENDPOINT = "https://basic.10jqka.com.cn/mapp/%s/a_manager_introduce.json";
    static final String PAGE_URL = "https://basic.10jqka.com.cn/%s/company.html";
    static final double MAX_AGE_HOURS = 24 * 7;
    static final List<List<String>> GROUPS =
            List.of(
                    List.of("directors", "董事会"),
                    List.of("manager", "高管"),
                    List.of("supervisors", "监事会"),
                    List.of("auditors", "审计委员会"),
                    List.of("family", "家族"));

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final Clock clock;

    @Autowired
    public ManagementAdapter(
            ResearchCacheStore store, StockMapper stockMapper, UpstreamClient upstream) {
        this(store, stockMapper, upstream, Clock.systemDefaultZone());
    }

    ManagementAdapter(
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
    public ToolResult management(String rawCode, int resumeChars) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.MANAGEMENT,
                            code,
                            null,
                            MAX_AGE_HOURS,
                            clock,
                            () -> fetch(code));
            return build(code, ensured, resumeChars);
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult build(String code, DatacenterSupport.CacheResult ensured, int resumeChars) {
        Map<String, Object> cached = DatacenterSupport.plain(ensured.data());
        String name = stockMapper.selectNameByCode(code);
        String asOf = DatacenterSupport.text(cached.get("as_of"));
        if (asOf == null) {
            // Python: as_of or (fetched_at or "")[:10]

            String fetchedAt = ensured.fetchedAt() == null ? "" : ensured.fetchedAt();
            asOf = fetchedAt.isEmpty() ? "" : fetchedAt.substring(0, Math.min(10, fetchedAt.length()));
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Object> rawGroups = cached.get("groups") instanceof Map<?, ?> map
                ? castMap(map)
                : Map.of();
        for (Map.Entry<String, Object> entry : rawGroups.entrySet()) {
            Map<String, Object> group = castMap(entry.getValue());
            List<Map<String, Object>> members = new ArrayList<>();
            for (Map<String, Object> member :
                    DatacenterSupport.plainList(EastmoneySupport.MAPPER.valueToTree(group.get("members")))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", member.get("name"));
                item.put("duties", member.getOrDefault("duties", List.of()));
                item.put("tenure", member.get("tenure"));
                item.put("age", member.get("age"));
                item.put("education", member.get("education"));
                item.put("salary_wan", DatacenterSupport.wan(member.get("salary")));
                item.put("hold_wan", DatacenterSupport.wan(member.get("hold")));
                item.put("hold_ratio", DatacenterSupport.pct(member.get("hold_ratio")));
                item.put("direct_ratio", DatacenterSupport.pct(member.get("direct_ratio")));
                item.put("indirect_ratio", DatacenterSupport.pct(member.get("indirect_ratio")));
                item.put("is_control", Boolean.TRUE.equals(member.get("is_control")));
                item.put("is_legal_person", Boolean.TRUE.equals(member.get("is_legal_person")));
                String resume = DatacenterSupport.text(member.get("resume"));
                item.put(
                        "resume",
                        resumeChars == 0 ? null : emptyToNull(clip(resume, resumeChars)));
                String change = DatacenterSupport.text(member.get("change"));
                if (change != null) {
                    item.put("change", change);
                }
                members.add(item);
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", entry.getKey());
            view.put("label", group.get("label"));
            view.put("count", group.get("count"));
            view.put("salary_total_wan", DatacenterSupport.wan(group.get("salary_total")));
            view.put("hold_ratio", DatacenterSupport.pct(group.get("hold_ratio")));
            view.put("direct_ratio", DatacenterSupport.pct(group.get("direct_ratio")));
            view.put("indirect_ratio", DatacenterSupport.pct(group.get("indirect_ratio")));
            view.put("members", members);
            groups.add(view);
        }
        if (groups.isEmpty()) {
            return ToolResult.failure(code, "没有管理层数据");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("salary_year", cached.get("salary_year"));
        data.put("count", cached.get("count"));
        data.put("stale", ensured.stale());
        data.put("groups", groups);

        String shownName = name == null || name.isEmpty() ? code : name;
        Source source =
                new Source(
                        "management:" + code,
                        "管理层与持股",
                        (shownName + " " + asOf).strip(),
                        asOf,
                        ensured.fetchedAt() == null ? "" : ensured.fetchedAt(),
                        PAGE_URL.formatted(code));
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    // ---------------------------------------------------------------- 取数

    private JsonNode fetch(String code) {
        String body;
        try {
            body = upstream.get(ENDPOINT.formatted(code), Map.of());
        } catch (RuntimeException error) {
            throw new IllegalStateException("获取管理层数据失败：" + error.getMessage(), error);
        }
        JsonNode payload;
        try {
            payload = EastmoneySupport.MAPPER.readTree(body);
        } catch (Exception error) {
            throw new IllegalStateException("获取管理层数据失败：" + error.getMessage(), error);
        }
        return fromPayload(payload, code);
    }

    /** {@code stock/management.py:management_from_payload}。 */
    static JsonNode fromPayload(JsonNode payload, String code) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalStateException("高管接口返回格式异常");
        }
        JsonNode status = payload.path("status_code");
        if (!status.isMissingNode() && !status.isNull() && !(status.isNumber() && status.asInt() == 0)
                && !"0".equals(status.asText())) {
            String message = payload.path("status_msg").asText("接口返回失败");
            throw new IllegalStateException("获取管理层数据失败（" + message + "）");
        }
        JsonNode raw = payload.path("data");
        if (!raw.isObject()) {
            throw new IllegalStateException("没有取到管理层数据");
        }

        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (List<String> definition : GROUPS) {
            JsonNode rawGroup = raw.path(definition.get(0));
            if (!rawGroup.isObject()) {
                continue;
            }
            List<Map<String, Object>> members = members(rawGroup.path("list"));
            if (members.isEmpty()) {
                continue;
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("label", definition.get(1));
            group.put("count", members.size());
            group.put("salary_total", numberOrNull(rawGroup.get("salary")));
            group.put("hold_ratio", numberOrNull(rawGroup.get("rate")));
            group.put("direct_ratio", numberOrNull(rawGroup.get("directnum_rate")));
            group.put("indirect_ratio", numberOrNull(rawGroup.get("indirectnum_rate")));
            group.put("members", members);
            groups.put(definition.get(0), group);
        }
        int total = 0;
        List<String> reports = new ArrayList<>();
        for (Map<String, Object> group : groups.values()) {
            total += DatacenterSupport.intOf(group.get("count"));
            for (Map<String, Object> member : castMembers(group.get("members"))) {
                String report = DatacenterSupport.text(member.get("report"));
                if (report != null) {
                    reports.add(report);
                }
            }
        }
        if (total == 0) {
            throw new IllegalStateException("没有取到管理层数据");
        }
        String salaryYear = text(raw.get("salary_year"));
        String asOf =
                reports.isEmpty()
                        ? (salaryYear != null && salaryYear.matches("\\d+")
                                ? salaryYear + "-12-31"
                                : null)
                        : reports.stream().max(String::compareTo).orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("as_of", asOf);
        out.put("salary_year", salaryYear);
        out.put("bonus_year", text(raw.get("bonus_year")));
        out.put("groups", groups);
        out.put("count", total);
        return EastmoneySupport.MAPPER.valueToTree(out);
    }

    /** {@code stock/management.py:_members}。 */
    private static List<Map<String, Object>> members(JsonNode items) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                continue;
            }
            String name = text(item.get("name"));
            if (name == null) {
                continue;
            }
            String start = text(item.get("from"));
            String end = text(item.get("to"));
            String duty = text(item.get("duty"));
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("name", name);
            member.put("duty", duty);
            member.put("duties", duties(duty));
            member.put(
                    "tenure",
                    start != null && end == null
                            ? start + " 至今"
                            : (start != null ? start + "–" + end : end));
            member.put("tenure_start", start);
            member.put("tenure_end", end);
            member.put("change", text(item.get("change")));
            member.put("sex", text(item.get("sex")));
            member.put("age", text(item.get("age")));
            member.put("birth_year", intOrNull(item.get("brith_year")));
            member.put("education", text(item.get("record")));
            member.put("salary", numberOrNull(item.get("salary")));
            member.put("hold", numberOrNull(item.get("hold")));
            member.put("direct_hold", numberOrNull(item.get("directnum")));
            member.put("indirect_hold", numberOrNull(item.get("indirectnum")));
            member.put("hold_ratio", numberOrNull(item.get("rate")));
            member.put("direct_ratio", numberOrNull(item.get("directnum_rate")));
            member.put("indirect_ratio", numberOrNull(item.get("indirectnum_rate")));
            member.put("is_control", booleanOf(item.get("is_control")));
            member.put("is_legal_person", booleanOf(item.get("is_legal_person")));
            member.put("report", text(item.get("report")));
            member.put("resume", text(item.get("resume")));
            members.add(member);
        }
        return members;
    }

    private static List<String> duties(String duty) {
        if (duty == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : duty.replace("，", ",").replace("、", ",").split(",")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private static boolean booleanOf(JsonNode node) {
        return node != null && !node.isNull() && node.asBoolean(false);
    }

    private static Double numberOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode()
                ? null
                : EastmoneySupport.num(node);
    }

    private static Integer intOrNull(JsonNode node) {
        Double value = numberOrNull(node);
        return value == null ? null : (int) (double) value;
    }

    private static String text(JsonNode node) {
        return EastmoneySupport.text(node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMembers(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static String clip(String text, int maxChars) {
        String value = text == null ? "" : text;
        if (value.length() <= maxChars) {
            return value;
        }
        String head = value.substring(0, maxChars).replaceAll("[，、；： ]+$", "");
        return head + "…";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
