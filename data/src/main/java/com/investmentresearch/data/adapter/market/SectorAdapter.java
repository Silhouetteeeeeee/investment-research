package com.investmentresearch.data.adapter.market;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.SectorPort;
import com.investmentresearch.core.research.SectorRotation;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Duration;
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
 * Port of {@code research.sector_rotation.build_sector_rotation()}：取行业快照与各板块日线，
 * 交给 {@link SectorRotation} 算轮动排序。取数失败按段记进 {@code errors}。
 */
@Component
public final class SectorAdapter implements SectorPort {
    static final int MAX_WORKERS = 6;
    static final int CACHE_SECONDS = 600;
    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** shortcut: 只做进程内 10 分钟缓存（与 Python 一致），多实例不共享；进程重启即失效。 */
    private volatile String cacheKey;
    private volatile LocalDateTime cachedAt;
    private volatile ToolResult cached;

    private final UpstreamClient client;
    private final Clock clock;

    @Autowired
    public SectorAdapter(UpstreamClient client) {
        this(client, Clock.systemDefaultZone());
    }

    SectorAdapter(UpstreamClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public ToolResult sectors(int limit) {
        LocalDateTime now = LocalDateTime.now(clock);
        String asOf = now.format(TIMESTAMP);
        String today = now.toLocalDate().toString();
        String key = today + ":" + limit;
        ToolResult hit = cached(key, now);
        if (hit != null) {
            return hit;
        }

        Map<String, String> errors = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        List<Map<String, Object>> summary;
        try {
            summary = new ThsSectorSource(client).rows();
        } catch (RuntimeException error) {
            errors.put("sectors", "行业快照：" + message(error));
            return failed(asOf, errors, notes);
        }

        Map<String, Double> benchmarkCloses = new LinkedHashMap<>();
        try {
            for (Map<String, Object> bar : new TencentTrendSource(client).barsOf(SectorRotation.BENCHMARK_CODE)) {
                String day = date(bar.get("date"));
                Double close = MarketSupport.number(bar.get("close"));
                if (!day.isEmpty() && close != null && close != 0) {
                    benchmarkCloses.put(day, close);
                }
            }
            if (benchmarkCloses.isEmpty()) {
                errors.put("benchmark", "基准日线为空");
            }
        } catch (RuntimeException error) {
            errors.put("benchmark", "基准日线：" + message(error));
        }

        Map<String, List<Map<String, Object>>> history = histories(summary, errors);

        Double benchmarkToday = null;
        try {
            for (Map<String, Object> row : new SinaIndexSource(client).rows()) {
                if (!SectorRotation.BENCHMARK_CODE.equals(text(row.get("代码")))) {
                    continue;
                }
                // 用现价/昨收现算，保留 Python 侧未取整的原始值，超额收益取整才不跑偏
                Double price = MarketSupport.number(row.get("最新价"));
                Double previous = MarketSupport.number(row.get("昨收"));
                benchmarkToday =
                        price == null || previous == null || previous == 0
                                ? null
                                : (price / previous - 1) * 100;
                break;
            }
        } catch (RuntimeException error) {
            errors.put("benchmark_today", "基准当日行情：" + message(error));
        }

        Map<String, Object> session = MarketAdapter.sessionState(asOf, today);
        if (session.get("progress") == null) {
            notes.add("交易时段未知，成交额放大倍数未按盘中进度折算");
        }

        Map<String, Object> data =
                SectorRotation.build(summary, history, benchmarkCloses, benchmarkToday, session, asOf, limit, errors, notes);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) data.get("ranking");
        if (ranking == null || ranking.isEmpty()) {
            return failed(asOf, errors, notes);
        }
        ToolResult result = ToolResult.ok(null, asOf, sources(data, now.format(TIMESTAMP)), data);
        cacheKey = key;
        cachedAt = now;
        cached = result;
        return result;
    }

    /** 每个板块一次日线请求；单个板块失败只记进 {@code errors}，不影响其他板块。 */
    private Map<String, List<Map<String, Object>>> histories(List<Map<String, Object>> summary, Map<String, String> errors) {
        ThsBoardHistorySource source = new ThsBoardHistorySource(client);
        Map<String, List<Map<String, Object>>> history = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<Callable<List<Map<String, Object>>>> tasks = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> row : summary) {
            String name = text(row.get("板块")).strip();
            String code = text(row.get("代码"));
            if (name.isEmpty() || code.isEmpty()) {
                continue;
            }
            codes.add(name);
            tasks.add(() -> source.rows(code));
        }
        ExecutorService pool = Executors.newFixedThreadPool(MAX_WORKERS);
        try {
            List<Future<List<Map<String, Object>>>> results = pool.invokeAll(tasks);
            for (int index = 0; index < results.size(); index++) {
                String name = codes.get(index);
                try {
                    history.put(name, results.get(index).get());
                } catch (ExecutionException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    failures.put(name, cause.getMessage() == null ? "空数据" : cause.getMessage());
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failures.put("interrupted", "板块历史取数被中断");
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
            errors.put("history", "板块历史 " + failures.size() + " 个失败，例如 " + String.join("；", sample));
        }
        return history;
    }

    private ToolResult cached(String key, LocalDateTime now) {
        if (!key.equals(cacheKey) || cachedAt == null || cached == null) {
            return null;
        }
        return Duration.between(cachedAt, now).getSeconds() < CACHE_SECONDS ? cached : null;
    }

    private static List<Source> sources(Map<String, Object> data, String fetched) {
        String tradeDate = text(data.get("trade_date"));
        String asOf = tradeDate.isEmpty() ? text(data.get("as_of")) : tradeDate;
        String benchmark = text(data.get("benchmark"));
        return List.of(
                new Source("sector:ths:" + asOf, "行业板块", "同花顺行业指数", asOf, fetched),
                new Source(
                        "sector:benchmark:" + SectorRotation.BENCHMARK_CODE,
                        "基准指数",
                        benchmark.isEmpty() ? SectorRotation.BENCHMARK_NAME : benchmark,
                        asOf,
                        fetched));
    }

    /** 行业快照都取不到时的形状：与 Python 早退分支的键一致。 */
    private static ToolResult failed(String asOf, Map<String, String> errors, List<String> notes) {
        List<String> detail = new ArrayList<>();
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            detail.add(entry.getKey() + "：" + entry.getValue());
        }
        return ToolResult.failure(
                null, "行业轮动获取失败：" + (detail.isEmpty() ? "没有可用板块数据" : String.join("；", detail)));
    }

    private static String date(Object value) {
        String text = text(value);
        return text.length() > 10 ? text.substring(0, 10) : text;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
