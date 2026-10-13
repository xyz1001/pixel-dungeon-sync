"""Unhosted, disabled-by-default per-request DAV authorization decision.

No proxy config, auth implementation, credential creation or live listener.
"""
from contextlib import closing
from dataclasses import dataclass
import sqlite3
import time

from orders import DenyAuthentication, Principal, confirmed_expiry


READ_METHODS = frozenset(('GET', 'HEAD', 'PROPFIND', 'OPTIONS'))
WRITE_METHODS = frozenset(('PUT', 'POST', 'MKCOL', 'MOVE', 'COPY', 'DELETE',
                           'PROPPATCH', 'LOCK', 'UNLOCK'))


@dataclass(frozen=True)
class Decision:
    status: int
    code: str


class SqliteEntitlements:
    def __init__(self, path):
        self.path = str(path)

    def expiry(self, owner):
        # Read-only URI prevents a mistyped/missing DB from becoming a new DB.
        from pathlib import Path
        uri = Path(self.path).resolve().as_uri() + '?mode=ro'
        with closing(sqlite3.connect(uri, uri=True, timeout=2)) as db:
            return confirmed_expiry(db, owner)


class WriteGate:
    def __init__(self, *, enabled=False, authenticator=None, ledger=None, clock=None):
        self.enabled = enabled
        self.auth = authenticator or DenyAuthentication()
        self.ledger = ledger
        self.clock = clock or (lambda: time.time_ns() // 1_000_000)

    def decide(self, method, headers):
        """Method must come from the actual request, never an override header.

        Authenticator must verify existing DAV credentials upstream and produce
        a trusted subject; it must not trust X-User or decode unverified Basic.
        On reads the downstream SFTPGo authentication/authorization remains.
        """
        if self.enabled is not True:
            return Decision(503, 'WRITE_GATE_DISABLED')
        if method not in READ_METHODS | WRITE_METHODS:
            return Decision(405, 'METHOD_NOT_ALLOWED')
        try:
            identity = self.auth.authenticate(headers)
            if not isinstance(identity, Principal) or identity.role != 'user' or not identity.subject:
                return Decision(401, 'AUTHENTICATION_REQUIRED')
        except Exception:
            return Decision(401, 'AUTHENTICATION_REQUIRED')
        if method in READ_METHODS:
            return Decision(204, 'PASS_TO_SFTPGO')
        try:
            expiry = self.ledger.expiry(identity.subject)
            now = self.clock()
            if expiry is None:
                return Decision(403, 'UPLOAD_ENTITLEMENT_REQUIRED')
            if type(expiry) is not int or expiry < 0 or type(now) is not int:
                return Decision(503, 'ENTITLEMENT_UNAVAILABLE')
            if expiry <= now:
                return Decision(403, 'UPLOAD_ENTITLEMENT_EXPIRED')
            return Decision(204, 'PASS_TO_SFTPGO')
        except Exception:
            return Decision(503, 'ENTITLEMENT_UNAVAILABLE')
