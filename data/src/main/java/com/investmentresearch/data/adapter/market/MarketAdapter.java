package com.investmentresearch.data.adapter.market;

import com.investmentresearch.core.domain.Source;
import com.investmentresearch.core.domain.ToolResult;
import com.investmentresearch.core.port.MarketPort;
import com.investmentresearch.core.research.MarketState;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Port of {@code stock.market.build_market_overview()}：六段取数各自独立失败，组装成一份盘面，
 * 附上 {@link MarketState} 的状态分类。
 *
 * <p>与 Python 一样，任一段取不到只记进 {@code errors}，其余照常返回；六段全空才是失败结果。
 */
@Component
public final class MarketAdapter implements MarketPort {
    static final List<String> INDEX_CODES =
            List.of("sh000001", "sz399001", "sz399006", "sh000300", "sh000905", "sh000852", "sh000688");
    static final List<String> INDEX_NAMES =
            List.of("上证指数", "深证成指", "创业板指", "沪深300", "中证500", "中证1000", "科创50");
    static final int SECTOR_TOP = 8;
    static final int SECTOR_BOTTOM = 5;
    static final int NEWS_LIMIT = 10;
    static final int DISTRIBUTION_TOP = 5;
    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UpstreamClient client;
    private final Clock clock;

    @Autowired
    public MarketAdapter(UpstreamClient client) {
        this(client, Clock.systemDefaultZone());
    }

    MarketAdapter(UpstreamClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public ToolResult overview() {
        Map<String, Object> errors = new LinkedHashMap<>();

        LeguBreadthSource.Breadth breadth = null;
        try {
            breadth = new LeguBreadthSource(client).fetch();
        } catch (RuntimeException error) {
            errors.put("breadth", "市场宽度：" + message(error));
        }

        String nowText = LocalDateTime.now(clock).format(TIMESTAMP);
        String asOf = breadth == null || breadth.asOf().isEmpty() ? nowText : breadth.asOf();
        String today = LocalDate.now(clock).toString();

        List<Map<String, Object>> indexRows = List.of();
        try {
            indexRows = new SinaIndexSource(client).rows();
        } catch (RuntimeException error) {
            errors.put("indices", "指数行情：" + message(error));
        }

        Map<String, List<Map<String, Object>>> trendRows = Map.of();
        try {
            TencentTrendSource.Trend trend = new TencentTrendSource(client).fetch();
            trendRows = trend.daily();
            if (!trend.failures().isEmpty()) {
                errors.put("trend", "指数日线：" + String.join("；", trend.failures()));
            }
        } catch (RuntimeException error) {
            errors.put("trend", "指数日线：" + message(error));
        }

        List<Map<String, Object>> sectorRows = List.of();
        try {
            sectorRows = new ThsSectorSource(client).rows();
        } catch (RuntimeException error) {
            errors.put("sectors", "行业板块：" + message(error));
        }

        List<Map<String, Object>> limitRows = List.of();
        String tradeDate = asOf.length() >= 10 ? asOf.substring(0, 10).replace("-", "") : "";
        if (tradeDate.length() == 8) {
            try {
                limitRows = new LimitUpSource(client).rows(tradeDate);
            } catch (RuntimeException error) {
                errors.put("limit_up", "涨停池：" + message(error));
            }
        }

        List<Map<String, Object>> newsRows = List.of();
        try {
            newsRows = new ClsNewsSource(client).rows();
        } catch (RuntimeException error) {
            errors.put("news", "市场快讯：" + message(error));
        }

        Map<String, Object> session = sessionState(asOf, today);
        List<Map<String, Object>> indices = indexSnapshot(indexRows);
        List<Map<String, Object>> trend = trendList(trendRows, indices);
        Map<String, Object> breadthMap = breadth(breadth);
        Map<String, Object> sectors = sectors(sectorRows);
        Map<String, Object> limitUp = limitUp(limitRows);
        List<Map<String, Object>> news = news(newsRows);
        Map<String, Object> turnover = turnover(indexRows, trendRows, session);

        boolean hasData =
                !indices.isEmpty()
                        || breadthMap != null
                        || !trend.isEmpty()
                        || sectors != null
                        || limitUp != null
                        || !news.isEmpty();
        if (!hasData) {
            List<String> detail = new ArrayList<>();
            for (Map.Entry<String, Object> entry : errors.entrySet()) {
                detail.add(entry.getKey() + "：" + entry.getValue());
            }
            return ToolResult.failure(
                    null,
                    "大盘数据获取失败：" + (detail.isEmpty() ? "没有可用数据" : String.join("；", detail)));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("as_of", asOf);
        data.put("session", session);
        data.put("indices", indices);
        data.put("turnover", turnover);
        data.put("breadth", breadthMap);
        data.put("trend", trend);
        data.put("sectors", sectors);
        data.put("limit_up", limitUp);
        data.put("news", news);
        data.put("errors", errors);
        data.put("state", MarketState.classify(data));
        return ToolResult.ok(null, asOf, sources(data), data);
    }

    private List<Source> sources(Map<String, Object> overview) {
        String asOf = overview.get("as_of") == null ? "" : overview.get("as_of").toString();
        String fetched = LocalDateTime.now(clock).format(TIMESTAMP);
        String stamp = stamp(asOf);
        String date = date(asOf);
        List<Source> sources = new ArrayList<>();
        if (truthy(overview.get("indices"))) {
            sources.add(new Source("market:index:" + stamp, "指数行情", "新浪财经", asOf, fetched));
        }
        if (truthy(overview.get("breadth"))) {
            sources.add(new Source("market:breadth:" + stamp, "市场宽度", "乐咕乐股", asOf, fetched));
        }
        List<Map<String, Object>> trend = trendRowsOf(overview.get("trend"));
        if (!trend.isEmpty()) {
            String trendDate = "";
            for (Map<String, Object> item : trend) {
                String value = item.get("date") == null ? "" : item.get("date").toString();
                if (value.compareTo(trendDate) > 0) {
                    trendDate = value;
                }
            }
            if (trendDate.isEmpty()) {
                trendDate = date;
            }
            sources.add(new Source("market:trend:" + trendDate, "指数日线", "新浪财经", trendDate, fetched));
        }
        if (truthy(overview.get("sectors"))) {
            sources.add(new Source("market:sector:" + stamp, "行业板块", "同花顺", asOf, fetched));
        }
        if (truthy(overview.get("limit_up"))) {
            sources.add(new Source("market:limitup:" + date, "涨停池", "东方财富", date, fetched));
        }
        if (truthy(overview.get("news"))) {
            sources.add(new Source("market:news:" + stamp, "市场快讯", "财联社", asOf, fetched));
        }
        if (overview.get("state") != null) {
            sources.add(new Source("market:state:" + stamp, "市场状态", "规则计算", asOf, fetched));
        }
        return sources;
    }

    /** Python {@code session_state}：由数据时点判断盘前/盘中/午间/收盘，并给出交易时段进度。 */
    static Map<String, Object> sessionState(String clockText, String today) {
        Map<String, Object> state = new LinkedHashMap<>();
        String text = clockText == null ? "" : clockText.strip();
        LocalDateTime parsed = parseTimestamp(text);
        if (parsed == null) {
            state.put("state", "时点未知");
            state.put("progress", null);
            return state;
        }
        if (today != null && !today.isEmpty() && !parsed.toLocalDate().toString().equals(today)) {
            state.put("state", "最近交易日");
            state.put("progress", 1.0);
            return state;
        }
        int minutes = parsed.getHour() * 60 + parsed.getMinute();
        int morningOpen = 9 * 60 + 30;
        int morningClose = 11 * 60 + 30;
        int afternoonOpen = 13 * 60;
        int afternoonClose = 15 * 60;
        if (minutes < morningOpen) {
            state.put("state", "盘前");
            state.put("progress", 0.0);
        } else if (minutes <= morningClose) {
            state.put("state", "盘中");
            state.put("progress", round3((minutes - morningOpen) / 240.0));
        } else if (minutes < afternoonOpen) {
            state.put("state", "午间休市");
            state.put("progress", 0.5);
        } else if (minutes <= afternoonClose) {
            state.put("state", "盘中");
            state.put("progress", round3((120 + minutes - afternoonOpen) / 240.0));
        } else {
            state.put("state", "收盘");
            state.put("progress", 1.0);
        }
        return state;
    }

    /** Python {@code trend_stats}：均线只用已完成日线，{@code price} 是最新价。 */
    static Map<String, Object> trendStats(List<Map<String, Object>> rows, Double price) {
        List<Map<String, Object>> bars = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (MarketSupport.number(row.get("close")) != null) {
                bars.add(row);
            }
        }
        bars.sort((left, right) -> text(left.get("date")).compareTo(text(right.get("date"))));
        List<Double> closes = new ArrayList<>();
        for (Map<String, Object> bar : bars) {
            closes.add(MarketSupport.number(bar.get("close")));
        }
        if (closes.isEmpty()) {
            return null;
        }
        double last = closes.get(closes.size() - 1);
        Double livePrice = MarketSupport.number(price);
        double live = livePrice == null || livePrice == 0 ? last : livePrice;

        Double ma20 = ma(closes, 20);
        Double ma60 = ma(closes, 60);
        List<Double> window = closes.subList(Math.max(0, closes.size() - 20), closes.size());
        // Python 的 zip(closes[-21:-1], closes[-20:])：最后 20 个日收益
        List<Double> returns = new ArrayList<>();
        int start = Math.max(1, closes.size() - 20);
        for (int i = start; i < closes.size(); i++) {
            double previous = closes.get(i - 1);
            if (previous != 0) {
                returns.add(closes.get(i) / previous - 1);
            }
        }
        Double volatility = null;
        if (returns.size() >= 10) {
            double average = 0;
            for (double value : returns) {
                average += value;
            }
            average /= returns.size();
            double variance = 0;
            for (double value : returns) {
                variance += (value - average) * (value - average);
            }
            variance /= returns.size() - 1;
            volatility = MarketSupport.round(Math.sqrt(variance) * Math.sqrt(252) * 100, 2);
        }

        double high = window.get(0);
        double low = window.get(0);
        for (double value : window) {
            high = Math.max(high, value);
            low = Math.min(low, value);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("date", text(bars.get(bars.size() - 1).get("date")));
        stats.put("close", MarketSupport.round(last, 2));
        stats.put("price", MarketSupport.round(live, 2));
        stats.put("ma5", ma(closes, 5));
        stats.put("ma10", ma(closes, 10));
        stats.put("ma20", ma20);
        stats.put("ma60", ma60);
        stats.put("above_ma20", ma20 == null ? null : live > ma20);
        stats.put("above_ma60", ma60 == null ? null : live > ma60);
        stats.put("ma20_distance_pct", ma20 == null || ma20 == 0 ? null : MarketSupport.round((live / ma20 - 1) * 100, 2));
        stats.put("chg_5d_pct", change(closes, live, 5));
        stats.put("chg_20d_pct", change(closes, live, 20));
        stats.put("high_20d", MarketSupport.round(high, 2));
        stats.put("low_20d", MarketSupport.round(low, 2));
        stats.put("volatility_20d_pct", volatility);
        return stats;
    }

    private static Double ma(List<Double> closes, int days) {
        if (closes.size() < days) {
            return null;
        }
        double total = 0;
        for (int i = closes.size() - days; i < closes.size(); i++) {
            total += closes.get(i);
        }
        return MarketSupport.round(total / days, 2);
    }

    private static Double change(List<Double> closes, double live, int days) {
        if (closes.size() <= days) {
            return null;
        }
        double base = closes.get(closes.size() - 1 - days);
        return base == 0 ? null : MarketSupport.round((live / base - 1) * 100, 2);
    }

    private static List<Map<String, Object>> indexSnapshot(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byCode.put(text(row.get("代码")), row);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < INDEX_CODES.size(); i++) {
            Map<String, Object> row = byCode.get(INDEX_CODES.get(i));
            if (row == null) {
                continue;
            }
            Double price = MarketSupport.number(row.get("最新价"));
            if (price == null) {
                continue;
            }
            Double amount = MarketSupport.number(row.get("成交额"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", INDEX_CODES.get(i));
            item.put("name", text(row.get("名称")).isEmpty() ? INDEX_NAMES.get(i) : text(row.get("名称")));
            item.put("price", MarketSupport.round(price, 2));
            item.put("chg", MarketSupport.round(MarketSupport.number(row.get("涨跌额")), 2));
            item.put("chg_pct", MarketSupport.round(MarketSupport.number(row.get("涨跌幅")), 2));
            item.put("open", MarketSupport.round(MarketSupport.number(row.get("今开")), 2));
            item.put("high", MarketSupport.round(MarketSupport.number(row.get("最高")), 2));
            item.put("low", MarketSupport.round(MarketSupport.number(row.get("最低")), 2));
            item.put("prev_close", MarketSupport.round(MarketSupport.number(row.get("昨收")), 2));
            item.put("amount_yi", amount == null ? null : MarketSupport.round(amount / 1e8, 1));
            items.add(item);
        }
        return items;
    }

    private static List<Map<String, Object>> trendList(
            Map<String, List<Map<String, Object>>> trendRows, List<Map<String, Object>> indices) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Double> prices = new LinkedHashMap<>();
        for (Map<String, Object> item : indices) {
            names.put(text(item.get("code")), text(item.get("name")));
            prices.put(text(item.get("code")), MarketSupport.number(item.get("price")));
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < INDEX_CODES.size(); i++) {
            String code = INDEX_CODES.get(i);
            Map<String, Object> stats = trendStats(trendRows.getOrDefault(code, List.of()), prices.get(code));
            if (stats == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", names.getOrDefault(code, INDEX_NAMES.get(i)));
            item.putAll(stats);
            trend.add(item);
        }
        return trend;
    }

    /** 两市成交额与量能；成交量的单位统一成股（新浪快照沪市是手、深市是股）。 */
    private static Map<String, Object> turnover(
            List<Map<String, Object>> indexRows,
            Map<String, List<Map<String, Object>>> trendRows,
            Map<String, Object> session) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : indexRows) {
            byCode.put(text(row.get("代码")), row);
        }
        Map<String, Object> sh = byCode.get("sh000001");
        Map<String, Object> sz = byCode.get("sz399001");
        if (sh == null && sz == null) {
            return null;
        }
        Double shAmount = sh == null ? null : MarketSupport.number(sh.get("成交额"));
        Double szAmount = sz == null ? null : MarketSupport.number(sz.get("成交额"));
        double total = 0;
        if (shAmount != null) {
            total += shAmount;
        }
        if (szAmount != null) {
            total += szAmount;
        }

        double todayVolume = 0;
        boolean haveToday = false;
        if (sh != null) {
            Double shares = spotVolumeShares("sh000001", sh.get("成交量"));
            if (shares != null) {
                todayVolume += shares;
                haveToday = true;
            }
        }
        if (sz != null) {
            Double shares = spotVolumeShares("sz399001", sz.get("成交量"));
            if (shares != null) {
                todayVolume += shares;
                haveToday = true;
            }
        }

        List<Double> averages = new ArrayList<>();
        for (String code : List.of("sh000001", "sz399001")) {
            List<Map<String, Object>> bars = trendRows.getOrDefault(code, List.of());
            List<Double> volumes = new ArrayList<>();
            for (Map<String, Object> bar : bars.subList(Math.max(0, bars.size() - 20), bars.size())) {
                Double volume = MarketSupport.number(bar.get("volume"));
                if (volume != null) {
                    volumes.add(volume);
                }
            }
            if (!volumes.isEmpty()) {
                double sum = 0;
                for (double value : volumes) {
                    sum += value;
                }
                averages.add(sum / volumes.size());
            }
        }
        Double average = averages.size() == 2 ? averages.get(0) + averages.get(1) : null;

        Double progress = MarketSupport.number(session.get("progress"));
        Double ratio = null;
        if (haveToday && average != null && average != 0 && progress != null && progress >= 0.1) {
            ratio = MarketSupport.round(todayVolume / (average * progress), 2);
        }

        Map<String, Object> turnover = new LinkedHashMap<>();
        turnover.put("sh_yi", shAmount == null ? null : MarketSupport.round(shAmount / 1e8, 1));
        turnover.put("sz_yi", szAmount == null ? null : MarketSupport.round(szAmount / 1e8, 1));
        turnover.put("total_yi", total == 0 ? null : MarketSupport.round(total / 1e8, 1));
        turnover.put("today_shares_yi", haveToday ? MarketSupport.round(todayVolume / 1e8, 1) : null);
        turnover.put("avg20_shares_yi", average == null ? null : MarketSupport.round(average / 1e8, 1));
        turnover.put("pace_ratio", ratio);
        turnover.put("progress", progress);
        turnover.put("note", "pace_ratio 是按盘中成交速度折算全天的估算值");
        return turnover;
    }

    /** 新浪指数快照的成交量：沪市是手，深市是股。 */
    private static Double spotVolumeShares(String code, Object value) {
        Double number = MarketSupport.number(value);
        if (number == null) {
            return null;
        }
        return code.startsWith("sh") ? number * 100 : number;
    }

    private static Map<String, Object> breadth(LeguBreadthSource.Breadth breadth) {
        if (breadth == null) {
            return null;
        }
        Map<String, Object> values = breadth.byItem();
        Double up = MarketSupport.number(values.get("上涨"));
        Double down = MarketSupport.number(values.get("下跌"));
        if (up == null && down == null) {
            return null;
        }
        double flat = MarketSupport.number(values.get("平盘")) == null ? 0 : MarketSupport.number(values.get("平盘"));
        Double total = up != null && down != null ? up + down + flat : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("up", MarketSupport.round(up, 0));
        result.put("down", MarketSupport.round(down, 0));
        result.put("flat", MarketSupport.round(flat, 0));
        result.put("limit_up", MarketSupport.round(MarketSupport.number(values.get("涨停")), 0));
        result.put("limit_down", MarketSupport.round(MarketSupport.number(values.get("跌停")), 0));
        result.put("real_limit_up", MarketSupport.round(MarketSupport.number(values.get("真实涨停")), 0));
        result.put("real_limit_down", MarketSupport.round(MarketSupport.number(values.get("真实跌停")), 0));
        result.put("suspended", MarketSupport.round(MarketSupport.number(values.get("停牌")), 0));
        result.put("activity_pct", MarketSupport.round(MarketSupport.number(values.get("活跃度")), 2));
        result.put(
                "up_ratio_pct",
                total == null || total == 0 || up == null
                        ? null
                        : MarketSupport.round(up / total * 100, 1));
        return result;
    }

    private static Map<String, Object> sectors(List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Double change = MarketSupport.number(row.get("涨跌幅"));
            if (change == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", text(row.get("板块")).strip());
            item.put("chg_pct", MarketSupport.round(change, 2));
            item.put("amount_yi", MarketSupport.round(MarketSupport.number(row.get("总成交额")), 1));
            item.put("inflow_yi", MarketSupport.round(MarketSupport.number(row.get("净流入")), 2));
            item.put("up", MarketSupport.round(MarketSupport.number(row.get("上涨家数")), 0));
            item.put("down", MarketSupport.round(MarketSupport.number(row.get("下跌家数")), 0));
            String leader = text(row.get("领涨股")).strip();
            item.put("leader", leader.isEmpty() ? null : leader);
            item.put("leader_chg_pct", MarketSupport.round(MarketSupport.number(row.get("领涨股-涨跌幅")), 2));
            items.add(item);
        }
        if (items.isEmpty()) {
            return null;
        }
        items.sort((left, right) -> Double.compare(MarketSupport.number(right.get("chg_pct")), MarketSupport.number(left.get("chg_pct"))));
        List<Map<String, Object>> top = new ArrayList<>(items.subList(0, Math.min(SECTOR_TOP, items.size())));
        List<Map<String, Object>> rest = new ArrayList<>(items.subList(Math.min(SECTOR_TOP, items.size()), items.size()));
        List<Map<String, Object>> bottom = new ArrayList<>();
        for (int i = rest.size() - 1; i >= Math.max(0, rest.size() - SECTOR_BOTTOM); i--) {
            bottom.add(rest.get(i));
        }
        int upCount = 0;
        int downCount = 0;
        for (Map<String, Object> item : items) {
            double change = MarketSupport.number(item.get("chg_pct"));
            if (change > 0) {
                upCount++;
            } else if (change < 0) {
                downCount++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("top", top);
        result.put("bottom", bottom);
        result.put("up_count", upCount);
        result.put("down_count", downCount);
        result.put("total", items.size());
        return result;
    }

    private static Map<String, Object> limitUp(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        java.util.TreeMap<Integer, Integer> streaks = new java.util.TreeMap<>();
        Map<String, Integer> industries = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Double boards = MarketSupport.number(row.get("连板数"));
            int boardsValue = boards == null ? 1 : boards.intValue();
            streaks.merge(boardsValue, 1, Integer::sum);
            String industry = text(row.get("所属行业")).strip();
            if (!industry.isEmpty()) {
                industries.merge(industry, 1, Integer::sum);
            }
        }
        if (streaks.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> streakList = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : streaks.descendingMap().entrySet()) {
            streakList.add(MarketSupport.row("boards", entry.getKey(), "count", entry.getValue()));
        }
        List<Map<String, Object>> topIndustries = new ArrayList<>();
        industries.entrySet().stream()
                .sorted((left, right) -> right.getValue() - left.getValue())
                .limit(DISTRIBUTION_TOP)
                .forEach(entry -> topIndustries.add(MarketSupport.row("name", entry.getKey(), "count", entry.getValue())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", rows.size());
        result.put("max_streak", streaks.lastKey());
        result.put("streaks", streakList);
        result.put("top_industries", topIndustries);
        return result;
    }

    private static List<Map<String, Object>> news(List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String title = text(row.get("标题")).strip();
            if (title.isEmpty()) {
                continue;
            }
            String date = text(row.get("发布日期")).strip();
            String time = text(row.get("发布时间")).strip();
            String stamp = (date + " " + time).strip();
            items.add(MarketSupport.row("time", stamp, "title", title));
            if (items.size() >= NEWS_LIMIT) {
                break;
            }
        }
        return items;
    }

    private static String stamp(String asOf) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < asOf.length(); i++) {
            if (Character.isDigit(asOf.charAt(i))) {
                digits.append(asOf.charAt(i));
            }
        }
        return digits.length() == 0 ? "latest" : digits.toString();
    }

    private static String date(String asOf) {
        String text = asOf.strip();
        return text.length() >= 10 ? text.substring(0, 10) : "latest";
    }

    private static LocalDateTime parseTimestamp(String text) {
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern));
            } catch (RuntimeException error) {
                // try the next format
            }
        }
        return null;
    }

    private static Double round3(double value) {
        return MarketSupport.round(value, 3);
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? "" : error.getMessage();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof CharSequence text) {
            return !text.isEmpty();
        }
        if (value instanceof java.util.Collection<?> items) {
            return !items.isEmpty();
        }
        if (value instanceof Map<?, ?> item) {
            return !item.isEmpty();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> trendRowsOf(Object value) {
        return value instanceof List<?> items ? (List<Map<String, Object>>) items : List.of();
    }
}
