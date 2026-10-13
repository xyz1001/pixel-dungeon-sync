"""Versioned named-account API; secrets remain solely in SFTPGo/requests."""
import base64
import json
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_BODY = 4096
LOGIN_WINDOW = 900
LOGIN_LIMIT = 30
_attempts = {}
_lock = threading.Lock()


class ApiError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def fetch_json(req):
    with urllib.request.build_opener(NoRedirect()).open(req, timeout=10) as response:
        raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise ApiError(502, 'UPSTREAM_ERROR')
        result = json.loads(raw)
        if not isinstance(result, dict):
            raise ApiError(502, 'UPSTREAM_ERROR')
        return result


def login_allowed(ip):
    now = time.monotonic()
    with _lock:
        for key in list(_attempts):
            _attempts[key] = [t for t in _attempts[key] if now - t < LOGIN_WINDOW]
            if not _attempts[key]:
                del _attempts[key]
        # Bound memory without evicting active throttles.
        if ip not in _attempts and len(_attempts) >= 10000:
            return False
        attempts = _attempts.setdefault(ip, [])
        if len(attempts) >= LOGIN_LIMIT:
            return False
        attempts.append(now)
        return True


def credentials(body):
    if set(body) != {'version', 'action', 'username', 'password'} or type(body.get('version')) is not int or body['version'] != 2:
        raise ApiError(400, 'INVALID_REQUEST')
    action, user, password = body['action'], body['username'], body['password']
    if action not in ('register', 'login') or not isinstance(user, str) or not isinstance(password, str):
        raise ApiError(400, 'INVALID_REQUEST')
    pattern = r'[a-z][a-z0-9_-]{2,31}' if action == 'register' else r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}'
    if not re.fullmatch(pattern, user):
        raise ApiError(400, 'INVALID_USERNAME')
    if not 1 <= len(password.encode('utf-8')) <= 512 or any(ord(c) < 32 or ord(c) == 127 for c in password):
        raise ApiError(400, 'INVALID_PASSWORD')
    if action == 'register' and not 8 <= len(password) <= 128:
        raise ApiError(400, 'INVALID_PASSWORD')
    if action == 'register' and password.startswith(('$', '{')):
        # SFTPGo accepts pre-hashed strings in its management API; never treat user input as a hash.
        raise ApiError(400, 'INVALID_PASSWORD')
    return action, user, password


def account_info(env, username):
    req = urllib.request.Request(env['SFTPGO_API_URL'].rstrip('/') + '/api/v2/users/' + urllib.parse.quote(username, safe=''),
        headers={'Authorization': 'Bearer ' + env['get_admin_token']()})
    user = fetch_json(req)
    if user.get('username') != username or user.get('status') != 1:
        raise ApiError(401, 'INVALID_CREDENTIALS')
    # Never return the upstream user object: it may contain sensitive fields.
    return {'webdav_url': env['PUBLIC_WEBDAV_HOST'], 'username': username,
            'quota_bytes': user.get('quota_size', 0),
            'quota_mb': user.get('quota_size', 0) / (1024 * 1024),
            'upload_bandwidth_kb': user.get('upload_bandwidth', 0),
            'download_bandwidth_kb': user.get('download_bandwidth', 0)}


def perform(env, action, username, password, ip):
    if action == 'register':
        if not env['check_ip_rate_limit'](ip):
            raise ApiError(429, 'REGISTRATION_RATE_LIMITED')
        try:
            env['create_sftpgo_user'](username, password)
        except urllib.error.HTTPError as error:
            if error.code == 409:
                raise ApiError(409, 'USERNAME_UNAVAILABLE') from None
            if error.code == 400:
                raise ApiError(400, 'REGISTRATION_REJECTED') from None
            raise ApiError(502, 'UPSTREAM_ERROR') from None
    else:
        if not login_allowed(ip):
            raise ApiError(429, 'LOGIN_RATE_LIMITED')
        basic = base64.b64encode((username + ':' + password).encode('utf-8')).decode('ascii')
        req = urllib.request.Request(env['SFTPGO_API_URL'].rstrip('/') + '/api/v2/user/token',
            headers={'Authorization': 'Basic ' + basic})
        try:
            result = fetch_json(req)
            if not isinstance(result.get('access_token'), str) or not result['access_token']:
                raise ApiError(502, 'UPSTREAM_ERROR')
            # The user token is only evidence of upstream authentication, never persisted/returned.
            del result
        except urllib.error.HTTPError as error:
            if error.code in (401, 403, 404):
                raise ApiError(401, 'INVALID_CREDENTIALS') from None
            if error.code == 429:
                raise ApiError(429, 'LOGIN_RATE_LIMITED') from None
            raise ApiError(502, 'UPSTREAM_ERROR') from None
    return account_info(env, username)


def install(env):
    handler = env['AuthHandler']
    legacy_post = handler.do_POST

    def send(self, status, body):
        # Bodies/tokens are never added to logs; successful auth responses are not cacheable.
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'POST, GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def post(self):
        if self.path not in ('/api/register', '/register'):
            return legacy_post(self)
        try:
            if self.headers.get('Transfer-Encoding'):
                raise ApiError(400, 'INVALID_REQUEST')
            length = int(self.headers.get('Content-Length', '0'))
            if length < 0 or length > MAX_BODY:
                raise ApiError(413, 'REQUEST_TOO_LARGE')
            self.connection.settimeout(10)
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ApiError(400, 'INVALID_REQUEST')
            body = json.loads(raw) if raw else {}
            if body == {}:
                return legacy_post(self)
            if not isinstance(body, dict):
                raise ApiError(400, 'INVALID_REQUEST')
            if self.headers.get_content_type() != 'application/json':
                raise ApiError(415, 'JSON_REQUIRED')
            action, username, password = credentials(body)
            info = perform(env, action, username, password, self.get_client_ip())
            send(self, 200, {'code': 200, 'message': 'success', 'data': info})
        except ApiError as error:
            send(self, error.status, {'code': error.status, 'error': error.code, 'message': error.code})
        except (ValueError, UnicodeError):
            send(self, 400, {'code': 400, 'error': 'INVALID_REQUEST', 'message': 'INVALID_REQUEST'})
        except Exception:
            send(self, 502, {'code': 502, 'error': 'UPSTREAM_ERROR', 'message': 'UPSTREAM_ERROR'})

    handler.do_POST = post
