"""Fast fixture and admission checks; no headful application or heavy benchmark execution."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('benchmark-density.py')
spec = importlib.util.spec_from_file_location('density_benchmark', SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class DensityToolingTest(unittest.TestCase):
    def test_preserves_existing_output_and_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            for output in [Path(directory), tool.ROOT / 'not-created-density']:
                result = subprocess.run([sys.executable, str(SCRIPT), '--jar', 'unused', '--baseline-jar', 'unused', '--output', str(output), '--generate-only'], capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
            self.assertFalse((tool.ROOT / 'not-created-density').exists())

    def test_generates_bounded_exact_fixtures_without_java(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'fixtures'
            result = subprocess.run([sys.executable, str(SCRIPT), '--jar', 'unused', '--baseline-jar', 'unused', '--output', str(output), '--generate-only'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            fixtures = json.loads((output / 'fixtures.json').read_text())
            self.assertEqual([4096, 102400, 1048576], sorted(int(key) for key in fixtures))
            for size, fixture in fixtures.items():
                self.assertEqual(int(size), Path(fixture['path']).stat().st_size)
            self.assertEqual(30, len(list((output / 'fixtures').glob('tree-row-*.txt'))))
            self.assertFalse((output / 'classes').exists())

    def test_matrix_includes_wrap_split_sidebar_and_large_ui_font(self):
        self.assertEqual(6, len(tool.CASES))
        self.assertTrue(any(wrap and size == 1048576 for size, wrap, _, _, _ in tool.CASES))
        self.assertTrue(any(split for _, _, split, _, _ in tool.CASES))
        self.assertTrue(any(font == 24 for _, _, _, font, _ in tool.CASES))
        self.assertTrue(any(sidebar for _, _, _, _, sidebar in tool.CASES))
        self.assertLessEqual(max(size for size, _, _, _, _ in tool.CASES), 1048576)


if __name__ == '__main__':
    unittest.main()
