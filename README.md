# PIE-data search engine — Stage 2

Service-oriented version of the Stage 1 data layer: independent Java services that cooperate
through an ActiveMQ message broker and REST APIs, all running on one machine.

> Status: **foundation done** (issue #1): skeleton, contracts, Java tokenizer, JMH setup.
> Every service starts and answers `GET /status`; the real endpoints are added in issues #2–#5.

## Design documents

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — components, diagram, life of a book, replicas and threads
- [docs/DATAMART.md](docs/DATAMART.md) — SQLite schema shared by Indexing (writer) and Search (reader)
- [docs/EVENTS.md](docs/EVENTS.md) — broker queues, message formats, acknowledgements and retries

## Stage 1 reference files

`spec/` holds the frozen Stage 1 inputs and oracles (stop words, language map, golden books,
expected hashes, corpus manifests, query sets). See [spec/README.md](spec/README.md).
The Java ports in `common` are verified against Stage 1: the tokenizer
(`es.ulpgc.piedata.common.text.Tokenizer`) against `spec/golden/tokens_20.jsonl` by
`GoldenTokensTest`, the header parser (`es.ulpgc.piedata.common.metadata.HeaderParser`)
against `spec/golden/metadata_20.jsonl` by `GoldenMetadataTest`.

## Requirements

- Java 17 or newer (the code is compiled for Java 17)
- Maven 3.9 or newer

## Modules

| Module | What it is | Default port | Issue |
|---|---|---|---|
| `common` | Shared code: configuration, HTTP server conventions, JSON | – | #1 |
| `broker` | Embedded ActiveMQ broker (own process) | 61616 | #2 |
| `ingestion-service` | Downloads books into the datalake | 7001 | #3 |
| `indexing-service` | Builds the inverted index and the metadata datamart | 7002 | #4 |
| `search-service` | Front-end search API | 7003 | #5 |
| `feeder` | Decides which books to ingest | 7004 | #2 |

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

Results are written to `results/jmh/<module>.csv`. Run benchmarks on an idle machine and
record its specification together with the results.

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

## Conventions for every service

- All responses are JSON.
- Errors always have the form `{"error": "<code>", "message": "<text>"}`:
  400 for invalid parameters, 404 for unknown resources, 500 for unexpected failures (no internal details).
- `GET /status` is the health endpoint of every HTTP service.

## Continuous integration

GitHub Actions (`.github/workflows/ci.yml`) runs `mvn verify` on every pull request and on `main`.
Merge only green pull requests.
