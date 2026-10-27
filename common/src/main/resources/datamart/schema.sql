-- Datamart schema, version 1. Contract: docs/DATAMART.md section 2.
-- Executed by Datamart.open() on every start; every statement is idempotent.

CREATE TABLE IF NOT EXISTS books (
    book_id      INTEGER PRIMARY KEY,
    title        TEXT    NOT NULL,
    author       TEXT,
    language     TEXT,
    release_date TEXT,
    release_year INTEGER,
    header_path  TEXT    NOT NULL,
    body_path    TEXT    NOT NULL,
    body_bytes   INTEGER NOT NULL,
    sha256       TEXT    NOT NULL,
    n_tokens     INTEGER NOT NULL,
    ingested_at  TEXT    NOT NULL,
    indexed_at   TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_books_language ON books(language);
CREATE INDEX IF NOT EXISTS idx_books_year     ON books(release_year);
CREATE INDEX IF NOT EXISTS idx_books_author   ON books(author);

CREATE TABLE IF NOT EXISTS terms (
    term TEXT    PRIMARY KEY,
    df   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS postings (
    term      TEXT    NOT NULL,
    book_id   INTEGER NOT NULL REFERENCES books(book_id),
    tf        INTEGER NOT NULL,
    positions TEXT    NOT NULL,
    PRIMARY KEY (term, book_id)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_postings_book ON postings(book_id);

CREATE TABLE IF NOT EXISTS schema_info (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
INSERT OR REPLACE INTO schema_info (key, value) VALUES ('schema_version', '1');
