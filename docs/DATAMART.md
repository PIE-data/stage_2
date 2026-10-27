# Datamart contract — Stage 2

Status: **binding contract** between `indexing-service` (the only writer) and `search-service`
(read-only). Based on the Stage 1 schema ([SPEC §5.3, §6.3](https://github.com/PIE-data/stage_1/blob/main/docs/SPEC.md)),
merged into one file so that a book's metadata and postings are published in the same transaction.

## 1. File and connection settings

- Path: `${data.dir}/datamarts/datamart.db`, driver `org.xerial:sqlite-jdbc`.
- Writer connections (Indexing), set on every connection:

```sql
PRAGMA journal_mode = WAL;        -- readers never block the writer and vice versa
PRAGMA synchronous  = NORMAL;     -- durable at checkpoint; acceptable for a rebuildable index
PRAGMA busy_timeout = 30000;      -- wait up to 30 s for another writer instead of failing
PRAGMA foreign_keys = ON;
```

- Reader connections (Search): open with `jdbc:sqlite:file:<path>?mode=ro` and
  `PRAGMA query_only = ON`. Search **never** writes and never creates the file; if the file does
  not exist yet, every search returns `200` with no results and `/status` reports
  `"datamart": "empty"`.

## 2. Schema

```sql
CREATE TABLE IF NOT EXISTS books (
    book_id      INTEGER PRIMARY KEY,
    title        TEXT    NOT NULL,          -- "Unknown" when missing (SPEC §5.2)
    author       TEXT,                      -- verbatim, life-span suffix stripped (SPEC §5.2)
    language     TEXT,                      -- ISO 639-1 via spec/language_map.txt, else lowercased raw
    release_date TEXT,                      -- YYYY-MM-DD of the Gutenberg release, or NULL
    release_year INTEGER,                   -- year of release_date, or NULL  (used by the year filter)
    header_path  TEXT    NOT NULL,          -- relative to data.dir
    body_path    TEXT    NOT NULL,
    body_bytes   INTEGER NOT NULL,
    sha256       TEXT    NOT NULL,          -- of the body file
    n_tokens     INTEGER NOT NULL,          -- kept tokens (document length, for ranking)
    ingested_at  TEXT    NOT NULL,          -- copied from the ingestion receipt
    indexed_at   TEXT    NOT NULL           -- UTC ISO 8601, whole seconds
);
CREATE INDEX IF NOT EXISTS idx_books_language ON books(language);
CREATE INDEX IF NOT EXISTS idx_books_year     ON books(release_year);
CREATE INDEX IF NOT EXISTS idx_books_author   ON books(author);

CREATE TABLE IF NOT EXISTS terms (
    term TEXT    PRIMARY KEY,
    df   INTEGER NOT NULL                   -- number of books containing the term
);

CREATE TABLE IF NOT EXISTS postings (
    term      TEXT    NOT NULL,
    book_id   INTEGER NOT NULL REFERENCES books(book_id),
    tf        INTEGER NOT NULL,             -- occurrences of term in the book
    positions TEXT    NOT NULL,             -- comma-separated, ascending token positions
    PRIMARY KEY (term, book_id)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_postings_book ON postings(book_id);

CREATE TABLE IF NOT EXISTS schema_info (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- INSERT OR REPLACE INTO schema_info VALUES ('schema_version', '1');
```

Differences from Stage 1, all additive: one file instead of two; `release_year`, `n_tokens` and
`indexed_at` added; positions always stored; `schema_info` added. The canonical export
(SPEC §7) can still be produced from `terms` + `postings`, so the Stage 1 hash remains the
correctness oracle for the 20 golden books.

**About `year`**: Gutenberg headers carry only the *release date of the e-book*, not the year the
work was first published. `release_year` is therefore the Gutenberg release year (e.g. 1998 for
*Pride and Prejudice*). This limitation is stated in the API documentation and in the report.

## 3. Writing a book (Indexing)

One book = one transaction. The transaction may contain several books when Indexing batches
messages, but a book is never split across transactions.

```
BEGIN IMMEDIATE;                                   -- take the write lock now (waits busy_timeout)
-- 1. remove the previous version, if any (re-indexing must not duplicate)
UPDATE terms SET df = df - 1
  WHERE term IN (SELECT term FROM postings WHERE book_id = :id);
DELETE FROM terms WHERE df <= 0
  AND term IN (SELECT term FROM postings WHERE book_id = :id);   -- only this book's terms: no full scan
DELETE FROM postings WHERE book_id = :id;
-- 2. metadata
INSERT OR REPLACE INTO books (...) VALUES (...);
-- 3. postings, in term order
INSERT INTO postings (term, book_id, tf, positions) VALUES (...);   -- one row per distinct term
INSERT INTO terms (term, df) VALUES (:term, 1)
  ON CONFLICT(term) DO UPDATE SET df = df + 1;                       -- one per distinct term
COMMIT;
```

- Implemented by `common/.../datamart/Datamart.java`; the schema is `common/src/main/resources/datamart/schema.sql`.
- `df` is maintained **incrementally** (+1/−1 per book), never recomputed with `COUNT(*)`:
  recounting per book is quadratic in the corpus size (Stage 1 lesson, SPEC §6.3).
- The `books.indexed` message is published only **after** `COMMIT` returns (EVENTS.md §5).
- `POST /index/rebuild` replaces the whole content **in one transaction** (delete all rows, then
  write every ingested book): readers keep seeing the previous index until the commit, and a failed
  rebuild changes nothing. The file is never replaced, because swapping a SQLite file in WAL mode
  while other processes have it open would pair the new file with the old `-wal` file and corrupt
  it. Cost: other writers wait during the rebuild (up to `busy_timeout`, then their message is
  redelivered).

## 4. Reading (Search)

Each HTTP request uses **one read transaction**, so `total` and the returned page come from the
same snapshot. Query terms are normalized with the Stage 1 tokenizer (SPEC §1.1); `q` with several
terms means AND.

Matching books with filters (k = number of distinct query terms):

```sql
SELECT p.book_id
FROM postings p JOIN books b ON b.book_id = p.book_id
WHERE p.term IN (:t1, :t2, ...)
  AND (:author   IS NULL OR b.author   LIKE '%' || :author || '%')   -- case-insensitive for ASCII
  AND (:language IS NULL OR b.language = :language)
  AND (:year     IS NULL OR b.release_year = :year)
GROUP BY p.book_id
HAVING COUNT(*) = :k;
```

Ranking (computed in Java over the matching books, or in SQL with the same formula):

```
score(book) = Σ over query terms t of  tf(t, book) · ln(N / df(t))
N = SELECT COUNT(*) FROM books
order: score DESC, book_id ASC
```

`total` = number of matching books; the page is `ORDER BY score DESC, book_id ASC
LIMIT :limit OFFSET :offset`.

## 5. Concurrency summary

| Situation | What happens |
|---|---|
| Two Indexing threads or replicas write | The second waits on `BEGIN IMMEDIATE` up to 30 s, then retries via message redelivery. |
| Search reads while Indexing writes | WAL: Search sees the last committed snapshot, never a half-written book. |
| Same book indexed twice (duplicate message) | Step 1 removes the old rows: the result is identical. |
| Indexing crashes mid-transaction | SQLite rolls back; the message was not acknowledged and is redelivered. |
