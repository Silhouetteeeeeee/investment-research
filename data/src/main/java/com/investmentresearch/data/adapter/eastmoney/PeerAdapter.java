package com.investmentresearch.data.adapter.eastmoney;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.PeerPort;
import com.investmentresearch.data.persistence.CompanyPeerEntity;
import com.investmentresearch.data.persistence.CompanyPeerMapper;
import com.investmentresearch.data.persistence.StockMapper;
import com.investmentresearch.data.persistence.ValPeerEntity;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 东财业绩报表同业对比，照抄 {@code stock/peers.py} + {@code assistant/tools.py:_peers}：
 * 本公司最近报告期的行业样本不足 5 家时回退上一期，取到足够样本即停。
 */
@Repository
public class PeerAdapter implements PeerPort {
    static final String REPORT_NAME = "RPT_LICO_FN_CPD";
    static final int MIN_LIMIT = 3;
    static final int MAX_LIMIT = 20;
    static final int MIN_PEERS = 5;
    static final int MAX_FALLBACK_PERIODS = 4;
    static final int LEADERS = 3;
    static final double SIZE_BAND_LOW = 0.3;
    static final double SIZE_BAND_HIGH = 3.0;
    static final String A_SHARE = "A股";
    static final String PAGE_URL = "https://data.eastmoney.com/bbsj/%s/yjbb.html";
    static final List<String> METRICS =
            List.of(
                    "revenue",
                    "revenue_yoy",
                    "net_profit",
                    "profit_yoy",
                    "gross_margin",
                    "net_margin",
                    "roe",
                    "pe_ttm",
                    "pb_mrq",
                    "market_cap");

    private final CompanyPeerMapper mapper;
    private final StockMapper stockMapper;
    private final UpstreamClient upstreamClient;
    private final ValuationSupport valuationSupport;
    private final String endpoint;
    private final Clock clock;

    @Autowired
    public PeerAdapter(
            CompanyPeerMapper mapper,
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            ValuationSupport valuationSupport,
            @Value("${app.datacenter-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
                    String endpoint) {
        this(
                mapper,
                stockMapper,
                upstreamClient,
                valuationSupport,
                endpoint,
                Clock.systemDefaultZone());
    }

    PeerAdapter(
            CompanyPeerMapper mapper,
            StockMapper stockMapper,
            UpstreamClient upstreamClient,
            ValuationSupport valuationSupport,
            String endpoint,
            Clock clock) {
        this.mapper = mapper;
        this.stockMapper = stockMapper;
        this.upstreamClient = upstreamClient;
        this.valuationSupport = valuationSupport;
        this.endpoint = endpoint;
        this.clock = clock;
    }

    @Override
    public ToolResult peers(String rawCode, int limit) {
        int cap = Math.min(Math.max(limit, MIN_LIMIT), MAX_LIMIT);
        String code = EastmoneySupport.normalize(rawCode);
        if (code.isEmpty()) {
            return ToolResult.failure(rawCode, "请输入股票代码");
        }
        try {
            Loaded loaded = ensureCohort(code);
            Cohort cohort = loaded.cohort();
            List<ValPeerEntity> valuationRows = List.of();
            String valuationDate = null;
            boolean valuationStale = false;
            String valuationError = null;
            try {
                ValuationSupport.Snapshot valuation = valuationSupport.ensure(code);
                valuationRows = valuation.peers();
                if (!valuation.history().isEmpty()) {
                    valuationDate =
                            valuation.history().get(valuation.history().size() - 1).getTradeDate();
                }
                valuationStale = valuation.stale();
                valuationError = valuation.peersError();
            } catch (RuntimeException error) {
                // 估值是增强项，取不到只影响 PE/PB/市值，不影响财务对比
                valuationError = error.getMessage();
            }

            String name = stockMapper.selectNameByCode(code);
            Map<String, Object> data =
                    buildData(
                            cohort,
                            code,
                            name,
                            cap,
                            valuationRows,
                            valuationDate,
                            valuationStale,
                            valuationError,
                            loaded.stale());
            return toResult(cohort, code, data);
        } catch (RuntimeException error) {
            // 端口不抛异常：上游、读库或组装出的意外错误同样折成失败
            return ToolResult.failure(code, error.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 取数与缓存
    // ------------------------------------------------------------------
    private record Cohort(
            String code,
            String boardCode,
            String boardName,
            String reportDate,
            String qdate,
            String datatype,
            List<JsonNode> rows,
            List<String> notes,
            String fetchedAt) {}

    private record Loaded(Cohort cohort, boolean stale) {}

    private Loaded ensureCohort(String code) {
        CompanyPeerEntity cachedRow = mapper.selectById(code);
        Cohort cached = readCohort(cachedRow, code);
        if (cached != null
                && EastmoneySupport.fresh(
                        Collections.singletonList(cachedRow.getFetchedAt()), clock)) {
            return new Loaded(cached, false);
        }
        try {
            Cohort fetched = fetchCohort(code);
            save(code, fetched);
            Cohort stored = readCohort(mapper.selectById(code), code);
            return new Loaded(stored == null ? fetched : stored, false);
        } catch (EastmoneySupport.EastmoneyException error) {
            if (cached != null) {
                return new Loaded(cached, true);
            }
            throw error;
        }
    }

    private Cohort readCohort(CompanyPeerEntity entity, String code) {
        if (entity == null || entity.getData() == null) {
            return null;
        }
        JsonNode node;
        try {
            node = EastmoneySupport.MAPPER.readTree(entity.getData());
        } catch (JsonProcessingException error) {
            return null;
        }
        if (node == null || !node.isObject() || node.isEmpty()) {
            return null;
        }
        List<JsonNode> rows = new ArrayList<>();
        if (node.path("rows").isArray()) {
            for (JsonNode row : node.path("rows")) {
                if (row.isObject()) {
                    rows.add(row);
                }
            }
        }
        List<String> notes = new ArrayList<>();
        if (node.path("notes").isArray()) {
            for (JsonNode note : node.path("notes")) {
                if (note.isTextual()) {
                    notes.add(note.asText());
                }
            }
        }
        String storedCode = EastmoneySupport.text(node.get("code"));
        return new Cohort(
                storedCode == null ? code : storedCode,
                EastmoneySupport.text(node.get("board_code")),
                EastmoneySupport.text(node.get("board_name")),
                EastmoneySupport.text(node.get("report_date")),
                EastmoneySupport.text(node.get("qdate")),
                EastmoneySupport.text(node.get("datatype")),
                rows,
                notes,
                entity.getFetchedAt());
    }

    private void save(String code, Cohort cohort) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("code", cohort.code());
        stored.put("board_code", cohort.boardCode());
        stored.put("board_name", cohort.boardName());
        stored.put("report_date", cohort.reportDate());
        stored.put("qdate", cohort.qdate());
        stored.put("datatype", cohort.datatype());
        stored.put("rows", cohort.rows());
        stored.put("notes", cohort.notes());
        CompanyPeerEntity entity = new CompanyPeerEntity();
        entity.setCode(code);
        try {
            entity.setData(EastmoneySupport.MAPPER.writeValueAsString(stored));
        } catch (JsonProcessingException error) {
            throw new EastmoneySupport.EastmoneyException(error.getMessage());
        }
        entity.setFetchedAt(cohort.fetchedAt());
        mapper.insertOrUpdate(entity);
    }

    private Cohort fetchCohort(String code) {
        List<JsonNode> subjectRows =
                dedupePeriods(
                        EastmoneySupport.fetchRows(
                                upstreamClient,
                                endpoint,
                                REPORT_NAME,
                                "(SECURITY_CODE=\"" + code + "\")",
                                "-1",
                                "REPORTDATE",
                                40,
                                5,
                                "同业"));
        subjectRows.sort(
                Comparator.comparing(
                                (JsonNode row) -> {
                                    String date = EastmoneySupport.datePart(row.get("REPORTDATE"));
                                    return date == null ? "" : date;
                                })
                        .reversed());
        if (subjectRows.isEmpty()) {
            throw new EastmoneySupport.EastmoneyException(code + "：没有业绩报表数据");
        }

        List<Candidate> candidates = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonNode row : subjectRows) {
            String period = EastmoneySupport.datePart(row.get("REPORTDATE"));
            if (period == null || !seen.add(period)) {
                continue;
            }
            candidates.add(new Candidate(period, row));
            if (candidates.size() >= MAX_FALLBACK_PERIODS) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            throw new EastmoneySupport.EastmoneyException(code + "：没有可用的报告期");
        }

        JsonNode firstRow = candidates.get(0).row();
        String boardCode = boardCode(firstRow);
        String boardName =
                firstOr(
                        EastmoneySupport.text(firstRow.get("BOARD_NAME")),
                        EastmoneySupport.text(firstRow.get("PUBLISHNAME")));
        if (boardCode == null) {
            throw new EastmoneySupport.EastmoneyException(code + "：没有行业分类，无法做同业对比");
        }

        Best best = null;
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            List<JsonNode> cohort =
                    new ArrayList<>(
                            dedupe(
                                    EastmoneySupport.fetchRows(
                                            upstreamClient,
                                            endpoint,
                                            REPORT_NAME,
                                            "(BOARD_CODE=\""
                                                    + boardCode
                                                    + "\")(REPORTDATE='"
                                                    + candidate.period()
                                                    + "')",
                                            "-1",
                                            "TOTAL_OPERATE_INCOME",
                                            500,
                                            5,
                                            "同业")));
            cohort.removeIf(
                    item ->
                            !A_SHARE.equals(
                                    EastmoneySupport.text(item.get("SECURITY_TYPE"))));
            boolean hasSubject =
                    cohort.stream()
                            .anyMatch(
                                    item ->
                                            code.equals(
                                                    EastmoneySupport.text(
                                                            item.get("SECURITY_CODE"))));
            if (!hasSubject) {
                cohort.add(candidate.row());
            }
            Best current = new Best(candidate.period(), candidate.row(), cohort, index);
            if (best == null || cohort.size() > best.rows().size()) {
                best = current;
            }
            if (cohort.size() >= MIN_PEERS) {
                best = current;
                break;
            }
        }
        if (best == null) {
            throw new EastmoneySupport.EastmoneyException(code + "：没有取到同业数据");
        }

        List<String> notes = new ArrayList<>();
        if (best.index() > 0) {
            notes.add(
                    "公司最新报告期 "
                            + candidates.get(0).period()
                            + " 的同业样本不足 "
                            + MIN_PEERS
                            + " 家，改用 "
                            + best.period());
        }
        if (best.rows().size() < MIN_PEERS) {
            notes.add("同业样本只有 " + best.rows().size() + " 家，对比结果参考意义有限");
        }

        return new Cohort(
                code,
                boardCode,
                boardName,
                best.period(),
                EastmoneySupport.text(best.row().get("QDATE")),
                EastmoneySupport.text(best.row().get("DATATYPE")),
                best.rows(),
                notes,
                EastmoneySupport.timestamp(clock));
    }

    private record Candidate(String period, JsonNode row) {}

    private record Best(String period, JsonNode row, List<JsonNode> rows, int index) {}

    // ------------------------------------------------------------------
    // 组装工具输出
    // ------------------------------------------------------------------
    private Map<String, Object> buildData(
            Cohort cohort,
            String code,
            String name,
            int limit,
            List<ValPeerEntity> valuationRows,
            String valuationDate,
            boolean valuationStale,
            String valuationError,
            boolean stale) {
        List<JsonNode> rows = dedupe(cohort.rows());
        JsonNode subjectRaw =
                rows.stream()
                        .filter(
                                row ->
                                        code.equals(
                                                EastmoneySupport.text(
                                                        row.get("SECURITY_CODE"))))
                        .findFirst()
                        .orElse(null);
        if (subjectRaw == null) {
            throw new EastmoneySupport.EastmoneyException(code + "：同业数据里没有本公司");
        }

        Map<String, ValPeerEntity> valuations = new LinkedHashMap<>();
        for (ValPeerEntity valuation : valuationRows) {
            if (valuation.getCode() != null) {
                valuations.put(valuation.getCode(), valuation);
            }
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> item = normalize(row, valuations);
            if (item != null) {
                normalized.add(item);
            }
        }
        Map<String, Object> subject =
                normalized.stream()
                        .filter(item -> code.equals(item.get("code")))
                        .findFirst()
                        .orElse(null);
        if (subject == null) {
            throw new EastmoneySupport.EastmoneyException(code + "：同业数据里没有本公司");
        }

        String period =
                cohort.reportDate() != null
                        ? cohort.reportDate()
                        : EastmoneySupport.datePart(subjectRaw.get("REPORTDATE"));
        String qdate =
                firstOr(cohort.qdate(), EastmoneySupport.text(subjectRaw.get("QDATE")));
        String datatype =
                firstOr(cohort.datatype(), EastmoneySupport.text(subjectRaw.get("DATATYPE")));
        String boardName =
                firstOr(
                        EastmoneySupport.text(subjectRaw.get("BOARD_NAME")),
                        EastmoneySupport.text(subjectRaw.get("PUBLISHNAME")));
        String subjectBoardCode = boardCode(subjectRaw);

        for (Map<String, Object> item : normalized) {
            item.put("revenue_ratio", ratio(item.get("revenue"), subject.get("revenue")));
            item.put("market_cap_ratio", ratio(item.get("market_cap"), subject.get("market_cap")));
        }

        List<Map<String, Object>> selected = selectPeers(normalized, subject, limit);
        List<Map<String, Object>> leaders = leaders(normalized, subject);
        Map<String, Object> stats = new LinkedHashMap<>();
        for (String metric : METRICS) {
            Stat stat = stat(normalized, subject, metric);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("value", peerMetric(metric, stat.value()));
            value.put("median", peerMetric(metric, stat.median()));
            value.put("rank", stat.rank());
            value.put("percentile", EastmoneySupport.pct(stat.percentile()));
            value.put("count", stat.count());
            stats.put(metric, value);
        }
        List<String> notes =
                notes(
                        normalized,
                        subject,
                        selected,
                        cohort.notes(),
                        !valuationRows.isEmpty(),
                        valuationDate,
                        valuationStale,
                        valuationError,
                        boardName);
        int valuationCount =
                (int)
                        normalized.stream()
                                .filter(
                                        item ->
                                                item.get("pe_ttm") != null
                                                        || item.get("pb_mrq") != null
                                                        || item.get("market_cap") != null)
                                .count();

        Map<String, Object> industry = new LinkedHashMap<>();
        industry.put("name", boardName);
        industry.put("code", subjectBoardCode);
        industry.put("count", normalized.size());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("period", period);
        report.put("qdate", qdate);
        report.put("datatype", datatype);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("industry", normalized.size());
        counts.put("valuation", valuationCount);
        counts.put("selected", selected.size());
        counts.put("leaders", leaders.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("name", name);
        data.put("as_of", period == null ? "" : period);
        data.put("industry", industry);
        data.put("report", report);
        data.put("valuation_date", valuationDate);
        data.put("valuation_stale", valuationStale);
        data.put("valuation_error", valuationError);
        data.put("counts", counts);
        data.put("subject", peerRow(subject));
        data.put("stats", stats);
        data.put("peers", selected.stream().map(PeerAdapter::peerRow).toList());
        data.put("leaders", leaders.stream().map(PeerAdapter::peerRow).toList());
        data.put("notes", notes);
        data.put("stale", stale);
        return data;
    }

    private ToolResult toResult(Cohort cohort, String code, Map<String, Object> data) {
        @SuppressWarnings("unchecked")
        Map<String, Object> industry = (Map<String, Object>) data.get("industry");
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) data.get("report");
        String asOf = data.get("as_of") == null ? "" : (String) data.get("as_of");
        String name = (String) data.get("name");
        Object industryName = industry.get("name");
        Object industryCount = industry.get("count");
        String detail =
                ((name == null ? code : name)
                                + " "
                                + asOf
                                + " · "
                                + (industryName == null ? "同行业" : industryName)
                                + " "
                                + (industryCount == null ? 0 : industryCount)
                                + " 家")
                        .strip();
        String url = pageUrl((String) report.get("period"));
        Source source =
                new Source(
                        asOf.isEmpty() ? "peers:" + code : "peers:" + code + ":" + asOf,
                        "同业对比",
                        detail,
                        asOf,
                        cohort.fetchedAt() == null ? "" : cohort.fetchedAt(),
                        url);
        return ToolResult.ok(code, asOf, List.of(source), data);
    }

    static String pageUrl(String period) {
        if (period == null || period.isEmpty()) {
            return "";
        }
        return PAGE_URL.formatted(period.substring(0, Math.min(7, period.length())).replace("-", ""));
    }

    // ------------------------------------------------------------------
    // 解析与统计（逐个对应 stock/peers.py）
    // ------------------------------------------------------------------
    private static Map<String, Object> normalize(
            JsonNode raw, Map<String, ValPeerEntity> valuations) {
        String code = EastmoneySupport.text(raw.get("SECURITY_CODE"));
        if (code == null) {
            return null;
        }
        ValPeerEntity valuation = valuations.get(code);
        Double revenue = EastmoneySupport.num(raw.get("TOTAL_OPERATE_INCOME"));
        Double netProfit = EastmoneySupport.num(raw.get("PARENT_NETPROFIT"));
        Double netMargin = null;
        if (revenue != null && revenue != 0 && netProfit != null) {
            netMargin = netProfit / revenue * 100.0;
        }
        String name = EastmoneySupport.text(raw.get("SECURITY_NAME_ABBR"));
        if (name == null && valuation != null) {
            name = valuation.getName();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", name);
        item.put("market", EastmoneySupport.text(raw.get("TRADE_MARKET")));
        item.put("report_date", EastmoneySupport.datePart(raw.get("REPORTDATE")));
        item.put("revenue", revenue);
        item.put("net_profit", netProfit);
        item.put("revenue_yoy", EastmoneySupport.num(raw.get("YSTZ")));
        item.put("profit_yoy", EastmoneySupport.num(raw.get("SJLTZ")));
        item.put("gross_margin", EastmoneySupport.num(raw.get("XSMLL")));
        item.put("net_margin", netMargin);
        item.put("roe", EastmoneySupport.num(raw.get("WEIGHTAVG_ROE")));
        item.put("eps", EastmoneySupport.num(raw.get("BASIC_EPS")));
        item.put("bps", EastmoneySupport.num(raw.get("BPS")));
        item.put("ocf_per_share", EastmoneySupport.num(raw.get("MGJYXJJE")));
        item.put("pe_ttm", valuation == null ? null : valuation.getPeTtm());
        item.put("pb_mrq", valuation == null ? null : valuation.getPbMrq());
        item.put("market_cap", valuation == null ? null : valuation.getTotalMv());
        return item;
    }

    private record Stat(Double value, Double median, Integer rank, Double percentile, int count) {}

    private static Stat stat(
            List<Map<String, Object>> normalized, Map<String, Object> subject, String metric) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> item : normalized) {
            Double value = (Double) item.get(metric);
            if (valid(metric, value)) {
                values.add(value);
            }
        }
        Double own = (Double) subject.get(metric);
        boolean ownValid = valid(metric, own);
        Double median = EastmoneySupport.median(values);
        Integer rank = null;
        Double percentile = null;
        if (ownValid) {
            // rank 1 = 数值最高；percentile 是低于等于本公司的比例
            rank = 1 + (int) values.stream().filter(value -> value > own).count();
            if (!values.isEmpty()) {
                percentile =
                        values.stream().filter(value -> value <= own).count()
                                / (double) values.size()
                                * 100.0;
            }
        }
        return new Stat(own, median, rank, percentile, values.size());
    }

    private static boolean valid(String metric, Double value) {
        if (value == null) {
            return false;
        }
        if ("pe_ttm".equals(metric) || "pb_mrq".equals(metric) || "market_cap".equals(metric)) {
            return value > 0;
        }
        return true;
    }

    private static List<Map<String, Object>> selectPeers(
            List<Map<String, Object>> normalized, Map<String, Object> subject, int limit) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> item : normalized) {
            if (!subject.get("code").equals(item.get("code"))) {
                candidates.add(item);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        Double ownRevenue = (Double) subject.get("revenue");
        Double ownCap = (Double) subject.get("market_cap");
        String ratioKey;
        if (ownRevenue != null && ownRevenue > 0) {
            ratioKey = "revenue_ratio";
        } else if (ownCap != null && ownCap > 0) {
            ratioKey = "market_cap_ratio";
        } else {
            ratioKey = null;
        }
        Comparator<Map<String, Object>> key = selectionKey(ratioKey);
        candidates.sort(key);

        List<Map<String, Object>> selected =
                new ArrayList<>(candidates.subList(0, Math.min(limit, candidates.size())));
        selected.sort(byRevenueDesc());
        return selected;
    }

    private static Comparator<Map<String, Object>> selectionKey(String ratioKey) {
        if (ratioKey == null) {
            return Comparator.comparingDouble(PeerAdapter::negatedRevenue);
        }
        return (left, right) -> {
            Double leftRatio = (Double) left.get(ratioKey);
            Double rightRatio = (Double) right.get(ratioKey);
            int leftBand = band(leftRatio);
            int rightBand = band(rightRatio);
            if (leftBand != rightBand) {
                return Integer.compare(leftBand, rightBand);
            }
            int closeness =
                    Double.compare(
                            closeness(leftRatio), closeness(rightRatio));
            if (closeness != 0) {
                return closeness;
            }
            // 缺估值的同业在 Python 里第三位固定 0.0，不再按营收排
            double leftTie = leftRatio == null ? 0.0 : negatedRevenue(left);
            double rightTie = rightRatio == null ? 0.0 : negatedRevenue(right);
            return Double.compare(leftTie, rightTie);
        };
    }

    private static int band(Double ratio) {
        if (ratio == null) {
            return 2;
        }
        return ratio >= SIZE_BAND_LOW && ratio <= SIZE_BAND_HIGH ? 0 : 1;
    }

    private static double closeness(Double ratio) {
        return ratio == null ? Double.POSITIVE_INFINITY : Math.abs(ratio - 1.0);
    }

    private static double negatedRevenue(Map<String, Object> item) {
        Double revenue = (Double) item.get("revenue");
        return -(revenue == null ? 0.0 : revenue);
    }

    private static Comparator<Map<String, Object>> byRevenueDesc() {
        return Comparator.comparingDouble(
                        (Map<String, Object> item) -> {
                            Double revenue = (Double) item.get("revenue");
                            return revenue == null ? Double.NEGATIVE_INFINITY : revenue;
                        })
                .reversed();
    }

    private static List<Map<String, Object>> leaders(
            List<Map<String, Object>> normalized, Map<String, Object> subject) {
        List<Map<String, Object>> others = new ArrayList<>();
        for (Map<String, Object> item : normalized) {
            if (!subject.get("code").equals(item.get("code"))) {
                others.add(item);
            }
        }
        others.sort(byRevenueDesc());
        return new ArrayList<>(others.subList(0, Math.min(LEADERS, others.size())));
    }

    private static List<String> notes(
            List<Map<String, Object>> normalized,
            Map<String, Object> subject,
            List<Map<String, Object>> selected,
            List<String> cohortNotes,
            boolean hasValuation,
            String valuationDate,
            boolean valuationStale,
            String valuationError,
            String boardName) {
        List<String> result = new ArrayList<>();
        for (String note : cohortNotes) {
            if (note != null && !note.isBlank()) {
                result.add(note.strip());
            }
        }

        Double subjectRevenue = (Double) subject.get("revenue");
        if (!selected.isEmpty() && subjectRevenue != null && subjectRevenue != 0) {
            int inBand = 0;
            for (Map<String, Object> item : selected) {
                Double ratio = (Double) item.get("revenue_ratio");
                if (ratio != null && ratio >= SIZE_BAND_LOW && ratio <= SIZE_BAND_HIGH) {
                    inBand++;
                }
            }
            if (inBand < selected.size()) {
                result.add(
                        "营收在 0.3–3 倍范围内的公司只有 "
                                + inBand
                                + " 家，其余按最接近的规模补齐");
            }
        }
        result.add("同行按东方财富行业板块筛选，细分业务可能不完全可比");

        if (valuationError != null && !valuationError.isBlank()) {
            if (hasValuation) {
                result.add("估值同业抓取失败，使用本地缓存：" + valuationError);
            } else {
                result.add("估值数据抓取失败：" + valuationError);
            }
        } else if (!hasValuation) {
            result.add("没有取到估值数据，PE/PB/市值为空");
        } else if (valuationStale) {
            result.add("估值数据来自本地缓存，可能不是最新收盘");
        }

        String period = (String) subject.get("report_date");
        if (valuationDate != null && period != null && !valuationDate.equals(period)) {
            result.add(
                    "估值日期 " + valuationDate + " 与财报期 " + period + " 不同，"
                            + "PE/PB 是最新市场数据");
        }

        if (hasValuation) {
            int missing = 0;
            for (Map<String, Object> item : normalized) {
                if (item.get("pe_ttm") == null
                        && item.get("pb_mrq") == null
                        && item.get("market_cap") == null) {
                    missing++;
                }
            }
            if (missing > 0) {
                result.add(missing + " 家同业缺少估值数据");
            }
        }

        boolean negative =
                normalized.stream()
                        .anyMatch(
                                item -> {
                                    Double pe = (Double) item.get("pe_ttm");
                                    return pe != null && pe <= 0;
                                });
        if (negative) {
            result.add("PE 为负的公司处于亏损，不参与 PE 排名");
        }
        Double ownPe = (Double) subject.get("pe_ttm");
        if (ownPe != null && ownPe <= 0) {
            result.add("本公司 PE 为负，PE 排名不适用");
        }

        if (boardName != null
                && (boardName.contains("银行")
                        || boardName.contains("保险")
                        || boardName.contains("证券"))) {
            result.add("金融行业毛利率、净利率口径不可比，重点看 ROE、PB 与资产质量");
        }
        return new ArrayList<>(new LinkedHashSet<>(result));
    }

    private static Map<String, Object> peerRow(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", item.get("code"));
        out.put("name", item.get("name"));
        out.put("market", item.get("market"));
        out.put("revenue_yi", EastmoneySupport.yi((Double) item.get("revenue")));
        out.put("net_profit_yi", EastmoneySupport.yi((Double) item.get("net_profit")));
        out.put("revenue_yoy", EastmoneySupport.pct((Double) item.get("revenue_yoy")));
        out.put("profit_yoy", EastmoneySupport.pct((Double) item.get("profit_yoy")));
        out.put("gross_margin", EastmoneySupport.pct((Double) item.get("gross_margin")));
        out.put("net_margin", EastmoneySupport.pct((Double) item.get("net_margin")));
        out.put("roe", EastmoneySupport.pct((Double) item.get("roe")));
        out.put("eps", EastmoneySupport.round((Double) item.get("eps"), 2));
        out.put("bps", EastmoneySupport.round((Double) item.get("bps"), 2));
        out.put("ocf_per_share", EastmoneySupport.round((Double) item.get("ocf_per_share"), 2));
        out.put("pe_ttm", EastmoneySupport.round((Double) item.get("pe_ttm"), 2));
        out.put("pb_mrq", EastmoneySupport.round((Double) item.get("pb_mrq"), 2));
        out.put("market_cap_yi", EastmoneySupport.yi((Double) item.get("market_cap")));
        out.put("revenue_ratio", EastmoneySupport.round((Double) item.get("revenue_ratio"), 2));
        out.put(
                "market_cap_ratio",
                EastmoneySupport.round((Double) item.get("market_cap_ratio"), 2));
        return out;
    }

    private static Double peerMetric(String metric, Double value) {
        if (value == null) {
            return null;
        }
        if ("revenue".equals(metric)
                || "net_profit".equals(metric)
                || "market_cap".equals(metric)) {
            return EastmoneySupport.yi(value);
        }
        if ("revenue_yoy".equals(metric)
                || "profit_yoy".equals(metric)
                || "gross_margin".equals(metric)
                || "net_margin".equals(metric)
                || "roe".equals(metric)) {
            return EastmoneySupport.pct(value);
        }
        return EastmoneySupport.round(value, 2);
    }

    // ------------------------------------------------------------------
    // 行去重与排序小工具
    // ------------------------------------------------------------------
    private static List<JsonNode> dedupe(List<JsonNode> rows) {
        Map<String, Stamped> best = new LinkedHashMap<>();
        for (JsonNode raw : rows) {
            String code = EastmoneySupport.text(raw.get("SECURITY_CODE"));
            if (code == null) {
                continue;
            }
            Stamped current = best.get(code);
            String stamp = stamp(raw);
            if (current == null || stamp.compareTo(current.stamp()) >= 0) {
                best.put(code, new Stamped(stamp, raw));
            }
        }
        return new ArrayList<>(best.values().stream().map(Stamped::row).toList());
    }

    private static List<JsonNode> dedupePeriods(List<JsonNode> rows) {
        Map<String, Stamped> best = new LinkedHashMap<>();
        for (JsonNode raw : rows) {
            String code = EastmoneySupport.text(raw.get("SECURITY_CODE"));
            String period = EastmoneySupport.datePart(raw.get("REPORTDATE"));
            if (code == null || period == null) {
                continue;
            }
            String key = code + "\u0000" + period;
            Stamped current = best.get(key);
            String stamp = stamp(raw);
            if (current == null || stamp.compareTo(current.stamp()) >= 0) {
                best.put(key, new Stamped(stamp, raw));
            }
        }
        return new ArrayList<>(best.values().stream().map(Stamped::row).toList());
    }

    private record Stamped(String stamp, JsonNode row) {}

    private static String stamp(JsonNode raw) {
        return firstOr(
                EastmoneySupport.text(raw.get("UPDATE_DATE")),
                EastmoneySupport.text(raw.get("NOTICE_DATE")),
                "");
    }

    private static String boardCode(JsonNode row) {
        String board = EastmoneySupport.text(row.get("BOARD_CODE"));
        if (board != null) {
            return board;
        }
        String original = EastmoneySupport.text(row.get("ORI_BOARD_CODE"));
        if (original == null) {
            return null;
        }
        return original.startsWith("BK") ? original : "BK" + original;
    }

    private static Double ratio(Object value, Object base) {
        if (!(value instanceof Double numerator)
                || !(base instanceof Double denominator)
                || denominator == 0) {
            return null;
        }
        return numerator / denominator;
    }

    private static String firstOr(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
