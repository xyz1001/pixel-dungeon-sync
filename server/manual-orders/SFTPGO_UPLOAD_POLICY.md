# Donation expiry: preserve login/download, restrict uploads

Research only; no production permission changes have been deployed. Product rule:
each manually verified appreciation payment grants one calendar year from review,
or extends the current unexpired entitlement. It is not a fixed annual fee.
The user reports no existing product accounts; no paid-account migration is planned.
Development/test accounts and files must nevertheless remain untouched.

## Observed server facts (2026-10-13, read-only)

The deployed `sftpgo-auth` service is active. `auth_v2.py` SHA256 is
`0c13e271e77321a929bc1eeed45cc204f4f0ea4c68656e377726e3b74e817af4`.
The existing gateway's functions are admin-token retrieval, SFTPGo account
creation, IP rate limiting and HTTP handling. Its account-creation template sets
status 1 and root permissions `['*']`; it does not set account expiration.
Neither deployed gateway module contains order/approval/expiry/renewal logic.
Top-level deployment files contain the existing auth service/docs/compose only.
The one non-comment root crontab entry contains no SFTPGo/expiry/renewal/payment
markers. These checks do not prove the absence of all unrelated system services;
they establish that the examined current auth deployment has no subscription
controller or implemented renewal workflow. No user secrets or full cron/config
content were retrieved. The earlier version check reported SFTPGo 2.7.6.

Consequently “keep the current renewal method” can preserve the existing single
username/password login path. There is no verified existing paid-renewal API to
reuse. The sandbox order routes are repository-only and are not deployed there.

## Exact-version source evidence

- [SFTPGo v2.7.6 user permission definitions and path lookup](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/dataprovider/user.go)
- [WebDAV file opening/upload checks](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/webdavd/handler.go)
- [WebDAV read/write/finalization](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/webdavd/file.go)
- [Common mkdir/rename/copy authorization](https://github.com/drakkan/sftpgo/blob/v2.7.6/internal/common/connection.go)
- [Exact WebDAV dependency pin](https://github.com/drakkan/sftpgo/blob/v2.7.6/go.mod)
- [Pinned WebDAV method dispatch and COPY/MOVE](https://github.com/drakkan/webdav/blob/f3e211091328/webdav.go)
- [v2.7.6 management API](https://github.com/drakkan/sftpgo/blob/v2.7.6/openapi/openapi.yaml)

`expiration_date` disables account login when expired, so it is unsuitable for
the requested upload-only expiry. Keep account status/login policy intact.
Desired expiry enforcement should remove write capabilities in the app-managed
DAV scope while preserving `list` and `download`. A root permission change alone
is insufficient if child paths, wildcard entries or inherited groups grant writes;
effective permissions for all relevant paths must be validated. Do not silently
modify shared groups or unrelated folders.

| Operation | Exact-version behavior / expiry requirement |
| --- | --- |
| GET/HEAD, PROPFIND | Preserve list/download; file read checks download permission. Normal login must remain available. |
| PUT new file | Requires `upload` on the parent. Revoke it. |
| PUT overwrite | Separate `overwrite` check exists for an existing file. Revoke both upload and overwrite; removing only upload is insufficient. |
| MKCOL | Uses common CreateDir and requires `create_dirs`. Revoke to prevent directory/chunk staging. |
| MOVE | Uses rename authorization for source/target, including rename, rename_files and rename_dirs. Revoke all relevant rename rights so pre-existing temporary content cannot be committed after expiry. |
| COPY | Pinned DAV implements recursive copy via filesystem operations. Target create/write and overwrite/remove paths must remain denied. Removing only a named copy permission is not proof that DAV COPY is blocked. Test new target and overwrite, files and directories. |
| DELETE / metadata | A read-only managed scope should not grant delete variants, chtimes/chmod/chown or symlink writes. This prevents alternate mutation routes; production policy must explicitly define the desired managed scope. |
| LOCK / UNLOCK | Existing-resource locks can be in-memory metadata; LOCK on a missing path can require file creation. A lock must never be treated as permission to upload. Check null-resource locks and subsequent writes. |
| Other methods | The pinned DAV dispatcher lists its supported verbs; unknown verbs do not create a supported alternate upload path. Reverse-proxy/API upload routes still need separate inventory. |

This app uses PUT for both snapshot objects and commit records. The expiry gate
must cover both, including zero-byte writes and guessed/direct URLs, not only
ZIP names or the purchase UI. Other clients can stage then MOVE/COPY to final
paths; those operations need equivalent denial. SFTPGo atomic upload staging is
an internal path selected after permission checking: an already-open transfer
may finalize after permissions change. The write/close path does not prove a
fresh subscription check on every byte/commit.

## Production implementation and validation still required

Use a serialized background reconciliation job to derive current upload rights
from the persisted entitlement and update/read back **managed** SFTPGo permissions.
Preserve every unmanaged field and omit password on user updates. Do not reset
passwords, clear account expiration as an authentication workaround, or create a
second credential set. The API's default `disconnect=0` does not terminate an
existing connection; cache invalidation and any decision to abort active uploads
must be tested explicitly. Do not promise an exact instantaneous cutoff for an
already-started transfer. A stale management update must not reverse newer rights.

Before activation, a disposable SFTPGo 2.7.6 instance must prove: login/download
before and after expiry; denial of new/overwrite PUT, MKCOL, COPY/MOVE and staged
commit attempts after expiry; group/subpath effective permissions; renewal
restoration using the same credentials; bounded controller delay; cached-user
requests; transfers started before the cutoff; restart/retry and stale updates.
No such live mutation tests were performed against the user's server in this phase.

The appreciation-code image does not supply a verified price or remark field.
Amount is selected in the payment channel and recorded by the human reviewer
from the actual transaction. The independent order ID remains authoritative;
the optional four-digit code is not required input, authentication, or proof.
