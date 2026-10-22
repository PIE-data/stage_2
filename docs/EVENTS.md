# Event contract — Stage 2

Status: **binding contract** for every producer and consumer of broker messages. Implemented by
issue #2 (broker + messaging library) and used by #3, #4 and the feeder.

## 1. Broker

- ActiveMQ Classic, embedded in the `broker` jar, OpenWire on `tcp://localhost:61616`
  (`broker.url`), persistent store (KahaDB) in `${data.dir}/broker`.
- All messages are **persistent** JMS `TextMessage`s whose body is UTF-8 JSON (one object, no
  trailing newline). Unknown fields must be ignored by consumers (forward compatibility).
- Consumer prefetch = 1, so that messages are spread among replicas instead of being hoarded by the
  first one connected.

## 2. Destinations

| Name | Type | Producer | Consumers | Meaning |
|---|---|---|---|---|
| `ingest.requests` | queue | feeder; ingestion (`POST /ingest/{id}` re-uses it) | ingestion replicas, competing | "please ingest this book" |
| `books.downloaded` | queue | ingestion | indexing replicas, competing | "header, body and receipt of this book are complete on disk" |
| `books.indexed` | topic | indexing | feeder, metrics (any number, non-durable) | "this book is committed in the datamart and searchable" |
| `ActiveMQ.DLQ` | queue | broker | operators, checks | messages that failed after all redeliveries |

A queue delivers each message to **one** consumer (work distribution); a topic delivers it to
**every** current subscriber (notification).

## 3. Payloads

```json
// ingest.requests
{"event":"IngestRequested","book_id":1342,"requested_at":"2026-10-22T10:00:00Z"}

// books.downloaded
{"event":"BookDownloaded","book_id":1342,
 "header_path":"datalake/00/13/1342.header.txt","body_path":"datalake/00/13/1342.body.txt",
 "ingested_at":"2026-10-22T10:00:03Z"}

// books.indexed
{"event":"BookIndexed","book_id":1342,"indexed_at":"2026-10-22T10:00:05Z"}
```

| Field | Type | Rule |
|---|---|---|
| `event` | string | exactly the name above; consumers reject other values (→ DLQ) |
| `book_id` | integer | > 0, Gutenberg id |
| `*_at` | string | UTC ISO 8601, whole seconds, `Z` suffix |
| `*_path` | string | relative to `data.dir`, `/` separators |

The JMS property `book_id` (long) is also set on every message, for logging and broker tools.

## 4. Delivery guarantees and duplicates

- Delivery is **at least once**: a message can arrive twice (redelivery after a crash, or the same
  book requested twice). There is no exactly-once delivery and none is needed, because every
  consumer is **idempotent**:
  - ingestion: if the receipt `control/ingestion/hash/<id>.json` already exists, the download is
    skipped and `books.downloaded` is sent again (harmless);
  - indexing: re-indexing a book replaces its rows (DATAMART.md §3), the result is identical;
  - feeder: counts distinct `book_id`s.
- Two ingestion replicas receiving the same book at the same time: the one that obtains the OS file
  lock `control/claims/<id>.lock` (`FileChannel.lock`, released automatically if the process
  dies) downloads it; the other blocks on the lock, then finds the receipt and skips. Threads of
  the same replica also need an in-process lock per book id (the JVM refuses two OS locks on the
  same file from one process).
- **No ordering** is assumed between different books. For one book the order is guaranteed by the
  flow itself (`books.downloaded` is produced only after ingestion finished).

## 5. Acknowledgement, retries and failures

Every consumer uses a **transacted JMS session**. For one message:

```
receive  →  do the work  →  commit storage (files / SQLite)  →  send the next message
         →  session.commit()      // acknowledges the input AND sends the output atomically
```

| What goes wrong | Consumer action | Result |
|---|---|---|
| Transient error (network, `SQLITE_BUSY` after timeout, I/O) | `session.rollback()` | Redelivered: up to **5 redeliveries**, delay 1 s doubling each time (1, 2, 4, 8, 16 s), then `ActiveMQ.DLQ` |
| Permanent error: book not on Gutenberg (404), no start/end markers | append `<id>\tNOT_FOUND|NO_MARKERS\t<time>` to `control/failed_books.txt`, then `session.commit()` | Not retried; visible in `GET /ingest/status/{id}` as `failed` |
| Malformed message (bad JSON, unknown `event`, `book_id` ≤ 0) | `session.rollback()` | Ends in `ActiveMQ.DLQ` after the redeliveries |
| Process killed before `session.commit()` | – | Broker redelivers the message to another consumer; storage writes are idempotent |
| Process killed after storage commit, before `session.commit()` | – | Same as above: the work is repeated (idempotent) and the output message is sent then |

Because the output message is sent in the **same transaction** that acknowledges the input,
"work done but next message never sent" cannot happen for consumers.

The only producer outside a consumer transaction is `POST /ingest/{id}` (HTTP request, no input
message). It publishes to `ingest.requests` and returns `202 Accepted`; the actual ingestion then
follows the normal consumer path above, so the same guarantees apply.

## 6. Broker unavailable

- Producers and consumers reconnect automatically (`failover:(tcp://localhost:61616)` URL).
- While the broker is down, HTTP endpoints that need it answer `503 Service Unavailable`
  with an ApiError; read-only endpoints (`/status`, `/ingest/list`, `/search`) keep working.
- Messages already in the broker survive a broker restart (persistent store).

## 7. Checks that must exist (issue #2)

1. The same `BookDownloaded` delivered twice → one row per term in the datamart, identical export.
2. Consumer killed between work and `session.commit()` → the message is redelivered and processed once more.
3. A malformed message → it ends in `ActiveMQ.DLQ` after 5 redeliveries.
4. A 404 book → recorded in `failed_books.txt`, not redelivered.
5. Two ingestion replicas, same 100 ids requested twice → 100 books, 100 receipts, no `.part` files left.
