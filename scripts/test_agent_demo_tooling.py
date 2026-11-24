"""Synthetic admission checks; never launches the live workbench or a client process."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from native_fixture import Fixture, UnsafeFixture, digest

spec = importlib.util.spec_from_file_location('agent_demo_client', Path(__file__).with_name('agent-demo-client.py'))
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


class AgentDemoAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()
        self.addCleanup(self.fixture.close)

    def test_expected_empty_startup_environment_is_admitted(self):
        self.fixture.validate()
        client.safe_environment(self.fixture.env, self.fixture.run_root)

    def test_credentials_and_runtime_injection_fail_before_execution(self):
        for key in ['SSH_AUTH_SOCK', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']:
            with self.subTest(key=key), patch('subprocess.run') as launch:
                with self.assertRaises(UnsafeFixture):
                    client.safe_environment(dict(self.fixture.env, **{key: 'synthetic'}), self.fixture.run_root)
                launch.assert_not_called()

    def test_external_startup_sentinel_is_unchanged(self):
        with tempfile.TemporaryDirectory() as outside:
            sentinel = Path(outside) / 'synthetic-startup'
            sentinel.write_text('synthetic sentinel, never execute')
            before = (digest(sentinel), sentinel.stat().st_mtime_ns)
            for key in ['BASH_ENV', 'ENV']:
                with self.assertRaises(UnsafeFixture):
                    client.safe_environment(dict(self.fixture.env, **{key: str(sentinel)}), self.fixture.run_root)
            self.assertEqual(before, (digest(sentinel), sentinel.stat().st_mtime_ns))

    def test_nonempty_or_missing_fixture_startup_is_rejected(self):
        startup = Path(self.fixture.env['BASH_ENV'])
        startup.write_text('synthetic unwanted startup')
        with self.assertRaises(UnsafeFixture):
            client.safe_environment(self.fixture.env, self.fixture.run_root)
        startup.write_text('')
        env = dict(self.fixture.env)
        env.pop('ENV')
        with self.assertRaises(UnsafeFixture):
            client.safe_environment(env, self.fixture.run_root)
