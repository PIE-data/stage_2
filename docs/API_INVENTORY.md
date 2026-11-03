# API Inventory — Stage 2

This document is a compact inventory of the HTTP interfaces currently implemented in the PIE-data Stage 2 system.

For each endpoint it answers three questions: who provides it, who uses it, and what it is for.

Non-HTTP coordination through ActiveMQ is defined in [EVENTS.md](EVENTS.md).

## 1. Front-end API

| Method | Route | Provider | Consumer | Purpose |
|---|---|---|---|---|
| GET | `/search` | Search Service | Front-end / HTTP client | Search indexed books by query text, with optional author, language, year and pagination parameters. |
| GET | `/search/filters` | Search Service | Front-end / HTTP client | Return languages and Gutenberg release-year range available in the current datamart. |
| GET | `/books/{book_id}` | Search Service | Front-end / HTTP client | Return metadata for one indexed book. |

Search runs directly on port `7003`. The deployment issue (#7) adds the nginx gateway in front of Search replicas on port `8080`; gateway validation is added when that work is merged.

## 2. Internal API

| Method | Route | Provider | Consumer | Purpose |
|---|---|---|---|---|
| POST | `/ingest/{book_id}` | Ingestion Service | Operator / HTTP client | Accept an ingestion request. Returns `200` if already ingested or `202` after publishing `IngestRequested` to ActiveMQ. |
| GET | `/ingest/status/{book_id}` | Ingestion Service | Operator / scripts / validation | Return `downloaded`, `failed` or `not_found` for one book. |
| GET | `/ingest/list` | Ingestion Service | Feeder / scripts | Return ids that already have ingestion receipts so the Feeder can skip completed work. |

The Feeder calls `/ingest/list` with Java `HttpClient`. The current implementation does not set an explicit request timeout and does not retry the HTTP request. If the call fails, the Feeder falls back to its local `ReceiptStore`.

Most service-to-service coordination is not HTTP. It uses ActiveMQ:

`Feeder → ingest.requests → Ingestion → books.downloaded → Indexing → books.indexed`

The event contract, payloads, acknowledgement rules, retries, duplicate handling and DLQ behaviour are documented in [EVENTS.md](EVENTS.md).

## 3. Administrative and health API

| Method | Route | Provider | Consumer | Purpose |
|---|---|---|---|---|
| POST | `/index/update/{book_id}` | Indexing Service | Operator / scripts / validation | Index or re-index one ingested book. A `200` response means the update is already committed. |
| POST | `/index/rebuild` | Indexing Service | Operator / scripts / validation | Rebuild the whole datamart atomically from ingestion receipts. |
| GET | `/index/status` | Indexing Service | Monitoring / scripts / benchmarks | Return book, term and posting counts, datamart size and rebuild state. |
| POST | `/feeder/run` | Feeder | Operator / scripts / benchmarks | Read a manifest and publish ingestion requests for selected books that are not yet ingested. |
| GET | `/feeder/status` | Feeder | Monitoring / scripts | Return requested, downloaded, indexed and failed counters. |
| GET | `/broker/stats` | Broker | Monitoring / benchmarks | Return queue depth, enqueue/dequeue counts, inflight messages and average queue time. |
| GET | `/status` | Broker | Monitoring / scripts | Report Broker HTTP process health plus broker queue information. |
| GET | `/status` | Ingestion Service | Monitoring / scripts | Report service health plus `ingested_count`. |
| GET | `/status` | Indexing Service | Monitoring / scripts | Report service health. |
| GET | `/status` | Search Service | Monitoring / scripts | Report service health plus datamart readiness and indexed-book count. |
| GET | `/status` | Feeder | Monitoring / scripts | Report service health plus Feeder progress counters. |

Default ports:

| Service | Port |
|---|---:|
| Broker HTTP monitoring | `7000` |
| ActiveMQ OpenWire | `61616` |
| Ingestion Service | `7001` |
| Indexing Service | `7002` |
| Search Service | `7003` |
| Feeder | `7004` |
| Gateway | `8080` when issue #7 is available |

## 4. Completion, retries and idempotency

`POST /ingest/{book_id}` is asynchronous when the book is new. `202 Accepted` means the request was published to ActiveMQ; it does not mean the book has already been downloaded. If the book already has a receipt, the endpoint returns `200` with `already_ingested`.

Indexing HTTP operations are synchronous. `POST /index/update/{book_id}` and `POST /index/rebuild` return `200` only after the datamart operation has completed.

Ingestion and indexing are idempotent. Duplicate broker delivery is allowed because the system uses at-least-once delivery. Ingestion skips an already completed book, indexing replaces the existing rows for a book, and the Feeder counts distinct indexed ids.

Broker consumers use transacted JMS sessions. Transient failures are rolled back and redelivered up to five times with exponential delays. Malformed messages eventually go to `ActiveMQ.DLQ`. See [EVENTS.md](EVENTS.md) for the binding non-HTTP contract.

## 5. Detailed HTTP rules and checks

The full HTTP contract is defined in `api/openapi.yaml`.

Runnable manual requests are in `api/api-checks.http`.

Automated endpoint and event checks are launched through `scripts/check-api.sh`.
