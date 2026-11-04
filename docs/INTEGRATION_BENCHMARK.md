# Integration benchmarks — plan and tools (#8)

Status: **plan, measurement client and runner ready**; `scripts/bench-integration.sh` runs the
experiments with the deployment scripts of #7 (start/stop, CPU/memory sampling). Brief references: §6.1 (scope), §6.2 (replication
and threading), §6.4 (integration benchmarking), §6.5 (analysis).

## 1. Questions and metrics

| Brief question / metric (§6, §6.4) | How it is measured | Tool / file |
|---|---|---|
| Ingestion throughput (books/s) | `books.downloaded` messages enqueued per second during a `pipeline` run | `pipeline` → `summary.json: ingestion_throughput_books_s`, `broker.csv` |
| Indexing throughput (books/s) and indexing latency (s/book) | `index-only`: client publishes `BookDownloaded` for books already in the datalake, waits for every `BookIndexed` | `index-only` → `throughput_books_s`, `time_per_book_ms`; per-book `latency_ms` from I4 (paced) |
| End-to-end latency (request → searchable) | `pipeline`: client `POST /ingest/{id}` → client receives `BookIndexed` (same clock) | `pipeline` → `latency_ms`, `books.csv` |
| Query response time (mean, p95) under concurrent load | closed-loop clients on `GET /search`, warm-up excluded | `search` → `latency_ms`, `throughput_qps`, `timeline.csv` |
| Coordination cost | broker average time in queue per destination; e2e latency minus processing time (JMH: ~18 ms/book in batches, 05bcfec) | `broker.csv`, `summary.json: broker` |
| CPU / memory per process and host | sampled by the #7 monitor while the client runs | #7 `scripts/monitor.sh` CSV |

Every measured run writes `summary.json` with the options used and the client host, so results
are self-describing. Client concurrency (`--concurrency`) is always reported separately from
service threads (`consumer.threads`, `server.threads.*`) and replicas, as §6.1 requires.

## 2. The measurement client (`bench-client`)

A separate Maven module, **not deployed**: it runs next to the system and uses only public
interfaces (HTTP APIs, broker destinations). Built by `mvn verify` like the services.

```bash
J="java -jar bench-client/target/bench-client.jar"
# workload of queries from a datamart (reproducible: fixed seed, terms from 3 df groups)
$J queries --datamart ~/bench/s2-tier1000/datamarts/datamart.db --out bench/queries-tier1000.txt
# search load: 8 clients, 10 s warm-up, 60 s measured
$J search --url http://127.0.0.1:8080 --queries bench/queries-tier1000.txt --concurrency 8 \
          --warmup 10 --duration 60 --out results/integration/<date>/search/c8/run-1 --label search-c8
# indexing only (books already in the datalake; start from an empty datamart)
$J index-only --data-dir ~/bench/s2-tier1000 --broker-http http://127.0.0.1:7000 \
          --out results/integration/<date>/index/b20-t2-r1/run-1 --label index-b20-t2-r1
# full pipeline (empty datalake; ingestion source = local mirror)
$J pipeline --ingest-url http://127.0.0.1:7001,http://127.0.0.1:7011 --ids bench/ids-tier1000.txt \
          --broker-http http://127.0.0.1:7000 --out results/integration/<date>/pipeline/i2/run-1 --label pipeline-i2
# repeated runs -> mean, sd, min, max per metric and label
$J aggregate --runs results/integration/<date> --out results/integration/<date>/aggregate.csv
```

Notes:
- `pipeline` counts a book that is already ingested (HTTP 200) as *skipped*: it produces no event.
  Use an empty datalake, otherwise the run measures nothing.
- `--rate R` sends R books per second (open loop); default 0 = as fast as possible (saturation).
- `broker.csv` is sampled once per second; `average_time_ms` is ActiveMQ's average since the
  broker started, so the broker is restarted before every run.
- The list of ids for `pipeline`: `ls ~/bench/s2-tier1000/control/ingestion/hash | sed 's/\.json$//' | sort -n > bench/ids-tier1000.txt`.

## 3. Experiments (one factor at a time, §6.2)

Baseline (single instance): 1 replica per service, `consumer.threads` ingestion 4 / indexing 2,
`consumer.batch-size` 20, `server.threads` 8/64, heap 64m/512m, dataset tier 1000 (1000 books,
`~/bench/s2-tier1000`). Each configuration is run **3 times**; tables show mean ± sd.

| Id | Question | Varied factor (others = baseline) | Workload | Main outputs |
|---|---|---|---|---|
| S1 | Search latency vs client load | client concurrency 1, 2, 4, 8, 16, 32 (1 search replica, direct port) | `search`, 60 s, idle indexing | qps, p50/p95 vs concurrency; saturation point |
| S2 | Do Search replicas help? | search replicas 1, 2, 3 behind the gateway, at the concurrency where S1 saturates and one above | `search` via :8080 | qps and p95 vs replicas; CPU per replica |
| I1 | Indexing batch size | `consumer.batch-size` 1, 5, 20, 50 | `index-only`, 1000 books, empty datamart | books/s, s/book, latency p95 |
| I2 | Indexing threads | `consumer.threads` 1, 2, 4 | as I1 | books/s (expected flat: single SQLite writer, JMH 05bcfec) |
| I3 | Indexing replicas | indexing replicas 1, 2, 3 | as I1 | books/s, `SQLITE_BUSY` / rollbacks in logs, broker time in queue |
| I4 | Per-book indexing latency below saturation | send rate 2, 5, 10 books/s (`--rate`), 200 books, batch 20 | `index-only` paced | latency mean/p95 per book (batch wait + write), not queue backlog |
| P1 | Ingestion replicas | ingestion replicas 1, 2, 4 | `pipeline`, 1000 books from the local mirror, empty datalake + datamart | ingestion books/s, e2e latency, queue depth over time |
| X1 | Search while indexing | none: S1 at the saturating concurrency **during** an `index-only` run | `search` + `index-only` in parallel | p95 idle vs under indexing (WAL readers vs single writer) |

Order: S1 → S2 → I1 → I2 → I3 → I4 → P1 → X1.

Latency of unpaced runs (I1–I3, all 1000 messages sent at once) is mostly **time waiting in the
queue** (backlog): it describes saturation, not the cost of one book. Per-book indexing latency is
`time_per_book_ms` (inverse throughput) and the paced runs of I4. Search runs also record
`empty_results` and `results_total`, because Search answers 200 with 0 results when the datamart is
missing: a run where every query is empty is flagged. Final numbers only from the final code (report rule:
only results of the delivered code), on a clean commit (`host.txt` flags uncommitted changes).
X1 measures `X1_DURATION` s (default 30) so that the search window fits inside the re-index; the
runner flags the run if the re-index ended earlier.

Expected bottlenecks to confirm or reject (ARCHITECTURE.md §6): the single SQLite writer for
indexing; network/disk for ingestion (removed by the local mirror, so ingestion measures CPU +
disk); CPU for Search replicas, which share the same cores as everything else.

## 4. Running: `scripts/bench-integration.sh`

```bash
mvn verify                                                  # builds the jars, bench-client included
bash scripts/bench-integration.sh                           # all experiments, 3 runs each (~2 h)
bash scripts/bench-integration.sh S1 I1                     # a subset
RUNS=1 DURATION=20 S1_LEVELS="1 8" bash scripts/bench-integration.sh S1 I1   # quick trial
```

Settings are environment variables listed at the top of the script (data folders, runs, warm-up,
duration, concurrency levels, timeout, `DRY_RUN=1`). Data: `BENCH_DATA` (default
`~/bench/s2-tier1000`) is only read; everything that changes lives in `BENCH_WORK` (default
`~/bench/s2-integration`): links to the datalake, the datamart and broker store of each run, a
copy of the full datamart (built once with `POST /index/rebuild`) restored before every search
run, the empty datalake of P1. P1 starts the Stage 1 mirror server (`STAGE1`, default
`~/bench/stage_1`) and points `SOURCE_BASE_URL` at it, so ingestion does not depend on the network.
Every run keeps its `deployment.env`, `run-all.log`, `metrics/` (CPU/memory CSV) and the client
output; a line `CHECK FAILED` in the console means the run must not be used (step 6 below).

What the runner does for each run:

1. Once per session: host description (CPU, cores, RAM, disk, OS/WSL, Java, commit) in
   `results/integration/<date>/host.txt`. Keep the host idle while it runs.
2. Reset state: stop all services; for `index-only` delete `<data.dir>/datamarts/`; for `pipeline`
   delete the datalake and control folders too; the broker store is deleted so queues start empty.
3. Start the configuration with the #7 scripts, wait until every `/status` answers.
4. Start the #7 monitor (CPU/memory CSV) into the run folder.
5. Run the client command with `--out results/integration/<date>/<experiment>/<config>/run-<k>`.
6. Check before keeping the run: `missing = 0`, `errors = 0`, nothing enqueued on `ActiveMQ.DLQ`,
   no book skipped as already ingested (P1).
7. Stop everything; repeat from 2 for the next run.

## 5. Analysis (§6.5)

- `aggregate.csv`: one row per label and metric (mean, sd, min, max over the runs).
- Plots: throughput vs concurrency (S1), p95 vs replicas (S2), books/s vs batch/threads/replicas
  (I1–I3), queue depth over time (P1, from `broker.csv`), latency distribution idle vs indexing (X1).
- For each experiment state where throughput stops growing and why (CPU saturated? single writer?
  queue growing?), using the CPU/memory CSV and broker statistics, not only the throughput.
- Limits: one machine (WSL 2), so replicas share cores and disk; local speedup is not evidence of
  cluster scalability (§6.2). What does not carry over to Stage 3: localhost URLs, the shared local
  datalake folder and the single SQLite file.
