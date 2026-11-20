# naruprof-eclipseDitto

Private, rebuildable **digital twin** of one local learner profile. NARU publishes a full-replacement
learning summary over gRPC (`naruprof.learning.v1.LearningProjection`); this module projects it into
one Eclipse Ditto Thing guarded by a dedicated Policy and serves it to Vert.x through a private
Servlet GET. Losing the projection store (Ditto or SQL or both) is recoverable: the module rebuilds
from NARU's snapshot and serves the same counts and source revision.

```
NARU ──gRPC GetSnapshot/Watch──▶ twin projector ──HTTP /api/2 (projector)──▶ Ditto Thing  naruprof.learning:<uuid>
                                      │            └─(operator)────────────▶ Ditto Policy naruprof.learning:policy-<uuid>
                                      ▼
                         H2 checkpoint (cursor, status, lease)
Vert.x ──Bearer──▶ GET /internal/v1/profiles/{uuid}/summary ──(reader)──▶ Thing features/learning/properties
```

Thing after revision 3 of the contract trace (verified byte-for-byte by `VerticalSliceIT`):

```json
{"policyId":"naruprof.learning:policy-f7933216-20db-49cb-90e0-3a9d5396966d",
 "features":{"learning":{"properties":{"sequence":"3","teachingSessions":"1","practiceSessions":"1",
                                       "propositions":"2","reasoningChains":"1"}}}}
```

## Pyramid layout

| Sub-project | Layer | Contents |
|---|---|---|
| `twin-infra` | infra | `ProfileId` (parsed, lowercase UUID), `U64` (uint64, compared unsigned), summary/state types |
| `twin-service-api` | service-api | `LearningTwinModule` facade |
| `twin-dal-api` | dal-api | semantic ports: `TwinThingStore`, `TwinPolicyStore`, `TwinReadModel`, `CheckpointStore`, `LeaseStore`, `LearningSource` |
| `twin-dal-ditto-http` | dal | Ditto persisted-twin HTTP adapter (3 identities, timeouts, circuit breaker) |
| `twin-dal-jpa` | dal | checkpoint + fenced lease (JPA/H2) |
| `twin-contract-naru-grpc` | generated | stubs from the byte-identical published proto (checksum-tested) |
| `twin-dal-naru-grpc` | dal | gRPC client adapter (validation, flow control, status classification) |
| `twin-service-impl` | service-impl | provisioning, projection, deletion lifecycle, reconcile, read, per-profile consumer |
| `twin-ws-rest` | ws | incoming Servlet REST adapter + per-role bearer filter |
| `twin-app` | assembly | Spring Boot composition root, the only place vendor implementations are wired |
| `twin-fixture-naru` | fixture | deterministic fake NARU publisher (standalone + embeddable) |
| `twin-architecture-tests` | test | ArchUnit: controller→DAL, concrete DAL deps, vendor-free services, contract confinement, fixture isolation |
| `twin-it` | test | JUnit against the real pinned Ditto stack (`-Pditto-it`) |

## Build and test

Prerequisites: JDK 21+, Maven 3.9+, Docker with Compose v2. Pinned versions: `deploy/ditto/versions.properties`.

```sh
mvn -B install                       # unit, contract, REST and ArchUnit tests (no Docker needed)
scripts/ditto-up.sh --fresh          # Windows: .\scripts\ditto-up.ps1 -Fresh
mvn -B -Pditto-it install            # + real-Ditto integration tests (stops/starts the gateway once;
                                     #   disable that drill with -Dditto.it.allowStop=false)
```

## Run

```sh
scripts/ditto-up.sh                  # private Ditto 3.9.8 on 127.0.0.1:8090 (nginx front only)
scripts/run-fake-naru.sh             # fake NARU, profile f7933216-..., trace 0..3 (or the real NARU)
scripts/run-twin.sh                  # twin on 127.0.0.1:8083 with deploy/twin.env.example (TEST-ONLY secrets)
scripts/query-summary.sh twin        # GET summary, compare with the feed revision (fake NARU /head)
scripts/query-summary.sh kong 3      # same through Kong → Vert.x (GET /api/naruprof/twin/summary)
```

Windows equivalents: `scripts\run-twin.ps1`, `scripts\query-summary.ps1 -Mode kong -Expected 3`.

## Private HTTP surface

| Route | Auth | Result |
|---|---|---|
| `GET /internal/v1/profiles/{uuid}/summary` | `Bearer VERTX_TO_TWIN_TOKEN` | 200 six-field JSON · 404 `{"code":"twin_missing"}` · 503 `{"code":"twin_unavailable"}` · 401 · 403 |
| `GET /internal/health/live`, `/ready` | none (loopback) | 200 / 503 |
| `GET /internal/v1/projection/status` | `Bearer TWIN_MAINTENANCE_TOKEN` | revisions, lag, consumer state, failure class, breaker, counters |
| `POST /internal/v1/maintenance/profiles/{uuid}/reconcile[?acceptSourceRestore=true]` | maintenance | explicit reconciliation |

No native Thing/Policy mutation is exposed, and nothing here is meant to be routed through Kong except
Vert.x's own `/api/naruprof/twin/summary`.

## Documents

* [docs/design.md](docs/design.md): ordering, atomic replacement, recovery and deletion design
* [docs/access-control.md](docs/access-control.md): subjects, policy, release findings, verified matrix
* [docs/operations.md](docs/operations.md): setup, bootstrap, rotation, backup/rebuild, storage, failures, telemetry
* [docs/acceptance-evidence.md](docs/acceptance-evidence.md): acceptance table → tests → measured results
* [deploy/handoff/twin-service.manifest.yaml](deploy/handoff/twin-service.manifest.yaml): readiness + deployment manifest for the Kong agent
