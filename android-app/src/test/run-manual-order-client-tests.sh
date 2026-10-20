#!/usr/bin/env bash
# Host-only contract tests: all transports/identities/images are synthetic; no sockets.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
SRC="$REPO/android-app/src/main/java/io/github/xyz1001/pdsync/android"
JAVA=${JAVA_BIN:-java}
RUNTIME=${PDSYNC_RUNTIME_LIB:?Set PDSYNC_RUNTIME_LIB to the existing pinned runtime jars}
CORE=${PDSYNC_CORE_JAR:-$REPO/core/build/libs/core.jar}
if [[ ! -f "$CORE" ]]; then
  echo "Build the current core first: ./gradlew :core:jar" >&2
  exit 1
fi
OUT=$(mktemp -d "${TMPDIR:-/tmp}/pdsync-manual-order.XXXXXXXX")
trap 'rm -rf "$OUT"' EXIT
# Exercise the checked-in HTTP adapter's response production entirely in memory.
# Even socket construction is denied; its server constructor is a handler-capture stub.
python3 - "$REPO/server/manual-orders" "$OUT/server-order-fixtures.jsonl" <<'PY'
import email.message
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import types
from unittest.mock import patch

sys.dont_write_bytecode = True
root = pathlib.Path(sys.argv[1])
def load(name):
    spec = importlib.util.spec_from_file_location(name, root / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module

with patch('socket.socket', side_effect=AssertionError('Host tests must not open sockets')):
    orders = load('orders')
    http_api = load('http_api')
    class Auth:
        def authenticate(self, headers):
            return orders.Principal('synthetic-fixture-owner')
    class Ledger:
        expiry = None
        fail_once = True
        def ensure(self, username, expires_at_ms, operation_id):
            if self.fail_once:
                self.fail_once = False
                raise RuntimeError("synthetic first application is unresolved")
            self.expiry = expires_at_ms
        def observed_expiry(self, username):
            return self.expiry
    plan = orders.Plan('fixture_year', quota_bytes=1, upload_kbps=1, download_kbps=1)
    display = http_api.SandboxDisplay('仅沙箱 🧪', '合成测试，没有真实收款', b'\x89PNG\r\n\x1a\nfixture')
    fixtures = []
    with tempfile.TemporaryDirectory(prefix='pdsync-orders-host-') as temp:
        service = orders.Orders(pathlib.Path(temp) / 'sandbox.db', [plan], mode='sandbox',
                                authenticator=Auth(), entitlements=Ledger(), clock=lambda: 1700000000000)
        with patch.object(http_api, 'ThreadingHTTPServer', side_effect=lambda addr, handler: handler):
            handler = http_api.create_server(service, enabled=True, port=18080, displays={plan.id: display})
        def invoke(method, path, body=None):
            instance = handler.__new__(handler)
            instance.command, instance.path = method, path
            instance.server = types.SimpleNamespace(server_address=('127.0.0.1', 18080))
            instance.headers = email.message.Message()
            instance.headers['Host'] = '127.0.0.1:18080'
            encoded = b'' if body is None else json.dumps(body).encode('utf-8')
            instance.headers['Content-Length'] = str(len(encoded))
            if body is not None:
                instance.headers['Content-Type'] = 'application/json'
            instance.rfile = io.BytesIO(encoded)
            captured = []
            instance.send = lambda status, payload, content_type=None: captured.append((status, payload))
            instance.handle_request()
            assert len(captured) == 1
            status, payload = captured[0]
            assert status == 200, payload
            return payload
        fixtures.append(invoke('GET', '/api/order/plans'))
        created = invoke('POST', '/api/order/create', {'plan_id': plan.id, 'request_key': 'synthetic-stable-key-001'})
        oid = created['data']['id']
        fixtures.append(invoke('GET', '/api/order/mine'))
        fixtures.append(invoke('POST', '/api/order/submit', {'order_id': oid}))
        fixtures.append(invoke('GET', '/api/order/entitlement'))
        # Pure in-memory fixture approval, not an HTTP administrator call or real payment.
        admin = orders.Principal('synthetic-fixture-admin', 'admin')
        try:
            service.approve(admin, oid, 'synthetic-receipt', 'sandbox', 123, 'CNY')
            raise AssertionError('synthetic adapter should require a retry')
        except orders.OrderError as error:
            assert error.status == 503 and error.code == 'ENTITLEMENT_RETRY_REQUIRED'
        applying = invoke('GET', '/api/order/status/' + oid)
        assert applying['data']['state'] == 'applying'
        service.retry(admin, oid)
        fixtures.append(invoke('GET', '/api/order/status/' + oid))
        fixtures.append(invoke('GET', '/api/order/entitlement'))
        next_order = invoke('POST', '/api/order/create', {'plan_id': plan.id, 'request_key': 'synthetic-stable-key-002'})
        cancelled = invoke('POST', '/api/order/cancel', {'order_id': next_order['data']['id']})
        fixtures.append({'sandbox': True, 'data': [cancelled['data']]})
        service.auth = orders.DenyAuthentication()
        status, result = service.dispatch('GET', '/api/order/plans')
        assert status == 401
        fixtures.append(result)
        service.mode = 'disabled'
        status, result = service.dispatch('GET', '/api/order/plans')
        assert status == 503
        fixtures.append(result)
        fixtures.append(applying)
    pathlib.Path(sys.argv[2]).write_text('\n'.join(json.dumps(value, ensure_ascii=False) for value in fixtures) + '\n', encoding='utf-8')
PY
CP="$CORE:$(find -L "$RUNTIME" -maxdepth 1 -name '*.jar' | sort | paste -sd:)"
"$JAVA" -m jdk.compiler/com.sun.tools.javac.Main -sourcepath "$REPO/core/src/main/java" -source 17 -target 17 -encoding UTF-8 -cp "$CP" -d "$OUT" \
  "$SRC/ManualOrderClient.java" \
  "$HERE/java/io/github/xyz1001/pdsync/android/ManualOrderClientChecks.java"
"$JAVA" -Duser.language=zh -Duser.country=CN -cp "$OUT:$CP" io.github.xyz1001.pdsync.android.ManualOrderClientChecks "$OUT/server-order-fixtures.jsonl"
