"""Execute the production reentry and authorization methods with deterministic async ports."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

HERE = Path(__file__).resolve().parents[1]
SRC = HERE.parent / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (SRC / 'MainActivity.java').read_text(encoding='utf-8')


def declaration(signature):
    start = MAIN.index(signature)
    opening = MAIN.index('{', start)
    return MAIN[start:opening] + body(MAIN, signature)


class RootReentryHostExecution(unittest.TestCase):
    def test_explicit_reentry_probes_and_respects_guards(self):
        methods = '\n'.join(declaration(s) for s in (
            'private void requestRootAuthorization()', 'void m20xb2d4905('))
        fixture = (HERE / 'fixtures/root-reentry-host.java.txt').read_text(encoding='utf-8')
        with tempfile.TemporaryDirectory(prefix='pdsync-root-reentry-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            source.write_text(fixture.replace('/* PRODUCTION_METHODS */', methods), encoding='utf-8')
            java = os.environ.get('JAVA_BIN', 'java')
            result = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                '-sourcepath', str(HERE.parents[2] / 'core/src/main/java'), '-d', tmp,
                str(SRC / 'GameSetupFlow.java'), str(source)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            result = subprocess.run([java, '-cp', tmp, 'io.github.xyz1001.pdsync.android.MainActivity'],
                capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('root reentry host checks passed', result.stdout)

    def test_background_refresh_does_not_trigger_probe(self):
        for signature in ('public void refreshReadiness()', 'Object m68x764cc8a4(', 'private void readRootReadiness()'):
            code = body(MAIN, signature)
            for forbidden in ('requestRootAuthorization(', 'probeRootAuthorization(', 'RootSetupAuthorization.probe('):
                self.assertNotIn(forbidden, code)


if __name__ == '__main__':
    unittest.main()
