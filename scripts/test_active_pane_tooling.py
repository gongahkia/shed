import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/benchmark-active-pane.py'


class ActivePaneToolingTest(unittest.TestCase):
    def test_explicit_lifecycle_mode_can_generate_without_native_desktop_work(self):
        with tempfile.TemporaryDirectory() as scratch:
            result = subprocess.run([sys.executable, str(SCRIPT), '--jar', 'unused.jar', '--baseline-jar', 'unused.jar',
                '--output', str(Path(scratch) / 'generated'), '--generate-only', '--lifecycle-only'], capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_bounded_sizes(self):
        spec = importlib.util.spec_from_file_location('active_pane_tooling', SCRIPT)
        tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tool)
        self.assertEqual([4096, 102400, 1048576], tool.SIZES)

    def test_fixture_generation_and_output_safety(self):
        with tempfile.TemporaryDirectory() as scratch:
            output = Path(scratch) / 'generated'
            command = [sys.executable, str(SCRIPT), '--jar', 'unused.jar', '--baseline-jar', 'unused.jar',
                       '--output', str(output), '--generate-only']
            result = subprocess.run(command, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)
            fixtures = json.loads((output / 'fixtures.json').read_text())
            self.assertEqual({'4096', '102400', '1048576'}, set(fixtures))
            for size, fixture in fixtures.items():
                self.assertEqual(int(size), Path(fixture['path']).stat().st_size)
            rejected = subprocess.run(command, capture_output=True, text=True, timeout=15)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn('NEW directory outside', rejected.stderr)


if __name__ == '__main__':
    unittest.main()
