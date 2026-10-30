CREATE TABLE biz_segment (
    code TEXT NOT NULL,
    report_date TEXT NOT NULL,
    kind TEXT NOT NULL,
    item_name TEXT NOT NULL,
    income REAL,
    share REAL,
    cost REAL,
    profit REAL,
    gross_margin REAL,
    rank REAL,
    fetched_at TEXT,
    PRIMARY KEY (code, report_date, kind, item_name)
);
