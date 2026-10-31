package com.investmentresearch.data.adapter.market;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.ScreenPort;
import com.investmentresearch.core.research.Screening;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Port of {@code research.screening.screen_candidates()}：全市场快照 → 板块配额选样 → 个股日线打分。
 */
@Component
public final class ScreenAdapter implements ScreenPort {
    static final int HISTORY_DAYS = 140;
    static final int MAX_WORKERS = 10;
    static final int CACHE_SECONDS = 900;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** shortcut: 只做进程内 15 分钟缓存（与 Python 一致），多实例不共享。 */
    private volatile String cacheKey;
    private volatile LocalDateTime cachedAt;
    private volatile ToolResult cached;

    private final UpstreamClient client;
    private final Clock clock;

    @Autowired
    public ScreenAdapter(UpstreamClient client) {
        this(client, Clock.systemDefaultZone());
    }

    ScreenAdapter(UpstreamClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public ToolResult screen(int limit, double minAmountYi) {
        LocalDateTime now = LocalDateTime.now(clock);
        String asOf = now.format(TIMESTAMP);
        String key = now.toLocalDate() + ":" + limit + ":" + minAmountYi;
        ToolResult hit = cached(key, now);
        if (hit != null) {
            return hit;
        }

        Map<String, String> errors = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        List<Map<String, Object>> rows;
        try {
            rows = new SinaAShareSpotSource(client).rows();
        } catch (RuntimeException error) {
            return ToolResult.failure(null, "量化筛选失败：全市场快照：" + message(error));
        }

        Map<String, Object> shortlisted =
                Screening.shortlist(rows, minAmountYi, Screening.DEFAULT_MIN_PRICE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> picked = (List<Map<String, Object>>) shortlisted.get("picked");
        @SuppressWarnings("unchecked")
        Map<String, Object> universe = (Map<String, Object>) shortlisted.get("stats");

        Map<String, List<Map<String, Object>>> history = histories(picked, errors);

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> row : picked) {
            String code = text(row.get("代码")).strip().toLowerCase();
            List<Map<String, Object>> bars = history.getOrDefault(code, List.of());
            Map<String, Object> item = Screening.stockStats(row, withOutstanding(row, bars));
            item.put("score", Screening.scoreStock(item));
            item.put("reasons", Screening.reasons(item));
            item.put("risks", Screening.risks(item));
            item.put("valuation", null);
            if (item.get("score") == null) {
                continue;
            }
            candidates.add(item);
        }

        // 打分高的在前；同分看成交额（Python 的 (score, spot_amount_yi or 0) 倒序）
        candidates.sort(
                Comparator.comparingDouble((Map<String, Object> item) -> num(item.get("score")))
                        .thenComparingDouble(item -> {
                            Double amount = MarketSupport.number(item.get("spot_amount_yi"));
                            return amount == null ? 0 : amount;
                        })
                        .reversed());

        List<Map<String, Object>> finalists =
                Screening.diversify(candidates, Math.max(1, Math.min(limit, 30)), Screening.MAX_PER_BOARD);

        missingValuation(notes, finalists);
        notes.add("量化分只反映量价与位置，不含基本面与估值结论");
        notes.add("候选池按板块配额取成交额靠前的股票，不是全市场涨幅榜");
        notes.add("候选池不含" + String.join("、", Screening.EXCLUDED_BOARDS));
        notes.add("最终名单每个板块最多 " + Screening.MAX_PER_BOARD + " 只，避免同一类股票占满");

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("min_amount_yi", minAmountYi);
        filters.put("exclude", "ST、退市、上市新股、停牌、无成交");
        filters.put("exclude_boards", Screening.EXCLUDED_BOARDS);
        filters.put("boards", Screening.BOARD_QUOTAS);
        filters.put("shortlisted", picked.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("as_of", asOf);
        data.put("universe", universe);
        data.put("filters", filters);
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> item : finalists) {
            top.add(Screening.publicView(item));
        }
        data.put("candidates", top);
        data.put("errors", errors);
        data.put("notes", notes);
        data.put(
                "missing",
                List.of("PE/PB 历史分位", "营收与利润增速、ROE、现金流", "公司公告与新闻事件", "所属行业与同业估值"));

        if (finalists.isEmpty()) {
            return ToolResult.failure(null, "量化筛选失败：" + (errors.isEmpty() ? "没有符合条件的股票" : detail(errors)));
        }
        ToolResult result = ToolResult.ok(null, asOf, sources(asOf, now.format(TIMESTAMP)), data);
        cacheKey = key;
        cachedAt = now;
        cached = result;
        return result;
    }

    /**
     * 每只股票一次日线请求，单只失败只记进 {@code errors}。
     *
     * <p>日线本身不带流通股本；快照的流通市值（万元）除以现价就是股本，补到最后一根上，
     * 市值与换手才算得出来。
     */
    private Map<String, List<Map<String, Object>>> histories(
            List<Map<String, Object>> picked, Map<String, String> errors) {
        EmStockDailySource source = new EmStockDailySource(client);
        List<Callable<List<Map<String, Object>>>> tasks = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> row : picked) {
            String code = text(row.get("代码")).strip().toLowerCase();
            if (code.isEmpty()) {
                continue;
            }
            codes.add(code);
            tasks.add(() -> source.rows(code, HISTORY_DAYS));
        }
        Map<String, List<Map<String, Object>>> history = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(MAX_WORKERS);
        try {
            List<Future<List<Map<String, Object>>>> results = pool.invokeAll(tasks);
            for (int index = 0; index < results.size(); index++) {
                try {
                    history.put(codes.get(index), results.get(index).get());
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failures.put(codes.get(index), cause.getMessage() == null ? "空数据" : cause.getMessage());
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failures.put("interrupted", "日线取数被中断");
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
            errors.put("history", "日线 " + failures.size() + " 只失败，例如 " + String.join("；", sample));
        }
        return history;
    }

    /** 用快照的流通市值补上最后一根日线的流通股本。 */
    static List<Map<String, Object>> withOutstanding(
            Map<String, Object> spot, List<Map<String, Object>> bars) {
        if (bars.isEmpty()) {
            return bars;
        }
        Double capWan = MarketSupport.number(spot.get("流通市值"));
        Double price = MarketSupport.number(spot.get("最新价"));
        if (capWan == null || capWan == 0 || price == null || price == 0) {
            return bars;
        }
        List<Map<String, Object>> copy = new ArrayList<>(bars);
        Map<String, Object> last = new LinkedHashMap<>(bars.get(bars.size() - 1));
        // 万元 → 元再除以现价，得到股数
        last.put("outstanding_share", capWan * 1e4 / price);
        copy.set(copy.size() - 1, last);
        return copy;
    }

    private static void missingValuation(List<String> notes, List<Map<String, Object>> finalists) {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> item : finalists) {
            if (item.get("valuation") == null) {
                missing.add(text(item.get("code")));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        List<String> sample = missing.subList(0, Math.min(3, missing.size()));
        notes.add(
                "最终名单里有 " + missing.size() + " 只没取到 PE/PB（" + String.join("、", sample)
                        + (missing.size() > 3 ? " 等" : "") + "），需要时让助手用 get_valuation 再查");
    }

    private static List<Source> sources(String asOf, String fetched) {
        String day = asOf.length() >= 10 ? asOf.substring(0, 10) : asOf;
        return List.of(
                new Source("screen:spot:" + day, "全市场快照", "新浪 A 股行情", asOf, fetched),
                // Python 用的新浪日线也是不复权，这里用东财的 fqt=0 对齐
                new Source("screen:daily:" + day, "个股日线", "东财日线（不复权）", asOf, fetched));
    }

    private ToolResult cached(String key, LocalDateTime now) {
        if (!key.equals(cacheKey) || cachedAt == null || cached == null) {
            return null;
        }
        return Duration.between(cachedAt, now).getSeconds() < CACHE_SECONDS ? cached : null;
    }

    private static String detail(Map<String, String> errors) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            parts.add(entry.getKey() + "：" + entry.getValue());
        }
        return String.join("；", parts);
    }

    private static double num(Object value) {
        Double parsed = MarketSupport.number(value);
        return parsed == null ? 0 : parsed;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
