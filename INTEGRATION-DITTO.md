# FINAL — Eclipse Ditto / twin — Windows

You own `naruprof-eclipseDitto`: its Java twin facade, gRPC consumer, checkpoint storage, and native Ditto deployment. Inspect and change implementation code/configuration so the real service matches this handoff. A documentation-only change or a fake-peer demonstration does not complete real integration. Preserve Pyramid Architecture and existing pinned versions; do not change another module's source or the shared proto.

## How to use this file

This is the final, self-contained handoff to send to this module owner and their coding agent. Implement the changes in your existing module repository. The appendices include the complete public route inventory and proposed tailnet policy, so other workspace documents are not required to understand this assignment. Use your repository's existing LearnHarness handlers/tests for detailed feature payload semantics; this handoff does not authorize changing those payloads. If older local descriptions disagree on the integration wiring, use the explicit corrections here and report any remaining conflict. The shared proto and route names must remain unchanged.

The team topology is fixed: **NARU on its owner's Linux computer, Vert.x and renderer on Ubuntu 24.04 LTS, Kong on Windows, twin/native Ditto on a second Windows computer.** All computers join the same Tailscale tailnet. The NARU owner already has Tailscale installed and will coordinate the test run. No real IP addresses or secret values are fabricated in this file; obtain the real addresses and agreed fixture credentials before launch.

## Implement these corrections

| Boundary | Required implementation |
| --- | --- |
| Real source | Set `NARU_GRPC_TARGET=<NARU_TS_IP>:9092` explicitly in integrated mode. An unset setting must not silently connect the real integration run to bundled fake NARU. Keep fake mode explicit and separate. |
| Profile | Use the UUID already installed by NARU; it must match the consumer and Vert.x configuration. Do not generate an independent learner identity. |
| gRPC auth | Send one lowercase `authorization` metadata value: `Bearer <TWIN_TO_NARU_TOKEN>`, on both Snapshot and Watch. |
| NARU auth failures | Current NARU returns `UNAUTHENTICATED` for missing, wrong or repeated metadata. Also handle `PERMISSION_DENIED` as a non-transient authorization failure. Do not depend on wrong tokens producing only PERMISSION_DENIED. |
| Twin HTTP | Serve on private port 8083. Summary requires `VERTX_TO_TWIN_TOKEN`; native Ditto credentials are distinct and remain local to this module. |
| Health | Exact private `GET /internal/health/live` and `/internal/health/ready` accept launcher probes without a role token. No blanket exemption for `/internal/**`. |
| Vert.x deadline | The caller's summary deadline is 2 seconds. Ensure ordinary native-read behavior fits it and unavailable reads fail promptly. Keep this separate from long-lived gRPC Watch policies. |

### Summary endpoint and status semantics

```text
GET /internal/v1/profiles/{profileId}/summary
Authorization: Bearer <VERTX_TO_TWIN_TOKEN>
```

Return JSON with `Cache-Control: no-store`. Read the actual native Thing through the read-only Ditto identity and translate it to exactly these fields:

```json
{"profileId":"f7933216-20db-49cb-90e0-3a9d5396966d","sequence":"3","teachingSessions":"1","practiceSessions":"1","propositions":"2","reasoningChains":"1"}
```

`sequence` and the four counts are canonical decimal strings in the unsigned 64-bit range. Compare them numerically without floating point. Preserve them as strings in native Ditto properties and facade JSON. An existing empty source profile at sequence zero has four valid zero counts; an uninitialized projection does not.

| HTTP status | Required meaning/body |
| --- | --- |
| 200 | Confirmed live projection actually read from Ditto; it may lag NARU |
| 404 | `{"code":"twin_missing"}` only after a confirmed profile tombstone |
| 503 | `{"code":"twin_unavailable"}` during bootstrap or when native state cannot be read reliably |
| 401 | `{"code":"unauthorized"}` for missing/wrong facade authentication |
| 403 | `{"code":"forbidden"}` for an authenticated unauthorized role/profile, following your existing role validation |
| 400 | `{"code":"invalid_profile_id"}` for an invalid UUID, subject to authentication/validation order |

Validate the path UUID against trusted configuration. Reject unsupported summary methods. Do not treat an unexplained missing Thing, unknown source profile, native authorization failure or lost Ditto volume as proof of deletion. Do not invent counts, return a cached checkpoint as a successful native read, or expose native mutation through this facade.

Liveness describes the process. Readiness requires an authenticated native read plus initialized projection lifecycle, including a confirmed tombstone. A native outage makes readiness 503; a source disconnect can leave an existing readable applied revision available with lag recorded privately. Kong must not wait for twin readiness before opening core learning.

### Projection ingestion and recovery

Call `naruprof.learning.v1.LearningProjection/GetSnapshot` for the configured profile, validate the oneof state, apply a complete replacement, confirm native state, then subscribe to `Watch(after_sequence=<confirmed sequence>)`. Watch is exclusive and must replay commits between snapshot and subscription. Handle `OUT_OF_RANGE` with a fresh snapshot. Handle `UNAVAILABLE`, deadline failures and `RESOURCE_EXHAUSTED` with bounded backoff/reconnect from the last confirmed cursor, honoring gRPC flow control. Unknown profile is NOT_FOUND, not deletion.

Apply NARU `sequence` monotonically and use Ditto's ETag separately for conditional native updates. Store all five properties atomically in the owned state. Duplicate matching revisions are idempotent; older redeliveries cannot rewind the twin. An equal revision with conflicting counts is drift requiring authoritative reconciliation. Confirm an ambiguous native write by reading it back before advancing the checkpoint. Preserve a recoverable operator Policy identity and separate projector/read permissions.

After a crash between native write and checkpoint commit, detect the already-applied state and advance once. Rebuild when either checkpoint storage or Ditto storage is lost, using isolated test volumes. An actual source restore to an older revision requires the existing explicit restore/reconcile operation; normal reconnect must not authorize rollback. Apply tombstones with durable pending cleanup, remove the owned Thing/Policy, and prevent stale resurrection.

Keep the documented operator endpoints protected with the separate maintenance role, including projection status and reconcile/accept-restore. They are owner operations, not browser APIs. Do not publish native `/api/2`, databases or operator secrets to Kong.

## Windows: Tailscale and network access

Use **PowerShell on the Windows host**, with an elevated terminal for service/firewall changes. If Tailscale is absent, install it using the [official Windows installer](https://tailscale.com/docs/install/windows). If present, retain the existing installation. Connect in the Tailscale app to the team's existing tailnet using your invited identity; the owner completes browser login/device approval. Do not create a separate personal tailnet.

Resolve the host CLI, set only the suggested device name, and record the actual IP. The `set` command preserves unspecified preferences:

```powershell
$TsExe = (Get-Command tailscale.exe -ErrorAction SilentlyContinue).Source
if (-not $TsExe) { $TsExe = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe' }
if (-not (Test-Path $TsExe)) { throw 'Install/open Tailscale on the Windows host first.' }
Get-Service -Name Tailscale
# If the existing service is stopped, run Start-Service -Name Tailscale as Administrator.
& $TsExe set --hostname=ditto-box
if ($LASTEXITCODE -ne 0) { throw 'Tailscale could not update the device name.' }
& $TsExe status
$RoleTsIp = (& $TsExe ip -4 | Select-Object -First 1).Trim()
$RoleTsIp
```

Ask the administrator to assign `tag:naruprof-ditto` in the tailnet console and merge the policy appendix with actual identities. Check the device appears alongside NARU and the other two peers. Do not enable Funnel, Serve, an exit node or subnet routing. Windows Tailscale connects the host; a Docker/WSL container still needs a tested route to remote tailnet IPs.

Collect NARU's actual address and installed profile UUID, plus Vert.x and Kong addresses for the inbound firewall scope:

```powershell
$NaruTsIp = Read-Host 'NARU Tailscale IPv4 (also the test runner)'
$VertxTsIp = Read-Host 'Vert.x Tailscale IPv4'
$KongTsIp = Read-Host 'Kong Tailscale IPv4'
$ProfileId = Read-Host 'Installed NARU profile UUID'
$env:TWIN_PUBLISH_ADDRESS = $RoleTsIp
$env:CONSOLE_PUBLISH_ADDRESS = '127.0.0.1'
$env:FAKE_NARU_PUBLISH_ADDRESS = '127.0.0.1'
$env:NARU_GRPC_TARGET = "${NaruTsIp}:9092"
$env:NARUPROF_PROFILE_ID = $ProfileId
# Load matching VERTX_TO_TWIN_TOKEN and TWIN_TO_NARU_TOKEN privately.
# TWIN_MAINTENANCE_TOKEN and native Ditto credentials stay owner-only.
```

Use the project's pinned native Ditto deployment through **Docker Desktop Linux containers/WSL2**. Trace the settings into the actual twin container. Set the real gRPC target explicitly: blank configuration must not silently keep the integration run on fake NARU. Keep `DITTO_API_BASE` pointed at private native Ditto with `/api/2`; do not substitute NARU's public URL or expose the native gateway to teammates.

Publish facade 8083 only on the Windows Tailscale IP. A Java listener inside the container may use its internal `0.0.0.0:8083`, with Compose publishing `${TWIN_PUBLISH_ADDRESS}:8083:8083` if those are your actual ports. Keep native Ditto, fake NARU, consoles and databases on loopback/private Docker networks. An environment variable has no effect unless your Compose/application configuration consumes it; implement the missing mapping if necessary.

Create a narrowly scoped Windows firewall rule if an equivalent one does not exist, from an elevated PowerShell terminal:

```powershell
New-NetFirewallRule -DisplayName 'NaruProf twin tailnet 8083' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $RoleTsIp -LocalPort 8083 -RemoteAddress @($NaruTsIp, $VertxTsIp, $KongTsIp) -Profile Any
```

Do not disable Defender Firewall or open native Ditto/maintenance control ports broadly. If Docker Desktop/WSL cannot bind the Windows Tailscale address, publish the facade to Windows loopback `127.0.0.1:18083` and, after inspecting existing portproxy rules, create an explicit host bridge as Administrator:

```powershell
netsh interface portproxy add v4tov4 listenaddress=$RoleTsIp listenport=8083 connectaddress=127.0.0.1 connectport=18083
netsh interface portproxy show v4tov4
```

This fallback needs a running Windows IP Helper service and a verified loopback facade on 18083. Do not reset other entries. Test it from Ubuntu/NARU, not only Windows itself. Separately verify that the **actual gRPC consumer inside Docker/WSL** can reach NARU 9092. Host connectivity alone does not prove the container route; use numeric tailnet IPs if container MagicDNS is unavailable.

```powershell
& $TsExe ping $NaruTsIp
Test-NetConnection -ComputerName $NaruTsIp -Port 9092
curl.exe --max-time 5 "http://${RoleTsIp}:8083/internal/health/ready"
# An authenticated summary must return the applied source revision:
$TwinHeaders = @{ Authorization = "Bearer $env:VERTX_TO_TWIN_TOKEN" }
Invoke-RestMethod -Uri "http://${RoleTsIp}:8083/internal/v1/profiles/${ProfileId}/summary" -Headers $TwinHeaders
```

For ordinary configuration updates use `docker compose up -d --force-recreate twin` if `twin` is the actual service name. Retain volumes; do not use `down -v` for reconnect tests. When switching from fake history to real NARU, use a fresh isolated test stack or the existing owner reconciliation procedure. Reusing one UUID for unrelated histories is not an ordinary reconnect.

Send the NARU/Vert.x owners your actual `http://<DITTO_TS_IP>:8083` URL and MagicDNS name. The address in an older guide is not proof of today's address. Share role tokens privately, not in the report.

## Acceptance tests on real boundaries

Run your fixture suite against actual native Ditto: empty sequence 0; fixture 1→2→3; duplicate/late revision; ETag conflict; wrong caller/profile; reader write denial; missing native state; authenticated status/readiness; tombstone; and restart recovery. Do not assert raw native revision/ETag equals NARU sequence.

From this computer or the container with a permitted route, verify the real source using grpcurl and the shared proto. Substitute actual paths/addresses and load the token privately:

```powershell
# Save the exact proto appendix as learning_projection_v1.proto in the working folder.
$SnapshotRequest = @{ profileId = $ProfileId } | ConvertTo-Json -Compress
$SnapshotRequest | grpcurl.exe -plaintext -import-path . -proto learning_projection_v1.proto -H "authorization: Bearer $env:TWIN_TO_NARU_TOKEN" -d '@' "${NaruTsIp}:9092" naruprof.learning.v1.LearningProjection/GetSnapshot
```

Verify Watch with a bounded test client: start after a recorded snapshot revision, have a public chat creation commit through Kong, and observe a greater sequence. The twin initiates this gRPC connection; NARU streams committed states back on it. NARU does not POST updates to a twin HTTP callback.

Have Vert.x compare its authenticated private summary read with the public `GET /api/naruprof/twin/summary` through Kong. The NARU test runner can do the same using its explicit network test grant and fixture credentials. Require exact counters when comparing equal revisions and bounded convergence when the twin is behind.

Stop the actual native Ditto gateway/service discovered in your Compose file, create/update learning through Kong, assert core learning succeeds and summary returns 503, restart without deleting volumes, then wait for the new revision. Also exercise a crash after native write/before checkpoint persistence and a commit between Snapshot/Watch. Use bounded deadlines rather than a fixed one-second success assumption. Test profile retirement only on a dedicated disposable profile via NARU's owner maintenance operation.

Run `python3 endpoints/check_integration.py --live --smoke --grpc --require-twin` from the designated NARU test runner once all peers are up. This checks snapshot/summary convergence and core HTTP wiring; it does not replace Watch/recovery/Policy tests. The 24 missing NARU application method/path pairs remain separate all-endpoint blockers, not twin-owned implementation work.

Return actual implementation/config changes, versions, your host IP/name and published port, real startup/restart commands, test results, observed source and applied revisions, and any unresolved network/auth/source issue to the user. Do not automatically send team messages or include credentials in that report.

## Appendix A — common wire and testing reference

The single public origin is `http://<KONG_TS_IP>:8080`. For ordinary learning APIs: browser → Kong:8080 → Vert.x:8081 → NARU:8082. The twin summary path instead goes Vert.x → twin:8083. Pages/assets go Kong → renderer:3000; renderer SSR reads go renderer → Vert.x. The twin initiates NARU gRPC:9092; committed state flows back on that Watch connection. Only the twin talks to the native Ditto API. Kong's launcher probes private readiness. The NARU computer has a separate temporary test-runner grant to probe every boundary; a normal tester has only Kong access.

Use one persisted NARU profile UUID and five **distinct** fixture role tokens. Token names indicate their sending/validating peers: GATEWAY_TO_VERTX_TOKEN, RENDERER_TO_VERTX_TOKEN, VERTX_TO_NARU_TOKEN, VERTX_TO_TWIN_TOKEN, TWIN_TO_NARU_TOKEN. Tailscale provides reachability; it does not distribute or replace bearer authentication. Do not put service tokens in the browser or tracked files. The fixture test runner may temporarily hold all five for direct comparisons.

Core payloads to preserve:

```text
POST /api/chats
Content-Type: application/json
{"title":"Fractions"}
→ 200 {"id":"<id>","title":"Fractions"}

POST /api/chat
Content-Type: application/json
{"chatId":"<id>","input":{"type":"text","text":"Why is 1/2 equal to 2/4?"}}
→ ordinary admission: 200 {"ok":true}
→ overlap: 409; missing model configuration: route-specific 503
→ pending-sibling completion can itself be SSE
```

Create practice by adding `"kind":"practice"`; omitted kind is teaching. Chat/side streams and multipart formats are specified in the main requirements. Do not assume every successful endpoint returns JSON or status 200. Preserve domain 400/404/409/412/413/502/503 responses and endpoint-specific bodies; never replace a refusal with a fake success. Side-thread PATCH and global/turn stream semantics differ.

Private Java health paths are exact GET `/internal/health/live` and `/internal/health/ready`, without a role token. NARU's protected `GET /internal/v1/profile/export` is an authenticated ZIP stream, not a public API route. Test Kong control endpoints on its own loopback and native Ditto/operator endpoints under their owner's roles. No public route may expose these private surfaces.

## Appendix B — full application route inventory

Brackets represent one parameter; `[...file]` and `[...segment]` consume remaining path segments. These 46 paths expand to 69 method/path pairs. Document-conversion methods include intentional refusals; forward the method so the owning handler can respond correctly. The additional twin GET below is owned by Vert.x, not NARU.

| Public path | Methods |
| --- | --- |
| `/api/activity/abort` | POST |
| `/api/activity` | GET |
| `/api/chat` | POST |
| `/api/chats/[id]/artifact-ui` | POST |
| `/api/chats/[id]/dismiss` | POST |
| `/api/chats/[id]/images/[snapshotId]` | GET |
| `/api/chats/[id]/markdown` | GET |
| `/api/chats/[id]` | GET, PUT, DELETE |
| `/api/chats/[id]/settings` | PUT |
| `/api/chats/[id]/side-chats/[threadId]` | PATCH, GET |
| `/api/chats/[id]/side-chats/[threadId]/stop` | POST |
| `/api/chats/[id]/side-chats` | GET, POST |
| `/api/chats/[id]/steer` | POST |
| `/api/chats/[id]/stream` | GET |
| `/api/chats` | GET, POST |
| `/api/claude-code` | GET |
| `/api/custom-provider` | GET, POST, PATCH, DELETE |
| `/api/events` | GET |
| `/api/graph/costs` | GET |
| `/api/graph/coverage/extract` | POST |
| `/api/graph/import` | POST |
| `/api/graph/nodes/[id]` | DELETE |
| `/api/graph/positions` | PUT |
| `/api/graph/render-settings` | GET, PUT |
| `/api/graph` | GET |
| `/api/graph/sources` | GET, DELETE, POST |
| `/api/graph/status` | GET |
| `/api/jobs` | GET |
| `/api/keys/[id]` | PUT, DELETE |
| `/api/keys` | GET |
| `/api/models` | GET |
| `/api/plugins/[id]/assets/[...file]` | GET |
| `/api/plugins/[id]/call` | POST |
| `/api/plugins/[id]/documents/markdown` | POST, OPTIONS, GET, HEAD, PUT, PATCH, DELETE |
| `/api/plugins/[id]` | PUT |
| `/api/plugins` | GET, POST |
| `/api/search` | GET |
| `/api/settings/[section]` | PUT |
| `/api/settings/prompts/[...segment]` | PUT, DELETE |
| `/api/settings/prompts` | GET |
| `/api/settings` | GET |
| `/api/stt/token` | GET |
| `/api/subscriptions/browser` | GET, POST |
| `/api/subscriptions` | GET, POST |
| `/api/themes/[id]` | DELETE |
| `/api/themes` | GET, POST |
| `/api/naruprof/twin/summary` | GET (additional Vert.x → twin read) |

Current missing NARU method/path pairs (24; placeholder names normalized):

- `DELETE /api/custom-provider`
- `DELETE /api/plugins/{}/documents/markdown`
- `GET /api/claude-code`
- `GET /api/custom-provider`
- `GET /api/graph/costs`
- `GET /api/plugins`
- `GET /api/plugins/{}/assets/{*}`
- `GET /api/plugins/{}/documents/markdown`
- `GET /api/search`
- `GET /api/subscriptions`
- `GET /api/subscriptions/browser`
- `HEAD /api/plugins/{}/documents/markdown`
- `OPTIONS /api/plugins/{}/documents/markdown`
- `PATCH /api/custom-provider`
- `PATCH /api/plugins/{}/documents/markdown`
- `POST /api/chats/{}/artifact-ui`
- `POST /api/custom-provider`
- `POST /api/plugins`
- `POST /api/plugins/{}/call`
- `POST /api/plugins/{}/documents/markdown`
- `POST /api/subscriptions`
- `POST /api/subscriptions/browser`
- `PUT /api/plugins/{}`
- `PUT /api/plugins/{}/documents/markdown`

These are backend feature blockers. Route-forwarding tests can pass while a feature remains unimplemented. Full acceptance requires the original features and refusal semantics; do not treat an arbitrary 404 as successful feature implementation. The owner already has a core preflight tool, but it does not test the full inventory.

## Appendix C — tailnet administrator policy template

One administrator applies this once after replacing the two example login identities, including the tester identity in the final test case. Merge with the existing tailnet policy; preserve unrelated settings. Remove or scope conflicting allow-all rules for these devices, because access grants are additive. Validate the policy and its included tests in the Tailscale admin console before saving. This is a proposed test topology, not a claim that the policy is already active.

Assign Kong `tag:naruprof-kong`, Ubuntu/renderer `tag:naruprof-vertx`, twin `tag:naruprof-ditto`, and NARU both `tag:naruprof-naru` and temporary `tag:naruprof-test-runner`. The runner tag deliberately broadens the owner's diagnostic access. Ordinary browser testers use their invited untagged devices and receive Kong access only. Network rules restrict ports; per-service bearer checks restrict API roles/paths on those ports.

```json
{
  "groups": {
    "group:naruprof-admins": ["replace-admin@example.com"],
    "group:naruprof-testers": ["replace-tester@example.com"]
  },
  "tagOwners": {
    "tag:naruprof-kong": ["group:naruprof-admins"],
    "tag:naruprof-vertx": ["group:naruprof-admins"],
    "tag:naruprof-naru": ["group:naruprof-admins"],
    "tag:naruprof-ditto": ["group:naruprof-admins"],
    "tag:naruprof-test-runner": ["group:naruprof-admins"]
  },
  "grants": [
    {"src": ["group:naruprof-testers"], "dst": ["tag:naruprof-kong"], "ip": ["tcp:8080"]},
    {"src": ["tag:naruprof-kong"], "dst": ["tag:naruprof-vertx"], "ip": ["tcp:8081", "tcp:3000"]},
    {"src": ["tag:naruprof-kong"], "dst": ["tag:naruprof-naru"], "ip": ["tcp:8082"]},
    {"src": ["tag:naruprof-kong"], "dst": ["tag:naruprof-ditto"], "ip": ["tcp:8083"]},
    {"src": ["tag:naruprof-vertx"], "dst": ["tag:naruprof-vertx"], "ip": ["tcp:8081"]},
    {"src": ["tag:naruprof-vertx"], "dst": ["tag:naruprof-naru"], "ip": ["tcp:8082"]},
    {"src": ["tag:naruprof-vertx"], "dst": ["tag:naruprof-ditto"], "ip": ["tcp:8083"]},
    {"src": ["tag:naruprof-ditto"], "dst": ["tag:naruprof-naru"], "ip": ["tcp:9092"]},
    {"src": ["tag:naruprof-test-runner"], "dst": ["tag:naruprof-kong"], "ip": ["tcp:8080"]},
    {"src": ["tag:naruprof-test-runner"], "dst": ["tag:naruprof-vertx"], "ip": ["tcp:8081", "tcp:3000"]},
    {"src": ["tag:naruprof-test-runner"], "dst": ["tag:naruprof-naru"], "ip": ["tcp:8082", "tcp:9092"]},
    {"src": ["tag:naruprof-test-runner"], "dst": ["tag:naruprof-ditto"], "ip": ["tcp:8083"]}
  ],
  "tests": [
    {"src": "tag:naruprof-kong", "proto": "tcp", "accept": ["tag:naruprof-vertx:8081", "tag:naruprof-vertx:3000", "tag:naruprof-naru:8082", "tag:naruprof-ditto:8083"], "deny": ["tag:naruprof-naru:9092"]},
    {"src": "tag:naruprof-vertx", "proto": "tcp", "accept": ["tag:naruprof-naru:8082", "tag:naruprof-ditto:8083"], "deny": ["tag:naruprof-naru:9092"]},
    {"src": "tag:naruprof-ditto", "proto": "tcp", "accept": ["tag:naruprof-naru:9092"], "deny": ["tag:naruprof-vertx:8081", "tag:naruprof-naru:8082"]},
    {"src": "tag:naruprof-test-runner", "proto": "tcp", "accept": ["tag:naruprof-kong:8080", "tag:naruprof-vertx:8081", "tag:naruprof-vertx:3000", "tag:naruprof-naru:8082", "tag:naruprof-naru:9092", "tag:naruprof-ditto:8083"]},
    {"src": "replace-tester@example.com", "proto": "tcp", "accept": ["tag:naruprof-kong:8080"], "deny": ["tag:naruprof-vertx:8081", "tag:naruprof-naru:8082", "tag:naruprof-naru:9092", "tag:naruprof-ditto:8083"]}
  ]
}
```

Official references: [Tailscale network grants](https://tailscale.com/docs/reference/syntax/grants), [CLI preferences and status](https://tailscale.com/docs/reference/tailscale-cli). Do not enable policy/host changes by replacing examples with guessed identities or addresses.

## Appendix D — exact NARU projection proto

Save as `learning_projection_v1.proto` without changing package, field numbers or service methods.

```proto
syntax = "proto3";
package naruprof.learning.v1;

option java_package = "org.naruprof.contract.learning.v1";
option java_multiple_files = true;

// A profile is assigned by the local installation. Authorize it at the facade.
service LearningProjection {
  rpc GetSnapshot(Profile) returns (ProjectionState);
  rpc Watch(WatchRequest) returns (stream ProjectionState);
}

message Profile {
  string profile_id = 1;
}

message WatchRequest {
  string profile_id = 1;
  uint64 after_sequence = 2; // Exclusive. Zero is the initial snapshot version.
}

// Full replacements. sequence is monotonic within a profile across restarts.
message ProjectionState {
  string profile_id = 1;
  uint64 sequence = 2;
  oneof state {
    Summary summary = 3;
    Deleted deleted = 4;
  }
}

message Summary {
  uint64 teaching_sessions = 1;
  uint64 practice_sessions = 2;
  uint64 propositions = 3;
  uint64 reasoning_chains = 4;
}

message Deleted {}
```

Windows command references: [Microsoft firewall rules](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule), [TCP portproxy](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/netsh-interface). Platform commands still require execution on the actual Windows host; they were not executed on the NARU computer.
