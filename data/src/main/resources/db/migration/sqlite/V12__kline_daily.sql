-- 日 K 面板的本地缓存：同一代码同一交易日一行，重复抓取时覆盖。
-- 只存前复权价，与 KlineAdapter 的取数口径一致。
CREATE TABLE IF NOT EXISTS kline_daily (
    code       TEXT NOT NULL,
    trade_date TEXT NOT NULL,
    open       REAL,
    close      REAL,
    high       REAL,
    low        REAL,
    volume     REAL,
    amount     REAL,
    fetched_at INTEGER NOT NULL,
    PRIMARY KEY (code, trade_date)
) WITHOUT ROWID;
