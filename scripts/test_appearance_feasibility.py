"""Fast admission and fixture checks; no JVM or desktop launch."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('benchmark-appearance-feasibility.py')
spec = importlib.util.spec_from_file_location('appearance_feasibility', SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class AppearanceFeasibilityTest(unittest.TestCase):
    def test_admission_protects_checkout_existing_output_and_repetitions(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / 'never-created'
            for output, options in [(missing, ['--runs', '0']), (missing, ['--runs', '3']),
                                    (Path(directory), []), (tool.ROOT / 'not-created-appearance', [])]:
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only', *options], capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
            self.assertFalse(missing.exists())
            self.assertFalse((tool.ROOT / 'not-created-appearance').exists())

    def test_bounded_fixtures_are_exact_and_require_no_java(self):
        for group in ['pilot', 'matrix', 'native']:
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'fixtures'
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--group', group, '--generate-only'], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertFalse((output / 'classes').exists())
                self.assertFalse((output / 'report.json').exists())
                fixtures = json.loads((output / 'fixtures.json').read_text())
                for size, panes, windows in tool.plan(group):
                    self.assertLessEqual(panes * windows, 2)
                    file = Path(fixtures[str(size)]['path'])
                    self.assertEqual(size, file.stat().st_size)
                    self.assertTrue(file.read_text().endswith('\n'))

    def test_summary_separates_first_use_and_excludes_unmeasured_paint(self):
        summary = tool.summarize([
            {'operation': 'first_insert', 'edt_ms': 3, 'completed_paint_ms': 20},
            {'operation': 'insert', 'edt_ms': 1, 'completed_paint_ms': 2},
            {'operation': 'insert', 'edt_ms': 2, 'completed_paint_ms': 4},
            {'operation': 'mutation_only', 'edt_ms': 1, 'completed_paint_ms': -1}])
        self.assertEqual(1, summary['first_insert/completed_paint_ms']['n'])
        self.assertEqual(20, summary['first_insert/completed_paint_ms']['p95'])
        self.assertEqual(4, summary['insert/completed_paint_ms']['p95'])
        self.assertNotIn('mutation_only/completed_paint_ms', summary)


if __name__ == '__main__': unittest.main()
