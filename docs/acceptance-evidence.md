# Acceptance evidence

Run of 2026-11-20 on Windows 11 (20 CPUs), Docker Desktop 28.2.2, JDK 21.0.6, Maven 3.9.16,
Ditto 3.9.8 pinned by digest (`deploy/ditto/versions.properties`).

Command: `scripts/ditto-up.sh --fresh && mvn -B -Pditto-it clean install` → **BUILD SUCCESS, 75 tests,
0 failures** (56 unit/contract/REST/ArchUnit + 19 against real Ditto), 95 s for build and tests.

| Fault or input | Observable result | Evidence |
|---|---|---|
| Empty profile, then 1→2→3 | Real Thing + explicit Policy, one feature, exact values; REST body byte-equal to the shared JSON | `VerticalSliceIT`; `ProjectionServiceTest.emptyProfile…`, `.commonTrace…` |
| Duplicate 3, late 2, concurrent update | No count change or rollback, Ditto ETag unchanged; stale ETag → 412 → reread → decide again | `VerticalSliceIT` (injected 3 and 2 on the real stream); `ProjectionServiceTest.duplicateAndLateReplay…`, `.staleEtag…`, `.concurrentNewerWriteWins…` |
| Crash after native PUT, before SQL commit | Restart recognizes the state, no second write, checkpoint advances exactly once | `RecoveryIT.crashAfterNativePut…` (real Ditto); `ProjectionServiceTest.crashAfterNativeWrite…` |
| Stream breaks before a new revision / while catching up; update between snapshot and subscribe | Resume `Watch(after=cursor)`; 30-revision backlog caught up after a mid-catch-up break; commit injected between GetSnapshot and Watch is applied; OUT_OF_RANGE → snapshot | `RecoveryIT.streamBreak…`, `.updateExactlyBetween…`, `.outOfRangeCursor…`; `ProfileProjectorTest` (gap, saturation, flow control) |
| Unknown identity, wrong profile, reader write | 401 / 404 (existence hiding) / 403; reader can't touch the Thing or Policy | `AccessControlIT` (7 tests, real gateway); facade 401/403 in `VerticalSliceIT`, `TwinSummaryWSTest` |
| Stop Ditto while NARU accepts a lesson | Source keeps committing; summary 503 `twin_unavailable`; caught up to the new revision **18 s** after the stop | `DittoOutageIT` (`docker compose stop/start gateway`) |
| Delete one source with shared provenance | Counts follow the canonical snapshot (unchanged, then reduced); lifecycle untouched | `RecoveryIT.deletingOneSource…` (counts are NARU's; the twin mirrors them) |
| Profile deletion + delayed update + restart | Thing removed (proven by temporarily restoring access), Policy removed, tombstone `DELETED` kept, 404 `twin_missing`, no resurrection after restart | `RecoveryIT.profileDeletion…`; `ProjectionServiceTest.deletion…`, `.interruptedDeletionResumes…`, `.reconcileRemovesResurrected…` |
| Clear only the checkpoint store / only the Ditto store | Both converge to the same snapshot body | `RecoveryIT.clearingOnly…` (2 tests) **and** a real-volume drill (below) |
| Source restored backwards | `RESTORE_CONFLICT` (409), keeps serving revision 3, accepted only via explicit reconcile | `RecoveryIT.sourceRestoredBackwards…` |
| First boot, NARU unavailable | 503 `twin_unavailable`, readiness 503, then bootstraps | `RecoveryIT.firstBootWithoutConfirmedStateIs503` |

## Real-volume drills (standalone jars + fake NARU)

* `scripts/rebuild-drill.sh`: `docker compose down -v` destroyed the Mongo volume, the stack was
  ready again in 23 s, `POST …/reconcile` → `LIVE 4`, and the body was identical before and after (40 s total).
* Checkpoint store: twin killed, `$TWIN_DATA_DIR/checkpoint` deleted, restarted → ready in 9.7 s,
  diagnostic `checkpoint_rebuilt … adopted existing twin at 4`, no native write, body identical.
* Log hygiene: no credentials, tokens or Basic/Bearer values in any twin or test log.

## Measured startup

| What | Time |
|---|---|
| Fresh Ditto stack (empty volume, images cached) → authenticated `whoami` | 26–27 s (3 runs) |
| Twin JVM → live / ready (first boot, creates Policy + Thing) | 9.4 s / 10.1 s |
| Stack memory | ~880 MiB idle (limits 1.95 GiB) |
| First image pull (~2.1 GB) | not measured (network-dependent) |

## Not covered here

* Real NARU, Vert.x and Kong were not available. `scripts/query-summary.sh vertx|kong` is supplied
  for the integrated run that the Kong agent owns; only the `twin` mode was executed.
* Multi-process fencing (two live JVMs) is covered by port-level tests only. The H2 file lock prevents
  two processes sharing one data dir.
