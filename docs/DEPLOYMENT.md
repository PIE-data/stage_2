# Single-machine deployment

Requirements: Linux/WSL 2, Bash, Python 3 (standard library), Java 17,
Maven 3.9+, nginx. Existing `config/deployment.env` and
`scripts/generate-nginx.sh` are required. Run scripts with Bash; no executable
bit or sudo is required. Configuration files are trusted local Bash code.

## Search and gateway

From the repository root, after `mvn verify`:

```bash
bash scripts/run-all.sh --mode search --data-dir "$HOME/bench/s2-tier1000"
bash scripts/status-all.sh
curl -i 'http://127.0.0.1:8080/search?q=sea&limit=1'
bash scripts/monitor.sh --duration 30
bash scripts/stop-all.sh
```

`--config /absolute/path/to/deployment.env` is supported by every wrapper.
Use the same config for start, status, monitoring and stop. `--data-dir`
overrides the Java data workspace for that invocation only. A missing
datamart is permitted by Search's contract; status then reports empty.

## Full pipeline

The default run mode is `full`. Startup order is broker, ingestion replicas,
indexing replicas, search replicas, feeder and gateway. The default configuration
starts two replicas each of Ingestion, Indexing and Search.

After `mvn verify`, run from the repository root:

```bash
bash scripts/run-all.sh --mode full
bash scripts/status-all.sh
```

The broker exposes messaging on port 61616 and HTTP status/statistics on
port 7000. The gateway exposes Search on port 8080.

Startup does not submit books automatically. To request up to 20 books:

```bash
curl --fail --max-time 60 -sS -X POST \
  'http://127.0.0.1:7004/feeder/run?manifest=100&count=20'
```

`manifest=100` selects `spec/corpus/manifest_100.txt`. An explicit manifest
path is also supported; its format is one numeric book ID per line.
The feeder skips already-ingested books. Use a fresh data workspace and
broker store for a reproducible end-to-end test.

Inspect completion and queues with:

```bash
curl --fail -sS http://127.0.0.1:7004/feeder/status
curl --fail -sS http://127.0.0.1:7002/index/status
curl --fail -sS http://127.0.0.1:7000/broker/stats
curl --fail -sS 'http://127.0.0.1:8080/search?q=sea&limit=3'
```

An accepted feeder request does not imply indexing has completed.

## Configuration and lifecycle

All operational defaults come from `config/deployment.env`: ports, replica
counts, port step, paths, executable names, JVM heap, HTTP and consumer
threads, SQLite cache, gateway timeouts and monitor interval.

Indexing batches are configured per consumer session:

| Deployment variable | JVM property | Default |
|---|---|---|
| `INDEXING_BATCH_SIZE` | `consumer.batch-size` | 20 |
| `INDEXING_BATCH_WAIT_MS` | `consumer.batch-wait-ms` | 200 ms |

The deployment script validates both values as positive integers and passes
them to every Indexing replica. Ingestion and Search do not receive these
batch properties.

Runtime files are outside the repository under RUN_DIR:

- processes.json: PID, /proc start time, boot ID and log path for each process;
- lifecycle.lock: prevents concurrent start/stop operations;
- logs/<timestamp>/<process>.log: separate process stdout/stderr;
- nginx/nginx.conf: generated configuration;
- nginx/logs/access.log and error.log: nginx request and error logs;
- metrics/<timestamp>/processes.csv and host.csv: monitoring output.

Startup checks the requested ports and JARs, validates nginx configuration,
then waits for readiness after each launch. On an ordinary startup failure,
SIGINT or SIGTERM it attempts to stop processes started by that invocation.
An already-running deployment is rejected. Readiness is a startup check,
not continuous supervision: crashed services are not automatically restarted.
A readiness HTTP check validates service name and running status, not datamart
completeness or downstream broker connectivity.

Stop checks PID, start time and boot ID before signalling. It sends SIGTERM,
waits STOP_TIMEOUT_SECONDS and escalates to SIGKILL for matching processes
still alive. Repeated stop is safe. The registry is retained if shutdown
fails. As with any PID-based user-space manager, checking identity and sending
a signal are separate operations; this is not a race-free pidfd supervisor.
Do not change RUN_DIR while managed processes are running.

nginx runs with `daemon off;` as a managed background process. The generator
uses round-robin and retries upstream errors/timeouts. Access logs expose
upstream addresses and statuses. This is passive failure detection.

## Monitoring interpretation

The monitor samples Linux /proc, with no Python packages required.
It observes the process registry at monitor startup. Run a new monitor after
restarting the deployment. Sampling duration defaults to unlimited; Ctrl+C
finishes recording without stopping the services. `--output DIRECTORY`
selects a destination; existing CSV files are not overwritten.

- epoch_s: Unix timestamp in seconds;
- cpu_percent_one_core: CPU time delta divided by elapsed monotonic time;
  100% equals one logical CPU and multithreaded processes may exceed 100%;
- rss_bytes: resident memory, including shared pages; summing RSS can double
  count shared memory;
- alive: 0 for a stopped managed process, with blank CPU/RSS;
- host CPU: aggregate utilization across CPUs, treating idle and iowait as idle;
- host memory: MemTotal minus MemAvailable.

nginx master and its direct workers are separate CSV rows. A worker's first
CPU sample is blank because no earlier counter is available. Short-lived
processes between samples may not be captured.

In WSL, **host means the Linux/WSL environment**, not total Windows CPU/memory.
Use an additional Windows collector if the integration report requires the
physical Windows host's total resource usage.

## Verification status

Local checks passed: Python compilation, Bash syntax and five tests covering
process identity, numeric validation, CSV creation, simulated shutdown,
stale PID protection and rejection of invalid indexing batch settings before
any process is launched.

Run the tests with:

```bash
python3 scripts/deployment/test_manage.py
```

The scripted Search deployment was also verified on Ubuntu 24.04 / WSL 2
on 2026-10-29:

- Two Search replicas started on ports 7003 and 7013.
- nginx started on port 8080.
- A gateway search for `sea` returned HTTP 200, total 823 and book 9600 first.
- Monitoring recorded 15 host samples and 60 process samples across
  two Search processes, the nginx master and one nginx worker.
- Stop completed successfully, the process registry was emptied, and
  ports 7003, 7013 and 8080 had no remaining listeners.

CSV evidence is stored in
`results/deployment/search-scripted-20261029/`.

This was an idle monitoring check, not a load benchmark.
Host metrics describe Linux/WSL, not the entire Windows host.

Earlier manual gateway tests also verified round-robin distribution,
continued searches after stopping one Search replica with Ctrl+C, and
reintegration after restarting it. Those tests are separate from the
scripted lifecycle check.

Additional manual WSL checks passed on 2026-11-02: duplicate-start
rejection without disrupting existing services, occupied-port rejection
without affecting the external listener, and cleanup after an intentional
failure of the second Search replica. Evidence and observations are in
`results/deployment/startup-checks-20261102/`.

### Full pipeline verification — 2026-11-03

Both runs used Ubuntu/WSL, fresh isolated data and broker directories,
two replicas each of Ingestion, Indexing and Search, and indexing batches
configured at 20 messages with a 200 ms wait.

**20-book smoke test**

- Requested, downloaded and indexed: 20; reported failures: 0.
- All 20 book IDs were retrieved through the gateway.
- Both Search upstreams were observed.
- Searching for `sea` returned 15 matches.
- Both work queues drained; the DLQ had zero depth and zero enqueues.
- All managed services stopped and the deployment ports were released.

Evidence: `results/deployment/full-smoke-20261103-193735/`.

**100-book replica failure test**

- Sent SIGKILL to `indexing-2` after observing 70 indexed books.
- The remaining Indexing replica completed processing: 100 downloaded and
  indexed books, with zero reported failures.
- Both work queues drained; the DLQ had zero depth and zero enqueues.
- All 100 book IDs were retrieved through the gateway, with both Search
  upstreams observed.
- Searching for `sea` returned 79 distinct results, all from the manifest.
- `indexing-2` remained stopped; no automatic restart was performed.
- Final shutdown released all deployment ports.

Evidence: `results/deployment/full-failover-20261103-194255/`.

The failure was injected during processing, after a count of 70/100 was
observed, rather than exactly at the halfway point. The test demonstrates
completion after replica loss; it does not establish that the killed
replica was inside a SQLite transaction at that instant.

These are functional deployment and resilience checks, not the performance
benchmarks of issue #8. Monitoring includes idle time and may include time
after service shutdown. Host metrics refer to Linux/WSL.

The two full-pipeline runs used IDs from `spec/corpus/manifest_100.txt`;
the 20-book smoke test is not the separate golden dataset test.

### Offline golden quick run

Verified on 2026-11-03: the preparation utility produced 20 indexed books
and matched the canonical SHA-256:

`b36095ef3966ccd1b5f273f914859aa83ff82e2499f695a422e364e8ecf0fc94`

Two Search replicas and the nginx gateway were started against this
datamart. Searching for `sea` through port 8080 returned 10 matches,
with book 10056 ranked first.

This quick run uses the bundled golden headers and compressed bodies.
It prepares the datamart directly using the shared indexing code and
does not exercise broker messaging or live downloads. Those paths were
tested separately in the full-pipeline runs above.

From the repository root, with Java, Maven and nginx installed:

```bash
mvn verify

mkdir -p "$HOME/bench"
goldenDir="$(mktemp -d "$HOME/bench/s2-golden-quick.XXXXXX")"
cp config/deployment.env "$goldenDir/deployment.env"

{
    printf '\nDATA_DIR="%s/data"\n' "$goldenDir"
    printf 'RUN_DIR="%s/run"\n' "$goldenDir"
} >> "$goldenDir/deployment.env"

java -cp "search-service/target/test-classes:search-service/target/search-service.jar" \
    es.ulpgc.piedata.search.PrepareGoldenDatamart \
    "$goldenDir/data"
```

Continue only if preparation exits successfully and reports the expected
canonical hash and 20 indexed books:

```bash
bash scripts/run-all.sh --config "$goldenDir/deployment.env" --mode search

curl --fail -sS 'http://127.0.0.1:8080/search?q=sea&limit=20'

bash scripts/stop-all.sh --config "$goldenDir/deployment.env"
```

The preparation utility refuses to overwrite an existing database.
Keep the same shell open so that `goldenDir` remains available.
Evidence is stored under `results/deployment/golden-quick-*/`.