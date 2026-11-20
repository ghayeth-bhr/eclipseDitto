# Design

## What the counts mean (verified against LearnHarness `cb25c8e`)

The twin does **not** compute counts; it projects NARU's canonical summary. For reference
(`src/core/contracts/chat.ts`, `src/core/chains.ts`, `src/core/contracts/graph.ts`):
teaching sessions include chats whose stored record omits `kind` (absent reads as teach); propositions
and reasoning chains are distinct **stored** records after canonical merge. Derived arrows/junctions
drawn by the canvas are not stored arguments, so two propositions joined by one argument are
`propositions=2, reasoningChains=1`. Deleting one imported source only produces a new summary revision;
assertions supported by another source survive. Ratings are never part of the summary and the twin's
feature accepts exactly five keys, so a stray `rating` is treated as drift and removed.

## Ordering and atomic replacement

* NARU `sequence` (uint64, compared with `Long.compareUnsigned`, stored as canonical decimal text) is
  the only ordering signal. Ditto's own `"rev:N"` ETag is only a concurrency token.
* Each apply: fence check → projector GET Thing (capture ETag, verify `thingId` and
  `policyId`) → decide:
  * absent → operator ensures Policy → projector PUT with `If-None-Match: *`;
  * lower sequence → ignore (a delayed delivery never rewinds the twin);
  * equal and identical → already applied (confirm cursor);
  * equal but different (or malformed owned feature) → **drift**: no write, reconcile from a fresh
    snapshot, which may repair it under `If-Match` and logs `twin_repaired`;
  * higher → one full-Thing PUT with `If-Match: <exact ETag>`, preserving `policyId`, `attributes`,
    `definition`; all five properties and the new sequence in the same write.
* 412 → reread and decide again (≤ 5 attempts). Timeout after send → read back and decide again.
* The checkpoint advances only after the native state is confirmed. There is no transaction across
  gRPC, JPA and Ditto; each step is idempotent so a crash between "Ditto written" and "SQL committed"
  is resolved on restart by recognizing equal state and advancing the cursor exactly once.

## Consumer, fencing, back-pressure

* One `ProfileProjector` per profile: a single worker thread with a bounded queue performs every
  decision and native write. gRPC uses manual flow control (`disableAutoRequestWithInitial(window)`
  and `request(1)` after each processed message), so a conforming producer cannot overrun it.
  If a producer ignores demand and the queue fills, the stream is cancelled (`stream_saturated`), the
  already-queued in-order prefix is still applied, and the watch resumes from the confirmed cursor.
* Cross-process single writer: SQL lease row with a fencing token. Every checkpoint mutation locks
  the lease row and checks owner + token in the same local transaction; every native write decision
  is preceded by a fence check. A restart of the same installation takes over immediately with a new
  token (fencing in-flight writes of the dead process); another live installation must wait for expiry.
* Watch resume: transient drop → `Watch(after=cursor)`. Gap (`received > cursor+1`), `OUT_OF_RANGE`,
  drift or corrupt checkpoint → `GetSnapshot` then `Watch(after=snapshot)`. Duplicate/lower replays are
  ignored. The producer is required to replay commits made between `GetSnapshot` and `Watch`
  (tested with a commit injected exactly between them).

## Reconciliation (startup, periodic, explicit)

`ReconcileService` compares {checkpoint, Thing, Policy} with a fresh snapshot:

| Checkpoint | Thing | Snapshot | Result |
|---|---|---|---|
| none / corrupt | absent | n | create Policy + Thing at n |
| none | at n | n | adopt without writing, cursor := n |
| m | absent (Ditto lost) | n ≥ m | recreate at n (`twin_rebuilt`) |
| m | at m+1 (crash before SQL) | m+1 | already applied, cursor := m+1 once |
| m | at k < n (old Ditto backup) | n ≥ m | replace with n |
| m | at m | n < m | **RESTORE_CONFLICT**, keep serving m, stop streaming |
| any | any | tombstone | deletion lifecycle |
| DELETED | any | live | stay retired, finish/verify cleanup (`retired_but_source_live`) |

Policy shape is verified (and our own drifted policy repaired) on every reconcile even when the
checkpoint looks current. `acceptSourceRestore=true` lets an operator accept a disaster-restored NARU.

## Deletion lifecycle

Tombstone (from snapshot or stream) → persist `PENDING_DELETION` with the source sequence → under the
lease, projector deletes the Thing with `If-Match` and verifies absence → operator deletes the dedicated
Policy (only if positively ours) and verifies absence → `DELETED` with the tombstone retained.
Unfinished steps resume after restart. Live events for a retired id (including delayed creates) are
rejected; reconciliation removes a resurrected Thing or orphan Policy of a retired profile. A source
`NOT_FOUND` is never treated as deletion.

## Read path

`SummaryReadService`: canonical UUID → configured profile only (else 403) → checkpoint: none or
bootstrapping → 503; retired → 404 → reader GET of `features/learning/properties` → exact five canonical
fields → 200 with the **actual** applied revision. Anything else (Ditto down, Thing missing, malformed)
→ 503. Zeros are never invented.
