#!/usr/bin/env bash
# Standalone twin service. Settings from $TWIN_ENV (default: deploy/twin.env.example, test-only).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
load_env() {  # values already in the environment win over the (test-only) example file
  while IFS='=' read -r k v; do
    [[ "$k" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    [[ -z "${!k:-}" ]] && export "$k=$v"
  done < "$1"
}
load_env "${TWIN_ENV:-$root/deploy/twin.env.example}"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$root/twin-app/target/naruprof-twin-exec.jar"
