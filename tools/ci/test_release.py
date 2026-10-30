import os
from unittest import TestCase, main
from unittest.mock import patch
import release

class ReleaseGateTests(TestCase):
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
