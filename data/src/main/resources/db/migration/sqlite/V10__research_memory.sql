-- 与 Python assistant/research_memory.py:59-180 的 _SCHEMA 逐列一致。
CREATE TABLE IF NOT EXISTS company_memory (
  code             TEXT PRIMARY KEY,
  name             TEXT NOT NULL,
  created_at       TEXT NOT NULL,
  updated_at       TEXT NOT NULL,
  last_reviewed_at TEXT
);

CREATE TABLE IF NOT EXISTS memory_fact (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  code            TEXT NOT NULL,
  fact_id         TEXT NOT NULL,
  subject         TEXT NOT NULL,
  fact_type       TEXT NOT NULL,
  content         TEXT NOT NULL,
  metrics_json    TEXT NOT NULL DEFAULT '[]',
  report_period   TEXT NOT NULL,
  published_at    TEXT,
  source_id       TEXT NOT NULL,
  source_label    TEXT NOT NULL DEFAULT '',
  source_locator  TEXT NOT NULL DEFAULT '',
  source_url      TEXT NOT NULL DEFAULT '',
  verification_status TEXT NOT NULL DEFAULT '已核验',
  dedupe_key      TEXT NOT NULL,
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  UNIQUE (code, fact_id),
  UNIQUE (code, dedupe_key)
);

CREATE INDEX IF NOT EXISTS idx_fact_company ON memory_fact(code);

CREATE TABLE IF NOT EXISTS memory_hypothesis (
  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
  code                  TEXT NOT NULL,
  hypothesis_id         TEXT NOT NULL,
  statement             TEXT NOT NULL,
  supporting_fact_ids_json TEXT NOT NULL DEFAULT '[]',
  assumptions           TEXT NOT NULL DEFAULT '',
  confidence            TEXT NOT NULL,
  verification_method   TEXT NOT NULL,
  falsification_condition TEXT NOT NULL,
  status                TEXT NOT NULL,
  next_review_date      TEXT,
  created_at            TEXT NOT NULL,
  updated_at            TEXT NOT NULL,
  UNIQUE (code, hypothesis_id)
);

CREATE INDEX IF NOT EXISTS idx_hypothesis ON memory_hypothesis(code, status, next_review_date);

CREATE TABLE IF NOT EXISTS memory_conclusion (
  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
  code                  TEXT NOT NULL,
  conclusion_id         TEXT NOT NULL,
  title                 TEXT NOT NULL,
  statement             TEXT NOT NULL,
  status                TEXT NOT NULL,
  confidence            TEXT NOT NULL DEFAULT '',
  based_on_fact_ids_json TEXT NOT NULL DEFAULT '[]',
  based_on_hypothesis_ids_json TEXT NOT NULL DEFAULT '[]',
  last_change_id        TEXT,
  created_at            TEXT NOT NULL,
  updated_at            TEXT NOT NULL,
  UNIQUE (code, conclusion_id)
);

CREATE INDEX IF NOT EXISTS idx_conclusion ON memory_conclusion(code, status);

CREATE TABLE IF NOT EXISTS memory_question (
  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
  code                  TEXT NOT NULL,
  question_id           TEXT NOT NULL,
  question              TEXT NOT NULL,
  importance            TEXT NOT NULL,
  known_facts_json      TEXT NOT NULL DEFAULT '[]',
  missing_evidence      TEXT NOT NULL,
  next_action           TEXT NOT NULL,
  resolution_criteria   TEXT NOT NULL,
  related_hypothesis_ids_json TEXT NOT NULL DEFAULT '[]',
  status                TEXT NOT NULL,
  last_checked_at       TEXT,
  resolution_note       TEXT NOT NULL DEFAULT '',
  created_at            TEXT NOT NULL,
  updated_at            TEXT NOT NULL,
  UNIQUE (code, question_id)
);

CREATE INDEX IF NOT EXISTS idx_question ON memory_question(code, status, importance);

CREATE TABLE IF NOT EXISTS memory_change (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  code          TEXT NOT NULL,
  change_id     TEXT NOT NULL,
  from_conclusion_id TEXT NOT NULL,
  to_conclusion_id   TEXT NOT NULL,
  operation     TEXT NOT NULL,
  old_conclusion TEXT NOT NULL,
  new_conclusion TEXT NOT NULL,
  trigger_event TEXT NOT NULL,
  trigger_date  TEXT NOT NULL,
  evidence_ids_json TEXT NOT NULL DEFAULT '[]',
  reason        TEXT NOT NULL,
  impact        TEXT NOT NULL,
  review_status TEXT NOT NULL DEFAULT '',
  created_at    TEXT NOT NULL,
  UNIQUE (code, change_id),
  FOREIGN KEY (code, from_conclusion_id) REFERENCES memory_conclusion (code, conclusion_id),
  FOREIGN KEY (code, to_conclusion_id) REFERENCES memory_conclusion (code, conclusion_id)
);

CREATE INDEX IF NOT EXISTS idx_change ON memory_change(code);

-- 变化只追加：Python 在触发器里禁掉 update/delete，服务层也不提供这两个方法。
CREATE TRIGGER IF NOT EXISTS memory_change_no_update
BEFORE UPDATE ON memory_change
BEGIN SELECT RAISE(ABORT, 'conclusion changes are append-only'); END;

CREATE TRIGGER IF NOT EXISTS memory_change_no_delete
BEFORE DELETE ON memory_change
BEGIN SELECT RAISE(ABORT, 'conclusion changes are append-only'); END;
