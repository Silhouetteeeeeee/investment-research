CREATE TABLE IF NOT EXISTS screen_quote (
    code TEXT PRIMARY KEY, name TEXT NOT NULL, industry TEXT,
    price REAL, chg_pct REAL, amount_yi REAL, turnover REAL,
    pe REAL, pb REAL, total_cap_yi REAL,
    trade_date TEXT NOT NULL, fetched_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS screen_finance (
    code TEXT NOT NULL, report_date TEXT NOT NULL,
    eps REAL, revenue REAL, net_profit REAL, roe REAL, gross_margin REAL,
    revenue_yoy REAL, profit_yoy REAL, ocf_per_share REAL,
    update_date TEXT, fetched_at TEXT NOT NULL,
    PRIMARY KEY (code, report_date)
);
CREATE INDEX IF NOT EXISTS idx_screen_finance_report ON screen_finance(report_date);
