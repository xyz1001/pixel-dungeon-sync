# Optional WebDAV `/dav/` prefix alternative: not selected

This earlier second-listener proposal is retained for reference only. The
selected approach is the existing origin-root DAV URL described in
[`README.md`](README.md#webdav-root-url), with no new listener or port. Do not
apply the 8091/second-binding examples below as part of the root-URL correction.
They require a separate explicit deployment decision and authorization.

This document proposes configuration changes only. It does not deploy, restart,
change accounts, obtain user passwords, or relax client URL validation. Apply it
only as a separately approved production change. Keep the existing SFTPGo image
version, persistent volumes, accounts, permissions, and authentication policy.

## Why HTTP 207 can still fail

The reviewed deployment uses SFTPGo 2.7.6, a WebDAV binding on port 8090 with
`prefix: ""`, and nginx that strips the external `/dav/` before proxying. For a
root PROPFIND this means the handler receives `/` and generates a root XML
`href` of `/`. The client requested `/dav/`, so its existing exact-path check
correctly rejects the response before attempting MKCOL. HTTP 207 alone does not
prove that namespace validation succeeded.

This is a deduction from the reported effective routing and exact-version
source, not a claim that production XML was captured. No production credentials
are needed for the source review or synthetic client reproduction.

SFTPGo uses `github.com/drakkan/webdav`, pinned to
`v0.0.0-20260827120142-f3e211091328`, rather than directly using `x/net/webdav`.
The handler removes its configured prefix for filesystem access and includes
the prefix again when generating PROPFIND hrefs. A second binding with
`prefix: "/dav"`, receiving the unchanged `/dav/` request, therefore generates
`/dav/`. It still addresses the same user's filesystem root; it does not create
a physical `dav` directory or a separate account namespace.

## Docker bridge topology: add a second binding

The intended topology is host nginx talking to a Docker bridge container via a
host-loopback published port. Keep the existing binding 0 on 8090 and all its
settings unchanged. Assuming binding index 1 is unused, merge these additions
into the **existing** SFTPGo Compose service. This is an additive excerpt, not a
replacement Compose file; retain all current environment entries, published
ports, networks, mounts, image pin, and service options.

```yaml
services:
  sftpgo:  # Use the existing service's actual name.
    environment:
      SFTPGO_WEBDAVD__BINDINGS__1__PORT: "8091"
      SFTPGO_WEBDAVD__BINDINGS__1__ADDRESS: "0.0.0.0"
      SFTPGO_WEBDAVD__BINDINGS__1__ENABLE_HTTPS: "false"
      SFTPGO_WEBDAVD__BINDINGS__1__PREFIX: "/dav"
      SFTPGO_WEBDAVD__BINDINGS__1__PROXY_MODE: "0"
    ports:
      - "127.0.0.1:8091:8091/tcp"
```

Container `0.0.0.0:8091` and host `127.0.0.1:8091` are intentional: binding only
to loopback **inside** a bridge container would prevent host nginx from reaching
the service through Docker's port publication. Do not publish `8091:8091` on all
host interfaces. The container port can still be reachable by peers on its
Docker network; review network membership, direct routing, and firewall rules
so an untrusted client cannot reach either DAV backend without the existing
nginx access controls. Host-loopback publication alone is not proof of that
isolation. This excerpt is not for Docker host networking or containerized
nginx; those topologies need their own verified reachability plan.

If a configuration file is the established source of truth instead, append this
object to `webdavd.bindings`, preserving all existing entries exactly:

```json
{
  "port": 8091,
  "address": "0.0.0.0",
  "enable_https": false,
  "prefix": "/dav",
  "proxy_mode": 0
}
```

The Docker host-port publication is still required. Choose one binding
configuration route; inspect existing environment overrides before editing a
file. If binding 1 already exists, stop and select an unused index after review.
If the old binding has trusted client-IP proxy settings, explicitly review the
equivalent new binding settings; defaults do not preserve them automatically.
Do not trust arbitrary client-supplied forwarding headers. TLS remains terminated
by the existing nginx HTTPS listener.

## nginx: preserve the URI only for the two `/dav` locations

In each existing location below, change only the upstream target/URI mapping to
`proxy_pass http://127.0.0.1:8091;`. There is **no slash or URI after the port**.
Retain all other existing directives, including the gate shown here when it is
deployed, original-method handling, Authorization forwarding, Host/forwarding
policy, TLS, request limits, timeouts, and security headers. Do not create a
second copy of a location that already exists.

```nginx
location = /dav {
    set $managed_original_method $request_method;
    auth_request /_internal/dav-authorize;
    proxy_set_header Authorization $http_authorization;
    proxy_set_header X-Original-Method "";
    # Retain all other existing directives in this location.
    proxy_pass http://127.0.0.1:8091;
}

location ^~ /dav/ {
    set $managed_original_method $request_method;
    auth_request /_internal/dav-authorize;
    proxy_set_header Authorization $http_authorization;
    proxy_set_header X-Original-Method "";
    # Retain all other existing directives in this location.
    proxy_pass http://127.0.0.1:8091;
}
```

Keep the existing `/_internal/dav-authorize` implementation and every other
managed alias's access controls unchanged. This routing repair does not approve
removing a gate or changing expiry policy. Keep both the exact `/upload` and
slash-prefixed `/upload/` fixed-drop handlers, their fixed identity, bounded
destination, and existing 8090 routing unchanged. Do not add a managed-user gate
to a separately exempt fixed drop. Leave the root `location /` and all other
8090 mappings unchanged. Do not use a broad `location /dav` that also captures
`/dav-sibling`. Do not add XML `sub_filter` rewriting or loosen the client's
same-origin/path checks.

The existing `nginx.example.conf` describes the old strip-prefix arrangement;
do not reapply its two old `/dav` proxy mappings after this reviewed repair.

## Restart, validation, and rollback

1. Before mutation, save the effective Compose/SFTPGo and nginx configurations,
   confirm the image and persistent volumes, confirm binding 1 and host port
   8091 are unused, and record the existing drop/routing behavior. Check both
   Compose syntax and the complete effective nginx configuration, not just this
   excerpt. Do not copy secrets into diagnostic output or this repository.
2. Schedule a brief interruption. Adding a SFTPGo binding requires a process
   restart. With new Compose environment/port settings, recreate the existing
   SFTPGo container from the reviewed configuration; merely restarting the old
   container does not apply these changes. Do not upgrade/pull a different image
   or recreate unrelated services as part of this repair. SIGHUP reloads selected
   data/certificate settings; it does not rebuild WebDAV listeners.
3. The old 8090 binding and fixed drop share that process, so their configuration
   remains unchanged but availability is briefly interrupted. In-flight transfers
   may fail. Do not describe this as zero-downtime or promise a fixed duration.
4. Bring up and verify the new backend before switching nginx. Confirm the old
   binding remains present, the new binding has prefix `/dav`, and publication
   is restricted as intended. An unauthenticated 401 proves only that a listener
   responds, not that its XML href or authenticated filesystem behavior is right.
5. Validate nginx configuration with `nginx -t`, then perform the approved reload.
   Reuse the app's existing local credentials for a user-driven check; do not
   request, capture, or log the user's password or Authorization header. A root
   Depth:0 PROPFIND requesting only `resourcetype` must return one DAV response
   with href `/dav/` and a successful collection property. Missing child
   directories should then produce the normal PROPFIND/MKCOL/readback sequence.
   Check the existing gate's rejection behavior and the fixed-drop behavior with
   their already-authorized test procedures. Do not create/delete production test
   objects without separate authorization.
6. If the new backend fails before nginx is switched, leave nginx unchanged and
   restore the previous Compose/SFTPGo configuration, recreating only that service
   as necessary. If nginx was switched, first restore its original two `/dav`
   mappings to `http://127.0.0.1:8090/`, validate, and reload. Then remove only the
   newly added binding/publication through the reviewed Compose rollback. The
   second recreation also interrupts shared 8090/drop service. Keep all original
   bindings, data volumes, drop handlers, accounts, and expiry/gate settings.
   Restoring the old mapping restores old behavior, including the known client
   href mismatch; it is a rollback, not a successful fix.

## Existing offline regression coverage

No duplicate tests or client implementation changes are needed. The existing
[`NamespaceCreationTest`](../../core/src/test/kotlin/pdsync/NamespaceCreationTest.kt)
uses the real `AppendOnlyWebDav` with synthetic loopback MockWebServer:

- `unsafeOrUnexpectedHrefsFailClosedBeforeAnyCreation`: includes root href `/`,
  cross-origin URLs, sibling paths, traversal, and encoded escapes; asserts
  `UNSAFE_RESPONSE` and only the root PROPFIND, with zero MKCOL.
- `bothMissingChildrenAreCreatedInOrderWithReadbackBeforeTheNextChild`: a matching
  `/dav/` root permits missing child directories to enter MKCOL and requires
  readback before proceeding to the next child.
- `existingDirectoriesNeedOnlyDepthZeroChecksAndNeverMkcolOrPut`: valid existing
  collections require only exact Depth:0 checks.
- `baseWithoutTrailingSlashAndMatchingAbsoluteHrefsAreSupported`: validates the
  supported canonical root/absolute-href forms without allowing cross-origin URLs.

With the supported JDK and dependencies already cached, run from the repository
root without accessing production:

```sh
./gradlew --offline :core:test --tests pdsync.NamespaceCreationTest
```

These tests use synthetic loopback HTTP, not production credentials. They
verify the client's fail-closed behavior; deployment verification remains
necessary. For a server-only offline check, instantiate the pinned
`webdav.Handler` with `NewMemFS`, `NewMemLS`, and an HTTP test recorder:
empty prefix plus `/` should produce 207/href `/`, prefix `/dav` plus `/dav/`
should produce 207/href `/dav/`, and prefix `/dav` plus `/` should produce 404.

## Exact-version source references

- [SFTPGo 2.7.6 per-binding fields and listener construction](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/webdavd/webdavd.go)
- [Each binding's prefix passed to its WebDAV Handler](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/webdavd/server.go#L246-L252)
- [Exact WebDAV dependency pin](https://github.com/drakkan/sftpgo/blob/v2.7.6/go.mod)
- [Pinned PROPFIND prefix/href generation](https://github.com/drakkan/webdav/blob/f3e211091328/webdav.go#L625-L688)
- [Binding environment-variable parsing](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/config/config.go#L1346-L1390)
- [Unix SIGHUP/restart handling](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/service/signals_unix.go)
- [nginx proxy_pass URI-preservation rules](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)
