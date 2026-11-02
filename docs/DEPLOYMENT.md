# Single-machine deployment (work in progress)

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

The default run mode is `full`. With the supplied sources it fails before
launching anything because broker, feeder and ingestion are skeletons.
The full startup plan is broker, ingestion replicas, indexing replicas,
search replicas, feeder and gateway. This plan is provisional until the
messaging implementations are integrated and tested. It does not submit
feeder requests automatically: the real feeder API is still absent.
Neither a complete tier-100 run nor abrupt-kill recovery is claimed.

## Configuration and lifecycle

All operational defaults come from `config/deployment.env`: ports, replica
counts, port step, paths, executable names, JVM heap, HTTP and consumer
threads, SQLite cache, gateway timeouts and monitor interval.

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
stale PID protection and refusal of full mode with the supplied skeletons.

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

Pending acceptance: full messaging integration, tier-100 completion with
two replicas per service including abrupt replica failure, and golden
quick-run integration. Existing 20 golden inputs remain in `spec/golden`.