#!/usr/bin/env bash
# Microbenchmarks of the indexing path (issue #4, piece E), brief section 6.3.
#   common:            BookIndexerBenchmark (CPU only), DatamartWriteBenchmark (SQLite write)
#   indexing-service:  IndexingThroughputBenchmark with 1, 2, 4, 8 threads (threading experiment)
# Run on an idle machine, from the repository root:  bash scripts/bench-indexing.sh
# Takes about 25 minutes. Results: results/jmh/<date>/ (CSV per run + host.txt).
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="results/jmh/$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "commit: $(git rev-parse --short HEAD)"
  uname -a
  lscpu | grep -E 'Model name|^CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket' || true
  free -h | head -2
  df -h . | tail -1
  java -version 2>&1 | head -1
} > "$OUT/host.txt"

mvn -q install -DskipTests

mvn -pl common test-compile exec:exec \
  -Djmh.include='BookIndexerBenchmark|DatamartWriteBenchmark' \
  -Djmh.output="$PWD/$OUT/common.csv"

for t in 1 2 4 8; do
  mvn -pl indexing-service test-compile exec:exec \
    -Djmh.include=IndexingThroughputBenchmark -Djmh.threads="$t" \
    -Djmh.output="$PWD/$OUT/indexing-threads-$t.csv"
done

echo "Results in $OUT"
