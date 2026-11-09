package com.investmentresearch.data.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investmentresearch.core.domain.trade.EquitySnapshot;
import com.investmentresearch.core.domain.trade.ItemSource;
import com.investmentresearch.core.domain.trade.ItemStatus;
import com.investmentresearch.core.domain.trade.JournalEntry;
import com.investmentresearch.core.domain.trade.PhaseRecord;
import com.investmentresearch.core.domain.trade.PlanItem;
import com.investmentresearch.core.domain.trade.Position;
import com.investmentresearch.core.domain.trade.Side;
import com.investmentresearch.core.domain.trade.TickRecord;
import com.investmentresearch.core.domain.trade.TradeAccount;
import com.investmentresearch.core.domain.trade.TradeOrder;
import com.investmentresearch.core.domain.trade.TradePlan;
import com.investmentresearch.core.domain.trade.TradingPhase;
import com.investmentresearch.core.port.TradingRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link TradingRepository} 的 JDBC 实现；SQL 集中在这里，core 不碰数据库类型。 */
@Component
public class TradingRepositoryImpl implements TradingRepository {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final TypeReference<Map<String, Long>> PRICES = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    @Autowired
    public TradingRepositoryImpl(
            JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
    }

    @Override
    public TradeAccount account() {
        return jdbc.queryForObject(
                "select initial_cents, cash_cents from trade_account where id = 1",
                (rs, row) -> new TradeAccount(rs.getLong(1), rs.getLong(2)));
    }

    @Override
    public void saveAccount(TradeAccount account) {
        jdbc.update(
                "update trade_account set initial_cents = ?, cash_cents = ?, updated_at = ?"
                        + " where id = 1",
                account.initialCents(),
                account.cashCents(),
                now());
    }

    @Override
    public List<Position> positions() {
        return jdbc.query(
                "select code, name, shares, avg_cost_cents from trade_position order by code",
                (rs, row) -> position(rs));
    }

    @Override
    public void savePosition(Position position) {
        String stamp = now();
        jdbc.update(
                "insert into trade_position"
                        + " (code, name, shares, avg_cost_cents, opened_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?)"
                        + " on conflict(code) do update set name = excluded.name,"
                        + " shares = excluded.shares, avg_cost_cents = excluded.avg_cost_cents,"
                        + " updated_at = excluded.updated_at",
                position.code(),
                position.name(),
                position.shares(),
                position.avgCostCents(),
                stamp,
                stamp);
    }

    @Override
    public void deletePosition(String code) {
        jdbc.update("delete from trade_position where code = ?", code);
    }

    @Override
    public TradePlan activePlan(String tradeDate) {
        List<TradePlan> plans =
                jdbc.query(
                        PLAN_SELECT
                                + " where trade_date = ? and status = 'ACTIVE'"
                                + " order by version desc limit 1",
                        (rs, row) -> plan(rs),
                        tradeDate);
        if (plans.isEmpty()) {
            return null;
        }
        TradePlan plan = plans.get(0);
        return new TradePlan(
                plan.id(),
                plan.tradeDate(),
                plan.phase(),
                plan.version(),
                plan.notes(),
                plan.runId(),
                plan.status(),
                items(plan.id()));
    }

    @Override
    public TradePlan plan(long planId) {
        List<TradePlan> plans = jdbc.query(PLAN_SELECT + " where id = ?", (rs, row) -> plan(rs), planId);
        if (plans.isEmpty()) {
            return null;
        }
        TradePlan plan = plans.get(0);
        return new TradePlan(
                plan.id(),
                plan.tradeDate(),
                plan.phase(),
                plan.version(),
                plan.notes(),
                plan.runId(),
                plan.status(),
                items(plan.id()));
    }

    @Override
    public List<TradePlan> plans(String tradeDate) {
        return jdbc.query(
                PLAN_SELECT + " where trade_date = ? order by version",
                (rs, row) -> plan(rs),
                tradeDate);
    }

    @Override
    public long insertPlan(TradePlan plan) {
        return insert(
                "insert into trade_plan"
                        + " (trade_date, phase, version, notes, run_id, status, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?)",
                plan.tradeDate(),
                plan.phase().dbValue(),
                plan.version(),
                plan.notes(),
                plan.runId(),
                plan.status(),
                now());
    }

    @Override
    public void updatePlanStatus(long planId, String status) {
        jdbc.update("update trade_plan set status = ? where id = ?", status, planId);
    }

    @Override
    public void cancelPendingAgentItems(long planId) {
        jdbc.update(
                "update trade_plan_item set status = 'CANCELLED', updated_at = ?"
                        + " where plan_id = ? and status = 'PENDING' and source = 'AGENT'",
                now(),
                planId);
    }

    @Override
    public List<PlanItem> pendingAutoSellItems(long planId) {
        return jdbc.query(
                ITEM_SELECT + " where plan_id = ? and source = 'AUTO' and status = 'PENDING'",
                (rs, row) -> item(rs),
                planId);
    }

    @Override
    public PlanItem planItem(long planItemId) {
        List<PlanItem> items =
                jdbc.query(ITEM_SELECT + " where id = ?", (rs, row) -> item(rs), planItemId);
        return items.isEmpty() ? null : items.get(0);
    }

    @Override
    public long insertPlanItem(PlanItem item) {
        String stamp = now();
        return insert(
                "insert into trade_plan_item"
                        + " (plan_id, code, name, side, trigger_price_cents, shares,"
                        + " stop_loss_cents, take_profit_cents, reason, status, source,"
                        + " filled_shares, filled_price_cents, order_id, note, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                item.planId(),
                item.code(),
                item.name(),
                item.side().name(),
                item.triggerPriceCents(),
                item.shares(),
                item.stopLossCents(),
                item.takeProfitCents(),
                item.reason(),
                item.status().name(),
                item.source().name(),
                item.filledShares(),
                item.filledPriceCents(),
                item.orderId(),
                item.note(),
                stamp,
                stamp);
    }

    @Override
    public void updatePlanItem(PlanItem item) {
        jdbc.update(
                "update trade_plan_item set name = ?, side = ?, trigger_price_cents = ?,"
                        + " shares = ?, stop_loss_cents = ?, take_profit_cents = ?, reason = ?,"
                        + " status = ?, source = ?, filled_shares = ?, filled_price_cents = ?,"
                        + " order_id = ?, note = ?, updated_at = ? where id = ?",
                item.name(),
                item.side().name(),
                item.triggerPriceCents(),
                item.shares(),
                item.stopLossCents(),
                item.takeProfitCents(),
                item.reason(),
                item.status().name(),
                item.source().name(),
                item.filledShares(),
                item.filledPriceCents(),
                item.orderId(),
                item.note(),
                now(),
                item.id());
    }

    @Override
    public boolean claimPendingItem(long planItemId, int filledShares, long priceCents) {
        return jdbc.update(
                        "update trade_plan_item set status = 'FILLED', filled_shares = ?,"
                                + " filled_price_cents = ?, updated_at = ?"
                                + " where id = ? and status = 'PENDING'",
                        filledShares,
                        priceCents,
                        now(),
                        planItemId)
                > 0;
    }

    @Override
    public long insertOrder(TradeOrder order) {
        return insert(
                "insert into trade_order"
                        + " (trade_date, plan_item_id, code, name, side, price_cents, shares,"
                        + " amount_cents, fee_cents, tax_cents, realized_pnl_cents, reason,"
                        + " quote_time, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.tradeDate(),
                order.planItemId(),
                order.code(),
                order.name(),
                order.side().name(),
                order.priceCents(),
                order.shares(),
                order.amountCents(),
                order.feeCents(),
                order.taxCents(),
                order.realizedPnlCents(),
                order.reason(),
                order.quoteTime(),
                now());
    }

    @Override
    public List<TradeOrder> orders(String tradeDate) {
        return jdbc.query(
                "select id, trade_date, plan_item_id, code, name, side, price_cents, shares,"
                        + " amount_cents, fee_cents, tax_cents, realized_pnl_cents, reason, quote_time"
                        + " from trade_order where trade_date = ? order by id",
                (rs, row) -> order(rs),
                tradeDate);
    }

    @Override
    public long realizedPnl(String tradeDate) {
        Long value =
                jdbc.queryForObject(
                        "select coalesce(sum(realized_pnl_cents), 0) from trade_order"
                                + " where trade_date = ? and side = 'SELL'",
                        Long.class,
                        tradeDate);
        return value == null ? 0 : value;
    }

    @Override
    public void saveJournal(JournalEntry journal) {
        insert(
                "insert into trade_journal (trade_date, phase, text, run_id, created_at)"
                        + " values (?, ?, ?, ?, ?)",
                journal.tradeDate(),
                journal.phase().dbValue(),
                journal.text(),
                journal.runId(),
                now());
    }

    @Override
    public List<JournalEntry> journals(String tradeDate) {
        return jdbc.query(
                "select id, trade_date, phase, text, run_id from trade_journal"
                        + " where trade_date = ? order by id",
                (rs, row) -> journal(rs),
                tradeDate);
    }

    @Override
    public List<JournalEntry> recentJournals(int limit) {
        return jdbc.query(
                "select id, trade_date, phase, text, run_id from trade_journal"
                        + " order by id desc limit ?",
                (rs, row) -> journal(rs),
                Math.max(1, limit));
    }

    @Override
    public void savePhase(PhaseRecord phase) {
        jdbc.update(
                "insert into trading_phase"
                        + " (trade_date, phase, status, started_at, finished_at, run_id,"
                        + " payload_json, error)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?)"
                        + " on conflict(trade_date, phase) do update set status = excluded.status,"
                        + " started_at = excluded.started_at, finished_at = excluded.finished_at,"
                        + " run_id = excluded.run_id, payload_json = excluded.payload_json,"
                        + " error = excluded.error",
                phase.tradeDate(),
                phase.phase().dbValue(),
                phase.status(),
                phase.startedAt(),
                phase.finishedAt(),
                phase.runId(),
                phase.payloadJson(),
                phase.error());
    }

    @Override
    public PhaseRecord phase(String tradeDate, TradingPhase phase) {
        List<PhaseRecord> rows =
                jdbc.query(
                        PHASE_SELECT + " where trade_date = ? and phase = ?",
                        (rs, row) -> phaseRow(rs),
                        tradeDate,
                        phase.dbValue());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public PhaseRecord phaseById(long id) {
        List<PhaseRecord> rows =
                jdbc.query(PHASE_SELECT + " where id = ?", (rs, row) -> phaseRow(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<PhaseRecord> phases(String tradeDate) {
        return jdbc.query(
                PHASE_SELECT + " where trade_date = ? order by id",
                (rs, row) -> phaseRow(rs),
                tradeDate);
    }

    @Override
    public int markRunningPhasesError(String error) {
        return jdbc.update(
                "update trading_phase set status = 'ERROR', error = ?, finished_at = ?"
                        + " where status = 'RUNNING'",
                error,
                now());
    }

    @Override
    public int expirePendingItems(String tradeDate) {
        return jdbc.update(
                "update trade_plan_item set status = 'EXPIRED', updated_at = ?"
                        + " where status = 'PENDING' and plan_id in"
                        + " (select id from trade_plan where trade_date = ?)",
                now(),
                tradeDate);
    }

    @Override
    public TickRecord latestTick(String tradeDate) {
        List<TickRecord> rows =
                jdbc.query(
                        "select id, trade_date, checked_at, quotes_json, executed_json, error"
                                + " from trading_tick where trade_date = ? order by id desc limit 1",
                        (rs, row) -> tick(rs),
                        tradeDate);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void saveTick(TickRecord tick) {
        insert(
                "insert into trading_tick"
                        + " (trade_date, checked_at, quotes_json, executed_json, error)"
                        + " values (?, ?, ?, ?, ?)",
                tick.tradeDate(),
                tick.checkedAt(),
                tick.quotesJson(),
                tick.executedJson(),
                tick.error());
    }

    @Override
    public EquitySnapshot equity(String tradeDate) {
        List<EquitySnapshot> rows =
                jdbc.query(
                        "select trade_date, cash_cents, market_value_cents, equity_cents,"
                                + " day_pnl_cents, prices_json from trading_equity"
                                + " where trade_date = ?",
                        (rs, row) -> equityRow(rs),
                        tradeDate);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<EquitySnapshot> equitySeries() {
        return jdbc.query(
                "select trade_date, cash_cents, market_value_cents, equity_cents,"
                        + " day_pnl_cents, prices_json from trading_equity order by trade_date",
                (rs, row) -> equityRow(rs));
    }

    @Override
    public void saveEquity(EquitySnapshot snapshot) {
        jdbc.update(
                "insert into trading_equity"
                        + " (trade_date, cash_cents, market_value_cents, equity_cents,"
                        + " day_pnl_cents, prices_json, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?)"
                        + " on conflict(trade_date) do update set cash_cents = excluded.cash_cents,"
                        + " market_value_cents = excluded.market_value_cents,"
                        + " equity_cents = excluded.equity_cents,"
                        + " day_pnl_cents = excluded.day_pnl_cents,"
                        + " prices_json = excluded.prices_json",
                snapshot.tradeDate(),
                snapshot.cashCents(),
                snapshot.marketValueCents(),
                snapshot.equityCents(),
                snapshot.dayPnlCents(),
                pricesJson(snapshot.prices()),
                now());
    }

    @Override
    public List<String> tradeDates(int limit) {
        return jdbc.queryForList(
                "select trade_date from ("
                        + " select trade_date from trading_phase"
                        + " union select trade_date from trading_equity"
                        + " union select trade_date from trade_order)"
                        + " order by trade_date desc limit ?",
                String.class,
                Math.max(1, limit));
    }

    @Override
    public <T> T transaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private static final String PLAN_SELECT =
            "select id, trade_date, phase, version, notes, run_id, status from trade_plan";

    private static final String ITEM_SELECT =
            "select id, plan_id, code, name, side, trigger_price_cents, shares, stop_loss_cents,"
                    + " take_profit_cents, reason, status, source, filled_shares,"
                    + " filled_price_cents, order_id, note from trade_plan_item";

    private static final String PHASE_SELECT =
            "select id, trade_date, phase, status, started_at, finished_at, run_id,"
                    + " payload_json, error from trading_phase";

    private List<PlanItem> items(long planId) {
        return jdbc.query(ITEM_SELECT + " where plan_id = ? order by id", (rs, row) -> item(rs), planId);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    bind(statement, args);
                    return statement;
                },
                keys);
        Number key = keys.getKey();
        return key == null ? 0 : key.longValue();
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value == null) {
                statement.setNull(i + 1, Types.NULL);
            } else if (value instanceof Long number) {
                statement.setLong(i + 1, number);
            } else if (value instanceof Integer number) {
                statement.setInt(i + 1, number);
            } else {
                statement.setString(i + 1, String.valueOf(value));
            }
        }
    }

    private static Position position(ResultSet rs) throws SQLException {
        return new Position(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4));
    }

    private static TradePlan plan(ResultSet rs) throws SQLException {
        return new TradePlan(
                rs.getLong(1),
                rs.getString(2),
                TradingPhase.fromDb(rs.getString(3)),
                rs.getInt(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                List.of());
    }

    private static PlanItem item(ResultSet rs) throws SQLException {
        return new PlanItem(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getString(4),
                Side.valueOf(rs.getString(5)),
                longOrNull(rs, 6),
                rs.getInt(7),
                longOrNull(rs, 8),
                longOrNull(rs, 9),
                rs.getString(10),
                ItemStatus.valueOf(rs.getString(11)),
                ItemSource.valueOf(rs.getString(12)),
                rs.getInt(13),
                longOrNull(rs, 14),
                longOrNull(rs, 15),
                rs.getString(16));
    }

    private static TradeOrder order(ResultSet rs) throws SQLException {
        return new TradeOrder(
                rs.getLong(1),
                rs.getString(2),
                longOrNull(rs, 3),
                rs.getString(4),
                rs.getString(5),
                Side.valueOf(rs.getString(6)),
                rs.getLong(7),
                rs.getInt(8),
                rs.getLong(9),
                rs.getLong(10),
                rs.getLong(11),
                longOrNull(rs, 12),
                rs.getString(13),
                rs.getString(14));
    }

    private static JournalEntry journal(ResultSet rs) throws SQLException {
        return new JournalEntry(
                rs.getLong(1),
                rs.getString(2),
                TradingPhase.fromDb(rs.getString(3)),
                rs.getString(4),
                rs.getString(5));
    }

    private static PhaseRecord phaseRow(ResultSet rs) throws SQLException {
        return new PhaseRecord(
                rs.getLong(1),
                rs.getString(2),
                TradingPhase.fromDb(rs.getString(3)),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9));
    }

    private static TickRecord tick(ResultSet rs) throws SQLException {
        return new TickRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6));
    }

    private EquitySnapshot equityRow(ResultSet rs) throws SQLException {
        return new EquitySnapshot(
                rs.getString(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getLong(4),
                rs.getLong(5),
                prices(rs.getString(6)));
    }

    private static Long longOrNull(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    private String pricesJson(Map<String, Long> prices) {
        try {
            return json.writeValueAsString(prices == null ? Map.of() : prices);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("持仓价格序列化失败", error);
        }
    }

    private Map<String, Long> prices(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, PRICES);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("持仓价格解析失败", error);
        }
    }

    private static String now() {
        return TIMESTAMP.format(LocalDateTime.now());
    }
}
