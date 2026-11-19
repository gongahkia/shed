"""Fast tooling admission checks; no GUI or JVM launch."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('benchmark-theme-preview.py')
spec = importlib.util.spec_from_file_location('theme_preview_benchmark', SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class ThemePreviewToolingTest(unittest.TestCase):
    def test_protects_checkout_and_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            for output in [Path(directory), tool.ROOT / 'not-created-theme-preview']:
                result = subprocess.run([sys.executable, str(SCRIPT), '--jar', 'unused', '--baseline-jar', 'unused', '--output', str(output), '--generate-only'], capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
            self.assertFalse((tool.ROOT / 'not-created-theme-preview').exists())

    def test_generate_only_creates_bounded_fixtures_without_java(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'fixtures'
            result = subprocess.run([sys.executable, str(SCRIPT), '--jar', 'unused', '--baseline-jar', 'unused', '--output', str(output), '--generate-only'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            for size, fixture in json.loads((output / 'fixtures.json').read_text()).items():
                self.assertEqual(int(size), Path(fixture['path']).stat().st_size)
            self.assertFalse((output / 'classes').exists())

    def test_bounded_matrix_includes_splits_focus_and_preview(self):
        self.assertEqual(4, len(tool.CASES))
        self.assertIn((102400, True, False), tool.CASES)
        self.assertIn((4096, False, True), tool.CASES)
        self.assertEqual(1048576, max(size for size, _, _ in tool.CASES))


if __name__ == '__main__':
    unittest.main()
