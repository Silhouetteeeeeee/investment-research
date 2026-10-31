package com.investmentresearch.data.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 每日判断存档（对照 {@code research/store.py}）：一天一条，重复保存覆盖当天记录。
 *
 * <p>只存规则引擎算出的判断，不存模型写的文字结论——复盘要检验的是判断，不是措辞。
 */
@Component
public class DailyReportStore {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public DailyReportStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    DailyReportStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 保存一天的判断；同一天再次保存会覆盖，并说明是否覆盖了旧记录。 */
    public Map<String, Object> save(Map<String, Object> report) {
        String tradeDate = value(report == null ? null : report.get("trade_date")).strip();
        if (tradeDate.isEmpty()) {
            throw new IllegalArgumentException("存档缺少 trade_date");
        }
        String savedAt = LocalDateTime.now(clock).format(STAMP);
        String payload;
        try {
            payload = MAPPER.writeValueAsString(report);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("存档内容无法序列化", error);
        }
        boolean replaced =
                !jdbc.queryForList(
                                "select 1 from daily_reports where trade_date = ?", tradeDate)
                        .isEmpty();
        jdbc.update(
                "insert into daily_reports (trade_date, saved_at, payload) values (?, ?, ?)"
                        + " on conflict(trade_date) do update set"
                        + " saved_at = excluded.saved_at, payload = excluded.payload",
                tradeDate,
                savedAt,
                payload);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trade_date", tradeDate);
        result.put("saved_at", savedAt);
        result.put("replaced", replaced);
        return result;
    }

    /** 按交易日倒序返回存档，最新的在前。 */
    public List<Map<String, Object>> load(int limit) {
        int size = Math.max(1, limit);
        List<Map<String, Object>> reports = new ArrayList<>();
        jdbc.query(
                "select trade_date, saved_at, payload from daily_reports order by trade_date desc limit ?",
                rs -> {
                    String tradeDate = rs.getString("trade_date");
                    String savedAt = rs.getString("saved_at");
                    Map<String, Object> report;
                    try {
                        report = MAPPER.readValue(rs.getString("payload"), MAP);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                        // 与 Python 的 json.loads 失败一致：坏行直接跳过
                        return;
                    }
                    report.put("trade_date", tradeDate);
                    report.put("saved_at", savedAt);
                    reports.add(report);
                },
                size);
        return reports;
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
