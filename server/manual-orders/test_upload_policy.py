from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from dataclasses import replace
from pathlib import Path
import copy
import sqlite3
import tempfile
import threading
import unittest

from upload_policy import AccountSnapshot, UploadPolicy, PolicyError, READ, WRITE, new_account_policy


class FencedFake:
    atomic_fenced_permissions = True

    def __init__(self, permissions=None):
        self.account = AccountSnapshot('alice', '0', permissions or {'/': ['*']},
                                       effective_permissions_verified=True)
        self.generation = 0
        self.lock = threading.Lock()
        self.calls = 0
        self.fail_after = False
        # Not exposed via AccountSnapshot or passed in a policy patch.
        self.password = 'synthetic-test-only-password'
        self.unmanaged = {'quota_size': 123456, 'home_dir': 'synthetic-private-directory'}

    def read(self, owner):
        with self.lock:
            return copy.deepcopy(self.account)

    def apply_permissions(self, owner, expected_revision, generation, permissions):
        with self.lock:
            self.calls += 1
            if generation <= self.generation or expected_revision != self.account.revision:
                raise PolicyError('STALE_WRITE')
            self.account = replace(self.account, permissions=copy.deepcopy(permissions),
                                   revision=str(int(self.account.revision) + 1))
            self.generation = generation
            if self.fail_after:
                self.fail_after = False
                raise TimeoutError('unknown result after applied')


class UploadPolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'policy.sqlite'
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('CREATE TABLE entitlements(owner TEXT PRIMARY KEY, expires_ms INTEGER NOT NULL)')
            db.execute('INSERT INTO entitlements VALUES(?,?)', ('alice', 1000))
            db.commit()
        self.now = 1000
        self.fake = FencedFake()
        self.worker = self.make()
        self.worker.enroll('alice')

    def make(self, **kwargs):
        options = dict(adapter=self.fake, mode='sandbox', clock=lambda: self.now)
        options.update(kwargs)
        return UploadPolicy(self.path, **options)

    def renew(self, expiry):
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('UPDATE entitlements SET expires_ms=? WHERE owner=?', (expiry, 'alice'))
            db.commit()

    def test_disabled_default_and_no_plain_put_adapter(self):
        disabled = UploadPolicy(self.path)
        with self.assertRaisesRegex(PolicyError, 'UPLOAD_POLICY_DISABLED'):
            disabled.reconcile('alice')
        with self.assertRaisesRegex(PolicyError, 'LIVE_POLICY_UNAVAILABLE'):
            self.make(mode='live')
        self.fake.atomic_fenced_permissions = False
        self.assertFalse(self.worker.dry_run('alice')['executable'])
        with self.assertRaisesRegex(PolicyError, 'ATOMIC_FENCING_REQUIRED'):
            self.worker.reconcile('alice')
        self.assertEqual(0, self.fake.calls)

    def test_dryrun_is_readonly_and_never_exposes_private_account_fields(self):
        result = self.worker.dry_run('alice')
        self.assertEqual({'/': sorted(READ)}, result['after'])
        self.assertEqual(0, self.fake.calls)
        self.assertNotIn(self.fake.password, repr(result))
        self.assertNotIn('synthetic-private-directory', repr(result))
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM upload_policy_jobs').fetchone()[0])

    def test_expiry_preserves_login_password_and_unmanaged_fields(self):
        before = self.fake.password, copy.deepcopy(self.fake.unmanaged)
        result = self.worker.reconcile('alice')
        self.assertFalse(result['active'])  # equality means expired
        account = self.fake.read('alice')
        self.assertEqual(1, account.status)
        self.assertEqual(0, account.expiration_date)
        self.assertEqual({'/': ['download', 'list']}, account.permissions)
        self.assertEqual(before, (self.fake.password, self.fake.unmanaged))

    def test_expired_permissions_remove_all_write_families(self):
        self.worker.reconcile('alice')
        allowed = set(self.fake.read('alice').permissions['/'])
        # Map the exact-version native checks; this is not a live DAV test.
        method_permissions = {
            'PUT-new': {'upload'}, 'PUT-overwrite': {'overwrite'},
            'MKCOL': {'create_dirs'}, 'MOVE': {'rename', 'rename_files', 'rename_dirs'},
            'COPY-new-or-overwrite': {'upload', 'overwrite', 'copy', 'create_dirs'},
            'DELETE': {'delete', 'delete_files', 'delete_dirs'},
            'PROPPATCH-time': {'chtimes'}, 'symlink': {'create_symlinks'},
            'temporary-to-final': {'rename', 'rename_files', 'rename_dirs', 'upload', 'overwrite'},
        }
        for method, needed in method_permissions.items():
            self.assertFalse(allowed & needed, method)
        self.assertFalse(allowed & WRITE)
        self.assertEqual(READ, allowed)

    def test_renew_restores_exact_original_subset_without_widening(self):
        # Reuse the ledger but use another owner/baseline in the same DB.
        self.fake.account = replace(self.fake.account, username='bob',
                                    permissions={'/': ['download', 'list', 'upload']})
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('INSERT INTO entitlements VALUES(?,?)', ('bob', 1000))
            db.commit()
        self.worker.enroll('bob')
        self.worker.reconcile('bob')
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('UPDATE entitlements SET expires_ms=2000 WHERE owner=?', ('bob',))
            db.commit()
        self.worker.reconcile('bob')
        self.assertEqual({'/': ['download', 'list', 'upload']}, self.fake.read('bob').permissions)
        self.assertNotIn('overwrite', self.fake.read('bob').permissions['/'])

    def test_groups_subpaths_wildcards_unverified_and_login_restrictions_fail_closed(self):
        initial = self.fake.account
        cases = (
            replace(initial, groups=('inherited-writer',)),
            replace(initial, virtual_folders=('shared',)),
            replace(initial, permissions={'/': ['list','download'], '/child': ['*']}),
            replace(initial, permissions={'/': ['list','download'], '/*': ['*']}),
            replace(initial, effective_permissions_verified=False),
            replace(initial, expiration_date=900), replace(initial, status=0),
            replace(initial, permissions={'/': ['list', 'download', 'unknown-write']}),
            replace(initial, permissions={'/': ['upload']}),
        )
        for snapshot in cases:
            with self.subTest(snapshot=snapshot):
                self.fake.account = snapshot
                with self.assertRaises(PolicyError):
                    self.worker.reconcile('alice')
        self.assertEqual(0, self.fake.calls)

    def test_external_permission_change_is_not_overwritten(self):
        self.fake.account = replace(self.fake.account, permissions={'/': ['download', 'list', 'copy']})
        with self.assertRaisesRegex(PolicyError, 'EXTERNAL_PERMISSION_CHANGE'):
            self.worker.reconcile('alice')
        self.assertEqual(0, self.fake.calls)

    def test_unknown_remote_outcome_retry_rereads_new_entitlement_and_generation(self):
        self.fake.fail_after = True
        with self.assertRaisesRegex(PolicyError, 'POLICY_RETRY_REQUIRED'):
            self.worker.reconcile('alice')
        self.assertEqual(sorted(READ), self.fake.account.permissions['/'])
        self.renew(2000)
        result = self.make().reconcile('alice')
        self.assertTrue(result['active'])
        self.assertEqual({'/': ['*']}, self.fake.account.permissions)
        self.assertEqual(2, result['generation'])

    def test_delayed_expiry_request_cannot_overwrite_new_renewal_policy(self):
        started, release = threading.Event(), threading.Event()
        original = self.fake.apply_permissions
        def delayed(owner, rev, generation, permissions):
            if generation == 1:
                started.set()
                if not release.wait(5):
                    raise TimeoutError()
            return original(owner, rev, generation, permissions)
        self.fake.apply_permissions = delayed
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(self.worker.reconcile, 'alice')
            try:
                self.assertTrue(started.wait(2))
                self.renew(2000)
                result = self.make().reconcile('alice')
                self.assertEqual(2, result['generation'])
            finally:
                release.set()
            with self.assertRaises(PolicyError):
                pending.result()
        self.assertEqual({'/': ['*']}, self.fake.account.permissions)

    def test_account_revision_change_between_read_and_write_blocks_mutation(self):
        original = self.fake.apply_permissions
        def intervene(owner, rev, generation, permissions):
            self.fake.account = replace(self.fake.account, revision='99', groups=('new-group',))
            return original(owner, rev, generation, permissions)
        self.fake.apply_permissions = intervene
        with self.assertRaises(PolicyError):
            self.worker.reconcile('alice')
        self.assertEqual({'/': ['*']}, self.fake.account.permissions)

    def test_baseline_cannot_be_recaptured_from_expired_permissions(self):
        self.worker.reconcile('alice')
        with self.assertRaisesRegex(PolicyError, 'ALREADY_MANAGED'):
            self.make().enroll('alice')
        self.renew(3000)
        self.make().reconcile('alice')
        self.assertEqual({'/': ['*']}, self.fake.account.permissions)

    def test_no_ledger_entry_or_unknown_account_never_invents_free_entitlement(self):
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('DELETE FROM entitlements WHERE owner=?', ('alice',))
            db.commit()
        result = self.worker.reconcile('alice')
        self.assertFalse(result['active'])
        self.assertEqual({'/': sorted(READ)}, self.fake.account.permissions)

    def test_new_readonly_account_first_donation_expiry_and_renewal(self):
        template = ['list', 'download', 'upload', 'create_dirs']
        plan = new_account_policy(template)
        self.assertEqual({'/': sorted(READ)}, plan['initial_permissions'])
        self.assertNotIn('*', plan['activation_permissions']['/'])
        # Simulate provisioning directly with the pure plan's read-only CREATE
        # permissions. There has never been an unpaid writable remote profile.
        self.fake.account = replace(self.fake.account, username='new-user',
                                    permissions=plan['initial_permissions'])
        self.worker.enroll_new_account('new-user', template)
        self.assertEqual(0, self.fake.calls)
        self.assertFalse(self.worker.dry_run('new-user')['active'])
        self.worker.reconcile('new-user')
        self.assertEqual(plan['initial_permissions'], self.fake.account.permissions)
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('INSERT INTO entitlements VALUES(?,?)', ('new-user', 2000))
            db.commit()
        self.make().reconcile('new-user')
        self.assertEqual(plan['activation_permissions'], self.fake.account.permissions)
        self.now = 2000
        self.worker.reconcile('new-user')
        self.assertEqual(plan['initial_permissions'], self.fake.account.permissions)
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('UPDATE entitlements SET expires_ms=3000 WHERE owner=?', ('new-user',))
            db.commit()
        self.make().reconcile('new-user')
        self.assertEqual(plan['activation_permissions'], self.fake.account.permissions)
        self.assertNotIn('overwrite', self.fake.account.permissions['/'])
        self.assertNotIn('rename', self.fake.account.permissions['/'])

    def test_new_account_rejects_writable_start_star_or_untrusted_shape(self):
        template = ['list', 'download', 'upload']
        self.fake.account = replace(self.fake.account, username='unpaid')
        with self.assertRaisesRegex(PolicyError, 'NEW_ACCOUNT_MUST_START_READ_ONLY'):
            self.worker.enroll_new_account('unpaid', template)
        for bad in (['*'], ['list', 'download'], ['list', 'download', 'upload', '*'],
                    {'/': template}, ['list', 'download', 'unknown']):
            with self.assertRaises(PolicyError):
                new_account_policy(bad)
        self.assertEqual(0, self.fake.calls)

    def test_new_account_template_cannot_be_replaced_by_a_repeat_registration(self):
        template = ['list', 'download', 'upload']
        self.fake.account = replace(self.fake.account, username='new-user', permissions={'/': sorted(READ)})
        self.worker.enroll_new_account('new-user', template)
        with self.assertRaisesRegex(PolicyError, 'ALREADY_MANAGED'):
            self.make().enroll_new_account('new-user', template + ['overwrite'])


if __name__ == '__main__':
    unittest.main()
