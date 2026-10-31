-- 与 Python research/store.py:19-25 的 TABLE_SQL 一致。
CREATE TABLE IF NOT EXISTS daily_reports (
    trade_date TEXT PRIMARY KEY,
    saved_at   TEXT NOT NULL,
    payload    TEXT NOT NULL
);
