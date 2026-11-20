# Access control (verified against Ditto 3.9.8)

All statements below were exercised against the pinned stack in `deploy/ditto` (image digests in
`deploy/ditto/versions.properties`) and are re-checked by `twin-it/.../AccessControlIT`. The online
manual defaults to development docs; where this release behaves differently from what one might
expect, it is called out as a **release finding**.

## Authentication and subject mapping

* The Ditto gateway runs with `ENABLE_PRE_AUTHENTICATION=true` and **publishes no port**.
* The only entry point is the nginx front from the upstream deployment, bound to `127.0.0.1:8090`.
  It authenticates HTTP Basic credentials against `nginx.htpasswd` and sets
  `x-ditto-pre-authenticated: nginx:<user>`. `proxy_set_header` replaces any caller-supplied value,
  so a client cannot pick its subject (tested: spoofed header with no credential → 401; spoofed
  operator header with reader credential → still treated as reader).
* Issuer/subject mapping: issuer `nginx`, subject = `nginx:<basic-auth user>`.

| Role | Subject | Used by |
|---|---|---|
| operator (control identity) | `nginx:naruprof-twin-operator` | `ProvisioningService`: create / verify / repair / delete the dedicated Policy |
| projector (lifecycle identity) | `nginx:naruprof-twin-projector` | `ProjectionService`, `DeletionLifecycle`: create / conditional replace / delete the Thing |
| reader | `nginx:naruprof-twin-reader` | `SummaryReadService`, readiness (`/whoami`) |
| second profile's reader (test only) | `nginx:naruprof-twin-reader-b` | AccessControlIT |

## Creation scoping

`ditto.entity-creation.grant` (system properties on the policies and things services):

| resource | namespace | allowed subject |
|---|---|---|
| policy | `naruprof.learning` | operator only |
| thing  | `naruprof.learning` | projector only |

Verified: projector/reader creating a policy → `403 entity.notcreatable`; operator creating a
thing → 403; operator creating a policy in another namespace → 403; projector creating a thing in
another namespace → 403.

## Per-profile Policy `naruprof.learning:policy-<uuid>`

See `deploy/ditto/policy-examples/`. Entries are `importable: never`.

| Entry | Resources | 
|---|---|
| `naruprof-operator` | `policy:/` READ, WRITE |
| `naruprof-projector` | `thing:/` READ, WRITE; `policy:/` READ |
| `naruprof-reader` | `thing:/features/learning/properties` READ |

No grants for messages (`/inbox`, `/outbox` are additionally not routed by nginx), other Things,
search or connections.

### Release findings (3.9.8)

1. **Binding a Thing to a Policy needs READ on that Policy.** Without `policy:/` READ the projector's
   `PUT /things/{id}` with `If-None-Match: *` and an explicit `policyId` fails with
   `404 policies:policy.notfound` (existence hiding). Hence the projector's `policy:/` READ grant.
2. **Create, full replace and delete need unrestricted root WRITE.** Revoking WRITE on
   `thing:/policyId` (to stop policy rebinding) makes the projector's create, full replace
   **and** delete fail with `403 things:thing.notmodifiable` / `thing.notdeletable ... WRITE on root resource is required`.
   The projector therefore keeps plain `thing:/` WRITE. Consequence: a compromised projector
   credential could rebind `policyId`. Mitigation by detection: every projector read verifies
   `policyId == naruprof.learning:policy-<uuid>`; a mismatch is a hard `TWIN_CONFLICT` (never
   adopted, never written), and periodic reconciliation re-verifies it. The code never sends a
   different `policyId` (`DittoThingStore.replace` copies the read value only after verification).
3. **Sub-resource PUT of `policyId` to a non-existent policy succeeded (204)** for a subject with
   root WRITE during exploration; a full-Thing PUT with a foreign `policyId` was rejected with 404.
   This is why the rebinding risk above is real and why ownership is verified on every read.
4. Sub-resource ETags are content hashes (`"hash:..."`); Thing ETags are `"rev:N"`. The module
   always conditions full-Thing writes on the Thing's `"rev:N"` ETag.

## Verified matrix (real gateway)

| Caller | Request | Result |
|---|---|---|
| anonymous | any `/api/2/...` | 401 (nginx) |
| anonymous + forged `x-ditto-pre-authenticated` | GET thing | 401 |
| reader + forged operator header | GET policy | 404 (still the reader) |
| operator | GET/PUT policy (`If-None-Match: *` on existing → 412) | 200 / 412 |
| operator | GET thing | 404 (no thing grant; existence hiding) |
| projector | GET thing / GET policy | 200 / 200 |
| projector | PUT / DELETE policy | 403 `policy.notmodifiable` |
| projector | PUT thing with stale `If-Match` / exact ETag | 412 / 204 |
| reader | GET `.../features/learning/properties` | 200 |
| reader | GET whole thing | 200 partial view without `policyId` |
| reader | PUT property, DELETE thing, PUT `policyId`, PUT policy | 403 |
| reader | GET policy | 404 |
| reader of profile B | GET profile A's properties / thing | 404, indistinguishable from a non-existent twin |
| any | `/inbox`, `/outbox`, `/search`, `/connections`, `/ws`, devops | 404 (not routed) |

## Recoverable administrator

The operator entry is always present with `policy:/` WRITE; `ProvisioningService` refuses to adopt
or delete a policy that does not contain it (`TwinPolicyTemplate.isOurs`). Should the operator
credential be lost, rotate it in `nginx.htpasswd` under the **same user name** (the subject is
unchanged, so every policy stays administrable). See `operations.md`.
