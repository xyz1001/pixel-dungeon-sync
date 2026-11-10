"""Compile production read/finally/scope checks against deterministic I/O ports."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_embedded_install_contract import body

HERE = Path(__file__).resolve().parents[1]
SRC = HERE.parent / 'main/java/io/github/xyz1001/pdsync/android'
MAIN = (SRC / 'MainActivity.java').read_text()

class HomeHistoryReadExecution(unittest.TestCase):
    def test_read_terminal_and_stale_scopes(self):
        methods = []
        for signature in ('private void readComparison()', 'private void readComparison(boolean prepareNamespace)', 'private void readComparison(boolean prepareNamespace, boolean allowHomePreview)', 'private void requireCurrentArchiveRead(', 'public void rename(final String snapshotId)', 'private void renameCurrentInline()', 'public void editCurrent()', 'private AndroidSyncFacade sync()', 'private AndroidSyncFacade localSync()', 'private AndroidSyncFacade requireSyncFacade()', 'private void requireOfficialSyncAccess(SecureConnectionStore.Connection account)', 'private OfficialOperationLease.Binding officialOperationBinding(', 'private boolean ownsHomeNames(', 'private SyncHistory displayHomeHistory()', 'private void saveHomeName('):
            start = MAIN.index(signature)
            methods.append(MAIN[start:MAIN.index('{', start)] + body(MAIN, signature))
        with tempfile.TemporaryDirectory(prefix='pdsync-history-read-') as tmp:
            source = Path(tmp) / 'MainActivity.java'
            overlay = 'private static final class HomeNameOverlay ' + body(MAIN, 'private static final class HomeNameOverlay') + '\nprivate HomeNameOverlay homeNameOverlay; private long homeNameEpoch;'
            source.write_text((HERE / 'fixtures/home-history-read-host.java.txt').read_text().replace('/* PRODUCTION_METHODS */', overlay + '\n' + '\n'.join(methods)))
            java = os.environ.get('JAVA_BIN', 'java')
            compile = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-d', tmp, str(source), str(SRC / 'HomeArchiveReadState.java'), str(SRC / 'OfficialOperationLease.java')], capture_output=True, text=True)
            self.assertEqual(0, compile.returncode, compile.stderr)
            run = subprocess.run([java, '-cp', tmp, 'io.github.xyz1001.pdsync.android.MainActivity'], capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
