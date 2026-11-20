#!/usr/bin/env bash
# Real-volume rebuild drill (disposable stack only!). Requires: Ditto via scripts/ditto-up.sh, the fake
# NARU (scripts/run-fake-naru.sh) and the twin (scripts/run-twin.sh, data dir $TWIN_DATA_DIR) running.
#   1. wipe ONLY the Ditto volume (docker compose down -v; up) -> twin recreates Policy+Thing
#   2. the operator restarts the twin with ONLY the checkpoint store removed -> twin adopts the Thing
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
load_env() {  # values already in the environment win over the (test-only) example file
  while IFS='=' read -r k v; do
    [[ "$k" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    [[ -z "${!k:-}" ]] && export "$k=$v"
  done < "$1"
}
load_env "${TWIN_ENV:-$root/deploy/twin.env.example}"
maint() { curl -s -X POST -H "Authorization: Bearer ${TWIN_MAINTENANCE_TOKEN}" \
  "http://127.0.0.1:${TWIN_PORT}/internal/v1/maintenance/profiles/${NARUPROF_PROFILE_ID}/reconcile"; echo; }
before=$("$root/scripts/query-summary.sh" twin | tail -n +2 | head -1)
echo "before: $before"
echo "== wiping the Ditto store volume only"
"$root/scripts/ditto-up.sh" --fresh
maint
after=$("$root/scripts/query-summary.sh" twin | tail -n +2 | head -1)
echo "after Ditto rebuild: $after"
[[ "$before" == "$after" ]] && echo "OK: Ditto-store rebuild converged" || { echo "MISMATCH"; exit 1; }
echo "== now stop the twin, delete ${TWIN_DATA_DIR}/checkpoint, restart it, then run:"
echo "   scripts/query-summary.sh twin   # must print the same body"
