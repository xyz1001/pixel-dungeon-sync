from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest

from orders import Principal, OrderError
from write_gate import WriteGate, SqliteEntitlements, READ_METHODS, WRITE_METHODS


class FixtureAuth:
    def authenticate(self, headers):
        # Test-only stand-in for upstream verification; not a deployable auth scheme.
        fixture = {'fixture-alice': 'alice', 'fixture-bob': 'bob'}
        if headers.get('Authorization') not in fixture:
            raise OrderError(401, 'AUTHENTICATION_REQUIRED')
        return Principal(fixture[headers['Authorization']])


class WriteGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'gate.sqlite'
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('CREATE TABLE orders(owner TEXT, target_ms INTEGER, state TEXT)')
            db.execute('INSERT INTO orders VALUES(?,?,?)', ('alice', 1001, 'approved'))
            db.commit()
        self.now = 1000
        self.gate = WriteGate(enabled=True, authenticator=FixtureAuth(),
                              ledger=SqliteEntitlements(self.path), clock=lambda: self.now)
        gate = self.gate
        class Handler(BaseHTTPRequestHandler):
            def handle_gate(self):
                result = gate.decide(self.command, self.headers)
                self.send_response(result.status)
                self.send_header('Content-Length', '0')
                self.end_headers()
            def log_message(self, *args): pass
        for method in READ_METHODS | WRITE_METHODS | {'PATCH'}:
            setattr(Handler, 'do_' + method, Handler.handle_gate)
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, method, auth='fixture-alice', extra=None):
        headers = {'Authorization': auth}
        headers.update(extra or {})
        conn = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        try:
            conn.request(method, '/dav/synthetic', headers=headers)
            response = conn.getresponse()
            response.read()
            return response.status
        finally:
            conn.close()

    def test_expiry_denies_every_mutation_but_keeps_authenticated_reads(self):
        for method in WRITE_METHODS:
            self.assertEqual(204, self.request(method))
        self.now = 1001
        for method in WRITE_METHODS:
            self.assertEqual(403, self.request(method), method)
        for method in READ_METHODS:
            self.assertEqual(204, self.request(method), method)

    def test_no_rights_and_spoofed_identity_or_method_never_grant_upload(self):
        self.assertEqual(403, self.request('PUT', 'fixture-bob', {'X-User': 'alice'}))
        self.assertEqual(401, self.request('PUT', 'invalid', {'X-Authenticated-User': 'alice'}))
        self.assertEqual(401, self.request('GET', 'invalid'))
        self.assertEqual(403, self.request('PUT', 'fixture-bob', {'X-HTTP-Method-Override': 'GET'}))
        self.assertEqual(405, self.request('PATCH'))

    def test_renewal_reads_latest_ledger_without_stale_policy_jobs(self):
        self.now = 2000
        self.assertEqual(403, self.request('MOVE'))
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('UPDATE orders SET target_ms=3000 WHERE owner=?', ('alice',))
            db.commit()
        self.assertEqual(204, self.request('MOVE'))

    def test_database_failure_denies_writes_but_does_not_block_read_path(self):
        self.gate.ledger = SqliteEntitlements(Path(self.temp.name) / 'missing.sqlite')
        self.assertEqual(503, self.request('PUT'))
        self.assertEqual(204, self.request('GET'))
        self.assertFalse((Path(self.temp.name) / 'missing.sqlite').exists())

    def test_defaults_are_disabled_and_auth_is_not_implicit(self):
        self.assertEqual(503, WriteGate().decide('PUT', {}).status)
        self.assertEqual(401, WriteGate(enabled=True).decide('PUT', {'X-User': 'alice'}).status)


if __name__ == '__main__':
    unittest.main()
