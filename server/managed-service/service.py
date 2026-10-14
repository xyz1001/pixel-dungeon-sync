"""Opt-in loopback composition for real SFTPGo accounts and the shared ledger.

Run `python service.py validate-config /absolute/config.json` offline, or use
`serve` after the deployment readiness checks. Importing starts nothing.
"""
import argparse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import signal
import socket
import sys
import threading
import time

_SERVER = Path(__file__).resolve().parents[1]
for _component in ('sftpgo-auth', 'manual-orders', 'feishu-renewal-bot'):
    _directory = str(_SERVER / _component)
    if _directory not in sys.path:
        sys.path.insert(0, _directory)

from auth_v2 import ApiError, MAX_BODY, credentials
from bot import Binding
from ledger_gate import AccountWriteGate, ManagedLedger, SftpgoBasicAuthenticator
from orders import OrderError
from runtime_config import ConfigurationError, RuntimeConfig, load_appreciation_asset, load_runtime_secret, strict_json
from sftpgo_client import NewAccountTemplate, RuntimeAdminCredentials, SftpgoClient, SftpgoError
from write_gate import READ_METHODS, WRITE_METHODS

# Error output is an allowlist; never return arbitrary exception text or codes.
_PUBLIC_ERRORS = {
    'INVALID_REQUEST': 400, 'INVALID_USERNAME': 400, 'INVALID_PASSWORD': 400,
    'USERNAME_UNAVAILABLE': 409, 'INVALID_CREDENTIALS': 401, 'LOGIN_RATE_LIMITED': 429,
    'REGISTRATION_RATE_LIMITED': 429, 'REGISTRATION_REJECTED': 400,
    'REGISTRATION_IN_PROGRESS': 409, 'REGISTRATION_PROOF_CONFLICT': 409,
    'ACCOUNT_IDENTITY_CONFLICT': 409, 'LEGACY_ACCOUNT_MIGRATION_REQUIRED': 409,
    'LEGACY_TRIAL_MIGRATION_REQUIRED': 409, 'REGISTRATION_TRIALS_DISABLED': 503,
    'AUTHENTICATION_REQUIRED': 401,
    'REGISTRATION_TRIAL_RETRY_REQUIRED': 503,
}


class BurstLimit:
    """Bounded global admission rate: no untrusted forwarded-IP identity."""
    def __init__(self, count=120, window=60, clock=time.monotonic):
        self.count, self.window, self.clock = count, window, clock
        self.times, self.lock = deque(), threading.Lock()

    def accept(self):
        with self.lock:
            now = self.clock()
            while self.times and self.times[0] <= now - self.window:
                self.times.popleft()
            if len(self.times) >= self.count:
                return False
            self.times.append(now)
            return True


class ManagedApplication:
    def __init__(self, config, *, accounts, ledger, clock=None):
        self.config, self.accounts, self.ledger = config, accounts, ledger
        self.authenticator = SftpgoBasicAuthenticator(accounts)
        self.gate = AccountWriteGate(enabled=config.enabled, authenticator=self.authenticator,
                                     ledger=ledger, clock=clock)
        self.login_limit, self.registration_limit = BurstLimit(), BurstLimit(30, 3600)
        self.appreciation_asset = load_appreciation_asset(config.appreciation)

    def registration(self, body):
        action, username, password = credentials(body)
        if action == 'register':
            if not self.config.mutations_enabled:
                raise ApiError(503, 'REGISTRATION_TRIALS_DISABLED')
            if not self.registration_limit.accept():
                raise ApiError(429, 'REGISTRATION_RATE_LIMITED')
            try:
                self.ledger.register(username, password)
            except OrderError as error:
                # Login authenticates but cannot repair an uncertain registration
                # proof/application. Retry registration with its original operation.
                if error.status == 503 and error.code in {
                    'REGISTRATION_TRIAL_RETRY_REQUIRED', 'REGISTRATION_DATABASE_UNAVAILABLE',
                    'REGISTRATION_UPSTREAM_UNAVAILABLE', 'SFTPGO_REGISTRATION_RETRY_REQUIRED',
                    'SFTPGO_UPSTREAM_UNAVAILABLE', 'SFTPGO_ACCOUNT_LOOKUP_UNAVAILABLE',
                }:
                    raise ApiError(503, 'REGISTRATION_TRIAL_RETRY_REQUIRED') from None
                raise
        elif not self.login_limit.accept():
            raise ApiError(429, 'LOGIN_RATE_LIMITED')
        # Reauthenticate after durable registration before returning any account
        # data. The local binding check catches lifecycle changes after creation.
        account = self.accounts.authenticate_basic(username, password)
        if action == 'register':
            self.ledger.entitlement(account)
        return account.public_info(self.config.public_webdav_url)

    def entitlement(self, headers):
        identity = self.authenticator.authenticate(headers)
        # Entitlement accepts an adapter identity, not username supplied in JSON.
        return self.ledger.entitlement(identity)

    def appreciation(self, headers):
        if self.appreciation_asset is None:
            raise ApiError(404, 'NOT_FOUND')
        self.authenticator.authenticate(headers)
        # No username, credential, account identity or filesystem path belongs
        # in this static contract. The client gets its username from entitlement.
        return {'version': 1, 'payment_mode': 'donation',
                'image_path': '/api/account/appreciation/image',
                'image_sha256': self.appreciation_asset.sha256,
                'term': 'calendar_year', 'remark': 'username'}


class BoundedHTTPServer(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True
    allow_reuse_address = False
    request_queue_size = 32

    def __init__(self, address, handler, *, max_workers, request_timeout):
        self.workers = threading.BoundedSemaphore(max_workers)
        self.request_timeout = request_timeout
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        if not self.workers.acquire(blocking=False):
            try:
                request.settimeout(0.2)
                request.sendall(b'HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.workers.release()
            raise

    def process_request_thread(self, request, client_address):
        def expire():
            try:
                request.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        deadline = threading.Timer(self.request_timeout, expire)
        deadline.daemon = True
        deadline.start()
        try:
            super().process_request_thread(request, client_address)
        finally:
            deadline.cancel()
            self.workers.release()

    def handle_error(self, request, client_address):
        # Default traceback output could include upstream credential-bearing data.
        pass


def create_server(application, *, host='127.0.0.1', port=None):
    """Build but do not start. Explicit port=0 is reserved for local tests."""
    config = application.config
    if config.enabled is not True:
        raise ConfigurationError('SERVICE_DISABLED')
    if host != '127.0.0.1':
        raise ConfigurationError('LITERAL_LOOPBACK_LISTENER_REQUIRED')
    port = config.listen['port'] if port is None else port
    if type(port) is not int or not 0 <= port <= 65535:
        raise ConfigurationError('INVALID_LISTENER_PORT')

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'
        server_version = 'ManagedAccountService'
        sys_version = ''

        def setup(self):
            super().setup()
            self.connection.settimeout(config.listen['request_timeout_seconds'])

        def log_message(self, *args):
            pass

        def send(self, status, payload=None):
            raw = b'' if payload is None else json.dumps(payload, ensure_ascii=False, allow_nan=False).encode('utf-8')
            self.send_bytes(status, raw, 'application/json; charset=utf-8')

        def send_bytes(self, status, raw, content_type):
            self.close_connection = True
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Connection', 'close')
            if status == 401:
                self.send_header('WWW-Authenticate', 'Basic realm="WebDAV", charset="UTF-8"')
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(raw)

        def error(self, status, code):
            self.send(status, {'code': status, 'error': code, 'message': code})

        def send_error(self, code, message=None, explain=None):
            self.error(code, 'INVALID_HTTP_REQUEST')

        def request(self):
            try:
                if self.client_address[0] != '127.0.0.1':
                    return self.error(403, 'LOOPBACK_REQUIRED')
                names = [name.lower() for name in self.headers.keys()]
                if (len(names) > 64 or len(set(names)) != len(names)
                        or sum(len(k) + len(v) for k, v in self.headers.items()) > 16384
                        or any('\r' in v or '\n' in v for v in self.headers.values())):
                    return self.error(400, 'AMBIGUOUS_HEADERS')
                if self.headers.get('Host') != f'127.0.0.1:{self.server.server_port}':
                    return self.error(400, 'INVALID_HOST')
                if 'Transfer-Encoding' in self.headers or 'Expect' in self.headers:
                    return self.error(400, 'INVALID_REQUEST')
                if 'Origin' in self.headers or 'Cookie' in self.headers:
                    return self.error(403, 'BROWSER_SESSION_UNSUPPORTED')
                length = self.headers.get('Content-Length', '0')
                if not length.isascii() or not length.isdigit() or len(length) > 6:
                    return self.error(400, 'INVALID_REQUEST')
                length = int(length)
                if length > MAX_BODY:
                    return self.error(413, 'REQUEST_TOO_LARGE')
                # BaseHTTPRequestHandler normalizes a leading // to /; reject
                # that alias using the original request target as well.
                if (self.requestline.split()[1] != self.path
                        or len(self.path) > 2048 or not self.path.startswith('/')
                        or any(c in self.path for c in ('?', '#', '%', '\\')) or self.path.startswith('//')):
                    return self.error(400, 'INVALID_PATH')
                if self.command not in ('GET', 'POST'):
                    return self.error(405, 'METHOD_NOT_ALLOWED')
                if self.command == 'GET' and length:
                    return self.error(400, 'UNEXPECTED_BODY')
                if self.path == '/_internal/dav-authorize' and self.command == 'GET':
                    method = self.headers.get('X-Original-Method')
                    if method not in READ_METHODS | WRITE_METHODS:
                        return self.error(403, 'DAV_METHOD_REJECTED')
                    decision = application.gate.decide(method, self.headers)
                    if decision.status == 204:
                        return self.send(204)
                    # auth_request permits 2xx, 401 or 403; other failures must
                    # remain non-2xx and become a fail-closed proxy error.
                    status = decision.status if decision.status in (401, 403) else 503
                    return self.error(status, decision.code if decision.code in {
                        'AUTHENTICATION_REQUIRED', 'UPLOAD_ENTITLEMENT_REQUIRED', 'UPLOAD_ENTITLEMENT_EXPIRED',
                        'ACCOUNT_IDENTITY_CONFLICT', 'ENTITLEMENT_UNAVAILABLE', 'WRITE_GATE_DISABLED'
                    } else 'AUTHORIZATION_UNAVAILABLE')
                if 'X-Original-Method' in self.headers:
                    return self.error(400, 'UNEXPECTED_PROXY_HEADER')
                if self.path == '/healthz' and self.command == 'GET':
                    return self.send(200, {'status': 'listening', 'mutations_enabled': config.mutations_enabled,
                                           'route_enforcement_verified': False})
                if self.path == '/api/register' and self.command == 'POST':
                    if self.headers.get_content_type() != 'application/json':
                        return self.error(415, 'JSON_REQUIRED')
                    if not length:
                        return self.error(400, 'LEGACY_REGISTRATION_UNSUPPORTED')
                    raw = self.rfile.read(length)
                    if len(raw) != length:
                        return self.error(400, 'INVALID_REQUEST')
                    body = strict_json(raw.decode('utf-8'))
                    if body == {}:
                        return self.error(400, 'LEGACY_REGISTRATION_UNSUPPORTED')
                    if type(body) is not dict:
                        return self.error(400, 'INVALID_REQUEST')
                    data = application.registration(body)
                    return self.send(200, {'code': 200, 'message': 'success', 'data': data})
                if self.path == '/api/account/entitlement' and self.command == 'GET':
                    return self.send(200, {'code': 200, 'data': application.entitlement(self.headers)})
                if self.path in ('/api/account/appreciation', '/api/account/appreciation/image') and self.command == 'GET':
                    if application.appreciation_asset is None:
                        return self.error(404, 'NOT_FOUND')
                    if self.path == '/api/account/appreciation':
                        return self.send(200, {'code': 200, 'data': application.appreciation(self.headers)})
                    # Public fixed asset: no auth lookup, redirect, filesystem
                    # access, directory serving, or user-controlled image path.
                    return self.send_bytes(200, application.appreciation_asset.png, 'image/png')
                return self.error(404, 'NOT_FOUND')
            except (ApiError, OrderError, SftpgoError) as error:
                code = error.code if error.code in _PUBLIC_ERRORS else 'SERVICE_UNAVAILABLE'
                self.error(_PUBLIC_ERRORS.get(code, 503), code)
            except (ValueError, UnicodeError, RecursionError):
                self.error(400, 'INVALID_REQUEST')
            except (TimeoutError, ConnectionError, OSError):
                self.close_connection = True
            except Exception:
                self.error(503, 'SERVICE_UNAVAILABLE')

        do_GET = do_POST = do_HEAD = do_PUT = do_DELETE = do_PATCH = do_OPTIONS = request
        do_PROPFIND = do_MKCOL = do_MOVE = do_COPY = do_PROPPATCH = do_LOCK = do_UNLOCK = request

    return BoundedHTTPServer((host, port), Handler, max_workers=config.listen['max_workers'],
                             request_timeout=config.listen['request_timeout_seconds'])


def build_application(config):
    if config.enabled is not True:
        raise ConfigurationError('SERVICE_DISABLED')
    source = config.sftpgo['admin_credentials']
    runtime_credentials = (RuntimeAdminCredentials.from_file(source['file']) if 'file' in source else
                           RuntimeAdminCredentials.from_environment(source['username_env'], source['password_env']))
    # Fail before opening the listener if the explicit existing source is absent.
    runtime_credentials()
    account_config = config.sftpgo
    accounts = SftpgoClient(account_config['base_url'], instance_id=account_config['instance_id'],
                           admin_credentials=runtime_credentials,
                           template=NewAccountTemplate(**account_config['new_account']),
                           no_username_reuse=config.prerequisites['no_delete_or_reuse_managed_usernames'],
                           allow_loopback_http=account_config['allow_loopback_http'], timeout=account_config['timeout_seconds'])
    ledger = ManagedLedger(config.database_path, accounts=accounts, enabled=config.mutations_enabled)
    return ManagedApplication(config, accounts=accounts, ledger=ledger)


def serve(config):
    application = build_application(config)
    binding = secret = sdk = None
    if config.feishu['enabled']:
        binding = Binding.load(config.feishu['binding_file'])
        if not binding.enabled or not binding.complete():
            raise ConfigurationError('FEISHU_BINDING_DISABLED_OR_INCOMPLETE')
        secret = load_runtime_secret(config.feishu['app_secret'])
        import sdk_transport as sdk
        try:
            sdk._load_sdk()  # Wrong/missing SDK is a startup failure, not a no-op.
        except Exception:
            raise ConfigurationError('FEISHU_SDK_UNAVAILABLE_OR_VERSION_MISMATCH') from None
    server = create_server(application)
    worker = threading.Thread(target=server.serve_forever, name='managed-account-http', daemon=True)
    previous = None
    if threading.current_thread() is threading.main_thread():
        previous = signal.getsignal(signal.SIGTERM)
        def stop(signum, frame):
            raise KeyboardInterrupt()
        signal.signal(signal.SIGTERM, stop)
    worker.start()
    try:
        print('Loopback account service listening. Route closure is operator-attested, not automatically verified.', flush=True)
        if sdk is not None:
            sdk.run_service(binding, config.feishu['queue_path'], application.ledger, secret)
        else:
            while worker.is_alive():
                worker.join(timeout=0.5)
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=2)
        if previous is not None:
            signal.signal(signal.SIGTERM, previous)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('validate-config', 'serve'))
    parser.add_argument('config', help='Explicit external JSON configuration; no embedded credentials')
    args = parser.parse_args(argv)
    try:
        config = RuntimeConfig.load(args.config)
        if args.command == 'validate-config':
            load_appreciation_asset(config.appreciation)
            if config.feishu['enabled']:
                binding = Binding.load(config.feishu['binding_file'])
                if not binding.enabled or not binding.complete():
                    raise ConfigurationError('FEISHU_BINDING_DISABLED_OR_INCOMPLETE')
            print('Configuration valid; service ' + ('enabled.' if config.enabled else 'disabled.'))
            print('Appreciation asset validated offline.' if config.appreciation['enabled'] else
                  'Appreciation disabled; no asset opened.')
            print('Offline validation only: no credentials read, network calls, or deployment verification.')
            return 0
        serve(config)
        return 0
    except KeyboardInterrupt:
        return 0
    except ConfigurationError as error:
        # ConfigurationError is emitted only with fixed internal codes.
        print('Service failed: ' + str(error), file=sys.stderr)
        return 2
    except SftpgoError:
        print('Service failed: SFTPGO_RUNTIME_CREDENTIALS_OR_CONFIGURATION_UNAVAILABLE', file=sys.stderr)
        return 2
    except Exception:
        # No paths, credentials, event data, SDK errors, or upstream bodies.
        print('Service failed: DATABASE_SDK_BINDING_OR_LISTENER_UNAVAILABLE', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
