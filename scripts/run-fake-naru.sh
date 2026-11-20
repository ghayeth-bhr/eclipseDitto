#!/usr/bin/env bash
# Fake NARU LearningProjection publisher (common trace 0..3) on 127.0.0.1:9092, control on :9093.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
load_env() {  # values already in the environment win over the (test-only) example file
  while IFS='=' read -r k v; do
    [[ "$k" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
    [[ -z "${!k:-}" ]] && export "$k=$v"
  done < "$1"
}
load_env "${TWIN_ENV:-$root/deploy/twin.env.example}"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$root/twin-fixture-naru/target/twin-fixture-naru-1.0.0-exec.jar"
