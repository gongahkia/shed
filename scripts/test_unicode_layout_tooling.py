"""Admission and fixture checks only; no GUI, profiling or renderer initialization."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('investigate-unicode-layout.py')
spec = importlib.util.spec_from_file_location('unicode_layout', SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class UnicodeLayoutToolingTest(unittest.TestCase):
    def test_bounds_rejected_before_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'never-created'
            for args in [['--sizes', '1048577'], ['--sizes', '0'], ['--mode', 'stock', '--font', 'Dialog'],
                         ['--sizes', *(['4096'] * 31)]]:
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only', *args],
                                        capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
                self.assertFalse(output.exists())

    def test_existing_or_checkout_output_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for path in [Path(directory), tool.ROOT / 'not-created-unicode-investigation']:
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(path), '--generate-only'],
                                        capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
                self.assertIn('NEW directory outside', result.stderr)
            self.assertFalse((tool.ROOT / 'not-created-unicode-investigation').exists())

    def test_fixture_bytes_and_utf16_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            for category in tool.TEXT:
                path = Path(directory) / (category + '.md')
                info = tool.fixture(path, category, 4096)
                data = path.read_bytes()
                self.assertEqual(4096, len(data))
                self.assertEqual(len(data.decode().encode('utf-16-le')) // 2, info['utf16_units'])
                self.assertTrue(data.endswith(b'\n'))
                self.assertIn(tool.TEXT[category], data.decode())
                if category == 'long':
                    self.assertEqual(1, data.count(b'\n'))
                with self.assertRaises(FileExistsError):
                    tool.fixture(path, category, 4096)


if __name__ == '__main__':
    unittest.main()
