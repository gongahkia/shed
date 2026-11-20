"""Static/synthetic checks only. Native execution is an explicit separate opt-in."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from native_fixture import Fixture, UnsafeFixture, checked, digest


class NativeFixtureTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()
        self.addCleanup(self.fixture.close)

    def test_empty_home_and_private_root(self):
        f = self.fixture
        f.validate()
        self.assertEqual(f.root.stat().st_mode & 0o777, 0o700)
        self.assertEqual(set(Path(f.env['HOME']).iterdir()), {Path(f.env['ENV'])})

    def test_existing_owned_disposable_home(self):
        path = Path(self.fixture.env['HOME']) / 'synthetic-config'
        path.write_text('keep')
        self.fixture.validate()
        self.assertEqual(path.read_text(), 'keep')

    def test_no_parent_environment_inheritance(self):
        with patch.dict(os.environ, {'SSH_AUTH_SOCK': '/private-sentinel', 'AWS_SECRET_ACCESS_KEY': 'synthetic'}):
            f = Fixture()
            try:
                self.assertNotIn('SSH_AUTH_SOCK', f.env)
                self.assertNotIn('AWS_SECRET_ACCESS_KEY', f.env)
            finally:
                f.close()

    def test_missing_history_rejected_before_launch(self):
        env = dict(self.fixture.env)
        env.pop('HISTFILE')
        with patch('native_fixture.subprocess.Popen') as launch:
            with self.assertRaises(UnsafeFixture):
                self.fixture.validate(env)
            launch.assert_not_called()

    def test_external_history_rejected(self):
        env = dict(self.fixture.env, HISTFILE=str(self.fixture.root / 'outside'))
        with self.assertRaises(UnsafeFixture):
            self.fixture.validate(env)

    def test_explicit_internal_history(self):
        checked(self.fixture.env['HISTFILE'], self.fixture.run_root)

    def test_injected_credentials_rejected_without_exposing_value(self):
        env = dict(self.fixture.env, SSH_AUTH_SOCK='synthetic-secret')
        with self.assertRaises(UnsafeFixture) as error:
            self.fixture.validate(env)
        self.assertNotIn('synthetic-secret', str(error.exception))

    def test_injected_shell_startup_rejected(self):
        for key in ['BASH_ENV', 'ENV', 'ZDOTDIR', 'PATH', 'JAVA_TOOL_OPTIONS']:
            with self.subTest(key=key), self.assertRaises(UnsafeFixture):
                self.fixture.validate(dict(self.fixture.env, **{key: '/synthetic'}))

    def test_unexpected_working_directory_rejected(self):
        with self.assertRaises(UnsafeFixture):
            self.fixture.validate(cwd=self.fixture.root)

    def test_missing_home_rejected(self):
        path = Path(self.fixture.env['HOME'])
        renamed = path.with_name('moved')
        path.rename(renamed)
        try:
            with self.assertRaises(UnsafeFixture):
                self.fixture.validate()
        finally:
            renamed.rename(path)

    def test_readonly_home_rejected(self):
        path = Path(self.fixture.env['HOME'])
        path.chmod(0o500)
        try:
            with self.assertRaises(UnsafeFixture):
                self.fixture.validate()
        finally:
            path.chmod(0o700)

    def test_symlink_escape_rejected(self):
        link = self.fixture.run_root / 'escape'
        link.symlink_to(self.fixture.root, target_is_directory=True)
        with self.assertRaises(UnsafeFixture):
            checked(link / 'owner.json', self.fixture.run_root)

    def test_hardlink_rejected(self):
        link = self.fixture.run_root / 'linked-marker'
        os.link(self.fixture.root / 'owner.json', link)
        try:
            with self.assertRaises(UnsafeFixture):
                checked(link, self.fixture.run_root)
        finally:
            link.unlink()

    def test_parent_traversal_rejected(self):
        with self.assertRaises(UnsafeFixture):
            checked(self.fixture.run_root / '../owner.json', self.fixture.run_root)

    def test_prefix_collision_rejected(self):
        with self.assertRaises(UnsafeFixture):
            checked(Path(str(self.fixture.run_root) + '-sibling/file'), self.fixture.run_root)

    def test_relative_root_rejected(self):
        with self.assertRaises(UnsafeFixture):
            Fixture(Path('relative'))

    def test_symlink_parent_rejected(self):
        link = self.fixture.root / 'parent-alias'
        link.symlink_to(self.fixture.run_root, target_is_directory=True)
        with self.assertRaises(UnsafeFixture):
            Fixture(link)

    def test_wrong_owner_marker_rejected(self):
        marker = self.fixture.root / 'owner.json'
        original = marker.read_text()
        marker.write_text(json.dumps({'id': 'wrong', 'identity': list(self.fixture.identity)}))
        try:
            with self.assertRaises(UnsafeFixture):
                self.fixture.validate()
        finally:
            marker.write_text(original)

    def test_unsafe_execution_rejected_without_popen(self):
        with patch('native_fixture.subprocess.Popen') as launch:
            with self.assertRaises(UnsafeFixture):
                self.fixture.run()
            self.fixture.verified = True
            for options in [dict(scenario='rm -rf /'), dict(shell='fish'), dict(mode='unknown'), dict(timeout=10)]:
                with self.subTest(options=options), self.assertRaises(UnsafeFixture):
                    self.fixture.run(**options)
            launch.assert_not_called()

    def test_unsupported_platform_fail_closed(self):
        with patch('native_fixture.platform.system', return_value='Linux'), self.assertRaises(UnsafeFixture):
            self.fixture.profile()

    def test_global_zshenv_rejected(self):
        original = Path.exists
        def exists(path):
            return str(path) == '/etc/zshenv' or original(path)
        with patch.object(Path, 'exists', exists), self.assertRaises(UnsafeFixture):
            self.fixture.profile()

    def test_profile_no_fork_network_or_user_home_access(self):
        text = self.fixture.profile().read_text()
        self.assertNotIn('(allow process-fork', text)
        self.assertNotIn('(allow network', text)
        self.assertNotIn('(allow mach', text)
        self.assertNotIn(str(Path.home()), text)

    def test_cleanup_preserves_external_synthetic_sentinel(self):
        with tempfile.TemporaryDirectory() as parent:
            sentinel = Path(parent) / 'sentinel'
            sentinel.write_text('synthetic external data')
            before = digest(sentinel), sentinel.stat().st_mtime_ns
            (self.fixture.run_root / 'external-link').symlink_to(sentinel)
            self.fixture.close()
            self.assertEqual(before, (digest(sentinel), sentinel.stat().st_mtime_ns))

    def test_cleanup_idempotent(self):
        root = self.fixture.root
        self.fixture.close()
        self.fixture.close()
        self.assertFalse(root.exists())

    def test_cleanup_rejects_changed_marker(self):
        marker = self.fixture.root / 'owner.json'
        original = marker.read_text()
        marker.write_text('{}')
        try:
            with self.assertRaises(UnsafeFixture):
                self.fixture.close()
            self.assertTrue(marker.exists())
        finally:
            marker.write_text(original)

    def test_permission_error_propagates_no_launch(self):
        with patch('native_fixture.tempfile.mkdtemp', side_effect=PermissionError('synthetic denial')):
            with self.assertRaises(PermissionError):
                Fixture()

    def test_repeated_creation_cleanup(self):
        for _ in range(5):
            f = Fixture()
            f.validate()
            f.close()
            self.assertFalse(f.root.exists())


if __name__ == '__main__':
    unittest.main()

class BenchmarkAdmissionTest(unittest.TestCase):
    def test_whole_ide_runtime_rejects_before_toolchain_or_native_work(self):
        import subprocess
        import sys
        with tempfile.TemporaryDirectory() as scratch:
            output = Path(scratch) / 'blocked'
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('audit-whole-ide.py')),
                                     '--jar', 'unused.jar', '--output', str(output)],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 2)
            self.assertIn('unqualified', result.stderr)
            self.assertEqual(json.loads((output / 'blocked.json').read_text())['result'], 'BLOCKED')
            self.assertEqual({p.name for p in output.iterdir()}, {'plan.json', 'blocked.json'})

    def test_portable_runner_rejects_native_test_before_tool_lookup(self):
        import subprocess
        import sys
        with tempfile.TemporaryDirectory() as scratch:
            output = Path(scratch) / 'not-created'
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('run-portable-tests.py')),
                                     '--jdk', '/unused', '--maven', '/unused', '--repository', '/unused',
                                     '--output', str(output), '--tests', 'PtyTerminalConnectorTest'],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 2)
            self.assertIn('blocked', result.stderr)
            self.assertFalse(output.exists())

    def test_reviewed_test_manifest_matches_sources(self):
        root = Path(__file__).resolve().parents[1]
        lines = (root / 'src/test/resources/shed/portable-fixture-tests.properties').read_text().splitlines()
        self.assertEqual(len(lines), 8)
        for line in lines:
            name, expected = line.split('=')
            self.assertEqual(digest(root / 'src/test/java/shed' / (name + '.java')), expected)
