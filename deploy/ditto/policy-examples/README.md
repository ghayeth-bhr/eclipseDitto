# Policy examples (test-only identities)

The subjects below exist only in `../nginx.htpasswd` with `test-only-*` passwords. Never reuse
them outside disposable fixtures; see `docs/operations.md` for rotation.

| Subject | Policy entry | Grants | Why |
|---|---|---|---|
| `nginx:naruprof-twin-operator` | `naruprof-operator` | `policy:/` READ+WRITE | Recoverable administrator: creates, repairs and deletes the dedicated policy. No thing grants. |
| `nginx:naruprof-twin-projector` | `naruprof-projector` | `thing:/` READ+WRITE, `policy:/` READ | Ditto 3.9.8 requires **unrestricted** root WRITE for create, conditional full replace and delete (a `revoke` on `thing:/policyId` breaks all three), and READ on the policy to bind it at creation (`404 policy.notfound` otherwise). |
| `nginx:naruprof-twin-reader` | `naruprof-reader` | `thing:/features/learning/properties` READ | Exactly the read path used by the summary. |

Bootstrap by hand (what the operator identity does automatically):

```sh
curl -u naruprof-twin-operator:test-only-operator-secret -X PUT -H 'Content-Type: application/json' \
     -H 'If-None-Match: *' --data @policy-f7933216-20db-49cb-90e0-3a9d5396966d.json \
     http://127.0.0.1:8090/api/2/policies/naruprof.learning:policy-f7933216-20db-49cb-90e0-3a9d5396966d
curl -u naruprof-twin-projector:test-only-projector-secret -X PUT -H 'Content-Type: application/json' \
     -H 'If-None-Match: *' --data @thing-f7933216-20db-49cb-90e0-3a9d5396966d-rev3.json \
     http://127.0.0.1:8090/api/2/things/naruprof.learning:f7933216-20db-49cb-90e0-3a9d5396966d
```
