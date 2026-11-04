#!/usr/bin/env bash
# Integration benchmarks (#8, docs/INTEGRATION_BENCHMARK.md section 3), run one after the other
# with the deployment scripts of #7 and the measurement client (bench-client).
#
#   bash scripts/bench-integration.sh                 # every experiment: S1 S2 I1 I2 I3 I4 P1 X1
#   bash scripts/bench-integration.sh S1 I1           # only some of them
#   RUNS=1 DURATION=20 bash scripts/bench-integration.sh S1   # quick trial (not for the report)
#
# Settings (environment variables, defaults in brackets):
#   BENCH_DATA  datalake with the 1000 books, read only        [~/bench/s2-tier1000]
#   BENCH_WORK  scratch folder: data, runtime, datamart copy    [~/bench/s2-integration]
#   STAGE1      Stage 1 repo with the Gutenberg mirror (for P1) [~/bench/stage_1]
#   OUT         results folder                                  [results/integration/<date-time>]
#   RUNS=3  WARMUP=10  DURATION=60  (search: seconds not measured / measured)
#   S1_LEVELS="1 2 4 8 16 32"   S2_LEVELS="16 32"   I4_RATES="2 5 10"   I4_BOOKS=200
#   X1_CONCURRENCY=16   X1_DURATION=30
#   TIMEOUT=1800 (seconds to wait for every BookIndexed)
#   DRY_RUN=1   prints the commands instead of running them
#
# Every run starts from a clean state (new broker store; empty or restored datamart), starts the
# services with scripts/run-all.sh, samples CPU/memory with scripts/monitor.sh, runs the client,
# stops everything. Results: $OUT/<experiment>/<configuration>/run-<k>/ and $OUT/aggregate.csv.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

BENCH_DATA="${BENCH_DATA:-$HOME/bench/s2-tier1000}"
BENCH_WORK="${BENCH_WORK:-$HOME/bench/s2-integration}"
STAGE1="${STAGE1:-$HOME/bench/stage_1}"
OUT="${OUT:-results/integration/$(date +%Y%m%d-%H%M)}"
RUNS="${RUNS:-3}"
WARMUP="${WARMUP:-10}"
DURATION="${DURATION:-60}"
S1_LEVELS="${S1_LEVELS:-1 2 4 8 16 32}"
S2_LEVELS="${S2_LEVELS:-16 32}"
X1_CONCURRENCY="${X1_CONCURRENCY:-16}"
I4_RATES="${I4_RATES:-2 5 10}"     # books/s, below the batch-20 throughput (~16 books/s)
I4_BOOKS="${I4_BOOKS:-200}"
X1_DURATION="${X1_DURATION:-30}"     # must fit inside the re-index (~1 min for 1000 books)
TIMEOUT="${TIMEOUT:-1800}"
DRY_RUN="${DRY_RUN:-0}"
EXPERIMENTS=("$@")
[ ${#EXPERIMENTS[@]} -gt 0 ] || EXPERIMENTS=(S1 S2 I1 I2 I3 I4 P1 X1)

CLIENT="$ROOT/bench-client/target/bench-client.jar"
IDS="$ROOT/bench/ids-tier1000.txt"
QUERIES="$ROOT/bench/queries-tier1000.txt"
DATA="$BENCH_WORK/data"                 # datalake/control: links to BENCH_DATA; datamarts/broker: real
PRISTINE="$BENCH_WORK/datamart-tier1000"   # copy of the full datamart, restored before search runs
CFG="$BENCH_WORK/run.env"
BROKER_HTTP="http://127.0.0.1:7000"
MONITOR_PID=""
MIRROR_PID=""

log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }
run() { if [ "$DRY_RUN" = 1 ]; then echo "  + $*"; else "$@"; fi; }

# --- deployment ---------------------------------------------------------------------------------

# Writes the configuration of one run: repository defaults + baseline (1 replica each) + overrides.
config() {
    {
        cat "$ROOT/config/deployment.env"
        printf '\n# --- bench-integration.sh ---\n'
        printf 'DATA_DIR="%s"\nRUN_DIR="%s/run"\n' "$DATA" "$BENCH_WORK"
        printf 'INGESTION_REPLICAS=1\nINDEXING_REPLICAS=1\nSEARCH_REPLICAS=1\n'
        for kv in "$@"; do printf '%s\n' "$kv"; done
    } > "$CFG"
}

start() {   # $1 = full | search, $2 = run folder
    run bash scripts/run-all.sh --config "$CFG" --mode "$1" > "$2/run-all.log" 2>&1 \
        || { log "ERROR: start failed, see $2/run-all.log"; tail -5 "$2/run-all.log"; exit 1; }
}

stop() {
    [ "$DRY_RUN" = 1 ] && { echo "  + stop-all"; return; }
    [ -f "$CFG" ] || return 0
    bash scripts/stop-all.sh --config "$CFG" > "$BENCH_WORK/stop-all.log" 2>&1 \
        || log "WARNING: stop failed, see $BENCH_WORK/stop-all.log"
}

monitor_start() {   # CPU/memory per process and host, once per second, until monitor_stop
    [ "$DRY_RUN" = 1 ] && { echo "  + monitor -> $1/metrics"; return; }
    bash scripts/monitor.sh --config "$CFG" --output "$1/metrics" > "$1/monitor.log" 2>&1 &
    MONITOR_PID=$!
}

monitor_stop() {
    if [ -n "$MONITOR_PID" ]; then
        kill "$MONITOR_PID" 2>/dev/null || true   # CSV rows are flushed after every sample
        wait "$MONITOR_PID" 2>/dev/null || true
        MONITOR_PID=""
    fi
}

cleanup() {
    monitor_stop
    stop
    [ -n "$MIRROR_PID" ] && kill "$MIRROR_PID" 2>/dev/null || true
}
trap cleanup EXIT

# --- data ---------------------------------------------------------------------------------------

link_datalake() {
    mkdir -p "$DATA"
    ln -sfn "$BENCH_DATA/datalake" "$DATA/datalake"
    ln -sfn "$BENCH_DATA/control" "$DATA/control"
}

empty_datamart() {    # indexing runs: no index yet, empty broker store
    run rm -rf "$DATA/datamarts" "$DATA/broker"
}

restore_datamart() {  # search runs: the full 1000-book index, empty broker store
    run rm -rf "$DATA/datamarts" "$DATA/broker"
    run mkdir -p "$DATA/datamarts"
    run cp "$PRISTINE"/datamart.db* "$DATA/datamarts/"
}

# Builds the full datamart once (POST /index/rebuild) and keeps a copy of it.
prepare_datamart() {
    if [ -f "$PRISTINE/datamart.db" ]; then return; fi
    log "Building the 1000-book datamart once (rebuild, ~1 min)"
    mkdir -p "$BENCH_WORK/prepare"
    empty_datamart
    config
    start full "$BENCH_WORK/prepare"
    run curl -sS --fail -m "$TIMEOUT" -X POST http://127.0.0.1:7002/index/rebuild
    echo
    stop
    run mkdir -p "$PRISTINE"
    run cp "$DATA/datamarts"/datamart.db* "$PRISTINE/"
}

# --- one measured run ---------------------------------------------------------------------------

new_run() {   # $1 = experiment/configuration, $2 = k; prints the run folder
    local dir="$OUT/$1/run-$2"
    mkdir -p "$dir"
    echo "$dir"
}

# Runs bench-client; its log lines (ActiveMQ, warnings) go to client.log in the run folder.
client() {
    local out="" prev=""
    for a in "$@"; do [ "$prev" = --out ] && out="$a"; prev="$a"; done
    [ "$DRY_RUN" = 1 ] && { echo "  + bench-client $*"; return; }
    java -jar "$CLIENT" "$@" > /dev/null 2>> "$out/client.log" \
        || { log "ERROR: bench-client failed, see $out/client.log"; tail -5 "$out/client.log"; exit 1; }
}

# Prints a warning when a run should not be kept (brief: correct results before performance).
check() {
    [ "$DRY_RUN" = 1 ] && return
    python3 - "$1/summary.json" <<'EOF'
import json, sys
s = json.load(open(sys.argv[1]))
problems = []
for key in ("missing", "errors"):
    if s.get(key):
        problems.append(f"{key}={s[key]}")
dlq = s.get("broker", {}).get("ActiveMQ.DLQ", {}).get("enqueued_during_run")
if dlq:
    problems.append(f"DLQ enqueued={dlq}")
if s.get("kind") == "search" and s.get("requests") and s.get("empty_results", 0) >= s["requests"] - s.get("errors", 0):
    problems.append("every query returned 0 results (datamart missing or empty?)")
if s.get("skipped_already_ingested"):
    problems.append(f"skipped={s['skipped_already_ingested']} (datalake not empty)")
info = ""
if s.get("kind") == "search":
    info = f" (qps {s.get('throughput_qps')}, p95 {s['latency_ms'].get('p95')} ms, empty {s.get('empty_results')}/{s.get('requests')})"
elif "throughput_books_s" in s:
    info = f" ({s['throughput_books_s']} books/s, latency p95 {s['latency_ms'].get('p95')} ms)"
print(("  CHECK FAILED: " + ", ".join(problems) + "  -> " + sys.argv[1]) if problems else "  check ok" + info)
EOF
}

measure_search() {   # $1 run folder, $2 url, $3 concurrency, $4 label
    monitor_start "$1"
    client search --url "$2" --queries "$QUERIES" --concurrency "$3" --warmup "$WARMUP" \
        --duration "$DURATION" --out "$1" --label "$4"
    monitor_stop
    check "$1"
}

measure_index_paced() {   # $1 run folder, $2 label; uses $rate and I4_BOOKS
    monitor_start "$1"
    client index-only --data-dir "$DATA" --broker-http "$BROKER_HTTP" --timeout "$TIMEOUT" \
        --rate "$rate" --count "$I4_BOOKS" --out "$1" --label "$2"
    monitor_stop
    check "$1"
}

measure_index_only() {   # $1 run folder, $2 label
    monitor_start "$1"
    client index-only --data-dir "$DATA" --broker-http "$BROKER_HTTP" --timeout "$TIMEOUT" \
        --out "$1" --label "$2"
    monitor_stop
    check "$1"
}

one_run() {   # $1 experiment/config, $2 label, $3 mode, $4 data: restore|empty, $5 measure function, rest = config
    local name="$1" label="$2" mode="$3" data="$4" measure="$5"
    shift 5
    for k in $(seq "$RUNS"); do
        local dir
        dir="$(new_run "$name" "$k")"
        log "$name run $k/$RUNS"
        if [ "$data" = restore ]; then restore_datamart; else empty_datamart; fi
        config "$@"
        cp "$CFG" "$dir/deployment.env"
        start "$mode" "$dir"
        "$measure" "$dir" "$label"
        stop
    done
}

# --- experiments (docs/INTEGRATION_BENCHMARK.md section 3) -------------------------------------

search_direct() { measure_search "$1" http://127.0.0.1:7003 "$CONC" "$2"; }
search_gateway() { measure_search "$1" http://127.0.0.1:8080 "$CONC" "$2"; }

S1() {   # search latency vs client concurrency, 1 replica, direct port
    for CONC in $S1_LEVELS; do
        one_run "S1/c$CONC" "S1-c$CONC" search restore search_direct
    done
}

S2() {   # search replicas behind the gateway
    for r in 1 2 3; do
        for CONC in $S2_LEVELS; do
            one_run "S2/r$r-c$CONC" "S2-r$r-c$CONC" search restore search_gateway "SEARCH_REPLICAS=$r"
        done
    done
}

I1() {   # indexing batch size
    for b in 1 5 20 50; do
        one_run "I1/b$b" "I1-b$b" full empty measure_index_only "INDEXING_BATCH_SIZE=$b"
    done
}

I2() {   # consumer sessions per indexing replica
    for t in 1 2 4; do
        one_run "I2/t$t" "I2-t$t" full empty measure_index_only "INDEXING_CONSUMER_THREADS=$t"
    done
}

I4() {   # per-book indexing latency BELOW saturation: books sent at a fixed rate, not all at once
    for rate in $I4_RATES; do
        one_run "I4/rate$rate" "I4-rate$rate" full empty measure_index_paced "INDEXING_BATCH_SIZE=20"
    done
}

I3() {   # indexing replicas
    for r in 1 2 3; do
        one_run "I3/r$r" "I3-r$r" full empty measure_index_only "INDEXING_REPLICAS=$r"
    done
}

start_mirror() {   # local Gutenberg mirror of Stage 1 (same server as prepare-bench-datalake.sh)
    [ -n "$MIRROR_PID" ] && return
    [ -f "$STAGE1/tools/mirror_server.py" ] || { log "ERROR: mirror not found under $STAGE1"; exit 1; }
    local port_file="$BENCH_WORK/mirror.port"
    rm -f "$port_file"
    python3 "$STAGE1/tools/mirror_server.py" --root "$STAGE1/infra/mirror" --port-file "$port_file" > /dev/null 2>&1 &
    MIRROR_PID=$!
    for _ in $(seq 50); do [ -s "$port_file" ] && break; sleep 0.1; done
    [ -s "$port_file" ] || { log "ERROR: mirror server did not start"; exit 1; }
    MIRROR_URL="http://127.0.0.1:$(cat "$port_file")"
    local first
    first="$(grep -v '^#' "$IDS" | head -1)"
    curl -sS --fail -o /dev/null "$MIRROR_URL/cache/epub/$first/pg$first.txt" \
        || { log "ERROR: mirror does not serve book $first at $MIRROR_URL"; exit 1; }
    log "Mirror on $MIRROR_URL"
}

P1() {   # ingestion replicas, full pipeline from the local mirror into an EMPTY datalake
    start_mirror
    local saved_data="$DATA"
    DATA="$BENCH_WORK/pipeline-data"
    for r in 1 2 4; do
        local urls="" i
        for i in $(seq 0 $((r - 1))); do urls="$urls${urls:+,}http://127.0.0.1:$((7001 + 10 * i))"; done
        for k in $(seq "$RUNS"); do
            local dir
            dir="$(new_run "P1/i$r" "$k")"
            log "P1/i$r run $k/$RUNS"
            run rm -rf "$DATA"
            mkdir -p "$DATA"
            config "INGESTION_REPLICAS=$r" "SOURCE_BASE_URL=\"$MIRROR_URL\""
            cp "$CFG" "$dir/deployment.env"
            start full "$dir"
            monitor_start "$dir"
            client pipeline --ingest-url "$urls" --ids "$IDS" --broker-http "$BROKER_HTTP" \
                --timeout "$TIMEOUT" --out "$dir" --label "P1-i$r"
            monitor_stop
            check "$dir"
            stop
        done
    done
    DATA="$saved_data"
}

X1() {   # search while indexing: same concurrency, idle vs during a re-index of the 1000 books
    CONC="$X1_CONCURRENCY"
    for k in $(seq "$RUNS"); do
        local dir
        dir="$(new_run "X1/idle-c$CONC" "$k")"
        log "X1 idle run $k/$RUNS"
        restore_datamart
        config
        cp "$CFG" "$dir/deployment.env"
        start full "$dir"
        monitor_start "$dir"
        client search --url http://127.0.0.1:7003 --queries "$QUERIES" --concurrency "$CONC" \
            --warmup 5 --duration "$X1_DURATION" --out "$dir" --label "X1-idle-c$CONC"
        monitor_stop
        check "$dir"
        stop

        dir="$(new_run "X1/indexing-c$CONC" "$k")"
        local idx="$dir/indexing"
        mkdir -p "$idx"
        log "X1 during indexing run $k/$RUNS"
        restore_datamart
        config
        cp "$CFG" "$dir/deployment.env"
        start full "$dir"
        monitor_start "$dir"
        # same timing as idle: 5 s not measured, then X1_DURATION s measured, all inside the re-index
        if [ "$DRY_RUN" = 1 ]; then
            echo "  + (background) index-only -> $idx"
        else
            java -jar "$CLIENT" index-only --data-dir "$DATA" --broker-http "$BROKER_HTTP" \
                --timeout "$TIMEOUT" --out "$idx" --label "X1-indexing-load" > /dev/null 2> "$idx/client.log" &
        fi
        local loader="${!:-}"
        client search --url http://127.0.0.1:7003 --queries "$QUERIES" --concurrency "$CONC" \
            --warmup 5 --duration "$X1_DURATION" --out "$dir" --label "X1-indexing-c$CONC"
        [ "$DRY_RUN" = 1 ] || wait "$loader" || true
        monitor_stop
        check "$dir"
        if [ "$DRY_RUN" != 1 ]; then
            check "$idx"
            python3 -c 'import json,sys; d=json.load(open(sys.argv[1])).get("duration_s") or 0
if d < float(sys.argv[2]): print(f"  CHECK FAILED: the re-index lasted {d} s, less than the search window {sys.argv[2]} s: lower X1_DURATION")' \
                "$idx/summary.json" "$((5 + X1_DURATION))"
        fi
        stop
    done
}

# --- main ---------------------------------------------------------------------------------------

for e in "${EXPERIMENTS[@]}"; do
    case "$e" in S1|S2|I1|I2|I3|I4|P1|X1) ;; *) echo "unknown experiment '$e' (S1 S2 I1 I2 I3 I4 P1 X1)"; exit 2 ;; esac
done
for tool in java nginx curl python3; do
    command -v "$tool" > /dev/null || { echo "missing '$tool' (e.g. sudo apt install -y nginx)"; exit 1; }
done
[ -f "$CLIENT" ] || { echo "missing $CLIENT: run mvn verify first"; exit 1; }
[ -d "$BENCH_DATA/datalake" ] && [ -d "$BENCH_DATA/control" ] \
    || { echo "BENCH_DATA=$BENCH_DATA must contain datalake/ and control/ (scripts/prepare-bench-datalake.sh)"; exit 1; }
for f in "$IDS" "$QUERIES"; do [ -f "$f" ] || { echo "missing $f"; exit 1; }; done
case "$BENCH_WORK" in "$BENCH_DATA"|"$BENCH_DATA"/*) echo "BENCH_WORK must be outside BENCH_DATA"; exit 1 ;; esac

mkdir -p "$OUT" "$BENCH_WORK"
{
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit: $(git rev-parse --short HEAD) $(git diff --quiet || echo '(uncommitted changes!)')"
    echo "experiments: ${EXPERIMENTS[*]}  runs: $RUNS  warmup: ${WARMUP}s  duration: ${DURATION}s"
    echo "bench data: $BENCH_DATA ($(find "$BENCH_DATA/control/ingestion/hash" -name '*.json' | wc -l) receipts)"
    uname -a
    lscpu | grep -E 'Model name|^CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket' || true
    free -h | head -2
    df -h "$BENCH_WORK" | tail -1
    java -version 2>&1 | head -1
    nginx -v 2>&1 || true
} > "$OUT/host.txt"
log "Results in $OUT (host description in host.txt)"

link_datalake
case " ${EXPERIMENTS[*]} " in *" S1 "*|*" S2 "*|*" X1 "*) prepare_datamart ;; esac
for e in "${EXPERIMENTS[@]}"; do
    log "=== $e"
    "$e"
done

run java -jar "$CLIENT" aggregate --runs "$OUT" --out "$OUT/aggregate.csv" > /dev/null 2> "$OUT/aggregate.log"
log "Done: $OUT/aggregate.csv"
