# PIE-data search engine — Stage 2

Service-oriented version of the Stage 1 data layer: independent Java services that cooperate
through an ActiveMQ message broker and REST APIs, all running on one machine.

Final report: [report/main.pdf](report/main.pdf) (source `report/main.tex`).

Quick start (Linux or WSL 2, see [Requirements](#requirements)):

```bash
mvn verify                                   # build every module and run the tests
bash scripts/run-all.sh --mode full          # broker, 2 × ingestion, 2 × indexing, 2 × search, feeder, nginx
curl -sS -X POST 'http://127.0.0.1:7004/feeder/run?manifest=100&count=20'   # ingest and index 20 books
curl -sS 'http://127.0.0.1:8080/search?q=sea&limit=3'                        # query through the gateway
bash scripts/check-api.sh                    # API and event checks (expected: FAIL 0)
bash scripts/stop-all.sh
```

## Design documents

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — components, diagram, life of a book, replicas and threads
- [docs/DATAMART.md](docs/DATAMART.md) — SQLite schema shared by Indexing (writer) and Search (reader)
- [docs/EVENTS.md](docs/EVENTS.md) — broker queues, message formats, acknowledgements and retries
- [docs/SEARCH_SERVICE.md](docs/SEARCH_SERVICE.md) — Search API, front-end use cases, consistency and configuration
- [docs/SEARCH_BENCHMARK.md](docs/SEARCH_BENCHMARK.md) — tier-1000 JMH workloads, reproduction steps and results
- [api/openapi.yaml](api/openapi.yaml) — versioned HTTP contract of every service (OpenAPI)
- [docs/API_INVENTORY.md](docs/API_INVENTORY.md) — endpoint inventory: front-end, internal and operational, with provider and consumer
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) — single-machine deployment, configuration, monitoring and verification
- [docs/INTEGRATION_BENCHMARK.md](docs/INTEGRATION_BENCHMARK.md) — integration benchmark plan (experiments S1–X1, X0)
- [docs/INTEGRATION_RESULTS.md](docs/INTEGRATION_RESULTS.md) — integration benchmark results, figures and conclusions

## Stage 1 reference files

`spec/` holds the frozen Stage 1 inputs and oracles (stop words, language map, golden books,
expected hashes, corpus manifests, query sets). See [spec/README.md](spec/README.md).
The Java ports in `common` are verified against Stage 1: the tokenizer
(`es.ulpgc.piedata.common.text.Tokenizer`) against `spec/golden/tokens_20.jsonl` by
`GoldenTokensTest`, the header parser (`es.ulpgc.piedata.common.metadata.HeaderParser`)
against `spec/golden/metadata_20.jsonl` by `GoldenMetadataTest`; the datamart writer
(`es.ulpgc.piedata.common.datamart.Datamart`) against `spec/golden/expected.sha256` by
`GoldenDatamartTest` (canonical export of the 20 golden books stored in SQLite).

## Requirements

- Java 17 or newer (the code is compiled for Java 17)
- Maven 3.9 or newer
- To run the whole system: Linux or WSL 2, Bash, Python 3, curl and nginx (the gateway)

## Modules

| Module | What it is | Default port | Issue |
|---|---|---|---|
| `common` | Shared code: configuration, HTTP server conventions, JSON | – | #1 |
| `broker` | Embedded ActiveMQ broker (own process) | 61616 | #2 |
| `ingestion-service` | Downloads books into the datalake | 7001 | #3 |
| `indexing-service` | Builds the inverted index and the metadata datamart | 7002 | #4 |
| `search-service` | Front-end search API | 7003 | #5 |
| `feeder` | Decides which books to ingest | 7004 | #2 |
| `bench-client` | Load generator for the integration benchmarks, not deployed (`docs/INTEGRATION_BENCHMARK.md`) | – | #8 |

## Build and test

```bash
mvn verify          # compiles every module, runs all tests, builds the jars
```

Each runnable module produces one self-contained jar: `<module>/target/<module>.jar`.

## Run a service

```bash
java -jar search-service/target/search-service.jar
curl http://localhost:7003/status
```

## Run the whole system on one machine

All processes run on one host. The scripts read `config/deployment.env` (ports, replica counts,
threads, heap, data paths, Indexing batch settings); use `--config <file>` for another file.
Full details: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

```bash
bash scripts/run-all.sh --mode full      # start order: broker, ingestion, indexing, search, feeder, gateway
bash scripts/status-all.sh               # /status of every process
bash scripts/monitor.sh --duration 30    # CPU and memory per process and host
bash scripts/stop-all.sh
```

`--mode search --data-dir <workspace>` starts only Search and the gateway on an existing datamart.
Books are requested through the Feeder (`POST /feeder/run?manifest=100&count=20`, manifests in
`spec/corpus/`); progress is in `GET /feeder/status`, queues in `GET http://127.0.0.1:7000/broker/stats`.
A `202` from ingestion or the Feeder means accepted, not yet indexed.

Main settings in `config/deployment.env`:

| Variable | Default | Meaning |
|---|---|---|
| `INGESTION_REPLICAS` / `INDEXING_REPLICAS` / `SEARCH_REPLICAS` | 2 / 2 / 2 | processes per service (ports 7001/7011, 7002/7012, 7003/7013, …) |
| `HTTP_THREADS_MIN` / `HTTP_THREADS_MAX` | 8 / 64 | Jetty request threads per process |
| `INGESTION_CONSUMER_THREADS` / `INDEXING_CONSUMER_THREADS` | 4 / 2 | JMS consumer sessions per process |
| `INDEXING_BATCH_SIZE` / `INDEXING_BATCH_WAIT_MS` | 20 / 200 | books per indexing transaction, wait to fill a batch |

## API specification and validation

- Contract: [api/openapi.yaml](api/openapi.yaml); inventory by consumer: [docs/API_INVENTORY.md](docs/API_INVENTORY.md);
  broker events (non-HTTP contract): [docs/EVENTS.md](docs/EVENTS.md).
- Manual requests: [api/api-checks.http](api/api-checks.http) (IntelliJ / VS Code REST client).
- Automated checks against a running full deployment:

```bash
bash scripts/check-api.sh        # search, filters, pagination, empty results, errors, ingestion, indexing, feeder, gateway, events
```

Expected result: `FAIL: 0` (final run: 32 PASS, 0 FAIL, 0 SKIP). Service URLs and the test book can be
changed with `SEARCH_URL`, `GATEWAY_URL`, …, `BOOK_ID` (default 1342); `RUN_MUTATING=0` and
`RUN_EVENT_CHECKS=0` skip the checks that change state or need Maven.

## Integration benchmarks

```bash
bash scripts/prepare-bench-datalake.sh 1000          # once: tier-1000 datalake in ~/bench/s2-tier1000 (Stage 1 mirror)
bash scripts/bench-integration.sh                    # every experiment, 3 runs each (~2.5 h)
bash scripts/bench-integration.sh S1 I1              # only some experiments
RUNS=1 DURATION=20 bash scripts/bench-integration.sh S1   # quick trial
python3 scripts/plot-integration.py results/integration/<date-time>/aggregate.csv docs/img/integration   # figures
```

Results go to `results/integration/<date-time>/` (`aggregate.csv`, `host.txt`, one folder per run).
Plan: [docs/INTEGRATION_BENCHMARK.md](docs/INTEGRATION_BENCHMARK.md); results:
[docs/INTEGRATION_RESULTS.md](docs/INTEGRATION_RESULTS.md).

## Microbenchmarks (JMH)

Benchmark classes live in each module under `src/test/java/.../benchmark` (example:
`common/.../benchmark/TokenizerBenchmark.java`). Settings shared by every module are in the
parent `pom.xml` (`jmh.*` properties): 5 warm-up and 10 measurement iterations, 1 fork,
1 thread, fixed heap 4 GB, CSV output.

```bash
mvn -q install -DskipTests                       # once, so modules can find common
mvn -pl common test-compile exec:exec            # all benchmarks of a module
mvn -pl common test-compile exec:exec -Djmh.include=TokenizerBenchmark -Djmh.threads=4
```

Results are written to `results/jmh/<module>.csv`.

Indexing path (tokenize + parse; SQLite write: re-index vs new book, one transaction per book vs
batches, page cache 2 vs 256 MiB; 1/2/4/8 threads in one process):

```bash
bash scripts/bench-indexing.sh                                    # 20 golden books (~1 h)
bash scripts/prepare-bench-datalake.sh 1000                       # once: tier-1000 datalake from the mirror
BENCH_DATADIR=~/bench/s2-tier1000 bash scripts/bench-indexing.sh  # + realistic index size (~1 h 45)
```

Results go to `results/jmh/<date>/` together with `host.txt` (machine specification). Run benchmarks on an idle machine and
record its specification together with the results.

## Indexing service API (internal / administrative)

| Method and route | Purpose | Success |
|---|---|---|
| `POST /index/update/{book_id}` | index one ingested book (idempotent) | `200` once committed and searchable |
| `POST /index/rebuild` | rebuild the datamart from every receipt, atomically | `200` with `books_processed`, `elapsed_time` |
| `GET /index/status` | `books_indexed`, `terms`, `postings`, `last_update`, `index_size_MB` | `200` |

Errors: `400 invalid_book_id`, `404 not_ingested` (no receipt), `409 datalake_inconsistent`
(receipt without file), `409 rebuild_in_progress`, `503 datamart_busy` (retry later).

## Search service API

| Method and route | Purpose |
|---|---|
| `GET /search?q=sea` | Keyword search with optional author, language, year and pagination parameters |
| `GET /books/{book_id}` | Metadata for an indexed book |
| `GET /search/filters` | Available languages and release-year range |
| `GET /status` | Service status and datamart readiness |

Search uses AND semantics for multiple normalized terms and ranks matches
by TF-IDF, with book ID as the tie-breaker. Database access is read-only,
with one read transaction per request.

Default configuration: port 7003, Jetty thread pool minimum 8 / maximum 250.
See [Search documentation](docs/SEARCH_SERVICE.md) for configuration,
response semantics and front-end use cases.

The dedicated Search JMH runner produces timestamped files under `results/jmh/`.
Its setup and results are described in
[Search benchmarks](docs/SEARCH_BENCHMARK.md).

## Configuration

Each service reads its defaults from `src/main/resources/application.properties` inside its jar.
Any key can be overridden, later layers winning:

1. external file: `CONFIG_FILE=/path/to/file.properties`
2. environment variable: key in upper case, `.` and `-` replaced by `_` (`server.port` → `SERVER_PORT`)
3. JVM property: `-Dserver.port=7013`

Example — a second replica of the search service on another port:

```bash
SERVER_PORT=7013 java -jar search-service/target/search-service.jar
```

Common keys:

| Key | Meaning |
|---|---|
| `service.name` | Name reported by `/status` |
| `server.port` | HTTP port of this process |
| `server.threads.min` / `server.threads.max` | Jetty request threads of this process |
| `data.dir` | Workspace root (datalake, datamarts) |
| `broker.url` | ActiveMQ address |
| `consumer.threads` | Message consumer sessions per process (ingestion, indexing) |
| `datamart.cache-mb` | SQLite page cache of the datamart writer, MiB (default 256) |

## Conventions for every service

- All responses are JSON.
- Errors always have the form `{"error": "<code>", "message": "<text>"}`:
  400 for invalid parameters, 404 for unknown resources, 500 for unexpected failures (no internal details).
- `GET /status` is the health endpoint of every HTTP service.

## Continuous integration

GitHub Actions (`.github/workflows/ci.yml`) runs `mvn verify` on every pull request and on `main`.
Merge only green pull requests.
