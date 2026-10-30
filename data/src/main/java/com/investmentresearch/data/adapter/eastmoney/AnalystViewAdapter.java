package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.AnalystViewPort;
import com.investmentresearch.data.persistence.ResearchCache;
import com.investmentresearch.data.persistence.ResearchCacheStore;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** 东方财富研报中心，照抄 {@code stock/analyst_views.py} 与 {@code assistant/tools.py:_analyst_views}。 */
@Repository
public class AnalystViewAdapter implements AnalystViewPort {
    static final String ENDPOINT = "https://reportapi.eastmoney.com/report/list";
    static final String PAGE_URL = "https://data.eastmoney.com/report/stock.jshtml?stockcode=%s";
    static final String INFO_URL = "https://data.eastmoney.com/report/info/%s.html";
    static final double MAX_AGE_HOURS = 24;
    static final int MAX_REPORTS = 30;
    static final int PAGE_SIZE = 50;
    static final int MAX_PAGES = 40;
    static final int MIN_DAYS = 30;
    static final int MAX_DAYS = 730;

    private final ResearchCacheStore store;
    private final StockMapper stockMapper;
    private final UpstreamClient upstream;
    private final Clock clock;

    @Autowired
    public AnalystViewAdapter(
            ResearchCacheStore store, StockMapper stockMapper, UpstreamClient upstream) {
        this(store, stockMapper, upstream, Clock.systemDefaultZone());
    }

    AnalystViewAdapter(
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
    public ToolResult analystViews(String rawCode, int days) {
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        int window = Math.min(Math.max(days, MIN_DAYS), MAX_DAYS);
        try {
            DatacenterSupport.CacheResult ensured =
                    DatacenterSupport.ensure(
                            store,
                            ResearchCache.REPORTS,
                            code,
                            window,
                            MAX_AGE_HOURS,
                            clock,
                            () -> fetch(code, window));
            return build(code, window, ensured);
        } catch (RuntimeException error) {
            return ToolResult.failure(code, error.getMessage());
        }
    }

    /** {@code build_analyst_views} + {@code assistant/tools.py:_analyst_views}。 */
    private ToolResult build(String code, int days, DatacenterSupport.CacheResult ensured) {
        Map<String, Object> cached = DatacenterSupport.plain(ensured.data());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : castList(cached.get("reports"))) {
            if (row.get("publish_date") != null) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing((Map<String, Object> row) -> dateOf(row)).reversed());

        String today = LocalDate.now(clock).toString();
        String start = LocalDate.now(clock).minusDays(days).toString();
        List<Map<String, Object>> window = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String publish = dateOf(row);
            if (start.compareTo(publish) <= 0 && publish.compareTo(today) <= 0) {
                window.add(row);
            }
        }
        List<Map<String, Object>> latestByOrg = latestByOrg(window);
        Map<String, Object> latest = null;
        if (!window.isEmpty()) {
            latest = window.get(0);
        } else if (!rows.isEmpty()) {
            latest = rows.get(0);
        }
        Map<String, Object> counts = counts(window, latestByOrg, LocalDate.now(clock).getYear());
        List<String> notes = notes(rows, window, days);
        String asOf = rows.isEmpty() ? null : dateOf(rows.get(0));

        List<Map<String, Object>> reports = new ArrayList<>();
        for (Map<String, Object> row : window.subList(0, Math.min(MAX_REPORTS, window.size()))) {
            reports.add(reportRow(row));
        }
        List<Map<String, Object>> orgRows = new ArrayList<>();
        for (Map<String, Object> row : latestByOrg) {
            orgRows.add(reportRow(row));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name(code, rows));
        data.put("as_of", asOf);
        data.put("window_start", start);
        data.put("window_end", today);
        data.put("days", days);
        data.put("has_reports", !rows.isEmpty());
        data.put("latest", latest == null ? null : reportRow(latest));
        data.put("reports", reports);
        data.put("latest_by_org", orgRows);
        data.put("reports_truncated", Math.max(0, window.size() - MAX_REPORTS));
        data.put("rows_truncated", Boolean.TRUE.equals(cached.get("truncated")));
        data.put("counts", counts);
        data.put("notes", notes);
        data.put("stale", ensured.stale());

        String shownName = (String) data.get("name");
        String detail =
                ((shownName == null || shownName.isEmpty() ? code : shownName)
                                + " 卖方研报 "
                                + (asOf == null ? "" : asOf))
                        .strip();
        Source source =
                new Source(
                        asOf == null ? "analyst:" + code : "analyst:" + code + ":" + asOf,
                        "研报观点",
                        detail,
                        asOf == null ? "" : asOf,
                        ensured.fetchedAt() == null ? "" : ensured.fetchedAt(),
                        PAGE_URL.formatted(code));
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    private String name(String code, List<Map<String, Object>> rows) {
        String name = stockMapper.selectNameByCode(code);
        if (name != null && !name.isEmpty()) {
            return name;
        }
        for (Map<String, Object> row : rows) {
            String stockName = DatacenterSupport.text(row.get("stock_name"));
            if (stockName != null) {
                return stockName;
            }
        }
        return null;
    }

    private static String dateOf(Map<String, Object> row) {
        return String.valueOf(row.get("publish_date"));
    }

    // ---------------------------------------------------------------- 汇总

    /** {@code stock/analyst_views.py:_latest_by_org}。 */
    private static List<Map<String, Object>> latestByOrg(List<Map<String, Object>> window) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> report : window) {
            String org = DatacenterSupport.text(report.get("org"));
            if (org == null) {
                org = "未知机构";
            }
            if (!seen.add(org)) {
                continue;
            }
            items.add(report);
        }
        return items;
    }

    /** {@code stock/analyst_views.py:_counts}。 */
    private static Map<String, Object> counts(
            List<Map<String, Object>> window,
            List<Map<String, Object>> latestByOrg,
            int year) {
        Map<String, Integer> ratings = new LinkedHashMap<>();
        List<Double> prices = new ArrayList<>();
        for (Map<String, Object> report : latestByOrg) {
            String rating = DatacenterSupport.text(report.get("rating"));
            if (rating == null) {
                rating = "未评级";
            }
            ratings.merge(rating, 1, Integer::sum);
            Double price = DatacenterSupport.number(report.get("target_price"));
            if (price != null) {
                prices.add(price);
            }
        }
        List<Map<String, Object>> ratingRows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ratings.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rating", entry.getKey());
            row.put("reports", entry.getValue());
            ratingRows.add(row);
        }
        ratingRows.sort(
                Comparator.comparingInt((Map<String, Object> row) -> -(int) row.get("reports"))
                        .thenComparing(row -> String.valueOf(row.get("rating"))));

        double low = prices.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double high = prices.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reports", window.size());
        out.put("orgs", latestByOrg.size());
        out.put("latest_date", window.isEmpty() ? null : dateOf(window.get(0)));
        out.put("ratings", ratingRows);
        out.put(
                "rating_changes",
                latestByOrg.stream()
                        .filter(row -> Boolean.TRUE.equals(row.get("rating_changed")))
                        .count());
        out.put("target_price_orgs", prices.size());
        out.put("target_price_low", prices.isEmpty() ? null : DatacenterSupport.round(low, 2));
        out.put("target_price_high", prices.isEmpty() ? null : DatacenterSupport.round(high, 2));
        out.put("target_price_median", DatacenterSupport.round(median(prices), 2));
        out.put("consensus_current_year", consensus(latestByOrg, year));
        out.put("consensus_next_year", consensus(latestByOrg, year + 1));
        return out;
    }

    /** {@code stock/analyst_views.py:_consensus}。 */
    private static Map<String, Object> consensus(List<Map<String, Object>> latestByOrg, int year) {
        List<Double> sameYear = new ArrayList<>();
        List<Double> nextYear = new ArrayList<>();
        for (Map<String, Object> report : latestByOrg) {
            Double reportYear = DatacenterSupport.number(report.get("year"));
            int value = reportYear == null ? 0 : (int) (double) reportYear;
            if (value == year) {
                Double eps = DatacenterSupport.number(report.get("eps_this_year"));
                if (eps != null) {
                    sameYear.add(eps);
                }
            }
            if (value == year - 1) {
                Double eps = DatacenterSupport.number(report.get("eps_next_year"));
                if (eps != null) {
                    nextYear.add(eps);
                }
            }
        }
        List<Double> values = sameYear.isEmpty() ? nextYear : sameYear;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("eps", DatacenterSupport.round(median(values), 4));
        out.put("orgs", values.size());
        return out;
    }

    /** {@code stock/analyst_views.py:_notes}。 */
    private static List<String> notes(
            List<Map<String, Object>> rows, List<Map<String, Object>> window, int days) {
        if (rows.isEmpty()) {
            return List.of("没有取到研报，可能近一段时间没有机构覆盖");
        }
        if (window.isEmpty()) {
            return List.of(
                    windowText(days) + "没有研报覆盖；最近一篇为" + dateOf(rows.get(0)),
                    "研报覆盖少不等于公司不好，只说明卖方关注度低");
        }
        return List.of(
                "卖方研报带乐观倾向，评级与目标价不是独立事实",
                "目标价只覆盖部分研报，且多为报告发布时点的判断",
                "EPS 预测是报告发布时的估计，会随财报和新报告修订");
    }

    private static String windowText(int days) {
        return days == 365 ? "近1年" : "近" + days + "天";
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> ordered = new ArrayList<>(values);
        ordered.sort(Double::compareTo);
        int middle = ordered.size() / 2;
        if (ordered.size() % 2 == 1) {
            return ordered.get(middle);
        }
        return (ordered.get(middle - 1) + ordered.get(middle)) / 2.0;
    }

    // ---------------------------------------------------------------- 整形

    /** {@code assistant/tools.py:_report_row}。 */
    private static Map<String, Object> reportRow(Map<String, Object> item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publish_date", item.get("publish_date"));
        row.put("year", item.get("year"));
        row.put("org", item.get("org"));
        row.put("researcher", item.get("researcher"));
        String title = titleOf(item.get("title"));
        row.put("title", title == null || title.isEmpty() ? null : title);
        row.put("rating", item.get("rating"));
        row.put("last_rating", item.get("last_rating"));
        row.put("rating_changed", Boolean.TRUE.equals(item.get("rating_changed")));
        row.put("target_price", DatacenterSupport.round(item.get("target_price"), 2));
        row.put("eps_this_year", DatacenterSupport.round(item.get("eps_this_year"), 4));
        row.put("eps_next_year", DatacenterSupport.round(item.get("eps_next_year"), 4));
        row.put("eps_next2_year", DatacenterSupport.round(item.get("eps_next2_year"), 4));
        row.put("pe_this_year", DatacenterSupport.round(item.get("pe_this_year"), 2));
        row.put("url", item.get("url"));
        return row;
    }

    /** {@code _clip(text, 80)}。 */
    private static String titleOf(Object value) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 80).replaceAll("[，、；： ]+$", "") + "…";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    // ---------------------------------------------------------------- 取数

    private JsonNode fetch(String code, int days) {
        String end = LocalDate.now(clock).toString();
        String begin = LocalDate.now(clock).minusDays(days).toString();
        List<Map<String, Object>> reports = new ArrayList<>();
        boolean truncated = false;
        int page = 1;
        int pages = 1;
        while (page <= MAX_PAGES) {
            JsonNode payload = getPage(code, begin, end, page);
            JsonNode data = payload.get("data");
            if (data == null || !data.isArray()) {
                throw new IllegalStateException(code + "：研报接口数据格式异常");
            }
            for (JsonNode item : data) {
                if (item.isObject()) {
                    Map<String, Object> normalized = normalizeRow(item);
                    if (normalized != null) {
                        reports.add(normalized);
                    }
                }
            }
            pages = intOr(payload.get("TotalPage"));
            if (page >= pages) {
                break;
            }
            page++;
        }
        // Python 的 while/else：页数被 MAX_PAGES 截断才算 truncated
        truncated = page > MAX_PAGES;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("days", days);
        out.put("reports", reports);
        out.put("count", reports.size());
        out.put("truncated", truncated);
        return EastmoneySupport.MAPPER.valueToTree(out);
    }

    private JsonNode getPage(String code, String begin, String end, int page) {
        String url =
                ENDPOINT
                        + "?industryCode=*&pageSize="
                        + PAGE_SIZE
                        + "&industry=*&rating=&ratingChange=&beginTime="
                        + begin
                        + "&endTime="
                        + end
                        + "&pageNo="
                        + page
                        + "&fields=&qType=0&orgCode=&code="
                        + code
                        + "&rcode=&p="
                        + page
                        + "&pageNum="
                        + page;
        try {
            JsonNode payload =
                    EastmoneySupport.MAPPER.readTree(
                            upstream.get(
                                    url,
                                    Map.of(
                                            "User-Agent", "Mozilla/5.0",
                                            "Referer", "https://data.eastmoney.com/report/")));
            if (payload == null || !payload.isObject()) {
                throw new IllegalStateException(code + "：研报接口返回格式异常");
            }
            return payload;
        } catch (IllegalStateException error) {
            throw error;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("获取研报数据失败：" + error.getMessage(), error);
        }
    }

    private static int intOr(JsonNode node) {
        if (node == null || node.isNull()) {
            return 1;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText().strip());
        } catch (NumberFormatException error) {
            return 1;
        }
    }

    /** {@code stock/analyst_views.py:_normalize_row}。 */
    static Map<String, Object> normalizeRow(JsonNode row) {
        String publishDate = date(row.get("publishDate"));
        if (publishDate == null) {
            return null;
        }
        String org = EastmoneySupport.text(row.get("orgSName"));
        if (org == null) {
            org = EastmoneySupport.text(row.get("orgName"));
        }
        String rating = EastmoneySupport.text(row.get("emRatingName"));
        String lastRating = EastmoneySupport.text(row.get("lastEmRatingName"));
        String infoCode = EastmoneySupport.text(row.get("infoCode"));
        String stockCode = row.get("stockCode") == null ? "" : row.get("stockCode").asText();
        Double aimHigh = EastmoneySupport.num(row.get("indvAimPriceT"));
        Double aimLow = EastmoneySupport.num(row.get("indvAimPriceL"));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("publish_date", publishDate);
        item.put("year", Integer.parseInt(publishDate.substring(0, 4)));
        item.put("org", org);
        item.put("researcher", EastmoneySupport.text(row.get("researcher")));
        item.put("title", EastmoneySupport.text(row.get("title")));
        item.put("rating", rating);
        item.put("last_rating", lastRating);
        item.put(
                "rating_changed",
                rating != null && lastRating != null && !rating.equals(lastRating));
        // Python 的 `a or b`：第一个为 0.0 也落到下一个
        item.put(
                "target_price",
                aimHigh != null && aimHigh != 0.0 ? aimHigh : aimLow);
        item.put("eps_this_year", EastmoneySupport.num(row.get("predictThisYearEps")));
        item.put("eps_next_year", EastmoneySupport.num(row.get("predictNextYearEps")));
        item.put("eps_next2_year", EastmoneySupport.num(row.get("predictNextTwoYearEps")));
        item.put("pe_this_year", EastmoneySupport.num(row.get("predictThisYearPe")));
        item.put("info_code", infoCode);
        item.put(
                "url",
                infoCode != null
                        ? INFO_URL.formatted(infoCode)
                        : PAGE_URL.formatted(stockCode));
        item.put("stock_name", EastmoneySupport.text(row.get("stockName")));
        return item;
    }

    private static String date(JsonNode node) {
        String text = EastmoneySupport.text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }
}
