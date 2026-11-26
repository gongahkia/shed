"""Probe admission checks only; no JVM, desktop or network execution."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('theme_marketplace', Path(__file__).with_name('test-theme-marketplace.py'))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class ThemeMarketplaceToolingTest(unittest.TestCase):
    def test_acceptance_requires_clean_exit_compilation_disposal_and_pass(self):
        report = dict(exit=0, compile_exit=0, fixture_removed=True, result={'state': 'PASS'}, log='')
        self.assertTrue(tool.accepted(report))
        for change in [dict(exit=1), dict(compile_exit=1), dict(fixture_removed=False), dict(result={'state': 'FAIL'}),
                       dict(log='PASS\nUNCAUGHT_DESKTOP_EXCEPTION'), dict(result={})]:
            self.assertFalse(tool.accepted(dict(report, **change)))

    def test_existing_or_noncanonical_output_rejected_before_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / 'bin').mkdir()
            for name in ('java', 'javac'):
                (root / 'bin' / name).touch()
            jar = root / 'app.jar'
            jar.touch()
            for output in (root, root / '..' / root.name / 'unused'):
                argv = ['probe', '--jdk', str(root), '--jar', str(jar), '--output', str(output)]
                with patch.object(sys, 'argv', argv), patch.object(tool, 'Fixture') as fixture, self.assertRaises(SystemExit) as error:
                    tool.main()
                self.assertEqual(2, error.exception.code)
                fixture.assert_not_called()

    def test_toolchain_failure_creates_no_fixture_or_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            jar = root / 'app.jar'
            jar.touch()
            output = root / 'output'
            with patch.object(sys, 'argv', ['probe', '--jdk', str(root), '--jar', str(jar), '--output', str(output)]), \
                 patch.object(tool, 'Fixture') as fixture, self.assertRaises(SystemExit) as error:
                tool.main()
            self.assertEqual(2, error.exception.code)
            fixture.assert_not_called()
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
