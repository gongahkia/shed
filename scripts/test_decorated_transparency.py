"""Fast gate tooling tests only; never open a window."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('qualify-decorated-transparency.py')
spec = importlib.util.spec_from_file_location('decorated_transparency', SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class DecoratedTransparencyTest(unittest.TestCase):
    def test_protects_existing_and_checkout_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            for output in [Path(directory), tool.ROOT / 'not-created-decorated']:
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only'], capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
            self.assertFalse((tool.ROOT / 'not-created-decorated').exists())

    def test_fixture_is_bounded_without_java(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'fixture'
            result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            fixture = json.loads((output / 'fixture.json').read_text())
            self.assertEqual(4096, Path(fixture['path']).stat().st_size)
            self.assertFalse((output / 'classes').exists())

    def test_negative_or_incomplete_evidence_is_not_a_go(self):
        self.assertEqual('INCOMPLETE', tool.verdict([]))
        self.assertEqual('INCOMPLETE', tool.verdict([{'operation': 'attempt', 'accepted': True}]))
        self.assertEqual('NO_GO', tool.verdict([{'operation': 'verdict', 'value': 'NO_GO'}]))


if __name__ == '__main__': unittest.main()
