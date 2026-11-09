package com.investmentresearch.data.persistence;

import com.investmentresearch.core.domain.screen.ScreenFinance;
import com.investmentresearch.core.domain.screen.ScreenQuote;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 选股快照表：行情整天替换、财务按 (code, report_date) 合并。 */
@Component
public class ScreenSnapshotStore {
    private static final String INSERT_QUOTE =
            "insert into screen_quote (code, name, industry, price, chg_pct, amount_yi, turnover,"
                    + " pe, pb, total_cap_yi, trade_date, fetched_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPSERT_FINANCE =
            "insert into screen_finance (code, report_date, eps, revenue, net_profit, roe,"
                    + " gross_margin, revenue_yoy, profit_yoy, ocf_per_share, update_date, fetched_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " on conflict(code, report_date) do update set eps = excluded.eps,"
                    + " revenue = excluded.revenue, net_profit = excluded.net_profit,"
                    + " roe = excluded.roe, gross_margin = excluded.gross_margin,"
                    + " revenue_yoy = excluded.revenue_yoy, profit_yoy = excluded.profit_yoy,"
                    + " ocf_per_share = excluded.ocf_per_share, update_date = excluded.update_date,"
                    + " fetched_at = excluded.fetched_at";

    private final JdbcTemplate jdbc;

    @Autowired
    public ScreenSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 整表替换：上一轮有、这一轮没有的代码必须消失，不能留下陈旧行。 */
    public void replaceQuotes(List<ScreenQuote> rows, String fetchedAt) {
        jdbc.update("delete from screen_quote");
        jdbc.batchUpdate(
                INSERT_QUOTE,
                rows.stream()
                        .map(
                                row ->
                                        new Object[] {
                                            row.code(),
                                            row.name(),
                                            row.industry(),
                                            row.price(),
                                            row.chgPct(),
                                            row.amountYi(),
                                            row.turnover(),
                                            row.pe(),
                                            row.pb(),
                                            row.totalCapYi(),
                                            row.tradeDate(),
                                            fetchedAt
                                        })
                        .toList());
    }

    public void upsertFinance(List<ScreenFinance> rows, String fetchedAt) {
        jdbc.batchUpdate(
                UPSERT_FINANCE,
                rows.stream()
                        .map(
                                row ->
                                        new Object[] {
                                            row.code(),
                                            row.reportDate(),
                                            row.eps(),
                                            row.revenue(),
                                            row.netProfit(),
                                            row.roe(),
                                            row.grossMargin(),
                                            row.revenueYoy(),
                                            row.profitYoy(),
                                            row.ocfPerShare(),
                                            row.updateDate(),
                                            fetchedAt
                                        })
                        .toList());
    }

    public List<ScreenQuote> loadQuotes() {
        return jdbc.query(
                "select code, name, industry, price, chg_pct, amount_yi, turnover, pe, pb,"
                        + " total_cap_yi, trade_date from screen_quote order by code",
                (rs, rowNum) ->
                        new ScreenQuote(
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("industry"),
                                nullableDouble(rs, "price"),
                                nullableDouble(rs, "chg_pct"),
                                nullableDouble(rs, "amount_yi"),
                                nullableDouble(rs, "turnover"),
                                nullableDouble(rs, "pe"),
                                nullableDouble(rs, "pb"),
                                nullableDouble(rs, "total_cap_yi"),
                                rs.getString("trade_date")));
    }

    public List<ScreenFinance> loadFinance() {
        return jdbc.query(
                "select code, report_date, eps, revenue, net_profit, roe, gross_margin,"
                        + " revenue_yoy, profit_yoy, ocf_per_share, update_date from screen_finance"
                        + " order by code, report_date desc",
                (rs, rowNum) ->
                        new ScreenFinance(
                                rs.getString("code"),
                                rs.getString("report_date"),
                                nullableDouble(rs, "eps"),
                                nullableDouble(rs, "revenue"),
                                nullableDouble(rs, "net_profit"),
                                nullableDouble(rs, "roe"),
                                nullableDouble(rs, "gross_margin"),
                                nullableDouble(rs, "revenue_yoy"),
                                nullableDouble(rs, "profit_yoy"),
                                nullableDouble(rs, "ocf_per_share"),
                                rs.getString("update_date")));
    }

    /** 行情里最新的交易日；还没有快照时为 {@code null}。 */
    public String latestTradeDate() {
        return jdbc.queryForObject("select max(trade_date) from screen_quote", String.class);
    }

    /** 行情优先取抓取时间，行情为空时退回财务表的抓取时间。 */
    public String latestFetchedAt() {
        String quotes = jdbc.queryForObject("select max(fetched_at) from screen_quote", String.class);
        return quotes != null
                ? quotes
                : jdbc.queryForObject("select max(fetched_at) from screen_finance", String.class);
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
