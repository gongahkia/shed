import importlib.util
import os
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
                          ['--loop'], ['--check-terminal-handoff'], ['--animation', 'reading', '--loop', '--check-terminal-handoff'],
                          ['--animation-demo', '--git-fixture', '--visual-only', '--loop'],
                          ['--animation', 'reading'], ['--animation', 'unknown'], ['--animation', 'move'], ['--animation', 'rename'], ['--animation', 'format'], ['--animation', 'undo'],
                          ['--animation-demo'], ['--animation-demo', '--git-fixture'],
                          ['--animation-demo', '--git-fixture', '--visual-only', '--qualification'],
                          ['--animation-demo', '--git-fixture', '--visual-only', '--live-codex', '/missing/codex']]:
                result = subprocess.run([sys.executable, str(ROOT / 'scripts/test-agent-desktop.py'),
                                         '--jdk', '/missing/jdk', '--jar', '/missing/jar', '--output', str(output), *flags],
                                        stdin=subprocess.DEVNULL, capture_output=True, timeout=5)
                self.assertEqual(2, result.returncode)
                self.assertFalse(output.exists())

    def test_animation_listing_needs_no_toolchain_or_fixture(self):
        with patch.object(sys, 'argv', ['test-agent-desktop.py', '--list-animations']), \
             patch.object(desktop, 'Fixture') as fixture, patch('builtins.print') as output:
            self.assertEqual(0, desktop.main())
        fixture.assert_not_called()
        self.assertEqual(len(desktop.ANIMATIONS), output.call_count)

    def test_make_targets_forward_each_selected_animation(self):
        for name in desktop.ANIMATIONS:
            with self.subTest(animation=name):
                result = subprocess.run(['make', '-n', '-o', 'target/shed-2.0.0.jar', f'demo-{name}'],
                                        cwd=ROOT, capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn(f'--animation {name}', result.stdout)
        result = subprocess.run(['make', '-n', '-o', 'target/shed-2.0.0.jar', 'demo'],
                                cwd=ROOT, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('--animation ', result.stdout)
        self.assertIn('--animation-demo', result.stdout)

    def test_deleted_move_target_and_composed_delete_create_sequence(self):
        self.assertNotIn('move', desktop.ANIMATIONS)
        for name in ('rename', 'format', 'undo'):
            self.assertNotIn(name, desktop.ANIMATIONS)
        removed = subprocess.run(['make', '-n', '-o', 'target/shed-2.0.0.jar', 'demo-move'], cwd=ROOT,
                                 capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, removed.returncode)
        for name in ('rename', 'format', 'undo'):
            removed = subprocess.run(['make', '-n', '-o', 'target/shed-2.0.0.jar', 'demo-'+name], cwd=ROOT,
                                     capture_output=True, text=True, timeout=5)
            self.assertNotEqual(0, removed.returncode)
        sequence = subprocess.run(['make', '-n', '-o', 'target/shed-2.0.0.jar', 'demo',
                                   'ANIMATIONS=deletion,insertion', 'LOOP=1'], cwd=ROOT,
                                  capture_output=True, text=True, timeout=5)
        self.assertEqual(0, sequence.returncode, sequence.stderr)
        self.assertIn('--animation deletion,insertion --loop', sequence.stdout)
        self.assertIn('--animation-demo', sequence.stdout)

    def test_make_forwards_loop_option(self):
        result = subprocess.run(['make', '-n', '-o', 'target/shed-2.0.0.jar', 'demo-reading', 'LOOP=1'],
                                cwd=ROOT, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--animation reading --loop', result.stdout)

    def test_loop_pause_is_an_intentional_stop_and_assertion_failures_are_not_success(self):
        self.assertTrue(desktop.demo_succeeded({'exit': 0, 'result': {'state': 'PASS'}}))
        paused = {'exit': 0, 'result': {'state': 'PAUSED'}}
        self.assertTrue(desktop.demo_succeeded(paused, loop=True))
        self.assertFalse(desktop.demo_succeeded(paused))
        for result in [{'exit': 0, 'result': {'state': 'FAIL'}}, {'exit': 1, 'result': {'state': 'PASS'}}, {'exit': 130}, {'exit': 0}]:
            self.assertFalse(desktop.demo_succeeded(result, loop=True))

    def test_gui_environment_only_adds_display_access(self):
        base = {'HOME': '/synthetic/fixture'}
        with patch.dict(os.environ, {'DISPLAY': ':17', 'XAUTHORITY': '/synthetic/xauth',
                                     'JAVA_TOOL_OPTIONS': 'unwanted', 'SSH_AUTH_SOCK': 'unwanted'}, clear=True):
            self.assertEqual(dict(base, DISPLAY=':17', XAUTHORITY='/synthetic/xauth'),
                             desktop.desktop_environment(base))
        self.assertEqual({'HOME': '/synthetic/fixture'}, base)
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(base, desktop.desktop_environment(base))

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
