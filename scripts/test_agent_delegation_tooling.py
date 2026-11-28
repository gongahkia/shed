"""Synthetic CLI admission checks; no Java process or window is launched."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('delegation_driver', Path(__file__).with_name('test-agent-delegation.py'))
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class AgentDelegationAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='shed-delegation-admission-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.jdk = self.root / 'jdk'
        (self.jdk / 'bin').mkdir(parents=True)
        for tool in ('java', 'javac'):
            (self.jdk / 'bin' / tool).write_text('synthetic; never executed')
        self.jar = self.root / 'fixture.jar'
        self.jar.write_bytes(b'synthetic; never executed')

    def reject(self, output, extra=()):
        argv = ['test-agent-delegation.py', '--jdk', str(self.jdk), '--jar', str(self.jar), '--output', str(output), *extra]
        with patch('sys.argv', argv), patch.object(driver.subprocess, 'run') as run, patch.object(driver.subprocess, 'Popen') as launch, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as failure:
                driver.main()
            self.assertEqual(failure.exception.code, 2)
            run.assert_not_called()
            launch.assert_not_called()

    def test_existing_output_rejected_without_touching_sentinel(self):
        output = self.root / 'existing'
        output.mkdir()
        sentinel = output / 'keep'
        sentinel.write_bytes(b'unchanged')
        before = sentinel.stat().st_mtime_ns
        self.reject(output)
        self.assertEqual(sentinel.read_bytes(), b'unchanged')
        self.assertEqual(sentinel.stat().st_mtime_ns, before)

    def test_symlinked_output_parent_rejected_before_any_process(self):
        outside = self.root / 'outside'
        outside.mkdir()
        link = self.root / 'link'
        link.symlink_to(outside, target_is_directory=True)
        self.reject(link / 'new')
        self.assertFalse((outside / 'new').exists())

    def test_missing_compiler_rejected_without_creating_output(self):
        (self.jdk / 'bin/javac').unlink()
        output = self.root / 'not-created'
        self.reject(output)
        self.assertFalse(output.exists())

    def test_interactive_completion_retains_existing_output_protection(self):
        output = self.root / 'existing-completion'
        output.mkdir()
        self.reject(output, ('--chat', '--inline'))

    def test_completion_requires_explicit_chat_fixture_before_any_process(self):
        output = self.root / 'not-created'
        self.reject(output, ('--inline',))
        self.assertFalse(output.exists())

    def test_ui_fixture_cannot_mix_chat_or_interactive_completion(self):
        for extra in [('--ui', '--chat'), ('--ui', '--inline')]:
            output = self.root / 'not-created-ui'
            self.reject(output, extra)
            self.assertFalse(output.exists())

    def test_ui_fixture_retains_existing_output_protection(self):
        output = self.root / 'existing-ui'
        output.mkdir()
        self.reject(output, ('--ui',))

    def test_settings_fixture_cannot_mix_other_workflows(self):
        for mode in ('--chat', '--ui', '--workbench', '--workbench-readonly', '--workbench-context', '--inline'):
            output = self.root / 'not-created-settings'
            self.reject(output, ('--settings', mode))
            self.assertFalse(output.exists())

    def test_settings_fixture_retains_existing_output_protection(self):
        output = self.root / 'existing-settings'
        output.mkdir()
        self.reject(output, ('--settings',))

    def test_settings_diagnostics_require_settings_fixture(self):
        for mode in ('--settings-profile', '--settings-paint-only'):
            output = self.root / 'not-created-diagnostic'
            self.reject(output, (mode,))
            self.assertFalse(output.exists())
