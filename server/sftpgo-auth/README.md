# SFTPGo named-account gateway extension

Adds explicit username/password registration and login to the existing Python
registration gateway. Both operations use the existing SFTPGo identity store;
the supplied password is also the WebDAV password. The module never persists
passwords or returns SFTPGo tokens/account hashes.

`auth_v2_contract.txt` defines the client request, response, validation and error
contract. Existing empty-body / `{}` random registration remains unchanged.
No new public route, Nginx rule or management permission is required.

## Integration

The existing deployment owns `auth_service.py`, including its private settings.
**Do not copy that file, environment files, backups, credentials or test output
into this repository.** The extension expects these existing gateway symbols:

- `AuthHandler` with `do_POST` and `get_client_ip`
- `SFTPGO_API_URL`, `PUBLIC_WEBDAV_HOST`
- `get_admin_token()`, `create_sftpgo_user(username, password)`
- `check_ip_rate_limit(client_ip)`

Before the gateway's `if __name__ == '__main__'` block, integrate once:

```python
from auth_v2 import install as _install_auth_v2
_install_auth_v2(globals())
```

`deploy_auth_v2.py` is a one-time migration for the existing `/opt/sftpgo`
deployment. Place it, `auth_v2.py` and `auth_v2_contract.txt` there first. It
validates Python syntax, preserves original file ownership/mode, backs up the
original service and documentation to a mode-0700 directory with mode-0600
files, atomically integrates the module and appends the contract. It refuses
an already-integrated service and never creates accounts or restarts services.
Review the target before running it. The backup contains private configuration
and must remain on the server. Activate a reviewed update by restarting only
the existing `sftpgo-auth` gateway, without changing SFTPGo/Nginx configuration.

## Validation

```sh
cd server/sftpgo-auth
python -m unittest -v test_auth_v2.py
```

Eight tests use an in-memory mock upstream and loopback HTTP server. They cover
legacy routing, exact password forwarding, upstream login, duplicate-account
rejection, invalid credentials, malformed input, rate limits and error
redaction. No real accounts or persistent credentials are created by them.

An explicitly authorized isolated account was also used to verify deployed
registration and login (200), WebDAV MKCOL/PUT (201), byte-identical GET (200)
and PROPFIND (207) against SFTPGo 2.7.6. Quota was 5 MiB, with 100 KB/s upload
and download limits. Credentials and response artifacts are deliberately absent
from this repository. Repeating a live test requires explicit authorization;
never retry an uncertain registration by creating another account.

Login validation is deliberately separate from new-registration rules, but is
not unrestricted: usernames remain ASCII `[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}`;
passwords must be nonempty, at most 512 UTF-8 bytes and contain no control
characters. Legacy random accounts fit these rules; arbitrary existing Unicode
usernames may not. Upstream 2FA/access restrictions are not bypassed.


## Optional registration-trial composition (sandbox only)

A trusted sandbox host can inject `env['registration_trials']` with the disabled-
by-default service from `../manual-orders/registration_trials.py`. Named register
then uses that boundary instead of the legacy create callback; failure never
falls back to unjournaled creation. Login and legacy empty-body registration
remain unchanged. No deploy script enables it.

The adapter must atomically create a read-only account with recoverable operation
and original server creation-time proof, and validate supplied credentials on
all retries. The service issues one fixed 72-hour grant after entitlement
confirmation. A username match, existing-user response, or local success callback
alone is insufficient. No production SFTPGo adapter is provided. Registration
retry recovers the same proof and original deadline; login never grants or
renews a trial. See the [ledger contract](../manual-orders/README.md#one-registration-trial-per-account-disabled-sandbox-seam).
