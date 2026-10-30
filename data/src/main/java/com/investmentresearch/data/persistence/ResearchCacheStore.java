package com.investmentresearch.data.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 阶段 2 缓存的读写。十一张表只有三种形状，逐个建 Entity/Mapper 只是样板，
 * 这里用一条按 {@link ResearchCache} 白名单拼表名的 SQL 覆盖。
 */
@Component
public class ResearchCacheStore {
    private final JdbcTemplate jdbc;

    public ResearchCacheStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一行缓存；{@code data} 是 Python 原样存下的 JSON 文本。 */
    public record Row(String data, String fetchedAt) {}

    public Optional<Row> read(ResearchCache cache, String code) {
        return read(cache, code, null);
    }

    public Optional<Row> read(ResearchCache cache, String code, Integer key) {
        String sql =
                "select data, fetched_at from \""
                        + cache.table()
                        + "\" where code = ?"
                        + (cache.keyColumn() == null ? "" : " and \""
                                + cache.keyColumn()
                                + "\" = ?");
        List<Row> rows =
                cache.keyColumn() == null
                        ? jdbc.query(
                                sql,
                                (rs, i) -> new Row(rs.getString("data"), rs.getString("fetched_at")),
                                code)
                        : jdbc.query(
                                sql,
                                (rs, i) -> new Row(rs.getString("data"), rs.getString("fetched_at")),
                                code,
                                key);
        return rows.stream().findFirst();
    }

    public void write(ResearchCache cache, String code, String data, String fetchedAt) {
        write(cache, code, null, data, fetchedAt);
    }

    public void write(ResearchCache cache, String code, Integer key, String data, String fetchedAt) {
        if (cache.keyColumn() == null) {
            jdbc.update(
                    "insert or replace into \"" + cache.table()
                            + "\" (code, data, fetched_at) values (?, ?, ?)",
                    code,
                    data,
                    fetchedAt);
            return;
        }
        jdbc.update(
                "insert or replace into \"" + cache.table() + "\" (code, \""
                        + cache.keyColumn() + "\", data, fetched_at) values (?, ?, ?, ?)",
                code,
                key,
                data,
                fetchedAt);
    }

    /** 调研纪要按活动缓存正文，Python 的 `ensure_note`。 */
    public Optional<Row> readNote(String code, String noteKey) {
        List<Row> rows =
                jdbc.query(
                        "select content as data, fetched_at from research_notes"
                                + " where code = ? and note_key = ?",
                        (rs, i) -> new Row(rs.getString("data"), rs.getString("fetched_at")),
                        code,
                        noteKey);
        return rows.stream().findFirst();
    }

    public void writeNote(String code, String noteKey, String content, String fetchedAt) {
        jdbc.update(
                "insert or replace into research_notes (code, note_key, content, fetched_at)"
                        + " values (?, ?, ?, ?)",
                code,
                noteKey,
                content,
                fetchedAt);
    }
}
