import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from native_fixture import Fixture, UnsafeFixture

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('live_agent_desktop', ROOT / 'scripts/test-agent-desktop.py')
desktop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(desktop)


class LiveAgentToolingTest(unittest.TestCase):
    def test_invalid_modes_fail_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'output'
            for flags in [['--live-codex', '/missing/codex'],
                          ['--qualification', '--replay-payload', '/missing/payload'],
                          ['--launch-only', '--qualification'], ['--motion'],
                          ['--animation-demo'], ['--animation-demo', '--git-fixture'],
                          ['--animation-demo', '--git-fixture', '--visual-only', '--qualification'],
                          ['--animation-demo', '--git-fixture', '--visual-only', '--live-codex', '/missing/codex']]:
                result = subprocess.run([sys.executable, str(ROOT / 'scripts/test-agent-desktop.py'),
                                         '--jdk', '/missing/jdk', '--jar', '/missing/jar', '--output', str(output), *flags],
                                        stdin=subprocess.DEVNULL, capture_output=True, timeout=5)
                self.assertEqual(2, result.returncode)
                self.assertFalse(output.exists())

    def test_unexpected_environment_fails_before_authentication_or_execution(self):
        fixture = Fixture()
        try:
            with patch.object(desktop.subprocess, 'Popen') as launch, patch.object(desktop.Path, 'home') as home:
                with self.assertRaisesRegex(UnsafeFixture, 'Unexpected live Codex environment'):
                    desktop.run_codex(Path('/missing/codex'), fixture, fixture.run_root / 'work',
                                      fixture.run_root / 'logs', dict(fixture.env, AWS_SHARED_CREDENTIALS_FILE='/synthetic'))
                launch.assert_not_called()
                home.assert_not_called()
        finally:
            fixture.close()

    def test_outside_working_directory_is_rejected(self):
        fixture = Fixture()
        try:
            env = self.environment(fixture)
            with tempfile.TemporaryDirectory() as outside, patch.object(desktop.subprocess, 'Popen') as launch:
                sentinel = Path(outside) / 'sentinel'
                sentinel.write_text('synthetic external file')
                with self.assertRaises(UnsafeFixture):
                    desktop.run_codex(Path('/missing/codex'), fixture, Path(outside), fixture.run_root / 'logs', env)
                self.assertEqual('synthetic external file', sentinel.read_text())
                launch.assert_not_called()
        finally:
            fixture.close()

    def test_symlinked_codex_home_is_rejected_without_touching_target(self):
        fixture = Fixture()
        try:
            binary = fixture.run_root / 'codex'
            binary.write_text('synthetic executable; never run')
            binary.chmod(0o700)
            env = self.environment(fixture)
            with tempfile.TemporaryDirectory() as outside, patch.object(desktop.subprocess, 'Popen') as launch:
                sentinel = Path(outside) / 'auth.json'
                sentinel.write_text('synthetic credential')
                (Path(env['HOME']) / '.codex').symlink_to(outside, target_is_directory=True)
                with self.assertRaises(UnsafeFixture):
                    desktop.run_codex(binary, fixture, fixture.run_root / 'work', fixture.run_root / 'logs', env)
                self.assertEqual('synthetic credential', sentinel.read_text())
                launch.assert_not_called()
        finally:
            fixture.close()

    @staticmethod
    def environment(fixture):
        return dict(fixture.env, PATH=str(fixture.run_root / 'no-executables'),
                    SHED_PORTABLE_FIXTURE_ROOT=str(fixture.root), SHED_LIVE_VISUAL_ONLY='1',
                    SHED_WATCH_QUALIFICATION='1', SHED_LIVE_CODEX='1', **desktop.git_environment(fixture))


if __name__ == '__main__':
    unittest.main()
