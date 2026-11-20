# Operations

## Private dependencies

| Dependency | Owner | Where | Notes |
|---|---|---|---|
| Ditto 3.9.8 (policies, things, gateway) | this module (vendor binaries) | `deploy/ditto` compose project `naruprof-ditto` | No search, connectivity, UI. Gateway unpublished. |
| MongoDB 7.0 | Ditto | volume `naruprof_ditto_mongo` | Ditto owns its persistence; the twin never touches it. |
| nginx 1.27 | this module | `127.0.0.1:8090` | Authenticating front; the only Ditto entry point. |
| NARU `LearningProjection` gRPC | NARU agent | `NARU_GRPC_TARGET` | Fake: `twin-fixture-naru`. |
| H2 file DB | this module | `$TWIN_DATA_DIR/checkpoint/` | Cursor, lifecycle status, lease. Private bookkeeping. |

## Fresh setup

Prerequisites: Docker Engine ≥ 24 with Compose v2 (Docker Desktop on Windows/macOS), JDK 21+,
Maven 3.9+, ~2.1 GB for images, ~2 GB RAM for the stack (limits total 1.95 GiB, ~880 MiB idle).

```sh
# Linux/macOS/Git Bash                          # Windows PowerShell
scripts/ditto-up.sh --fresh                     .\scripts\ditto-up.ps1 -Fresh
mvn -B install                                  mvn -B install
scripts/run-fake-naru.sh      # peer-independent: fake NARU with trace 0..3
scripts/run-twin.sh                             .\scripts\run-twin.ps1
scripts/query-summary.sh twin 3                 .\scripts\query-summary.ps1 -Mode twin -Expected 3
```

Measured (Windows 11, Docker Desktop 28.2.2, images cached): see `docs/acceptance-evidence.md`.

## Initial policy bootstrap

Automatic. On first boot the projector acquires the lease, takes `GetSnapshot`, and when the Thing
is absent the **operator** identity `PUT /api/2/policies/naruprof.learning:policy-<uuid>` with
`If-None-Match: *` (exact template, see `deploy/ditto/policy-examples`). A pre-existing policy is
adopted only if it matches the template; one that is ours (operator entry present) but drifted is
repaired with `If-Match`; anything else is `TWIN_CONFLICT` and nothing is written. The projector then
creates the Thing with `If-None-Match: *`, binding `policyId` explicitly.

Manual equivalent: `deploy/ditto/policy-examples/README.md`.

## Credential rotation

| Credential | Rotate how | Downtime |
|---|---|---|
| Ditto operator/projector/reader passwords | `openssl passwd -apr1 <new>` → replace the line for the **same user** in `deploy/ditto/nginx.htpasswd` → `docker compose -p naruprof-ditto exec nginx nginx -s reload` → restart the twin with the new `TWIN_DITTO_*_PASSWORD` | Twin reads/writes fail with `DITTO_AUTH_FAILED` (actionable, 60 s retry, no storm) between the two steps |
| Ditto subject names | Avoid. If unavoidable: add the new user, run `POST /internal/v1/maintenance/.../reconcile` after changing `TWIN_DITTO_*_USER` (the operator repairs the policy to the new template), then remove the old user | none |
| `VERTX_TO_TWIN_TOKEN`, `TWIN_TO_NARU_TOKEN` | Per-launch values from the integrated launcher; restart both peers | restart |
| `TWIN_MAINTENANCE_TOKEN` | Restart with a new value; unset disables the endpoints | none |

Credentials are only read from the environment, never logged (`DittoIdentity.toString` and
`BearerCallCredentials.toString` are redacted), never placed in Things, Policy subjects or URLs.

## Backup and rebuild

The twin is a **rebuildable projection**. NARU's snapshot is the source of truth.

| Situation | What happens | Operator action |
|---|---|---|
| Ditto volume lost, SQL intact | Summary 503; next reconcile recreates Policy + Thing from the snapshot (`twin_rebuilt` diagnostic) | none, or `POST /internal/v1/maintenance/profiles/<id>/reconcile` to do it now |
| SQL lost, Ditto intact | On boot the intact Thing is adopted without a write, cursor rebuilt (`checkpoint_rebuilt`) | none |
| Both lost | Fresh bootstrap from snapshot | none |
| Corrupt checkpoint row | Discarded, rebuilt from snapshot (`corrupt_checkpoint`) | none |
| NARU restored to an older revision | `RESTORE_CONFLICT`: twin keeps serving the applied (newer) revision, streaming stops | investigate, then `POST .../reconcile?acceptSourceRestore=true` |
| Thing bound to a foreign policy / foreign policy at our id | `TWIN_CONFLICT`, nothing written | investigate; remove the foreign object; reconcile |

Optional local backup: `docker run --rm -v naruprof_ditto_mongo:/d -v "$PWD":/b alpine tar czf /b/ditto-mongo.tgz -C /d .`
while the stack is stopped, and copy `$TWIN_DATA_DIR/checkpoint/`. Restoring mismatched backups is
safe: reconciliation converges or raises `RESTORE_CONFLICT`. Real-volume drill: `scripts/rebuild-drill.sh`.

## Storage locations

| Path | Content |
|---|---|
| Docker volume `naruprof_ditto_mongo` | Ditto things/policies journals and snapshots |
| `$TWIN_DATA_DIR/checkpoint/twin-checkpoint.mv.db` | tables `twin_checkpoint` (profile_id PK, status, applied/tombstone/conflict sequence as decimal text, writer token, row version) and `twin_projection_lease` (owner, fencing token, expiry) |
| `$TWIN_DATA_DIR/logs/` | Logback rolling logs (7 days, 200 MB cap) |

## Failure handling

| Failure | Classification | Behaviour |
|---|---|---|
| gRPC `UNAVAILABLE`, deadline, reset | transient | capped exponential back-off with full jitter (0.5 s → 30 s), resume `Watch(after=cursor)` |
| gRPC `OUT_OF_RANGE`, unexpected gap, corrupt checkpoint | resync | fresh `GetSnapshot`, then `Watch` |
| gRPC `UNAUTHENTICATED`/`PERMISSION_DENIED` | actionable | 60 s retry, `SOURCE_AUTH_FAILED` on status |
| gRPC `NOT_FOUND` | actionable | twin kept, **not** treated as deletion, `SOURCE_UNKNOWN_PROFILE` |
| malformed message (unset oneof, wrong profile) | actionable | stream cancelled, `SOURCE_INCOMPATIBLE` |
| Ditto 5xx/connect/timeout | transient | circuit breaker (50 % of 10 calls, open 10 s), back-off |
| Ditto 429 | throttled | honours `Retry-After` |
| Ditto 401/403 | actionable | 60 s retry, `DITTO_AUTH_FAILED` |
| Ditto 412 | ordering | reread and decide again (≤ 5 attempts) |
| timeout after a write | unknown outcome | read back, decide again |
| lease lost | fenced | stop writing, wait for lease |

Timeouts: `GetSnapshot` deadline 5 s; `Watch` stream lifetime 10 min (then rotated from the cursor) +
30 s keep-alive; Ditto connect 2 s, request 5 s.

## Telemetry

`GET /internal/v1/projection/status` (maintenance token): checkpoint status, applied sequence, last
source sequence, replay lag, consumer state, last failure class/time, circuit breaker state, last
reconcile time and counters (applies, retries, repairs, saturations, gaps...). Logback lines carry
`profile=<uuid> op=<operation id>` plus revisions, durations and failure classes; never record
content or credentials. Repairs and conflicts are logged on `naruprof.twin.diagnostics` at WARN with
a stable `event=` name.
