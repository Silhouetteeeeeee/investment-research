-- 阶段 2：公司研究类缓存表，结构与 Python 版逐列一致。
-- 八张表同形（code, data, fetched_at），另三张多一个键列。
CREATE TABLE company_management (
    code TEXT PRIMARY KEY,
    data TEXT,
    fetched_at TEXT
);

CREATE TABLE company_shareholders (
    code TEXT PRIMARY KEY,
    data TEXT,
    fetched_at TEXT
);

CREATE TABLE company_dividends (
    code TEXT PRIMARY KEY,
    data TEXT,
    fetched_at TEXT
);

CREATE TABLE company_insider_trades (
    code TEXT PRIMARY KEY,
    data TEXT,
    fetched_at TEXT
);

CREATE TABLE company_buybacks (
    code TEXT PRIMARY KEY,
    data TEXT,
    fetched_at TEXT
);

CREATE TABLE company_lockups (
    code TEXT PRIMARY KEY,
    data TEXT,
    fetched_at TEXT
);

CREATE TABLE company_pledges (
    code TEXT PRIMARY KEY,
    data TEXT,
    fetched_at TEXT
);

CREATE TABLE company_forecasts (
    code TEXT PRIMARY KEY,
    data TEXT,
    fetched_at TEXT
);

CREATE TABLE company_research (
    code TEXT NOT NULL,
    years INTEGER NOT NULL,
    data TEXT,
    fetched_at TEXT,
    PRIMARY KEY (code, years)
);

CREATE TABLE research_notes (
    code TEXT NOT NULL,
    note_key TEXT NOT NULL,
    content TEXT,
    fetched_at TEXT,
    PRIMARY KEY (code, note_key)
);

CREATE TABLE company_reports (
    code TEXT NOT NULL,
    days INTEGER NOT NULL,
    data TEXT,
    fetched_at TEXT,
    PRIMARY KEY (code, days)
);
