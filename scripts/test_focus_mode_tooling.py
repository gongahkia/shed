"""Fast bounded-fixture admission checks; no GUI or JVM launch."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('benchmark-focus-mode.py')
spec = importlib.util.spec_from_file_location('focus_mode_benchmark', SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class FocusModeToolingTest(unittest.TestCase):
    def test_protects_checkout_and_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            for output in [Path(directory), tool.ROOT / 'not-created-focus']:
                process = subprocess.run([sys.executable, str(SCRIPT), '--jar', 'unused.jar', '--output', str(output), '--generate-only'], capture_output=True, text=True)
                self.assertEqual(2, process.returncode)
            self.assertFalse((tool.ROOT / 'not-created-focus').exists())

    def test_generates_exact_bounded_fixtures_without_java(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'fixtures'
            process = subprocess.run([sys.executable, str(SCRIPT), '--jar', 'unused.jar', '--output', str(output), '--generate-only'], capture_output=True, text=True)
            self.assertEqual(0, process.returncode, process.stderr)
            fixtures = json.loads((output / 'fixtures.json').read_text())
            for size in [4096, 102400, 1048576, 10485760]:
                file = Path(fixtures[str(size)]['path']); self.assertEqual(size, file.stat().st_size)
                self.assertTrue(file.read_text().endswith('\n'))
            self.assertFalse((output / 'classes').exists())

    def test_matrix_includes_wrap_and_splits_with_bounded_document_sizes(self):
        self.assertEqual(8, len(tool.CASES))
        self.assertIn((102400, True, False), tool.CASES)
        self.assertIn((1048576, True, True), tool.CASES)
        self.assertLessEqual(max(size for size, _, _ in tool.CASES), 10 * 1024 * 1024)


if __name__ == '__main__': unittest.main()
