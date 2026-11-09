-- 后台模拟交易 Agent：账户、持仓、计划、成交、日志、阶段运行、盘中 tick、权益快照。
-- 金额列一律是「分」的 INTEGER；时间列是 yyyy-MM-dd'T'HH:mm:ss.SSSSSS 文本。

CREATE TABLE IF NOT EXISTS trade_account (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    initial_cents INTEGER NOT NULL,
    cash_cents    INTEGER NOT NULL,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trade_position (
    code          TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    shares        INTEGER NOT NULL,
    avg_cost_cents INTEGER NOT NULL,
    opened_at     TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trade_plan (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    trade_date TEXT NOT NULL,
    phase      TEXT NOT NULL,
    version    INTEGER NOT NULL,
    notes      TEXT NOT NULL DEFAULT '',
    run_id     TEXT,
    status     TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (trade_date, phase, version)
);

CREATE TABLE IF NOT EXISTS trade_plan_item (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id            INTEGER NOT NULL,
    code               TEXT NOT NULL,
    name               TEXT NOT NULL,
    side               TEXT NOT NULL,
    trigger_price_cents INTEGER,
    shares             INTEGER NOT NULL,
    stop_loss_cents    INTEGER,
    take_profit_cents  INTEGER,
    reason             TEXT NOT NULL DEFAULT '',
    status             TEXT NOT NULL,
    source             TEXT NOT NULL,
    filled_shares      INTEGER NOT NULL DEFAULT 0,
    filled_price_cents INTEGER,
    order_id           INTEGER,
    note               TEXT NOT NULL DEFAULT '',
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trade_order (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    trade_date         TEXT NOT NULL,
    plan_item_id       INTEGER,
    code               TEXT NOT NULL,
    name               TEXT NOT NULL,
    side               TEXT NOT NULL,
    price_cents        INTEGER NOT NULL,
    shares             INTEGER NOT NULL,
    amount_cents       INTEGER NOT NULL,
    fee_cents          INTEGER NOT NULL,
    tax_cents          INTEGER NOT NULL,
    realized_pnl_cents INTEGER,
    reason             TEXT NOT NULL DEFAULT '',
    quote_time         TEXT NOT NULL,
    created_at         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trade_journal (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    trade_date TEXT NOT NULL,
    phase      TEXT NOT NULL,
    text       TEXT NOT NULL,
    run_id     TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS trading_phase (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    trade_date   TEXT NOT NULL,
    phase        TEXT NOT NULL,
    status       TEXT NOT NULL,
    started_at   TEXT NOT NULL,
    finished_at  TEXT,
    run_id       TEXT,
    payload_json TEXT,
    error        TEXT,
    UNIQUE (trade_date, phase)
);

CREATE TABLE IF NOT EXISTS trading_tick (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    trade_date    TEXT NOT NULL,
    checked_at    TEXT NOT NULL,
    quotes_json   TEXT NOT NULL,
    executed_json TEXT NOT NULL DEFAULT '[]',
    error         TEXT
);

CREATE TABLE IF NOT EXISTS trading_equity (
    trade_date         TEXT PRIMARY KEY,
    cash_cents         INTEGER NOT NULL,
    market_value_cents INTEGER NOT NULL,
    equity_cents       INTEGER NOT NULL,
    day_pnl_cents      INTEGER NOT NULL,
    prices_json        TEXT NOT NULL,
    created_at         TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trade_plan_date ON trade_plan (trade_date);
CREATE INDEX IF NOT EXISTS idx_trade_plan_item_plan_status ON trade_plan_item (plan_id, status);
CREATE INDEX IF NOT EXISTS idx_trade_order_date ON trade_order (trade_date);
CREATE INDEX IF NOT EXISTS idx_trade_journal_date ON trade_journal (trade_date);
CREATE INDEX IF NOT EXISTS idx_trading_tick_date ON trading_tick (trade_date);

INSERT INTO trade_account (id, initial_cents, cash_cents, created_at, updated_at)
VALUES (1, 100000000, 100000000, strftime('%Y-%m-%dT%H:%M:%f', 'now'), strftime('%Y-%m-%dT%H:%M:%f', 'now'));
