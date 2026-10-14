"""Real HTTP/SQLite/client integration, with a synthetic SFTPGo REST provider.

No production account, real password, phone or actual SFTPGo daemon is used.
"""
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
import io
import json
from pathlib import Path
from types import SimpleNamespace
import threading
import unittest
from unittest.mock import patch

import service
from ledger_gate import ManagedLedger
from orders import OrderError, extend_ms
from registration_trials import TRIAL_MS
from runtime_config import RuntimeConfig
import test_integration


class NativeServiceTests(unittest.TestCase):
    request = test_integration.ManagedIntegrationTests.request
    account = test_integration.ManagedIntegrationTests.account
    gate = test_integration.ManagedIntegrationTests.gate
    entitlement = test_integration.ManagedIntegrationTests.entitlement
    stop_server = test_integration.ManagedIntegrationTests.stop_server

    def setUp(self):
        test_integration.ManagedIntegrationTests.setUp(self)
        self.stop_server()
        self.upstream.user_token_clock = lambda: self.now
        self.client = self.upstream.client(native_expiry_enabled=True,
            no_external_account_writers=True, clock=lambda: self.now)
        self.ledger = ManagedLedger(self.path, accounts=self.client, enabled=True,
            native_expiry=True, clock=lambda: self.now)
        self.config = replace(self.config, account_expiry={'enabled': True, 'single_writer': True})
        self.start_native()

    def start_native(self):
        self.server = service.create_server(service.ManagedApplication(self.config,
            accounts=self.client, ledger=self.ledger, clock=lambda: self.now), port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def renew(self, message='first-renewal', username='alice'):
        return self.ledger.renew(app_id='fixture-app', tenant_key='fixture-tenant',
            message_id=message, chat_id='fixture-chat', sender_id='fixture-admin', username=username)

    def test_register_expire_all_methods_renew_and_original_password_restored(self):
        self.ledger.assert_native_migration_ready()
        self.assertEqual(200, self.account()[0])
        initial = self.entitlement()
        self.assertEqual(self.now + TRIAL_MS, initial['expires_ms'])
        self.assertEqual(self.now, initial['server_time_ms'])
        remote = self.upstream.accounts['alice']
        self.assertEqual(initial['expires_ms'], remote['expiration_date'])
        original_password = self.upstream.passwords['alice']
        original_home = remote['home_dir']
        original_id = remote['id']
        self.assertEqual(204, self.gate('GET'))
        self.assertEqual(204, self.gate('PUT'))
        self.now = initial['expires_ms'] + 1  # Native SFTPGo compares expiry < now.
        for method in ('GET', 'HEAD', 'PROPFIND', 'PUT', 'MKCOL', 'DELETE', 'MOVE'):
            self.assertEqual(401, self.gate(method))
        expired_status, expired_error = self.account('login')
        self.assertEqual(401, expired_status)
        self.assertEqual('INVALID_CREDENTIALS', expired_error['error'])
        self.assertEqual(expired_error, self.account('login', password='wrong-fixture-value')[1])
        self.assertEqual(401, self.request('/api/account/entitlement', username='alice')[0])
        renewed = self.renew()
        self.assertEqual('confirmed', renewed['status'])
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), renewed['target_ms'])
        self.assertEqual(200, self.account('login')[0])
        self.assertTrue(self.entitlement()['active'])
        self.assertEqual(204, self.gate('PUT'))
        self.assertEqual(204, self.gate('GET'))
        self.assertEqual(original_password, self.upstream.passwords['alice'])
        self.assertEqual(original_home, self.upstream.accounts['alice']['home_dir'])
        self.assertEqual(original_id, self.upstream.accounts['alice']['id'])
        writes = len(self.upstream.updates)
        self.assertEqual(renewed['target_ms'], self.renew()['target_ms'])
        self.assertEqual(writes, len(self.upstream.updates))
        self.assertEqual(extend_ms(renewed['target_ms'], 'calendar_year', 0), self.renew('second-renewal')['target_ms'])

    def test_lost_create_response_restarts_and_repairs_original_trial(self):
        self.upstream.drop_create_response_once = True
        self.assertEqual(503, self.account()[0])
        original = self.upstream.accounts['alice']['expiration_date']
        self.now += 60000
        self.stop_server()
        self.ledger = ManagedLedger(self.path, accounts=self.client, enabled=True,
            native_expiry=True, clock=lambda: self.now)
        self.ledger.assert_native_migration_ready()
        self.start_native()
        self.assertEqual(200, self.account()[0])
        self.assertEqual(original, self.entitlement()['expires_ms'])
        self.assertEqual(1, len(self.upstream.creates))

    def test_expired_uncertain_registration_can_be_renewed_by_authorized_message(self):
        self.upstream.drop_create_response_once = True
        self.assertEqual(503, self.account()[0])
        original = self.upstream.accounts['alice']['expiration_date']
        self.now = original + 1
        self.assertEqual(401, self.account()[0])
        self.assertEqual(401, self.account('login')[0])
        result = self.renew('recover-expired-registration')
        self.assertEqual('confirmed', result['status'])
        self.assertEqual(extend_ms(self.now, 'calendar_year', 0), result['target_ms'])
        self.assertEqual(200, self.account('login')[0])
        self.assertEqual(200, self.account()[0])
        with self.ledger.journal.connect() as db:
            trial = db.execute("SELECT created_ms,expires_ms FROM registration_trials WHERE owner='alice'").fetchone()
        self.assertEqual(self.upstream.created_at_ms, trial['created_ms'])
        self.assertEqual(original, trial['expires_ms'])
        self.assertEqual(result['target_ms'], self.entitlement()['expires_ms'])
        self.assertEqual(1, len(self.upstream.creates))

    def test_lost_expiry_put_response_does_not_add_another_year(self):
        self.assertEqual(200, self.account()[0])
        self.upstream.drop_update_response_once = True
        first = self.renew()
        self.assertEqual('confirmed', first['status'])
        self.assertEqual(1, len(self.upstream.updates))
        self.assertEqual(first['target_ms'], self.renew()['target_ms'])
        self.assertEqual(1, len(self.upstream.updates))

    def test_nonmanaged_account_unmodified_and_manual_suspension_not_reenabled(self):
        self.upstream.put_account('drop', expiration_date=0)
        before = dict(self.upstream.accounts['drop'])
        self.assertEqual('unknown_user', self.renew(username='drop')['status'])
        self.assertEqual(before, self.upstream.accounts['drop'])
        self.assertEqual(0, len(self.upstream.updates))
        self.assertEqual(200, self.account()[0])
        self.upstream.accounts['alice']['status'] = 0
        self.assertEqual('conflict', self.renew('manual-disabled')['status'])
        self.assertEqual(0, self.upstream.accounts['alice']['status'])
        self.assertEqual(401, self.account('login')[0])

    def test_service_start_refuses_migration_gap_before_opening_listener(self):
        fake = SimpleNamespace(ledger=SimpleNamespace(assert_native_migration_ready=lambda: (
            _ for _ in ()).throw(OrderError(503, 'NATIVE_EXPIRY_MIGRATION_REQUIRED'))))
        with patch.object(service, 'build_application', return_value=fake), \
             patch.object(service, 'create_server', side_effect=AssertionError('listener opened')):
            with self.assertRaises(OrderError):
                service.serve(self.config)

    def test_explicit_migration_cli_pages_without_private_output_or_listener(self):
        results = [dict(processed=100, applied=100, failed=0, next_owner='private-account',
                        extra='private-upstream-detail'),
                   dict(processed=1, applied=1, failed=0, next_owner=None)]
        journal = SimpleNamespace(reconcile_native_accounts=lambda **kw: results.pop(0),
                                  assert_native_migration_ready=lambda: None)
        output = io.StringIO()
        with patch.object(RuntimeConfig, 'load', return_value=self.config), \
             patch.object(service, 'build_application', return_value=SimpleNamespace(ledger=journal)), \
             patch.object(service, 'create_server', side_effect=AssertionError('listener opened')), \
             redirect_stdout(output), redirect_stderr(output):
            self.assertEqual(0, service.main(['migrate-native-expiry', '/synthetic/config.json']))
        self.assertNotIn('private', output.getvalue())
        self.assertEqual(0, len(results))
        self.assertIn('migrated', output.getvalue())


if __name__ == '__main__':
    unittest.main()
