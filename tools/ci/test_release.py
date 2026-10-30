import os
from pathlib import Path
import tempfile
from unittest import TestCase, main
from unittest.mock import patch
import release

class ReleaseGateTests(TestCase):
    def test_verify_only_signs_checks_all_packages_without_github_access(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            out=root/'dist';out.mkdir()
            pin='a'*64
            (root/'tools/ci').mkdir(parents=True)
            (root/'tools/ci/android-signer.sha256').write_text(pin)
            for suffix in ('android.apk','windows-x64.exe','windows-x64-portable.zip','linux-x64.deb','linux-x64.pkg.tar.zst','darwin-arm64.dmg'):
                (out/('PDSync-1.0.0-'+suffix)).write_bytes(b'fixture')
            def external(*args,**kwargs):
                if 'badging' in args: return "versionCode='1' versionName='1.0.0'"
                if 'verify' in args: return 'Signer #1 certificate SHA-256 digest: '+pin
                self.assertNotEqual('gh',str(args[0]))
            env=dict(ANDROID_HOME=directory,PDSYNC_KEYSTORE_BASE64='Zml4dHVyZQ==',PDSYNC_KEY_ALIAS='fixture',PDSYNC_STORE_PASSWORD='fixture',PDSYNC_KEY_PASSWORD='fixture')
            with patch.dict(os.environ,env,clear=True),patch.object(release,'ROOT',root),patch.object(release,'version',return_value=dict(name='1.0.0',code=1,prerelease=False)),patch.object(release,'run',side_effect=external) as calls,patch.object(release,'release_exists') as lookup:
                release.main(verify_only=True)
            lookup.assert_not_called()
            self.assertEqual(6,len((out/'SHA256SUMS.txt').read_text().splitlines()))
            self.assertTrue(any(root/'tools/ci/SignApk.java' in c.args for c in calls.call_args_list))
            self.assertFalse(any('fixture' in c.args for c in calls.call_args_list))
            self.assertEqual(2,sum('verify' in c.args for c in calls.call_args_list))

    def test_missing_signer_stops_before_external_commands(self):
        with patch.dict(os.environ,{'RELEASE_TAG':'v'+release.version()['name']},clear=True), patch.object(release,'run') as run:
            with self.assertRaisesRegex(SystemExit,'Missing required signing secret'):
                release.main()
            run.assert_not_called()
    def test_wrong_tag_stops_before_external_commands(self):
        with patch.dict(os.environ,{'RELEASE_TAG':'v99.99.99'},clear=True), patch.object(release,'run') as run:
            with self.assertRaises(ValueError): release.main()
            run.assert_not_called()
    def test_ambiguous_release_lookup_does_not_create(self):
        with patch.dict(os.environ,{'GH_REPO':'example/project'}), patch.object(release.subprocess,'run') as run:
            run.return_value.returncode=1
            run.return_value.stderr='network timeout'
            with self.assertRaisesRegex(RuntimeError,'no publication attempted'):
                release.release_exists('v1.0.0')
    def test_missing_release_is_explicit_404(self):
        with patch.dict(os.environ,{'GH_REPO':'example/project'}), patch.object(release.subprocess,'run') as run:
            run.return_value.returncode=1
            run.return_value.stderr='HTTP 404'
            self.assertFalse(release.release_exists('v1.0.0'))

if __name__=='__main__': main()
