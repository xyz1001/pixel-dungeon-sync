"""Bounded REST adapter for SFTPGo 2.7.6. Importing it performs no I/O.

GET token, GET exact user, POST new user and an opt-in native expiry writer are
implemented. SFTPGo's username-addressed PUT is not compare-and-swap. Expiry
writes require caller-side durable serialization and NO external account
configuration writers, including administrators, for the whole GET/PUT/GET.

SFTPGo user tokens contain username and updated_at, NOT immutable account ID.
Authentication therefore requires an explicit deployment invariant: managed
usernames/IDs are never deleted, renamed or reused, and the SFTPGo database is
not restored/reset under the same instance_id. Two reads detect many changes;
they do not make remote authentication and lookup atomic. The runtime must also
isolate the DAV data plane behind its gate, and keep HTTP management private.

create_or_recover returns remote proof, not a completed registration. Its trusted
caller MUST durably bind account_id and enroll the original trial before external
success. Retry with the original operation after any uncertain response/crash.
Neither plaintext passwords nor upstream tokens are persisted or logged here.
"""

import base64
from dataclasses import dataclass
import http.client
import json
import math
import os
from pathlib import PurePosixPath
import re
import socket
import ssl
import stat
import time
import urllib.error
import urllib.parse
import urllib.request


MAX_JSON_BYTES = 1024 * 1024
MAX_TOKEN_BYTES = 16 * 1024
MAX_CREDENTIAL_FILE_BYTES = 4096
TRIAL_DURATION_MS = 72 * 60 * 60 * 1000
REGISTRATION_NAMESPACE = 'pixel-dungeon-sync.registration.v1'
_USERNAME = re.compile(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}')
_NEW_USERNAME = re.compile(r'[a-z0-9]{3,32}')
_OPERATION = re.compile(r'[A-Za-z0-9_-]{16,128}')
_INSTANCE = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}')


class SftpgoError(Exception):
    """Only fixed, non-sensitive error codes cross this boundary."""

    def __init__(self, status, code):
        super().__init__(code)
        self.status, self.code = status, code


class _UpstreamStatus(Exception):
    def __init__(self, status):
        super().__init__('SFTPGO_HTTP_STATUS')
        self.status = status


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result


def _json_object(raw):
    def invalid_constant(_):
        raise ValueError('invalid JSON number')
    value = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique_object,
                       parse_constant=invalid_constant)
    if not isinstance(value, dict):
        raise ValueError('JSON object required')
    return value


def _valid_integer(value, minimum=0):
    return type(value) is int and minimum <= value <= 2**63 - 1


def _validate_credentials(username, password, *, registration=False):
    pattern = _NEW_USERNAME if registration else _USERNAME
    if not isinstance(username, str) or not pattern.fullmatch(username):
        raise SftpgoError(400, 'INVALID_USERNAME')
    try:
        valid = (isinstance(password, str) and 1 <= len(password.encode('utf-8')) <= 512
                 and not any(ord(char) < 32 or ord(char) == 127 for char in password))
    except UnicodeError:
        valid = False
    if not valid or (registration and (not 6 <= len(password) <= 128 or password.startswith(('$', '{'))
                                      or any(128 <= ord(char) <= 159 for char in password))):
        # SFTPGo accepts pre-hashed strings; user input must always be plaintext.
        raise SftpgoError(400, 'INVALID_PASSWORD')


def _basic(username, password):
    return 'Basic ' + base64.b64encode((username + ':' + password).encode('utf-8')).decode('ascii')


class RuntimeAdminCredentials:
    """Read ONLY explicitly selected existing environment keys or JSON file.

    A file is an existing private regular file, owned by this process's user,
    containing exactly {"username": "...", "password": "..."}. No file/secret
    is generated or modified. Missing/rejected sources fail without discovery or
    fallback. Values are fetched at request time and not cached by this object.
    """

    def __init__(self, *, username_variable=None, password_variable=None, path=None, environ=None):
        self._username_variable, self._password_variable = username_variable, password_variable
        self._path, self._environ = path, environ

    @classmethod
    def from_environment(cls, username_variable, password_variable, *, environ=None):
        if (any(not isinstance(key, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key)
                for key in (username_variable, password_variable)) or username_variable == password_variable):
            raise ValueError('distinct explicit credential environment names required')
        return cls(username_variable=username_variable, password_variable=password_variable, environ=environ)

    @classmethod
    def from_file(cls, path):
        if not isinstance(path, str) or not os.path.isabs(path) or '\x00' in path:
            raise ValueError('absolute existing credential file path required')
        return cls(path=path)

    def __call__(self):
        try:
            if self._path is not None:
                # O_NOFOLLOW prevents a final-component symlink from changing
                # which credential file is read. Trusted parent directories are
                # an operator responsibility; no filesystem search is performed.
                flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
                with os.fdopen(os.open(self._path, flags), 'rb') as stream:
                    info = os.fstat(stream.fileno())
                    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                            or info.st_mode & 0o077 or info.st_size > MAX_CREDENTIAL_FILE_BYTES):
                        raise ValueError('private credential file required')
                    raw = stream.read(MAX_CREDENTIAL_FILE_BYTES + 1)
                if len(raw) > MAX_CREDENTIAL_FILE_BYTES:
                    raise ValueError('credential file too large')
                values = _json_object(raw)
                if set(values) != {'username', 'password'}:
                    raise ValueError('invalid credential file')
                username, password = values['username'], values['password']
            else:
                env = os.environ if self._environ is None else self._environ
                username, password = env[self._username_variable], env[self._password_variable]
            if (not isinstance(username, str) or not 1 <= len(username.encode('utf-8')) <= 128 or ':' in username
                    or any(ord(char) < 32 or ord(char) == 127 for char in username)
                    or not isinstance(password, str) or not 1 <= len(password.encode('utf-8')) <= 1024
                    or any(ord(char) < 32 or ord(char) == 127 for char in password)):
                raise ValueError('invalid admin credentials')
            return username, password
        except (OSError, KeyError, TypeError, ValueError, UnicodeError, RecursionError):
            raise SftpgoError(503, 'SFTPGO_ADMIN_CREDENTIALS_UNAVAILABLE') from None


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class _RestTransport:
    def __init__(self, base_url, *, allow_loopback_http=False, timeout=5.0):
        try:
            parsed = urllib.parse.urlsplit(base_url)
            port = parsed.port
            if (not isinstance(base_url, str) or any(ord(char) <= 32 or ord(char) == 127 for char in base_url)
                    or parsed.username is not None or parsed.password is not None
                    or parsed.query or parsed.fragment or parsed.path not in ('', '/')
                    or not parsed.hostname or (port is not None and not 1 <= port <= 65535)):
                raise ValueError()
            if parsed.scheme != 'https' and not (parsed.scheme == 'http' and allow_loopback_http is True
                    and parsed.hostname in ('127.0.0.1', '::1')):
                raise ValueError()
            if isinstance(timeout, bool) or not isinstance(timeout, (float, int)) or not math.isfinite(timeout) or not 0 < timeout <= 15:
                raise ValueError()
        except (TypeError, ValueError, AttributeError):
            raise ValueError('HTTPS or explicitly allowed exact-loopback HTTP URL and bounded timeout required') from None
        self.base_url, self.timeout = base_url.rstrip('/'), timeout
        # Never forward credentials through environment-selected proxies, follow
        # redirects, disable certificate verification or downgrade HTTPS.
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect(),
                            urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def request(self, method, path, *, authorization, payload=None):
        if method not in ('GET', 'POST', 'PUT') or not path.startswith('/api/v2/'):
            raise ValueError('unsupported SFTPGo operation')
        headers = {'Accept': 'application/json', 'Authorization': authorization,
                   'Cache-Control': 'no-store', 'Accept-Encoding': 'identity'}
        body = None
        if payload is not None:
            body = json.dumps(payload, separators=(',', ':'), allow_nan=False).encode('utf-8')
            if len(body) > MAX_JSON_BYTES:
                raise SftpgoError(503, 'SFTPGO_REQUEST_TOO_LARGE')
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            with self._opener.open(req, timeout=self.timeout) as response:
                if response.status not in (200, 201):
                    raise _UpstreamStatus(response.status)
                if response.headers.get_content_type() != 'application/json':
                    raise ValueError('JSON response required')
                declared = response.headers.get('Content-Length')
                if declared is not None and (not declared.isdigit() or int(declared) > MAX_JSON_BYTES):
                    raise ValueError('response too large')
                raw = response.read(MAX_JSON_BYTES + 1)
                if len(raw) > MAX_JSON_BYTES:
                    raise ValueError('response too large')
                return _json_object(raw)
        except urllib.error.HTTPError as error:
            status_code = error.code
            error.close()  # Do not read/log/forward its potentially sensitive body.
            if 300 <= status_code < 400:
                raise SftpgoError(503, 'SFTPGO_REDIRECT_REJECTED') from None
            raise _UpstreamStatus(status_code) from None
        except (urllib.error.URLError, OSError, socket.timeout, http.client.HTTPException):
            raise SftpgoError(503, 'SFTPGO_UPSTREAM_UNAVAILABLE') from None
        except (ValueError, UnicodeError, RecursionError):
            raise SftpgoError(503, 'SFTPGO_INVALID_RESPONSE') from None


def _access_token(result):
    token = result.get('access_token')
    if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9._~-]{1,%d}' % MAX_TOKEN_BYTES, token):
        raise SftpgoError(503, 'SFTPGO_INVALID_RESPONSE')
    return token


class AdminTokenProvider:
    """Obtain a token from GET /api/v2/token using runtime existing credentials.

    Deliberately no disk/token cache and no retries with broader permissions.
    The existing administrator needs view_users and add_users for registration,
    and edit_users only when the native expiry writer is explicitly enabled.
    A 2FA-protected admin without an approved runtime token flow fails closed.
    """

    def __init__(self, transport, credentials):
        if not isinstance(credentials, RuntimeAdminCredentials):
            raise ValueError('explicit runtime credential source required')
        self._transport, self._credentials = transport, credentials

    def __call__(self):
        username, password = self._credentials()
        try:
            result = self._transport.request('GET', '/api/v2/token', authorization=_basic(username, password))
        except _UpstreamStatus:
            raise SftpgoError(503, 'SFTPGO_ADMIN_AUTH_UNAVAILABLE') from None
        return _access_token(result)

    get_token = __call__


@dataclass(frozen=True)
class SftpgoAccount:
    username: str
    account_id: str
    created_at_ms: int
    registration_operation_id: str | None = None
    updated_at_ms: int = 0
    quota_bytes: int = 0
    upload_bandwidth_kb: int = 0
    download_bandwidth_kb: int = 0
    status: int = 1
    expiration_date_ms: int = 0

    def public_info(self, webdav_url):
        return {'webdav_url': webdav_url, 'username': self.username,
                'quota_bytes': self.quota_bytes, 'quota_mb': self.quota_bytes / (1024 * 1024),
                'upload_bandwidth_kb': self.upload_bandwidth_kb,
                'download_bandwidth_kb': self.download_bandwidth_kb}


@dataclass(frozen=True)
class NewAccountTemplate:
    """Trusted local-filesystem template, never populated from request JSON.

    Explicit positive quotas/rates are required. DAV write permissions are
    enforced by the separate mandatory request gate, not remote permission PUTs.
    HTTP is retained solely for private token authentication; SSH/FTP are denied.
    """

    home_root: str
    quota_bytes: int
    upload_bandwidth_kb: int
    download_bandwidth_kb: int

    def __post_init__(self):
        if (not isinstance(self.home_root, str) or not self.home_root.startswith('/')
                or self.home_root.startswith('//') or self.home_root == '/'
                or str(PurePosixPath(self.home_root)) != self.home_root
                or '..' in PurePosixPath(self.home_root).parts
                or any(ord(char) < 32 or ord(char) == 127 for char in self.home_root)
                or any(not _valid_integer(value, 1) for value in
                       (self.quota_bytes, self.upload_bandwidth_kb, self.download_bandwidth_kb))):
            raise ValueError('explicit canonical home root and positive account limits required')

    def payload(self, username, password, marker):
        _validate_credentials(username, password, registration=True)
        return {'username': username, 'password': password, 'status': 1,
                'home_dir': str(PurePosixPath(self.home_root) / username),
                'quota_size': self.quota_bytes, 'quota_files': 0,
                'upload_bandwidth': self.upload_bandwidth_kb,
                'download_bandwidth': self.download_bandwidth_kb,
                'expiration_date': 0, 'filesystem': {'provider': 0},
                'permissions': {'/': ['list', 'download', 'upload', 'overwrite', 'delete', 'rename', 'create_dirs']},
                'filters': {'denied_protocols': ['SSH', 'FTP'], 'allow_api_key_auth': False,
                            'web_client': ['write-disabled', 'shares-disabled', 'publickey-change-disabled',
                                           'tls-cert-change-disabled', 'api-key-auth-change-disabled']},
                'additional_info': marker}


class SftpgoClient:
    def __init__(self, base_url, *, instance_id, admin_credentials, template=None,
                 no_username_reuse=False, no_external_account_writers=False,
                 native_expiry_enabled=False, clock=None, allow_loopback_http=False, timeout=5.0):
        if not isinstance(instance_id, str) or not _INSTANCE.fullmatch(instance_id):
            raise ValueError('explicit stable SFTPGo instance_id required')
        if template is not None and not isinstance(template, NewAccountTemplate):
            raise ValueError('trusted NewAccountTemplate required')
        if type(no_username_reuse) is not bool:
            raise ValueError('explicit boolean no_username_reuse acknowledgement required')
        if type(no_external_account_writers) is not bool or type(native_expiry_enabled) is not bool:
            raise ValueError('explicit boolean native expiry and account-writer acknowledgements required')
        if clock is not None and not callable(clock):
            raise ValueError('callable server clock required')
        self.instance_id, self.template, self.no_username_reuse = instance_id, template, no_username_reuse
        self.no_external_account_writers = no_external_account_writers
        self.native_expiry_enabled = native_expiry_enabled
        self.clock = clock if clock is not None else lambda: time.time_ns() // 1_000_000
        self._transport = _RestTransport(base_url, allow_loopback_http=allow_loopback_http, timeout=timeout)
        self.admin_tokens = AdminTokenProvider(self._transport, admin_credentials)

    def _guard_identity(self):
        if self.no_username_reuse is not True:
            raise SftpgoError(503, 'SFTPGO_IDENTITY_INVARIANT_REQUIRED')

    def _admin(self, method, path, payload=None):
        return self._transport.request(method, path, authorization='Bearer ' + self.admin_tokens(), payload=payload)

    def _operation(self, info):
        if not isinstance(info, str) or not info:
            return None
        try:
            raw = info.encode('utf-8')
            if len(raw) > MAX_JSON_BYTES:
                return None
            marker = _json_object(raw).get(REGISTRATION_NAMESPACE)
        except (ValueError, UnicodeError, RecursionError):
            return None
        if (not isinstance(marker, dict) or set(marker) != {'instance_id', 'operation_id'}
                or marker['instance_id'] != self.instance_id or not isinstance(marker['operation_id'], str)
                or not _OPERATION.fullmatch(marker['operation_id'])):
            return None
        return marker['operation_id']

    def _account(self, value, username):
        # SFTPGo's SDK omits zero bandwidth fields; zero means unlimited. ID,
        # created_at, updated_at and quota_size are not omitempty in 2.7.6.
        numeric = ('id', 'created_at', 'updated_at', 'quota_size')
        upload, download = value.get('upload_bandwidth', 0), value.get('download_bandwidth', 0)
        expiration = value.get('expiration_date', 0)
        if (value.get('username') != username or any(not _valid_integer(value.get(key), 1 if key in ('id', 'created_at') else 0)
                for key in numeric) or not _valid_integer(upload) or not _valid_integer(download)
                or type(value.get('status')) is not int or value['status'] not in (0, 1)):
            raise SftpgoError(503, 'SFTPGO_INVALID_ACCOUNT_IDENTITY')
        if not _valid_integer(expiration):
            raise SftpgoError(503, 'SFTPGO_INVALID_EXPIRATION')
        return SftpgoAccount(username, f'sftpgo:{self.instance_id}:{value["id"]}:{value["created_at"]}',
                value['created_at'], self._operation(value.get('additional_info')), value['updated_at'],
                value['quota_size'], upload, download, value['status'], expiration)

    def _lookup_snapshot(self, username):
        if not isinstance(username, str) or not _USERNAME.fullmatch(username):
            raise SftpgoError(400, 'INVALID_USERNAME')
        try:
            value = self._admin('GET', '/api/v2/users/' + urllib.parse.quote(username, safe=''))
        except _UpstreamStatus as error:
            if error.status == 404:
                return None
            raise SftpgoError(503, 'SFTPGO_ACCOUNT_LOOKUP_UNAVAILABLE') from None
        return value, self._account(value, username)

    def lookup(self, username):
        snapshot = self._lookup_snapshot(username)
        return snapshot[1] if snapshot is not None else None

    def _expiry_snapshot(self, identity):
        self._guard_identity()
        username, account_id = getattr(identity, 'username', None), getattr(identity, 'account_id', None)
        if (not isinstance(username, str) or not _USERNAME.fullmatch(username)
                or not isinstance(account_id, str) or not account_id or len(account_id) > 256):
            raise SftpgoError(400, 'INVALID_ACCOUNT_IDENTITY')
        snapshot = self._lookup_snapshot(username)
        if snapshot is None or snapshot[1].account_id != account_id:
            raise SftpgoError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        value, account = snapshot
        # The SDK may omit zero-valued expiration_date. Zero is unlimited, NOT
        # an already-confirmed grant and NOT infinity for monotonic comparisons.
        return value, account, account.expiration_date_ms

    def observed_account_expiry(self, identity):
        """Read the native Unix-millisecond expiry of this exact incarnation.

        Zero means unlimited/uninitialized; callers must not confirm a positive
        journal target from it. This read does not prove enforcement or perform
        remote CAS. Disabled accounts cannot provide successful application proof.
        """
        _, account, expiration = self._expiry_snapshot(identity)
        if account.status != 1:
            raise SftpgoError(409, 'SFTPGO_ACCOUNT_DISABLED')
        return expiration

    def ensure_account_expiry(self, identity, target_ms):
        """Apply one fixed positive target, then independently read it back.

        The caller must durably reserve target_ms and serialize every write to
        this account across retries/processes. Together with the explicit
        no-reuse and no-external-writer deployment invariants this prevents
        stale full-user replacements; it is NOT remote compare-and-swap.

        SFTPGo v2.7.6 PUT replaces the user, so send the latest complete GET
        object, changing only expiration_date and omitting password (the API
        then preserves the current password). Status, permissions, quotas,
        groups, filesystem, filters and every other returned field survive.
        Contract: github.com/drakkan/sftpgo/blob/v2.7.6/internal/httpd/api_user.go
        updateUser, and openapi/openapi.yaml update_user.
        Never enable a manually disabled account. disconnect=1 invalidates
        old settings on this node; multi-node propagation is deployment scope.
        A lost PUT response is read back once, never replayed within this call.
        """
        self._guard_identity()
        if not self.native_expiry_enabled:
            raise SftpgoError(503, 'SFTPGO_NATIVE_EXPIRY_DISABLED')
        if not self.no_external_account_writers:
            raise SftpgoError(503, 'SFTPGO_ACCOUNT_WRITER_INVARIANT_REQUIRED')
        if not _valid_integer(target_ms, 1):
            raise SftpgoError(400, 'INVALID_EXPIRATION_TARGET')
        value, account, current = self._expiry_snapshot(identity)
        if account.status != 1:
            raise SftpgoError(409, 'SFTPGO_ACCOUNT_DISABLED')
        if current >= target_ms:
            return current
        payload = dict(value)
        payload.pop('password', None)
        payload['expiration_date'] = target_ms
        uncertain = None
        try:
            self._admin('PUT', '/api/v2/users/' + urllib.parse.quote(account.username, safe='')
                        + '?disconnect=1', payload)
        except _UpstreamStatus as error:
            if error.status == 404:
                raise SftpgoError(409, 'ACCOUNT_IDENTITY_CONFLICT') from None
            raise SftpgoError(503, 'SFTPGO_EXPIRY_UPDATE_UNAVAILABLE') from None
        except SftpgoError as error:
            # Connection loss/malformed success may follow a committed write.
            # A bounded GET can establish the postcondition without adding time.
            if error.code not in ('SFTPGO_UPSTREAM_UNAVAILABLE', 'SFTPGO_INVALID_RESPONSE'):
                raise
            uncertain = error
        observed = self.observed_account_expiry(identity)
        if observed < target_ms:
            if uncertain is not None:
                raise uncertain
            raise SftpgoError(503, 'SFTPGO_EXPIRY_NOT_CONFIRMED')
        return observed

    def authenticate_basic(self, username, password):
        self._guard_identity()
        _validate_credentials(username, password)
        before = self.lookup(username)
        if before is None or before.status != 1:
            raise SftpgoError(401, 'INVALID_CREDENTIALS')
        try:
            result = self._transport.request('GET', '/api/v2/user/token', authorization=_basic(username, password))
        except _UpstreamStatus as error:
            if error.status in (401, 403, 404):
                raise SftpgoError(401, 'INVALID_CREDENTIALS') from None
            if error.status == 429:
                raise SftpgoError(429, 'LOGIN_RATE_LIMITED') from None
            raise SftpgoError(503, 'SFTPGO_AUTH_UNAVAILABLE') from None
        token = _access_token(result)
        try:
            parts = token.split('.')
            if len(parts) != 3 or not parts[0] or not parts[2]:
                raise ValueError()
            raw = base64.b64decode(parts[1] + '=' * (-len(parts[1]) % 4), altchars=b'-_', validate=True)
            claims = _json_object(raw)
        except (ValueError, UnicodeError, RecursionError):
            raise SftpgoError(503, 'SFTPGO_INVALID_RESPONSE') from None
        # This is a just-issued token from the configured authenticated server,
        # not caller-supplied JWT authorization. Decoding is a consistency check,
        # NOT local JWT signature verification or proof of an immutable ID.
        if claims.get('username') != username or claims.get('sub') != str(before.updated_at_ms):
            raise SftpgoError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        after = self.lookup(username)
        if (after is None or after.status != 1 or before.account_id != after.account_id
                or before.updated_at_ms != after.updated_at_ms
                or before.registration_operation_id != after.registration_operation_id):
            raise SftpgoError(409, 'ACCOUNT_IDENTITY_CONFLICT')
        return after

    def create_or_recover(self, username, password, operation_id):
        """One POST at most; no password changes, timestamp reset or retry POST.

        A lost POST response is uncertain. A later same-operation call reads the
        atomically saved marker and authenticates the supplied password again.
        Existing accounts with different/missing proof are never modified.
        """
        self._guard_identity()
        _validate_credentials(username, password, registration=True)
        if not isinstance(operation_id, str) or not _OPERATION.fullmatch(operation_id):
            raise SftpgoError(400, 'INVALID_REGISTRATION_OPERATION')
        if self.template is None:
            raise SftpgoError(503, 'SFTPGO_REGISTRATION_TEMPLATE_REQUIRED')
        existing = self.lookup(username)
        if existing is None:
            marker = json.dumps({REGISTRATION_NAMESPACE: {'instance_id': self.instance_id,
                                'operation_id': operation_id}}, separators=(',', ':'))
            payload = self.template.payload(username, password, marker)
            if self.native_expiry_enabled:
                now = self.clock()
                if not _valid_integer(now, 1) or now > 2**63 - 1 - TRIAL_DURATION_MS:
                    raise SftpgoError(503, 'INVALID_SERVER_CLOCK')
                # Provisional finite deadline, never reset on recovery. The
                # durable trial still uses original SFTPGo created_at. Clocks
                # must be synchronized; the ledger owns final reconciliation.
                payload['expiration_date'] = now + TRIAL_DURATION_MS
            try:
                created = self._admin('POST', '/api/v2/users', payload)
                existing = self._account(created, username)
            except _UpstreamStatus as error:
                if error.status not in (400, 409):
                    raise SftpgoError(503, 'SFTPGO_REGISTRATION_RETRY_REQUIRED') from None
                # Some providers report duplicate create as 400 rather than 409.
                # A matching marker + reauthentication is the only recovery.
                existing = self.lookup(username)
                if existing is None:
                    raise SftpgoError(400, 'REGISTRATION_REJECTED') from None
        if existing.registration_operation_id != operation_id:
            raise SftpgoError(409, 'USERNAME_UNAVAILABLE')
        verified = self.authenticate_basic(username, password)
        if verified.account_id != existing.account_id or verified.registration_operation_id != operation_id:
            raise SftpgoError(409, 'REGISTRATION_PROOF_CONFLICT')
        return verified
