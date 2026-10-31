package com.investmentresearch.data.persistence;

import com.investmentresearch.core.domain.KlineBar;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 日线缓存（{@code kline_daily}）：按 {@code code + trade_date} upsert，
 * 供日 K 面板在换公司/刷新时直接读库，避免反复打上游。
 */
@Component
public class KlineDailyStore {
    private static final String UPSERT =
            "insert into kline_daily"
                    + " (code, trade_date, open, close, high, low, volume, amount, fetched_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " on conflict(code, trade_date) do update set"
                    + " open = excluded.open, close = excluded.close, high = excluded.high,"
                    + " low = excluded.low, volume = excluded.volume, amount = excluded.amount,"
                    + " fetched_at = excluded.fetched_at";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public KlineDailyStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    public KlineDailyStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 最近的 {@code days} 根，按日期升序；库里不够就有多少返回多少。 */
    public List<KlineBar> load(String code, int days) {
        List<KlineBar> bars =
                jdbc.query(
                        "select trade_date, open, close, high, low, volume, amount"
                                + " from kline_daily where code = ? order by trade_date desc limit ?",
                        (rs, row) -> bar(rs),
                        code,
                        Math.max(1, days));
        Collections.reverse(bars);
        return bars;
    }

    /** 这批数据最后一次抓取的时间；没缓存过返回 null。 */
    public Instant lastFetchedAt(String code) {
        Long stamp =
                jdbc.query(
                        "select max(fetched_at) from kline_daily where code = ?",
                        rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null,
                        code);
        return stamp == null ? null : Instant.ofEpochMilli(stamp);
    }

    /** 整批覆盖写入；上游返回的是一段连续窗口，逐日 upsert 即可。 */
    public void save(String code, List<KlineBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return;
        }
        long fetchedAt = clock.millis();
        jdbc.batchUpdate(
                UPSERT,
                bars,
                bars.size(),
                (ps, bar) -> {
                    ps.setString(1, code);
                    ps.setString(2, bar.date());
                    ps.setObject(3, bar.open());
                    ps.setObject(4, bar.close());
                    ps.setObject(5, bar.high());
                    ps.setObject(6, bar.low());
                    ps.setObject(7, bar.volume());
                    ps.setObject(8, bar.amount());
                    ps.setLong(9, fetchedAt);
                });
    }

    private static KlineBar bar(ResultSet rs) throws SQLException {
        return new KlineBar(
                rs.getString("trade_date"),
                number(rs, "open"),
                number(rs, "close"),
                number(rs, "high"),
                number(rs, "low"),
                number(rs, "volume"),
                number(rs, "amount"));
    }

    private static Double number(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
