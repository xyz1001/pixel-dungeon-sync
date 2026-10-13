"""Explicit loopback-only HTTP adapter for sandbox order integration.

No command-line launcher, default listener, authentication implementation,
production SFTPGo adapter, or real payment fixture is supplied.
"""
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re


@dataclass(frozen=True)
class SandboxDisplay:
    display_name: str
    instructions: str
    image_png: bytes

    def validate(self):
        if not self.display_name or not self.instructions:
            raise ValueError('explicit sandbox display and instructions required')
        if not isinstance(self.image_png, bytes) or not self.image_png.startswith(b'\x89PNG\r\n\x1a\n') or len(self.image_png) > 256 * 1024:
            raise ValueError('explicit bounded sandbox PNG required')


def create_server(service, *, enabled=False, host='127.0.0.1', port=0, displays=None):
    """Construct, but do not start, an explicit loopback listener.

    Caller owns serve_forever()/shutdown()/server_close(). Identity is always
    resolved by service.auth; no header or JSON field is interpreted as identity
    by this module. Refuse browser-origin/cookie requests: no CSRF session host.
    """
    if not enabled:
        raise ValueError('HTTP listener is disabled')
    if host != '127.0.0.1':
        raise ValueError('sandbox HTTP binds only to literal IPv4 loopback')
    displays = dict(displays or {})
    for key, display in displays.items():
        if not re.fullmatch(r'[a-z0-9_-]{1,48}', key) or not isinstance(display, SandboxDisplay):
            raise ValueError('invalid sandbox display')
        display.validate()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'

        def log_message(self, *args):
            # Do not log credentials, URL parameters, request bodies or receipts.
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def send(self, status, payload, content_type='application/json; charset=utf-8'):
            raw = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode('utf8')
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(raw)

        def error(self, status, code):
            self.send(status, {'error': code})

        def handle_request(self):
            try:
                if self.headers.get('Origin') is not None or self.headers.get('Cookie') is not None:
                    return self.error(403, 'BROWSER_SESSION_UNSUPPORTED')
                expected_host = f'127.0.0.1:{self.server.server_address[1]}'
                if self.headers.get_all('Host') != [expected_host]:
                    return self.error(400, 'INVALID_HOST')
                if self.headers.get('Transfer-Encoding') is not None:
                    return self.error(400, 'INVALID_REQUEST')
                lengths = self.headers.get_all('Content-Length', [])
                if len(lengths) > 1 or (lengths and not re.fullmatch(r'[0-9]{1,6}', lengths[0])):
                    return self.error(400, 'INVALID_REQUEST')
                length = int(lengths[0]) if lengths else 0
                if length > 4096:
                    return self.error(413, 'REQUEST_TOO_LARGE')
                if '?' in self.path or '#' in self.path or '%' in self.path:
                    return self.error(400, 'INVALID_REQUEST')
                if self.command not in ('GET', 'POST'):
                    return self.error(405, 'METHOD_NOT_ALLOWED')
                body = None
                if self.command == 'POST':
                    if self.headers.get_content_type() != 'application/json':
                        return self.error(415, 'JSON_REQUIRED')
                    raw = self.rfile.read(length)
                    if len(raw) != length:
                        return self.error(400, 'INVALID_REQUEST')
                    def unique_object(pairs):
                        obj = {}
                        for k, v in pairs:
                            if k in obj:
                                raise ValueError('duplicate key')
                            obj[k] = v
                        return obj
                    body = json.loads(raw, object_pairs_hook=unique_object)
                elif length:
                    return self.error(400, 'INVALID_REQUEST')
                image_prefix = '/api/order/payment-image/'
                is_image = self.path.startswith(image_prefix)
                route = '/api/order/plans' if is_image else self.path
                if service.mode == 'sandbox' and any(key not in displays for key in service.plans):
                    return self.error(503, 'SANDBOX_DISPLAY_REQUIRED')
                status, result = service.dispatch(self.command, route, self.headers, body)
                if status != 200:
                    return self.send(status, result)
                if route == '/api/order/plans':
                    if any(p['id'] not in displays for p in result['data']):
                        return self.error(503, 'SANDBOX_DISPLAY_REQUIRED')
                    if is_image:
                        plan_id = self.path[len(image_prefix):]
                        if plan_id not in {p['id'] for p in result['data']}:
                            return self.error(404, 'NOT_FOUND')
                        return self.send(200, displays[plan_id].image_png, 'image/png')
                    for plan in result['data']:
                        display = displays[plan['id']]
                        plan.update(display_name=display.display_name,
                                    payment_instructions=display.instructions,
                                    payment_image_path=image_prefix + plan['id'])
                self.send(status, result)
            except (ValueError, UnicodeError):
                self.error(400, 'INVALID_JSON')
            except (TimeoutError, ConnectionError, OSError):
                self.close_connection = True
            except Exception:
                self.error(503, 'SERVICE_UNAVAILABLE')

        do_GET = handle_request
        do_POST = handle_request
        do_PUT = handle_request
        do_DELETE = handle_request
        do_PATCH = handle_request
        do_OPTIONS = handle_request
        do_HEAD = handle_request

    return ThreadingHTTPServer((host, port), Handler)
