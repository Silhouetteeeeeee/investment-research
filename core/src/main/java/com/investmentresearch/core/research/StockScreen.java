package com.investmentresearch.core.research;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.domain.screen.ScreenFilter;
import com.investmentresearch.core.domain.screen.ScreenFinance;
import com.investmentresearch.core.domain.screen.ScreenQuote;
import com.investmentresearch.core.domain.screen.ScreenSort;
import com.investmentresearch.core.domain.screen.StockScreenQuery;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全市场参数化筛选的纯计算：校验条件，合并行情与财务，过滤、多级排序、截断。
 *
 * <p>不做 IO；行情与财务由调用方读快照后传入。缺值一律视为不满足，不拿 0 顶替。
 */
public final class StockScreen {
    public static final int MAX_FILTERS = 10;
    public static final int MAX_SORTS = 3;
    public static final int MAX_LIMIT = 50;

    public static final List<String> FILTER_FIELDS =
            List.of(
                    "pe", "pb", "market_cap", "price", "chg_pct", "turnover", "amount", "ps", "roe", "gross_margin",
                    "net_margin", "revenue_growth", "profit_growth", "ocf_per_share", "ocf_to_profit",
                    "ocf_positive_years", "boards");
    public static final List<String> SORT_FIELDS =
            FILTER_FIELDS.stream().filter(field -> !"boards".equals(field)).toList();
    public static final List<String> OPS = List.of(">", ">=", "<", "<=", "=");
    public static final List<String> NOTES = List.of("缺少的指标按不满足处理", "ROE 与增速为报告期累计值");

    private static final List<String> FINANCE_FIELDS =
            List.of(
                    "ps", "roe", "gross_margin", "net_margin", "revenue_growth", "profit_growth", "ocf_per_share",
                    "ocf_to_profit", "ocf_positive_years");

    private StockScreen() {}

    /** 按 6 位代码前缀判断板块；只用于筛选与展示。 */
    public static String boardOf(String code) {
        String value = code == null ? "" : code.strip();
        String prefix = value.length() >= 3 ? value.substring(0, 3) : value;
        return switch (prefix) {
            case "600", "601", "603", "605" -> "沪市主板";
            case "000", "001", "002", "003" -> "深市主板";
            case "300", "301" -> "创业板";
            case "688", "689" -> "科创板";
            default -> "其他";
        };
    }

    public static ToolResult screen(
            StockScreenQuery query,
            List<ScreenQuote> quotes,
            List<ScreenFinance> finances,
            String asOf,
            String fetchedAt,
            LocalDate today) {
        List<ScreenFilter> filters = query.filters() == null ? List.of() : query.filters();
        List<ScreenSort> sorts = query.sort() == null ? List.of() : query.sort();

        String invalid = validate(filters, sorts);
        if (invalid != null) {
            return ToolResult.failure(null, invalid);
        }
        if (needsFinance(filters, sorts) && (finances == null || finances.isEmpty())) {
            return ToolResult.failure(null, "筛选失败：财务数据尚未同步");
        }

        List<ScreenQuote> rows = quotes == null ? List.of() : quotes;
        Map<String, List<ScreenFinance>> historyByCode = new HashMap<>();
        for (ScreenFinance finance : finances == null ? List.<ScreenFinance>of() : finances) {
            if (finance != null && finance.code() != null) {
                historyByCode.computeIfAbsent(finance.code(), key -> new ArrayList<>()).add(finance);
            }
        }

        Map<String, Integer> excluded = new LinkedHashMap<>();
        List<Row> matched = new ArrayList<>();
        for (ScreenQuote quote : rows) {
            if (quote == null || quote.code() == null) {
                continue;
            }
            String reason = exclusionReason(quote);
            if (reason != null) {
                excluded.merge(reason, 1, Integer::sum);
                continue;
            }
            Row row = buildRow(quote, historyByCode.get(quote.code()));
            boolean keep = true;
            for (ScreenFilter filter : filters) {
                if (!matches(row, filter)) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                matched.add(row);
            }
        }

        Comparator<Row> comparator = (a, b) -> 0;
        for (ScreenSort sort : sorts) {
            boolean desc = sort.order() == null || "desc".equals(sort.order());
            comparator = comparator.thenComparing(
                    (a, b) -> compare(a.metrics().get(sort.field()), b.metrics().get(sort.field()), desc));
        }
        comparator = comparator.thenComparing(row -> row.quote().code());
        matched.sort(comparator);

        int limit = Math.max(1, Math.min(MAX_LIMIT, query.limit()));
        List<Row> picked = matched.subList(0, Math.min(limit, matched.size()));

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        for (Row row : picked) {
            candidates.add(row.candidate());
            String code = row.quote().code();
            String name = row.quote().name() == null ? code : row.quote().name();
            sources.add(
                    new Source(
                            "quote:" + code + ":" + row.quote().tradeDate(),
                            "行情快照",
                            name + " 行情",
                            row.quote().tradeDate(),
                            fetchedAt == null ? "" : fetchedAt));
            String reportDate = text(row.candidate().get("report_date"));
            if (!reportDate.isEmpty()) {
                sources.add(
                        new Source(
                                "finance:" + code + ":" + reportDate,
                                "财务报告",
                                name + " 财报 " + reportDate,
                                reportDate,
                                fetchedAt == null ? "" : fetchedAt));
            }
        }

        Map<String, Object> universe = new LinkedHashMap<>();
        universe.put("scanned", rows.size());
        universe.put("excluded", excluded);
        universe.put("after_filters", matched.size());

        List<String> notes = new ArrayList<>(NOTES);
        if (asOf != null && today != null) {
            try {
                if (LocalDate.parse(asOf).isBefore(today.minusDays(3))) {
                    notes.add("行情为 " + asOf + " 收盘数据");
                }
            } catch (DateTimeParseException ignored) {
                // 非 ISO 日期无法判断时效，不加提示
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "filters");
        data.put("as_of", asOf);
        data.put("filters", filtersView(filters));
        data.put("sort", sortView(sorts));
        data.put("universe", universe);
        data.put("candidates", candidates);
        data.put("notes", notes);
        return ToolResult.ok(null, asOf, sources, data);
    }

    private static String validate(List<ScreenFilter> filters, List<ScreenSort> sorts) {
        if (filters.size() > MAX_FILTERS) {
            return "筛选失败：filters 最多 " + MAX_FILTERS + " 条";
        }
        if (sorts.size() > MAX_SORTS) {
            return "筛选失败：sort 最多 " + MAX_SORTS + " 条";
        }
        for (ScreenFilter filter : filters) {
            if (filter == null || filter.field() == null || filter.op() == null) {
                return "筛选失败：filters 格式错误";
            }
            if (!FILTER_FIELDS.contains(filter.field())) {
                return "筛选失败：未知字段 " + filter.field() + "；合法字段：" + String.join(", ", FILTER_FIELDS);
            }
            if ("boards".equals(filter.field())) {
                if (!"in".equals(filter.op())) {
                    return "筛选失败：字段 boards 只支持操作符 in；合法操作符：in";
                }
                if (boardValues(filter.value()) == null) {
                    return "筛选失败：字段 boards 的值必须是板块名或板块名列表";
                }
            } else {
                if (!OPS.contains(filter.op())) {
                    return "筛选失败：字段 " + filter.field() + " 的操作符非法；合法操作符：" + String.join(", ", OPS);
                }
                if (SectorRotation.num(filter.value()) == null) {
                    return "筛选失败：字段 " + filter.field() + " 的值非法";
                }
            }
        }
        for (ScreenSort sort : sorts) {
            if (sort == null || sort.field() == null) {
                return "筛选失败：sort 格式错误";
            }
            if (!SORT_FIELDS.contains(sort.field())) {
                return "筛选失败：字段 " + sort.field() + " 不可排序；合法排序字段：" + String.join(", ", SORT_FIELDS);
            }
            if (sort.order() != null && !"asc".equals(sort.order()) && !"desc".equals(sort.order())) {
                return "筛选失败：排序方向 " + sort.order() + " 非法；合法方向：asc, desc";
            }
        }
        return null;
    }

    private static boolean needsFinance(List<ScreenFilter> filters, List<ScreenSort> sorts) {
        for (ScreenFilter filter : filters) {
            if (FINANCE_FIELDS.contains(filter.field())) {
                return true;
            }
        }
        for (ScreenSort sort : sorts) {
            if (FINANCE_FIELDS.contains(sort.field())) {
                return true;
            }
        }
        return false;
    }

    private static String exclusionReason(ScreenQuote quote) {
        String name = quote.name() == null ? "" : quote.name();
        if (name.toUpperCase().contains("ST") || name.contains("退")) {
            return "ST/退市";
        }
        if (quote.price() == null || quote.price() <= 0) {
            return "无报价";
        }
        return null;
    }

    private static Row buildRow(ScreenQuote quote, List<ScreenFinance> history) {
        List<ScreenFinance> sorted = history == null ? new ArrayList<>() : new ArrayList<>(history);
        sorted.sort(
                Comparator.comparing(
                                (ScreenFinance finance) -> finance.reportDate() == null ? "" : finance.reportDate())
                        .reversed());
        ScreenFinance latest = sorted.isEmpty() ? null : sorted.get(0);

        List<ScreenFinance> annual = new ArrayList<>();
        for (ScreenFinance finance : sorted) {
            if (finance.reportDate() != null && finance.reportDate().endsWith("-12-31")) {
                annual.add(finance);
                if (annual.size() == 3) {
                    break;
                }
            }
        }
        Integer positiveYears = null;
        if (!annual.isEmpty()) {
            int count = 0;
            for (ScreenFinance finance : annual) {
                if (finance.ocfPerShare() != null && finance.ocfPerShare() > 0) {
                    count++;
                }
            }
            positiveYears = count;
        }

        Double ps = null;
        if (!annual.isEmpty()
                && quote.totalCapYi() != null
                && annual.get(0).revenue() != null
                && annual.get(0).revenue() > 0) {
            ps = round(quote.totalCapYi() * 1e8 / annual.get(0).revenue());
        }
        Double netMargin = null;
        if (latest != null
                && latest.revenue() != null
                && latest.revenue() > 0
                && latest.netProfit() != null) {
            netMargin = round(latest.netProfit() / latest.revenue() * 100);
        }
        Double ocfToProfit = null;
        if (latest != null && latest.eps() != null && latest.eps() > 0 && latest.ocfPerShare() != null) {
            ocfToProfit = round(latest.ocfPerShare() / latest.eps());
        }

        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("code", quote.code());
        candidate.put("name", quote.name());
        candidate.put("board", boardOf(quote.code()));
        candidate.put("industry", quote.industry());
        candidate.put("price", quote.price());
        candidate.put("market_cap", quote.totalCapYi());
        candidate.put("pe", quote.pe());
        candidate.put("pb", quote.pb());
        candidate.put("ps", ps);
        candidate.put("roe", latest == null ? null : latest.roe());
        candidate.put("gross_margin", latest == null ? null : latest.grossMargin());
        candidate.put("net_margin", netMargin);
        candidate.put("revenue_growth", latest == null ? null : latest.revenueYoy());
        candidate.put("profit_growth", latest == null ? null : latest.profitYoy());
        candidate.put("ocf_per_share", latest == null ? null : latest.ocfPerShare());
        candidate.put("ocf_to_profit", ocfToProfit);
        candidate.put("ocf_positive_years", positiveYears);
        candidate.put("report_date", latest == null ? null : latest.reportDate());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("boards", candidate.get("board"));
        metrics.put("pe", quote.pe());
        metrics.put("pb", quote.pb());
        metrics.put("market_cap", quote.totalCapYi());
        metrics.put("price", quote.price());
        metrics.put("chg_pct", quote.chgPct());
        metrics.put("turnover", quote.turnover());
        metrics.put("amount", quote.amountYi());
        for (String field : FINANCE_FIELDS) {
            metrics.put(field, candidate.get(field));
        }
        return new Row(quote, candidate, metrics, annual.size());
    }

    private static boolean matches(Row row, ScreenFilter filter) {
        Object actual = row.metrics().get(filter.field());
        if (actual == null) {
            return false;
        }
        if ("boards".equals(filter.field())) {
            return boardValues(filter.value()).contains((String) actual);
        }
        double right = SectorRotation.num(filter.value());
        if ("ocf_positive_years".equals(filter.field()) && right > row.annualKnown()) {
            // 已知年度数不足条件值时不能把「没披露」当成「为正」
            return false;
        }
        double left = ((Number) actual).doubleValue();
        return switch (filter.op()) {
            case ">" -> left > right;
            case ">=" -> left >= right;
            case "<" -> left < right;
            case "<=" -> left <= right;
            case "=" -> left == right;
            default -> false;
        };
    }

    private static List<String> boardValues(Object value) {
        if (value instanceof String text) {
            return List.of(text);
        }
        if (value instanceof Iterable<?> items) {
            List<String> values = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof String text)) {
                    return null;
                }
                values.add(text);
            }
            return values;
        }
        return null;
    }

    private static int compare(Object left, Object right, boolean desc) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        int result =
                Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        return desc ? -result : result;
    }

    private static List<Map<String, Object>> filtersView(List<ScreenFilter> filters) {
        List<Map<String, Object>> view = new ArrayList<>();
        for (ScreenFilter filter : filters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", filter.field());
            item.put("op", filter.op());
            item.put("value", filter.value());
            view.add(item);
        }
        return view;
    }

    private static List<Map<String, Object>> sortView(List<ScreenSort> sorts) {
        List<Map<String, Object>> view = new ArrayList<>();
        for (ScreenSort sort : sorts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", sort.field());
            item.put("order", sort.order() == null ? "desc" : sort.order());
            view.add(item);
        }
        return view;
    }

    private static Double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private record Row(
            ScreenQuote quote, Map<String, Object> candidate, Map<String, Object> metrics, int annualKnown) {}
}
