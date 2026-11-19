"""Fast admission tests only; no layout benchmark, JVM or desktop launch."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('benchmark-unicode-candidate.py')
spec = importlib.util.spec_from_file_location('unicode_candidate', SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class UnicodeCandidateToolingTest(unittest.TestCase):
    def test_admission_rejects_unbounded_repetition_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'never-created'
            for arguments in [['--samples', '0'], ['--samples', '101'], ['--runs', '3']]:
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only', *arguments], capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
                self.assertIn('Bounds', result.stderr)
                self.assertFalse(output.exists())

    def test_existing_and_checkout_outputs_are_protected(self):
        with tempfile.TemporaryDirectory() as directory:
            for output in [Path(directory), tool.ROOT / 'not-created-candidate']:
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only'], capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
                self.assertIn('NEW directory outside', result.stderr)
            self.assertFalse((tool.ROOT / 'not-created-candidate').exists())

    def test_all_plans_have_bounded_utf8_fixtures_without_java(self):
        for group in ['pilot', 'gate', 'scripts', 'long', 'reads', 'model']:
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'fixtures'
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--group', group, '--generate-only'], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse((output / 'classes').exists())
                self.assertFalse((output / 'report.json').exists())
                for name, size in tool.plan(group):
                    file = output / f'{name}-{size}.md'
                    self.assertEqual(size, file.stat().st_size)
                    self.assertTrue(file.read_bytes().decode().endswith('\n'))


if __name__ == '__main__': unittest.main()
