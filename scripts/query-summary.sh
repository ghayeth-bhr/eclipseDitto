#!/usr/bin/env bash
# Query the protected summary and compare it with a known feed revision.
#
#   scripts/query-summary.sh twin   [expected_sequence]   direct private call (VERTX_TO_TWIN_TOKEN)
#   scripts/query-summary.sh vertx  [expected_sequence]   Vert.x facade GET /api/naruprof/twin/summary
#   scripts/query-summary.sh kong   [expected_sequence]   public origin via Kong (browser path)
#
# expected_sequence defaults to the fake NARU head (GET http://127.0.0.1:9093/head) when it runs.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
load_env() {  # values already in the environment win over the (test-only) example file
  while IFS='=' read -r k v; do
    [[ "$k" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    [[ -z "${!k:-}" ]] && export "$k=$v"
  done < "$1"
}
load_env "${TWIN_ENV:-$root/deploy/twin.env.example}"
mode="${1:-twin}"
expected="${2:-}"
if [[ -z "$expected" ]]; then
  expected=$(curl -sf "${FAKE_NARU_CONTROL:-http://127.0.0.1:9093}/head" | sed -E 's/.*"sequence":"([0-9]+)".*/\1/' || true)
fi
case "$mode" in
  twin)  url="http://127.0.0.1:${TWIN_PORT:-8083}/internal/v1/profiles/${NARUPROF_PROFILE_ID}/summary"
         resp=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer ${VERTX_TO_TWIN_TOKEN}" "$url") ;;
  vertx) url="${VERTX_URL:-http://127.0.0.1:8081}/api/naruprof/twin/summary"
         resp=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer ${GATEWAY_TO_VERTX_TOKEN:?set GATEWAY_TO_VERTX_TOKEN}" "$url") ;;
  kong)  url="${PUBLIC_ORIGIN:-http://127.0.0.1:8080}/api/naruprof/twin/summary"
         resp=$(curl -s -w '\n%{http_code}' -H "Origin: ${PUBLIC_ORIGIN:-http://127.0.0.1:8080}" -H "Sec-Fetch-Site: same-origin" "$url") ;;
  *) echo "usage: $0 twin|vertx|kong [expected_sequence]" >&2; exit 2 ;;
esac
status="${resp##*$'\n'}"; body="${resp%$'\n'*}"
echo "GET $url -> $status"
echo "$body"
[[ "$status" == "200" ]] || exit 1
got=$(echo "$body" | sed -E 's/.*"sequence":"([0-9]+)".*/\1/')
if [[ -n "$expected" ]]; then
  if [[ "$got" == "$expected" ]]; then echo "OK: applied revision $got == feed revision $expected";
  else echo "LAG: applied revision $got, feed revision $expected"; exit 3; fi
fi
