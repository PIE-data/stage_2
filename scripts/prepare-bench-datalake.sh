#!/usr/bin/env bash
# Builds a hash-layout datalake (books + receipts) for the indexing benchmarks, using the Stage 1
# Python downloader and the local Gutenberg mirror, until the Stage 2 Ingestion service (#3) exists.
# The files are identical to what Stage 2 ingestion produces (same SPEC, same receipt format).
#
#   bash scripts/prepare-bench-datalake.sh [tier] [target folder] [stage_1 repo]
#   defaults: 1000  ~/bench/s2-tier1000  ~/bench/stage_1
# Then:  BENCH_DATADIR=~/bench/s2-tier1000 bash scripts/bench-indexing.sh
set -euo pipefail

TIER="${1:-1000}"
TARGET="${2:-$HOME/bench/s2-tier$TIER}"
STAGE1="${3:-$HOME/bench/stage_1}"
MANIFEST="$(cd "$(dirname "$0")/.." && pwd)/spec/corpus/manifest_$TIER.txt"

[ -f "$MANIFEST" ] || { echo "no manifest for tier $TIER: $MANIFEST"; exit 1; }
[ -d "$STAGE1/infra/mirror" ] || { echo "mirror not found: $STAGE1/infra/mirror"; exit 1; }
if [ -e "$TARGET" ]; then echo "$TARGET already exists: remove it or choose another folder"; exit 1; fi
mkdir -p "$TARGET"

PORT_FILE="$(mktemp)"
python3 "$STAGE1/tools/mirror_server.py" --root "$STAGE1/infra/mirror" --port-file "$PORT_FILE" >/dev/null &
MIRROR=$!
trap 'kill $MIRROR 2>/dev/null; rm -f "$PORT_FILE"' EXIT
for _ in $(seq 50); do [ -s "$PORT_FILE" ] && break; sleep 0.1; done
[ -s "$PORT_FILE" ] || { echo "mirror server did not start"; exit 1; }

python3 "$STAGE1/src/python/cli.py" --workspace "$TARGET" --datalake-layout hash \
  download --manifest "$MANIFEST" --workers 8 --source-base "http://127.0.0.1:$(cat "$PORT_FILE")" || true

echo "books with a receipt: $(ls "$TARGET/control/ingestion/hash" | grep -c '\.json$')"
echo "failed (if any): $(wc -l < "$TARGET/control/failed_books.txt" 2>/dev/null || echo 0)"
echo "datalake ready in $TARGET"
