import importlib.util
import contextlib
import io
import os
from pathlib import Path
import shlex
import shutil
import tempfile
import unittest
from unittest.mock import Mock, patch
import subprocess

spec = importlib.util.spec_from_file_location('desktop_package', Path(__file__).with_name('desktop-package.py'))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class LinuxImageLayoutTest(unittest.TestCase):
    def image(self, root, name):
        app = root / name
        (app / 'lib/runtime/bin').mkdir(parents=True)
        (app / 'lib/runtime/bin/java').touch()
        (app / 'bin').mkdir()
        (app / 'bin' / name).touch()
        return app

    def test_lowercase_linux_package_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'pdsync')
            self.assertEqual(app, package.linux_image(root))
            self.assertEqual(app / 'bin/pdsync', package.linux_launcher(app))

    def test_title_case_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'PDSync')
            self.assertEqual(app, package.linux_image(root))

    def test_missing_runtime_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(AssertionError):
                package.linux_image(Path(directory))

    def test_ambiguous_images_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.image(root, 'pdsync')
            other = root / 'extra'
            (other / 'lib/runtime/bin').mkdir(parents=True)
            (other / 'lib/runtime/bin/java').touch()
            with self.assertRaises(AssertionError):
                package.linux_image(root)

    def test_timeout_retains_partial_output_and_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'pdsync')
            process = Mock(pid=12345)
            process.wait.side_effect = [subprocess.TimeoutExpired('launcher', 90), 0]
            def launch(command, **kwargs):
                kwargs['stdout'].write('PDSYNC_UI_SMOKE_OK\n')
                (root / 'test-xvfb.txt').write_text('xvfb timeout\n')
                return process
            printed = io.StringIO()
            with patch.object(package, 'run'), patch.object(package.subprocess, 'Popen', side_effect=launch), \
                 patch.object(package, 'linux_timeout_diagnostics') as diagnostics, \
                 patch.object(package.os, 'killpg', create=True) as killpg, \
                 patch.object(package.signal, 'SIGKILL', 9, create=True), contextlib.redirect_stdout(printed):
                with self.assertRaises(subprocess.TimeoutExpired):
                    package.smoke(app, 'Linux', root, 'test')
                killpg.assert_called_once_with(12345, package.signal.SIGKILL)
            self.assertEqual('PDSYNC_UI_SMOKE_OK\n', (root / 'test-smoke.txt').read_text())
            self.assertIn('PDSYNC_UI_SMOKE_OK', printed.getvalue())
            self.assertIn('xvfb timeout', printed.getvalue())
            diagnostics.assert_called_once()

    def test_nonzero_exit_retains_output_and_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'pdsync')
            process = Mock()
            process.wait.return_value = 1
            def launch(command, **kwargs):
                kwargs['stdout'].write('startup error\n')
                (root / 'test-xvfb.txt').write_text('xvfb error\n')
                return process
            printed = io.StringIO()
            with patch.object(package, 'run'), patch.object(package.subprocess, 'Popen', side_effect=launch), \
                 contextlib.redirect_stdout(printed):
                with self.assertRaises(subprocess.CalledProcessError):
                    package.smoke(app, 'Linux', root, 'test')
            self.assertEqual('startup error\n', (root / 'test-smoke.txt').read_text())
            self.assertIn('startup error', printed.getvalue())
            self.assertIn('xvfb error', printed.getvalue())

    def test_jcmd_falls_back_to_java_home_then_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'pdsync')
            java = app / 'lib/runtime/bin/java'
            jcmd = root / 'jdk/bin/jcmd'
            jcmd.parent.mkdir(parents=True)
            jcmd.touch()
            for home, expected in [(str(jcmd.parent.parent), str(jcmd)), ('', '/path/jcmd')]:
                results = [Mock(stdout='PID PPID PGID COMMAND\n43 1 42 pdsync\n'),
                           Mock(stdout='43 pdsync\n'), Mock(stdout='thread dump\n')]
                with self.subTest(home=home), patch.dict(os.environ, {'JAVA_HOME': home}), \
                     patch.object(package.shutil, 'which', return_value='/path/jcmd'), \
                     patch.object(package.subprocess, 'run', side_effect=results) as run:
                    package.linux_timeout_diagnostics(Mock(pid=42), java, root, 'test')
                self.assertEqual([expected, '-l'], run.call_args_list[1].args[0])
                self.assertEqual([expected, '43', 'Thread.print'], run.call_args_list[2].args[0])
                self.assertIn('thread dump', (root / 'test-smoke-diagnostics.txt').read_text())

    @unittest.skipUnless(os.name == 'posix' and shutil.which('bash'), 'requires POSIX bash')
    def test_pacman_failure_prints_logs_and_preserves_exit(self):
        script = Path(__file__).with_name('pacman.sh').read_text()
        block = script[script.index('  if runuser -u builder -- env PDSYNC_SMOKE=1'):]
        block = block[:block.index('\n  grep PDSYNC_UI_SMOKE_OK')]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pacman-xvfb.txt').write_text('xvfb failure\n')
            first, rest = block.split('\n', 1)
            block = 'if bash -c "echo smoke failure; exit 124" > ' + \
                shlex.quote(str(root / 'pacman-smoke.txt')) + ' 2>&1; then\n' + rest
            block = block.replace('/work/pacman-smoke.txt', shlex.quote(str(root / 'pacman-smoke.txt')))
            block = block.replace('/work/pacman-xvfb.txt', shlex.quote(str(root / 'pacman-xvfb.txt')))
            result = subprocess.run(['bash', '-euc', block], text=True, capture_output=True)
            self.assertEqual(124, result.returncode)
            self.assertIn('smoke failure', result.stdout)
            self.assertIn('xvfb failure', result.stdout)

    def test_linux_uses_software_and_separate_xvfb_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'pdsync')
            process = Mock()
            process.wait.return_value = 0
            def launch(command, **kwargs):
                self.assertEqual(['xvfb-run', '-a', '-e', str(root / 'test-xvfb.txt'),
                                  str(app / 'bin/pdsync')], command)
                self.assertEqual('1', kwargs['env']['PDSYNC_SMOKE'])
                self.assertIn('-Dskiko.renderApi=SOFTWARE', kwargs['env']['JAVA_TOOL_OPTIONS'])
                self.assertIn('-Dpdsync.workspace=', kwargs['env']['JAVA_TOOL_OPTIONS'])
                self.assertTrue(kwargs['start_new_session'])
                kwargs['stdout'].write('PDSYNC_UI_SMOKE_OK\n')
                return process
            with patch.object(package, 'run'), patch.object(package.subprocess, 'Popen', side_effect=launch):
                package.smoke(app, 'Linux', root, 'test')
            self.assertEqual('PDSYNC_UI_SMOKE_OK\n', (root / 'test-smoke.txt').read_text())

    def test_missing_marker_fails_and_retains_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.image(root, 'pdsync')
            process = Mock()
            process.wait.return_value = 0
            def launch(command, **kwargs):
                kwargs['stdout'].write('no marker\n')
                return process
            with patch.object(package, 'run'), patch.object(package.subprocess, 'Popen', side_effect=launch):
                with self.assertRaises(AssertionError):
                    package.smoke(app, 'Linux', root, 'test')
            self.assertEqual('no marker\n', (root / 'test-smoke.txt').read_text())
