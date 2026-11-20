#!/usr/bin/env bash
# Start the pinned private Ditto stack and wait for an authenticated native read.
# Usage: scripts/ditto-up.sh [--fresh]     (--fresh wipes the twin store volume first)
set -euo pipefail
cd "$(dirname "$0")/../deploy/ditto"
if [[ "${1:-}" == "--fresh" ]]; then docker compose down -v; fi
start=$(date +%s)
docker compose up -d
api="${DITTO_API_BASE:-http://127.0.0.1:8090/api/2}"
until curl -sf -u "naruprof-twin-reader:${TWIN_DITTO_READER_PASSWORD:-test-only-reader-secret}" "$api/whoami" >/dev/null; do
  sleep 1
  if (( $(date +%s) - start > 400 )); then echo "Ditto not ready after 400s" >&2; docker compose ps; exit 1; fi
done
echo "Ditto 3.9.8 ready (authenticated whoami) after $(( $(date +%s) - start ))s at $api"
