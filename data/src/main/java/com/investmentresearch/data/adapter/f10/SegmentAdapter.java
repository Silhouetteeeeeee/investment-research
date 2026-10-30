package com.investmentresearch.data.adapter.f10;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.SegmentPort;
import com.investmentresearch.data.persistence.BizSegmentEntity;
import com.investmentresearch.data.persistence.BizSegmentMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 东财 F10 主营构成：上游 → 解析 → 整份替换 → 读回 → 输出。 */
@Repository
public class SegmentAdapter implements SegmentPort {
    static final String[] PREFERRED_KINDS = {"2", "3", "1"};
    private static final String[][] SLICE_KINDS = {{"2", "product"}, {"3", "region"}};
    private static final Map<String, String> KIND_LABELS =
            Map.of("2", "产品", "3", "地区", "1", "行业");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BizSegmentMapper mapper;
    private final StockMapper stockMapper;
    private final UpstreamClient upstreamClient;
    private final String endpoint;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public SegmentAdapter(
            BizSegmentMapper mapper,
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            @Value(
                            "${app.f10-segments-url:"
                                    + "https://emweb.securities.eastmoney.com"
                                    + "/PC_HSF10/BusinessAnalysis/PageAjax}")
                    String endpoint,
            PlatformTransactionManager transactionManager) {
        this(
                mapper,
                stockMapper,
                upstreamClient,
                endpoint,
                Clock.systemDefaultZone(),
                transactionManager);
    }

    SegmentAdapter(
            BizSegmentMapper mapper,
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            String endpoint,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.stockMapper = stockMapper;
        this.upstreamClient = upstreamClient;
        this.endpoint = endpoint;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ToolResult segments(String rawCode, int years) {
        int window = Math.min(Math.max(years, 1), 10);
        String code = F10Support.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        String prefix = F10Support.prefix(code);
        if ("UNKNOWN".equals(prefix)) {
            return ToolResult.failure(code, code + "：无法判断交易所");
        }

        try {
            List<BizSegmentEntity> rows;
            List<BizSegmentEntity> cached = readRows(code);
            if (fresh(cached)) {
                rows = cached;
            } else {
                try {
                    rows = fetch(code, prefix);
                    if (!rows.isEmpty()) {
                        save(code, rows);
                        // 读回刚写入的行，和 Python _cached_rows 的排序/类型保持一致
                        rows = readRows(code);
                    }
                } catch (RuntimeException error) {
                    if (cached.isEmpty()) {
                        return ToolResult.failure(code, error.getMessage());
                    }
                    rows = cached;
                }
                if (rows.isEmpty() && !cached.isEmpty()) {
                    rows = cached;
                }
            }
            if (rows.isEmpty()) {
                return ToolResult.failure(code, code + "：没有主营构成数据");
            }
            return assemble(code, rows, window);
        } catch (RuntimeException error) {
            // 端口不抛异常：读库或组装出的意外错误同样折成失败
            return ToolResult.failure(code, error.getMessage());
        }
    }

    private ToolResult assemble(String code, List<BizSegmentEntity> rows, int years) {
        String name = stockMapper.selectNameByCode(code);
        String fetchedAt =
                rows.stream()
                        .map(BizSegmentEntity::getFetchedAt)
                        .filter(Objects::nonNull)
                        .max(String::compareTo)
                        .orElse("");

        List<Map<String, Object>> slices = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        for (String[] sliceKind : SLICE_KINDS) {
            Piece piece = segmentFromRows(rows, sliceKind[0], years);
            // 口径缺失时 segments_from_rows 会回退到别的口径，回退结果不能冒充本切片
            if (piece == null || !sliceKind[0].equals(piece.kind())) {
                continue;
            }
            Map<String, Object> slice = new LinkedHashMap<>();
            slice.put("kind", sliceKind[1]);
            slice.put("label", piece.label());
            slice.put("total_income_yi", F10Support.yi(piece.totalIncome()));
            slice.put("items", piece.items().stream().map(SegmentAdapter::itemMap).toList());
            slice.put(
                    "history",
                    piece.history().stream().map(SegmentAdapter::periodMap).toList());
            slices.add(slice);
            String label = "主营构成·" + piece.label();
            sources.add(
                    new Source(
                            "segments:" + code + ":" + piece.asOf() + ":" + sliceKind[1],
                            label,
                            F10Support.py(name) + " " + piece.asOf() + " 按" + piece.label(),
                            piece.asOf(),
                            fetchedAt));
        }
        if (slices.isEmpty()) {
            return ToolResult.failure(code, code + "：没有可用的主营构成");
        }
        String asOf = sources.get(0).asOf();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", asOf);
        data.put("slices", slices);
        return ToolResult.ok(code, asOf, sources, data);
    }

    private static Map<String, Object> itemMap(Item item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", item.name());
        out.put("income_yi", F10Support.yi(item.income()));
        out.put("share", F10Support.pct(item.share()));
        out.put("yoy", F10Support.pct(item.yoy()));
        out.put("gross_margin", F10Support.pct(item.grossMargin()));
        return out;
    }

    private static Map<String, Object> periodMap(Period period) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period.period());
        out.put("total_income_yi", F10Support.yi(period.totalIncome()));
        out.put("items", period.items().stream().map(SegmentAdapter::itemMap).toList());
        return out;
    }

    /** 照抄 {@code stock/segments.py:segments_from_rows}（flags/summary 不进入工具输出）。 */
    private static Piece segmentFromRows(List<BizSegmentEntity> rows, String kind, int years) {
        List<BizSegmentEntity> filtered =
                rows.stream()
                        .filter(
                                row ->
                                        row.getReportDate() != null
                                                && row.getItemName() != null)
                        .toList();
        if (filtered.isEmpty()) {
            return null;
        }
        Latest latest = latestSlice(filtered, kind);
        if (latest == null) {
            return null;
        }

        double total = 0;
        for (BizSegmentEntity row : latest.items()) {
            if (row.getIncome() != null) {
                total += row.getIncome();
            }
        }

        String previousDate = previousYear(latest.date());
        Map<String, BizSegmentEntity> previous = new LinkedHashMap<>();
        for (BizSegmentEntity row : filtered) {
            if (previousDate.equals(row.getReportDate())
                    && latest.kind().equals(row.getKind())
                    && !isTotal(row.getItemName())
                    && !isSubItem(row.getItemName())) {
                previous.put(row.getItemName(), row);
            }
        }
        List<BizSegmentEntity> sorted = new ArrayList<>(latest.items());
        sorted.sort(byIncomeDesc());
        List<Item> items = new ArrayList<>();
        for (BizSegmentEntity row : sorted) {
            Double income = row.getIncome();
            Double share = total != 0 && income != null ? income / total * 100.0 : row.getShare();
            BizSegmentEntity before = previous.get(row.getItemName());
            Double beforeIncome = before == null ? null : before.getIncome();
            Double yoy = null;
            if (beforeIncome != null && beforeIncome != 0 && income != null) {
                yoy = (income - beforeIncome) / beforeIncome * 100.0;
            }
            items.add(
                    new Item(
                            row.getItemName(),
                            income,
                            share,
                            yoy,
                            row.getGrossMargin()));
        }

        return new Piece(
                latest.kind(),
                KIND_LABELS.getOrDefault(latest.kind(), "构成"),
                latest.date(),
                total != 0 ? total : null,
                items,
                segmentHistory(filtered, latest.kind(), years));
    }

    /** 照抄 {@code stock/segments.py:segment_history}。 */
    private static List<Period> segmentHistory(
            List<BizSegmentEntity> rows, String kind, int years) {
        List<BizSegmentEntity> valid =
                rows.stream()
                        .filter(
                                row ->
                                        row.getReportDate() != null
                                                && row.getKind() != null
                                                && row.getItemName() != null
                                                && isSegmentItem(row))
                        .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        Latest latest = latestSlice(valid, kind);
        if (latest == null) {
            return List.of();
        }
        int window = Math.min(Math.max(years, 1), 10);

        TreeSet<String> dateSet = new TreeSet<>();
        for (BizSegmentEntity row : valid) {
            if (latest.kind().equals(row.getKind()) && isSegmentItem(row)) {
                dateSet.add(row.getReportDate());
            }
        }
        List<String> dates = new ArrayList<>(dateSet);
        String suffix = latest.date().substring(4);
        List<String> comparable =
                dates.stream().filter(date -> date.endsWith(suffix)).toList();
        if (comparable.isEmpty()) {
            comparable = dates;
        }
        List<String> periods =
                comparable.subList(Math.max(0, comparable.size() - window), comparable.size());

        List<Period> history = new ArrayList<>();
        Map<String, BizSegmentEntity> previous = new LinkedHashMap<>();
        for (String period : periods) {
            List<BizSegmentEntity> current = new ArrayList<>();
            for (BizSegmentEntity row : valid) {
                if (period.equals(row.getReportDate()) && latest.kind().equals(row.getKind())) {
                    current.add(row);
                }
            }
            double total = 0;
            for (BizSegmentEntity row : current) {
                if (row.getIncome() != null) {
                    total += row.getIncome();
                }
            }
            List<BizSegmentEntity> sorted = new ArrayList<>(current);
            sorted.sort(byIncomeDesc());
            List<Item> items = new ArrayList<>();
            for (BizSegmentEntity row : sorted) {
                Double income = row.getIncome();
                Double share =
                        total != 0 && income != null ? income / total * 100.0 : row.getShare();
                BizSegmentEntity before = previous.get(row.getItemName());
                Double beforeIncome = before == null ? null : before.getIncome();
                Double yoy = null;
                if (beforeIncome != null && beforeIncome != 0 && income != null) {
                    yoy = (income - beforeIncome) / beforeIncome * 100.0;
                }
                items.add(
                        new Item(
                                row.getItemName(),
                                income,
                                share,
                                yoy,
                                row.getGrossMargin()));
            }
            history.add(new Period(period, total != 0 ? total : null, items));
            previous = new LinkedHashMap<>();
            for (BizSegmentEntity row : current) {
                previous.put(row.getItemName(), row);
            }
        }
        return history;
    }

    /** 照抄 {@code stock/segments.py:_latest_slice}。 */
    private static Latest latestSlice(List<BizSegmentEntity> rows, String kind) {
        TreeSet<String> dateSet =
                new TreeSet<>(Comparator.reverseOrder());
        for (BizSegmentEntity row : rows) {
            dateSet.add(row.getReportDate());
        }
        List<List<String>> passes = new ArrayList<>();
        if (kind != null) {
            passes.add(List.of(kind));
        }
        passes.add(List.of(PREFERRED_KINDS));
        for (List<String> kinds : passes) {
            for (String date : dateSet) {
                for (String candidate : kinds) {
                    List<BizSegmentEntity> items = new ArrayList<>();
                    for (BizSegmentEntity row : rows) {
                        if (date.equals(row.getReportDate())
                                && candidate.equals(row.getKind())
                                && row.getIncome() != null
                                && !isTotal(row.getItemName())
                                && !isSubItem(row.getItemName())) {
                            items.add(row);
                        }
                    }
                    if (!items.isEmpty()) {
                        return new Latest(date, candidate, items);
                    }
                }
            }
        }
        return null;
    }

    private List<BizSegmentEntity> readRows(String code) {
        // 跟 Python _cached_rows 的 ORDER BY 完全一致；income 相同的行保持插入顺序，
        // 故意不加 tiebreaker，否则和金标准的行序会漂
        return mapper.selectList(
                Wrappers.<BizSegmentEntity>query()
                        .eq("code", code)
                        .orderByDesc("report_date")
                        .orderByAsc("kind")
                        .orderByDesc("income"));
    }

    private List<BizSegmentEntity> fetch(String code, String prefix) {
        String url = endpoint + "?code=" + code + "." + prefix;
        JsonNode payload;
        try {
            payload = MAPPER.readTree(upstreamClient.get(url, Map.of()));
        } catch (JsonProcessingException error) {
            throw new F10Exception("获取主营构成失败：" + error.getMessage());
        } catch (RuntimeException error) {
            throw new F10Exception("获取主营构成失败：" + error.getMessage());
        }
        JsonNode rawRows = payload == null ? null : payload.path("zygcfx");
        if (rawRows == null || !rawRows.isArray()) {
            return List.of();
        }
        String now = LocalDateTime.now(clock).format(TIMESTAMP);
        List<BizSegmentEntity> rows = new ArrayList<>();
        for (JsonNode raw : rawRows) {
            String reportDate = dateText(raw.get("REPORT_DATE"));
            String kind = F10Support.text(raw.get("MAINOP_TYPE"));
            String name = F10Support.text(raw.get("ITEM_NAME"));
            if (reportDate == null || kind == null || name == null) {
                continue;
            }
            BizSegmentEntity row = new BizSegmentEntity();
            row.setCode(code);
            row.setReportDate(reportDate);
            row.setKind(kind);
            row.setItemName(name);
            row.setIncome(F10Support.num(raw.get("MAIN_BUSINESS_INCOME")));
            row.setShare(percent(raw.get("MBI_RATIO")));
            row.setCost(F10Support.num(raw.get("MAIN_BUSINESS_COST")));
            row.setProfit(F10Support.num(raw.get("MAIN_BUSINESS_RPOFIT")));
            row.setGrossMargin(percent(raw.get("GROSS_RPOFIT_RATIO")));
            row.setRank(F10Support.num(raw.get("RANK")));
            row.setFetchedAt(now);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 与 Python {@code with conn:} + INSERT OR REPLACE 等价：删+插在一个事务里。
     * biz_segment 的复合主键 MyBatis-Plus 表达不了（@TableId 只有 code），
     * 所以不用 insertOrUpdate——它只会按 code 更新，删后插入才安全。
     */
    private void save(String code, List<BizSegmentEntity> rows) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    mapper.delete(Wrappers.<BizSegmentEntity>query().eq("code", code));
                    for (BizSegmentEntity row : rows) {
                        mapper.insert(row);
                    }
                });
    }

    /**
     * 按会用到的切片口径（产品/地区）分别判断是否已有最新应有报告期：
     * 东财各口径披露有先后（601606 地区还停在 2025-12-31），按全局最大报告期会漏掉后到的口径。
     * 某口径在缓存里从未出现过则不阻塞，避免上游本来就没有该口径时每次调用都重抓。
     * 口径落后但该口径 24 小时内抓过，则按新鲜处理——保留 Python 的 24 小时 TTL，
     * 否则停更的口径会让每次工具调用都打上游。
     */
    private boolean fresh(List<BizSegmentEntity> rows) {
        LocalDateTime now = LocalDateTime.now(clock);
        String expected = expectedReportDate(now.toLocalDate()).toString();
        boolean anySliceKind = false;
        for (String[] slice : SLICE_KINDS) {
            String kind = slice[0];
            String newest = newestDate(rows, kind);
            if (newest == null) {
                continue;
            }
            anySliceKind = true;
            if (newest.compareTo(expected) >= 0) {
                continue;
            }
            if (!within24Hours(newestFetchedAt(rows, kind), now)) {
                return false;
            }
        }
        if (anySliceKind) {
            return true;
        }
        // 只有行业口径等非切片数据时退回全局最新报告期
        String newest = newestDate(rows, null);
        return newest != null
                && (newest.compareTo(expected) >= 0
                        || within24Hours(newestFetchedAt(rows, null), now));
    }

    private static String newestDate(List<BizSegmentEntity> rows, String kind) {
        String newest = null;
        for (BizSegmentEntity row : rows) {
            if ((kind == null || kind.equals(row.getKind()))
                    && row.getReportDate() != null
                    && (newest == null || row.getReportDate().compareTo(newest) > 0)) {
                newest = row.getReportDate();
            }
        }
        return newest;
    }

    private static String newestFetchedAt(List<BizSegmentEntity> rows, String kind) {
        String newest = null;
        for (BizSegmentEntity row : rows) {
            if ((kind == null || kind.equals(row.getKind()))
                    && row.getFetchedAt() != null
                    && (newest == null || row.getFetchedAt().compareTo(newest) > 0)) {
                newest = row.getFetchedAt();
            }
        }
        return newest;
    }

    private static boolean within24Hours(String fetchedAt, LocalDateTime now) {
        if (fetchedAt == null) {
            return false;
        }
        try {
            Duration age = Duration.between(LocalDateTime.parse(fetchedAt, TIMESTAMP), now);
            return age.compareTo(Duration.ofHours(24)) < 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    /**
     * 最新应有报告期：披露截止日（一季报 4/30、半年报 8/31、三季报 10/31、年报次年 4/30）已过的
     * 最近报告期。缓存里没有这一期就重抓，替代 Python 的 24 小时 TTL。
     */
    static LocalDate expectedReportDate(LocalDate today) {
        LocalDate best = null;
        for (int year = today.getYear() - 1; year <= today.getYear(); year++) {
            best = later(best, LocalDate.of(year, 3, 31), LocalDate.of(year, 4, 30), today);
            best = later(best, LocalDate.of(year, 6, 30), LocalDate.of(year, 8, 31), today);
            best = later(best, LocalDate.of(year, 9, 30), LocalDate.of(year, 10, 31), today);
            best = later(best, LocalDate.of(year, 12, 31), LocalDate.of(year + 1, 4, 30), today);
        }
        return best;
    }

    private static LocalDate later(
            LocalDate best, LocalDate period, LocalDate deadline, LocalDate today) {
        if (!deadline.isAfter(today) && (best == null || period.isAfter(best))) {
            return period;
        }
        return best;
    }

    private static Comparator<BizSegmentEntity> byIncomeDesc() {
        return Comparator.comparingDouble(
                        (BizSegmentEntity row) -> row.getIncome() == null ? 0.0 : row.getIncome())
                .reversed();
    }

    private static boolean isSegmentItem(BizSegmentEntity row) {
        return row.getIncome() != null
                && !isTotal(row.getItemName())
                && !isSubItem(row.getItemName());
    }

    private static boolean isTotal(String name) {
        String value = name.strip();
        return value.equals("合计") || value.equals("总计");
    }

    private static boolean isSubItem(String name) {
        String value = name.strip();
        return value.startsWith("其中:") || value.startsWith("其中：");
    }

    private static String previousYear(String reportDate) {
        try {
            int year = Integer.parseInt(reportDate.substring(0, 4));
            return (year - 1) + reportDate.substring(4);
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static String dateText(JsonNode node) {
        String text = F10Support.text(node);
        return text == null ? null : text.substring(0, Math.min(10, text.length()));
    }

    /** Python {@code _percent}：接口给 0~1 小数，统一换成百分数。 */
    private static Double percent(JsonNode node) {
        Double number = F10Support.num(node);
        return number == null ? null : number * 100.0;
    }

    private record Latest(String date, String kind, List<BizSegmentEntity> items) {}

    private record Item(String name, Double income, Double share, Double yoy, Double grossMargin) {}

    private record Period(String period, Double totalIncome, List<Item> items) {}

    private record Piece(
            String kind,
            String label,
            String asOf,
            Double totalIncome,
            List<Item> items,
            List<Period> history) {}

    /** 解析失败也要带中文原因走到 ToolResult.failure，不能逃出端口。 */
    private static final class F10Exception extends RuntimeException {
        F10Exception(String message) {
            super(message);
        }
    }
}
