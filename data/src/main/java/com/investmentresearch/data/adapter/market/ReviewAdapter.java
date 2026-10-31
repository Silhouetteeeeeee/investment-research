package com.investmentresearch.data.adapter.market;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.MarketPort;
import com.investmentresearch.core.port.ReviewPort;
import com.investmentresearch.core.port.ScreenPort;
import com.investmentresearch.core.port.SectorPort;
import com.investmentresearch.core.research.MarketState;
import com.investmentresearch.core.research.Review;
import com.investmentresearch.core.research.SectorRotation;
import com.investmentresearch.data.persistence.DailyReportStore;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Port of {@code research.review.save_daily_judgment / build_review}：存档当天判断，并对旧存档算后续走势。
 */
@Component
public final class ReviewAdapter implements ReviewPort {
    static final int CACHE_SECONDS = 900;
    static final int MAX_WORKERS = 8;
    /** 复盘要看的是「存档日之后」的走势，日线一次多取一点再按区间过滤。 */
    static final int HISTORY_LIMIT = 400;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private volatile String cacheKey;
    private volatile LocalDateTime cachedAt;
    private volatile ToolResult cached;

    private final MarketPort market;
    private final SectorPort sectors;
    private final ScreenPort screen;
    private final DailyReportStore store;
    private final UpstreamClient client;
    private final Clock clock;

    @Autowired
    public ReviewAdapter(
            MarketPort market,
            SectorPort sectors,
            ScreenPort screen,
            DailyReportStore store,
            UpstreamClient client) {
        this(market, sectors, screen, store, client, Clock.systemDefaultZone());
    }

    ReviewAdapter(
            MarketPort market,
            SectorPort sectors,
            ScreenPort screen,
            DailyReportStore store,
            UpstreamClient client,
            Clock clock) {
        this.market = market;
        this.sectors = sectors;
        this.screen = screen;
        this.store = store;
        this.client = client;
        this.clock = clock;
    }

    @Override
    public ToolResult save(Map<String, Object> strategy) {
        LocalDateTime now = LocalDateTime.now(clock);
        String asOf = now.format(TIMESTAMP);
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> marketBlock = new LinkedHashMap<>();
        Map<String, Object> sectorBlock = new LinkedHashMap<>();
        List<Map<String, Object>> candidateBlock = new ArrayList<>();
        String tradeDate = "";

        try {
            ToolResult overview = market.overview();
            if (!overview.ok()) {
                throw new MarketSupport.MarketException(overview.error());
            }
            marketBlock = marketBlock(overview.data());
            tradeDate = day(text(overview.asOf()));
        } catch (RuntimeException error) {
            errors.put("market", "大盘状态：" + message(error));
        }

        try {
            ToolResult rotation = sectors.sectors(8);
            if (!rotation.ok()) {
                throw new MarketSupport.MarketException(rotation.error());
            }
            sectorBlock = sectorBlock(rotation.data());
            tradeDate = first(text(rotation.data().get("trade_date")), tradeDate);
        } catch (RuntimeException error) {
            errors.put("sectors", "行业轮动：" + message(error));
        }

        try {
            ToolResult picked = screen.screen(15, 1.0);
            if (!picked.ok()) {
                throw new MarketSupport.MarketException(picked.error());
            }
            candidateBlock = candidateBlock(picked.data());
        } catch (RuntimeException error) {
            errors.put("candidates", "候选股筛选：" + message(error));
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("trade_date", tradeDate.isEmpty() ? day(asOf) : tradeDate);
        snapshot.put("as_of", asOf);
        snapshot.put("market", marketBlock);
        snapshot.put("sectors", sectorBlock);
        snapshot.put("candidates", candidateBlock);
        snapshot.put("strategy", strategyBlock(strategy));
        snapshot.put("errors", errors);
        List<String> notes = new ArrayList<>();
        notes.add("存档只保存规则引擎的判断，回答里的文字结论不参与复盘");
        notes.add(
                "收益按收盘价计算，成本按往返 "
                        + Review.ROUND_TRIP_COST_PCT
                        + "% 估算；未考虑停牌与涨跌停无法成交");
        snapshot.put("notes", notes);

        if (marketBlock.isEmpty() && sectorBlock.isEmpty() && candidateBlock.isEmpty()) {
            notes.add("全部数据源都失败，这份快照不保存");
            snapshot.put("saved", false);
            snapshot.put("store", null);
            return ToolResult.failure(
                    null, "判断存档失败：没有取到任何可存档的数据", snapshot);
        }

        Map<String, Object> stored = store.save(snapshot);
        snapshot.put("saved", true);
        snapshot.put("store", stored);
        String state = text(map(snapshot.get("market")).get("state"));
        boolean replaced = Boolean.TRUE.equals(stored.get("replaced"));
        Source source =
                new Source(
                        "research:" + text(stored.get("trade_date")),
                        "每日判断存档",
                        (state.isEmpty() ? "市场状态缺失" : state)
                                + " · "
                                + candidateBlock.size()
                                + " 只候选 · "
                                + (replaced ? "覆盖" : "新增"),
                        asOf,
                        first(text(stored.get("saved_at")), asOf));
        return ToolResult.ok(null, asOf, List.of(source), snapshot);
    }

    @Override
    public ToolResult review(int limit, int horizon) {
        LocalDateTime now = LocalDateTime.now(clock);
        String asOf = now.format(TIMESTAMP);
        String key = now.toLocalDate() + ":" + limit + ":" + horizon;
        ToolResult hit = cached(key, now);
        if (hit != null) {
            return hit;
        }

        List<Map<String, Object>> reports = store.load(limit);
        if (reports.isEmpty()) {
            Map<String, Object> data = Review.empty(asOf, horizon);
            List<String> notes = strings(data.get("notes"));
            return ToolResult.failure(
                    null,
                    "复盘失败：" + (notes.isEmpty() ? "还没有保存过每日判断" : String.join("；", notes)),
                    data);
        }

        String earliest = reports.get(0).get("trade_date").toString();
        for (Map<String, Object> report : reports) {
            earliest = earliest.compareTo(text(report.get("trade_date"))) > 0
                    ? text(report.get("trade_date"))
                    : earliest;
        }
        LocalDate startDay = LocalDate.parse(earliest, DAY).minusDays(20);
        String start = startDay.format(DateTimeFormatter.BASIC_ISO_DATE);
        String end = now.format(DateTimeFormatter.BASIC_ISO_DATE);

        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> benchmark;
        try {
            benchmark = Review.series(inRange(benchmarkRows(), start, end));
            if (strings(benchmark.get("dates")).isEmpty()) {
                errors.put("benchmark", "基准日线为空");
            }
        } catch (RuntimeException error) {
            benchmark = Review.series(List.of());
            errors.put("benchmark", "基准日线：" + message(error));
        }

        Map<String, Object> keys = Review.keys(reports);
        Map<String, List<Map<String, Object>>> stockRows;
        Map<String, List<Map<String, Object>>> sectorRows;
        try {
            stockRows = fetch(strings(keys.get("codes")), code -> inRange(stockRows(code), start, end), errors, "个股日线");
            sectorRows = fetch(strings(keys.get("names")), name -> sectorRows(name, start, end), errors, "行业日线");
        } catch (RuntimeException error) {
            return ToolResult.failure(null, "复盘失败：" + message(error));
        }

        Map<String, Object> data = Review.build(reports, benchmark, stockRows, sectorRows, horizon, asOf, errors);
        List<Source> sources = new ArrayList<>();
        for (Map<String, Object> row : reports) {
            Map<String, Object> state = map(row.get("market"));
            List<Map<String, Object>> candidates = maps(row.get("candidates"));
            sources.add(
                    new Source(
                            "review:" + text(row.get("trade_date")),
                            "复盘样本",
                            (text(state.get("state")).isEmpty() ? "状态缺失" : text(state.get("state")))
                                    + " · "
                                    + candidates.size()
                                    + " 只候选",
                            text(row.get("trade_date")),
                            text(row.get("saved_at"))));
        }
        sources.add(
                new Source(
                        "review:benchmark:" + day(asOf),
                        "复盘基准",
                        Review.BENCHMARK_NAME + " 日线与行业、个股日线",
                        asOf,
                        asOf));
        ToolResult result = ToolResult.ok(null, asOf, sources, data);
        cacheKey = key;
        cachedAt = now;
        cached = result;
        return result;
    }

    private List<Map<String, Object>> benchmarkRows() {
        return new TencentTrendSource(client).barsOf(SectorRotation.BENCHMARK_CODE);
    }

    private List<Map<String, Object>> stockRows(String code) {
        return new EmStockDailySource(client).rows(code, HISTORY_LIMIT);
    }

    /** 行业名 → 板块代码（一次抓全表），再取该板块的日线。 */
    private List<Map<String, Object>> sectorRows(String name, String start, String end) {
        if (sectorCodes == null) {
            Map<String, String> codes = new LinkedHashMap<>();
            for (Map<String, Object> row : new ThsSectorSource(client).rows()) {
                codes.put(text(row.get("板块")), text(row.get("代码")));
            }
            sectorCodes = codes;
        }
        String code = sectorCodes.get(name);
        if (code == null || code.isEmpty()) {
            throw new MarketSupport.MarketException("没有该板块的代码");
        }
        return new ThsBoardHistorySource(client).rows(code);
    }

    private volatile Map<String, String> sectorCodes;

    /** 并发取数：单个标的失败只记进 {@code errors}，不影响整体复盘。 */
    private Map<String, List<Map<String, Object>>> fetch(
            List<String> keys,
            java.util.function.Function<String, List<Map<String, Object>>> loader,
            Map<String, String> errors,
            String label) {
        List<Callable<List<Map<String, Object>>>> tasks = new ArrayList<>();
        for (String key : keys) {
            tasks.add(() -> loader.apply(key));
        }
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(MAX_WORKERS);
        try {
            List<Future<List<Map<String, Object>>>> results = pool.invokeAll(tasks);
            for (int index = 0; index < results.size(); index++) {
                try {
                    data.put(keys.get(index), results.get(index).get());
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failures.put(keys.get(index), cause.getMessage() == null ? "空数据" : cause.getMessage());
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failures.put("interrupted", "取数被中断");
        } finally {
            pool.shutdownNow();
        }
        if (!failures.isEmpty()) {
            List<String> sample = new ArrayList<>();
            for (Map.Entry<String, String> entry : failures.entrySet()) {
                if (sample.size() >= 3) {
                    break;
                }
                sample.add(entry.getKey() + "：" + entry.getValue());
            }
            errors.put(
                    label.equals("个股日线") ? "stocks" : "sectors",
                    label + " " + failures.size() + (label.equals("个股日线") ? " 只" : " 个")
                            + "失败，例如 " + String.join("；", sample));
        }
        return data;
    }

    /** Python 用 {@code YYYYMMDD} 比较，这里统一成同一格式再筛。 */
    private static List<Map<String, Object>> inRange(List<Map<String, Object>> rows, String start, String end) {
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String day = text(row.get("date")).replace("-", "");
            if (day.length() >= 8) {
                day = day.substring(0, 8);
            }
            if (!day.isEmpty() && start.compareTo(day) <= 0 && day.compareTo(end) <= 0) {
                kept.add(row);
            }
        }
        return kept;
    }

    private static Map<String, Object> marketBlock(Map<String, Object> overview) {
        Map<String, Object> state = MarketState.classify(overview);
        List<Map<String, Object>> basis = new ArrayList<>();
        for (Map<String, Object> item : maps(state.get("basis"))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("factor", item.get("factor"));
            entry.put("reading", item.get("reading"));
            basis.add(entry);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("state", state.get("state"));
        block.put("risk_level", state.get("risk_level"));
        block.put("confidence", state.get("confidence"));
        block.put("basis", basis);
        block.put("invalidation", state.getOrDefault("invalidation", List.of()));
        block.put("metrics", state.getOrDefault("metrics", Map.of()));
        return block;
    }

    private static Map<String, Object> sectorBlock(Map<String, Object> rotation) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("leaders", top(rotation.get("leaders")));
        block.put("weak", top(rotation.get("weak")));
        block.put("emerging", names(rotation.get("emerging")));
        block.put("weakening", names(rotation.get("weakening")));
        block.put("breadth", rotation.get("breadth"));
        return block;
    }

    private static List<Map<String, Object>> top(Object items) {
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> item : maps(items)) {
            if (picked.size() >= Review.MAX_CANDIDATES) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", item.get("name"));
            entry.put("score", item.get("score"));
            entry.put("trend", item.get("trend"));
            entry.put("excess_5d", item.get("excess_5d"));
            picked.add(entry);
        }
        return picked;
    }

    private static List<Object> names(Object items) {
        List<Object> picked = new ArrayList<>();
        for (Map<String, Object> item : maps(items)) {
            picked.add(item.get("name"));
        }
        return picked;
    }

    private static List<Map<String, Object>> candidateBlock(Map<String, Object> screen) {
        List<Map<String, Object>> picked = new ArrayList<>();
        for (Map<String, Object> item : maps(screen.get("candidates"))) {
            if (picked.size() >= Review.MAX_CANDIDATES) {
                break;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", item.get("code"));
            entry.put("name", item.get("name"));
            entry.put("board", item.get("board"));
            entry.put("score", item.get("score"));
            entry.put("price", item.get("price"));
            picked.add(entry);
        }
        return picked;
    }

    /** 接受 build_strategy_plan 的原样返回，也接受压平后的同样字段。 */
    private static Map<String, Object> strategyBlock(Map<String, Object> strategy) {
        if (strategy == null) {
            return null;
        }
        Map<String, Object> caps = map(strategy.get("caps"));
        Map<String, Object> profile = map(strategy.get("profile"));
        Object stance = strategy.get("stance");
        Object total = caps.containsKey("total_pct") ? caps.get("total_pct") : strategy.get("total_pct");
        if (stance == null && total == null) {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("stance", stance);
        block.put("total_pct", total);
        block.put("single_pct", caps.containsKey("single_pct") ? caps.get("single_pct") : strategy.get("single_pct"));
        block.put(
                "stop_loss_pct",
                caps.containsKey("stop_loss_pct") ? caps.get("stop_loss_pct") : strategy.get("stop_loss_pct"));
        block.put(
                "position_pct",
                profile.containsKey("position_pct") ? profile.get("position_pct") : strategy.get("position_pct"));
        return block;
    }

    private ToolResult cached(String key, LocalDateTime now) {
        if (!key.equals(cacheKey) || cachedAt == null || cached == null) {
            return null;
        }
        return Duration.between(cachedAt, now).getSeconds() < CACHE_SECONDS ? cached : null;
    }

    private static String day(String asOf) {
        return asOf.length() >= 10 ? asOf.substring(0, 10) : asOf;
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        }
        return out;
    }

    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> row) {
                    out.add(map(row));
                }
            }
        }
        return out;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(text(item));
            }
        }
        return out;
    }

    private static String first(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
