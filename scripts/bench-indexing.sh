#!/usr/bin/env bash
# Microbenchmarks of the indexing path (issue #4), brief section 6.3.
#   common:            BookIndexerBenchmark (CPU only)
#                      DatamartWriteBenchmark (re-index one book; page cache 2 vs 256 MiB)
#                      DatamartInsertBenchmark (first indexing of one book)
#                      DatamartBatchBenchmark (20 books: one transaction each vs one batch)
#   indexing-service:  IndexingThroughputBenchmark (one book per call vs batches of 20), 1/2/4/8 threads
#
# Run on an idle machine, from the repository root:
#   bash scripts/bench-indexing.sh                                  # 20 golden books only (~1 h)
#   BENCH_DATADIR=~/bench/s2-tier1000 bash scripts/bench-indexing.sh  # + a real datalake (~1 h 45)
# (prepare the datalake with scripts/prepare-bench-datalake.sh; no spaces in the path)
# Results: results/jmh/<date>/ (CSV per run + host.txt).
set -euo pipefail
cd "$(dirname "$0")/.."

DATADIR="${BENCH_DATADIR:-}"
OUT="results/jmh/$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit: $(git rev-parse --short HEAD)"
  echo "bench datadir: ${DATADIR:-<none: 20 golden books>}"
  [ -n "$DATADIR" ] && echo "books in datadir: $(ls "$DATADIR/control/ingestion/hash" | grep -c '\.json$')"
  uname -a
  lscpu | grep -E 'Model name|^CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket' || true
  free -h | head -2
  df -h . | tail -1
  java -version 2>&1 | head -1
} > "$OUT/host.txt"

mvn -q install -DskipTests

# 1. CPU cost and write cost on a datamart of 20 golden books
mvn -pl common test-compile exec:exec \
  -Djmh.include='BookIndexerBenchmark|DatamartWriteBenchmark' \
  -Djmh.output="$PWD/$OUT/common-golden20.csv"

# 2. write costs on a datamart filled with the whole datalake
if [ -n "$DATADIR" ]; then
  mvn -pl common test-compile exec:exec \
    -Djmh.include='DatamartWriteBenchmark|DatamartInsertBenchmark|DatamartBatchBenchmark' \
    -Dbench.datadir="$DATADIR" \
    -Djmh.output="$PWD/$OUT/common-datadir.csv"
fi

# 3. threads: books/s of one Indexing process
for t in 1 2 4 8; do
  mvn -pl indexing-service test-compile exec:exec \
    -Djmh.include=IndexingThroughputBenchmark -Djmh.threads="$t" -Dbench.datadir="$DATADIR" \
    -Djmh.output="$PWD/$OUT/indexing-threads-$t.csv"
done

echo "Results in $OUT"
