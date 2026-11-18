"""Admission tests only; no GUI/JVM benchmark executes during these checks."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/qualify-markdown.py'


class QualificationToolingTest(unittest.TestCase):
    def test_existing_output_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(SCRIPT), '--output', directory, '--generate-only'], capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertIn('NEW directory outside', result.stderr)

    def test_repository_output_and_unknown_group_are_rejected(self):
        for args in [['--output', str(ROOT / 'not-created-qualification')], ['--group', 'unbounded']]:
            result = subprocess.run([sys.executable, str(SCRIPT), '--output', '/tmp/not-created-qualification', '--generate-only'] + args,
                                    capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
        self.assertFalse((ROOT / 'not-created-qualification').exists())

    def test_fixture_generation_is_bounded_and_does_not_launch_java(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'fixtures'
            result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertLess(sum(p.stat().st_size for p in output.rglob('*') if p.is_file()), 12 * 1024**2)
            self.assertFalse((output / 'classes').exists())
            self.assertFalse((output / 'report.json').exists())
