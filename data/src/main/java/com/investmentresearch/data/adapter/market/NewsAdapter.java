package com.investmentresearch.data.adapter.market;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.NewsPort;
import com.investmentresearch.core.research.NewsEvents;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Port of {@code research.news_events.build_news_events()}：四类来源各自独立失败，合并成一份事件清单。
 */
@Component
public final class NewsAdapter implements NewsPort {
    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> NOTES =
            List.of(
                    "事件按政策、海外、产业、公司的顺序评分排序；同一事件多源报道会合并",
                    "行业映射是关键词候选，不是因果结论；需要结合公告、行业数据与常识继续核实",
                    "海外市场取最近一个交易日收盘，A 股映射不一定同步");

    private final UpstreamClient client;
    private final Clock clock;

    @Autowired
    public NewsAdapter(UpstreamClient client) {
        this(client, Clock.systemDefaultZone());
    }

    NewsAdapter(UpstreamClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public ToolResult news(int limit) {
        LocalDateTime now = LocalDateTime.now(clock);
        String today = now.toLocalDate().toString();
        String stamp = now.format(STAMP);
        String fetched = now.format(TIMESTAMP);

        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> feeds = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();

        try {
            collect(feeds, events, "财联社", "市场快讯", "news:cls:" + stamp,
                    new ClsNewsSource(client).rows(), "cls", today, today);
        } catch (RuntimeException error) {
            errors.put("cls", "财联社：" + message(error));
        }
        try {
            collect(feeds, events, "东方财富", "财经快讯", "news:em:" + stamp,
                    new EmNewsSource(client).rows(), "em", today, today);
        } catch (RuntimeException error) {
            errors.put("em", "东方财富：" + message(error));
        }
        // 新闻联播当天内容常在晚间才出；同时看今天与昨天，避免漏掉前一晚的政策
        for (int offset = 0; offset <= 1; offset++) {
            String day = now.minusDays(offset).format(BASIC_DATE);
            try {
                collect(feeds, events, "新闻联播", "央视新闻", "news:cctv:" + day,
                        new CctvNewsSource(client).rows(day), "cctv", today, day);
            } catch (RuntimeException error) {
                errors.put("cctv:" + day, "新闻联播：" + message(error));
            }
        }

        OverseasMarketSource.Overseas overseas = new OverseasMarketSource(client).fetch(today);
        for (Map.Entry<String, String> entry : overseas.errors().entrySet()) {
            errors.put("overseas:" + entry.getKey(), "海外市场：" + entry.getValue());
        }
        String overseasAsOf = today;
        for (Map<String, Object> item : overseas.items()) {
            String day = text(item.get("date"));
            if (day.compareTo(overseasAsOf) > 0) {
                overseasAsOf = day;
            }
        }
        feeds.add(feed("海外市场", "新浪财经", "news:overseas:" + overseasAsOf, overseas.items().size(), overseasAsOf));

        List<Map<String, Object>> deduped =
                NewsEvents.selectEvents(NewsEvents.dedupeEvents(events), Math.max(1, limit));
        String latest = today.compareTo(overseasAsOf) >= 0 ? today : overseasAsOf;
        for (Map<String, Object> event : deduped) {
            String published = text(event.get("published_at"));
            if (published.compareTo(latest) > 0) {
                latest = published;
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> event : deduped) {
            counts.merge(text(event.get("category")), 1, Integer::sum);
        }

        if (deduped.isEmpty() && overseas.items().isEmpty()) {
            List<String> detail = new ArrayList<>();
            for (Map.Entry<String, String> entry : errors.entrySet()) {
                detail.add(entry.getKey() + "：" + entry.getValue());
            }
            return ToolResult.failure(
                    null,
                    "新闻事件获取失败：" + (detail.isEmpty() ? "没有可用数据" : String.join("；", detail)));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("as_of", latest);
        data.put("events", deduped);
        data.put("overseas", overseas.items());
        data.put("feeds", feeds);
        data.put("counts", counts);
        data.put("errors", errors);
        data.put("notes", new ArrayList<>(NOTES));
        return ToolResult.ok(null, latest, sources(feeds, latest, fetched), data);
    }

    private static void collect(
            List<Map<String, Object>> feeds,
            List<Map<String, Object>> events,
            String name,
            String detail,
            String sourceId,
            List<Map<String, Object>> rows,
            String kind,
            String today,
            String asOf) {
        int count = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> event = NewsEvents.makeEvent(row, name, sourceId, today, kind);
            if (event != null) {
                events.add(event);
                count++;
            }
        }
        feeds.add(feed(name, detail, sourceId, count, asOf));
    }

    private static Map<String, Object> feed(String name, String detail, String sourceId, int count, String asOf) {
        Map<String, Object> feed = new LinkedHashMap<>();
        feed.put("name", name);
        feed.put("detail", detail);
        feed.put("source_id", sourceId);
        feed.put("count", count);
        feed.put("as_of", asOf);
        return feed;
    }

    /** 每个实际返回数据的新闻源登记一条来源，事件里用 {@code source_id} 回指。 */
    private static List<Source> sources(List<Map<String, Object>> feeds, String asOf, String fetched) {
        List<Source> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> feed : feeds) {
            String sourceId = text(feed.get("source_id"));
            Integer count = feed.get("count") instanceof Integer number ? number : 0;
            if (sourceId.isEmpty() || count == 0 || !seen.add(sourceId)) {
                continue;
            }
            String detail = text(feed.get("detail"));
            String name = text(feed.get("name"));
            String feedAsOf = text(feed.get("as_of"));
            sources.add(
                    new Source(
                            sourceId,
                            detail.isEmpty() ? "新闻" : detail,
                            name.isEmpty() ? "来源" : name,
                            feedAsOf.isEmpty() ? asOf : feedAsOf,
                            fetched));
        }
        return sources;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
