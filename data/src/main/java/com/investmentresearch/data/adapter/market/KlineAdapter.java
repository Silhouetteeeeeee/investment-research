package com.investmentresearch.data.adapter.market;

import com.investmentresearch.core.domain.KlineBar;
import com.investmentresearch.core.port.KlinePort;
import com.investmentresearch.data.persistence.KlineDailyStore;
import com.investmentresearch.data.upstream.UpstreamClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * 日 K 面板取数：腾讯优先、东财兜底，结果落 {@code kline_daily}。
 * 同一只股票当天最多请求一次；收盘后允许再刷一次；上游全挂时退回旧缓存。
 */
@Repository
public class KlineAdapter implements KlinePort {
    // 面板最多看 500 根，一次多取一些存库，之后切回同一只股票就不用再请求
    static final int FETCH_DAYS = 500;
    private static final LocalTime CLOSE = LocalTime.of(15, 0);

    private final UpstreamClient client;
    private final KlineDailyStore store;
    private final Clock clock;

    @Autowired
    public KlineAdapter(UpstreamClient client, KlineDailyStore store) {
        this(client, store, Clock.systemDefaultZone());
    }

    KlineAdapter(UpstreamClient client, KlineDailyStore store, Clock clock) {
        this.client = client;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public List<KlineBar> daily(String code, int days) {
        List<KlineBar> cached = store.load(code, days);
        if (fresh(code)) {
            return cached;
        }
        try {
            List<KlineBar> bars = fetch(code, Math.max(days, FETCH_DAYS));
            store.save(code, bars);
            return bars.size() <= days ? bars : new ArrayList<>(bars.subList(bars.size() - days, bars.size()));
        } catch (RuntimeException error) {
            // 抓不到时旧缓存比报错有用；第一次就没有缓存才把错误抛出去
            if (!cached.isEmpty()) {
                return cached;
            }
            throw error;
        }
    }

    /** 当天抓过就复用；15:00 前抓的，收盘后再补一次。 */
    private boolean fresh(String code) {
        Instant fetched = store.lastFetchedAt(code);
        if (fetched == null) {
            return false;
        }
        ZonedDateTime at = fetched.atZone(clock.getZone());
        ZonedDateTime now = Instant.now(clock).atZone(clock.getZone());
        if (!at.toLocalDate().equals(now.toLocalDate())) {
            return false;
        }
        return at.toLocalTime().isAfter(CLOSE) || !now.toLocalTime().isAfter(CLOSE);
    }

    private List<KlineBar> fetch(String code, int days) {
        try {
            return toBars(new TencentStockDailySource(client).rows(code, days));
        } catch (RuntimeException tencent) {
            try {
                return toBars(new EmStockDailySource(client).rows(prefixed(code), days, 1));
            } catch (RuntimeException eastmoney) {
                throw new MarketSupport.MarketException(
                        "日线取数失败（腾讯："
                                + message(tencent)
                                + "；东财："
                                + message(eastmoney)
                                + "）");
            }
        }
    }

    private static List<KlineBar> toBars(List<Map<String, Object>> rows) {
        List<KlineBar> bars = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            bars.add(
                    new KlineBar(
                            String.valueOf(row.get("date")),
                            number(row.get("open")),
                            number(row.get("close")),
                            number(row.get("high")),
                            number(row.get("low")),
                            number(row.get("volume")),
                            number(row.get("amount"))));
        }
        return bars;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 东财只区分沪市（1）与其他（0），北交所归到 0 也能取到。 */
    private static String prefixed(String code) {
        return (code.startsWith("6") || code.startsWith("9") ? "sh" : "sz") + code;
    }
}
