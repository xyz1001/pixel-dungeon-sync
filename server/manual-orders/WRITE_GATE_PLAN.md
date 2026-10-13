# Per-request DAV write gate: observed topology and disabled prototype

Read-only observations on 2026-10-13 for `webdav.xyz1001.top`:

- nginx is active, listens on TLS 8443 for IPv4/IPv6.
- `/api/` proxies to `127.0.0.1:8088/api/` (existing auth gateway).
- `/dav/`, `/upload/`, and `/` each proxy to `127.0.0.1:8090/`.
- SFTPGo Compose publishes management port 8080 with `host_ip=127.0.0.1`.
- DAV 8090 is published with **no host_ip restriction in resolved Compose**.
  Do not assume it is loopback-only. Public reachability/firewall exposure has
  not been probed or changed. The three nginx aliases and possible direct port
  access must all be addressed; gating only `/dav/` is insufficient.

No nginx, Compose, firewall, account, password or authentication settings were
changed to gather this evidence. Private configuration bodies were not returned.

## Minimal production integration plan (not deployed)

1. Keep the existing single SFTPGo username/password and downstream DAV checks.
   Create new managed product accounts read-only initially with a separately
   trusted active template, or choose a gateway-owned access model that has been
   reviewed end to end. The live free registration template is currently still
   writable and unchanged. Do not claim it is already restricted.
2. Put one internal authorization service before **all** managed DAV aliases.
   It must obtain a verified account identity, not trust a user header or merely
   decode Basic credentials. An explicit upstream credential verifier/session
   design remains required; no new authentication system/key is invented here.
   On nginx auth_request, original method must be set by trusted internal config,
   not copied from a client-controlled override header. Never log credentials.
3. For mutation requests, consult the current SQLite desired entitlement at the
   server clock on each request. Missing/expired rights or lookup failure deny.
   Reads retain SFTPGo authentication and filesystem authorization; they must not
   become anonymous downloads. The pure prototype authenticates reads too.
4. Reconcile order application with gate availability: manual approval already
   commits its fixed desired ledger and applying job atomically. Gate integration
   must verify that the authoritative ledger is being enforced before marking
   provisioning approved. It must not route production through the experimental
   CAS permission worker or increment the term again.
5. Eliminate alternate data-write paths for managed identities. Review `/upload/`
   and `/`, direct 8090, container/network access and any exposed FTP/SFTP/WebClient
   write routes. Other services/admin operations must be deliberately scoped,
   not inadvertently blocked or left as a public bypass.

The concrete deployment differences that need approval/review are: add an
internal-only gate endpoint/process and scoped read access to the authoritative
order DB; add authorization to all three DAV proxy locations (while preserving
`/api/` authentication routes); overwrite/remove untrusted identity/method headers;
restrict DAV 8090 publishing to loopback or equivalent verified network isolation;
and inventory/restrict any other managed-user write protocol. Changing port
binding/network isolation and production auth routing is outside this prototype
and has **not** been performed. Rollout needs isolated method/auth tests, backup,
syntax check and rollback plan before any live reload.

## Implemented pure module

`WriteGate(enabled=False, authenticator=None, ledger=None)` is disabled and
default-deny. There is no listener or nginx config. `decide(actual_method, headers)`
calls a trusted authenticator then returns a status/code decision. No identity
or HTTP-method override header is interpreted by this module. The ledger reader
uses parameterized, read-only SQLite and does not create a missing database.

- Authenticated GET/HEAD/PROPFIND/OPTIONS pass to SFTPGo without an entitlement
  lookup. An entitlement DB outage therefore does not itself block downloads.
- PUT, POST, MKCOL, MOVE, COPY, DELETE, PROPPATCH, LOCK and UNLOCK require an
  unexpired ledger entry. Gating the whole mutation operation covers temporary
  uploads followed by MOVE/COPY, target overwrite and commit-record PUT.
- Unknown methods fail closed. Missing/failed authentication denies. Missing or
  expired rights deny writes; DB failure/malformed expiry returns an unavailable
  denial. A 204 means only pass to SFTPGo, never permission to bypass its checks.
- No old asynchronous remote permission job can overwrite this per-request
  decision: renewal is visible through the latest authoritative ledger. SQLite
  reads and HTTP admission are not a distributed transaction; an already-admitted
  upload may finish after expiry. Instant cutoff/abort remains a separate policy.

The HTTP tests host this function only on an ephemeral local listener with a
test-injected verifier. The fake Authorization values are synthetic fixtures,
not an authentication scheme to deploy. Tests cover all enumerated writes at
the exact expiry boundary, downloads after expiry, renewal, self-reported user
and method overrides, unknown verbs, disabled defaults, and DB lookup failure.
They do not prove actual nginx auth_request wiring, SFTPGo cache behavior, or
absence of production bypasses. No production or phone test occurred.

```sh
cd server/manual-orders
python -m unittest -v test_write_gate.py
```
