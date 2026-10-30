CREATE TABLE conversation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    primary_code TEXT NOT NULL DEFAULT '',
    primary_name TEXT NOT NULL DEFAULT '',
    companies TEXT NOT NULL DEFAULT '[]',
    usage_json TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE message (
    conversation_id INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    role TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (conversation_id, seq)
);

CREATE INDEX idx_message_conversation ON message (conversation_id);
