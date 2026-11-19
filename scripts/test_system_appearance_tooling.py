"""Fast feasibility admission checks; never change system appearance or launch GUI processes."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name('qualify-system-appearance.py')
spec = importlib.util.spec_from_file_location('system_appearance_probe', SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class SystemAppearanceToolingTest(unittest.TestCase):
    def test_real_changes_require_explicit_opt_in(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'not-created'
            result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output)], capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertIn('--execute-transitions', result.stderr)
            self.assertFalse(output.exists())

    def test_preserves_existing_output_and_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            for output in [Path(directory), tool.ROOT / 'not-created-system-appearance']:
                result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only'], capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
            self.assertFalse((tool.ROOT / 'not-created-system-appearance').exists())

    def test_failed_readiness_closes_only_its_owned_child(self):
        launched = []
        original_popen = subprocess.Popen

        def track(*args, **kwargs):
            child = original_popen(*args, **kwargs)
            launched.append(child)
            return child

        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(tool.Child, 'wait', side_effect=TimeoutError('readiness failure')):
                with mock.patch.object(tool.subprocess, 'Popen', side_effect=track):
                    with self.assertRaises(TimeoutError):
                        tool.Child([sys.executable, '-c', 'import sys; sys.stdin.readline()'], Path(directory))
            self.assertEqual(1, len(launched))
            self.assertEqual(0, launched[0].poll())

    def test_plan_is_bounded_and_does_not_build_or_touch_system(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'plan'
            result = subprocess.run([sys.executable, str(SCRIPT), '--output', str(output), '--generate-only'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads((output / 'plan.json').read_text())
            self.assertEqual(10, plan['transition_limit'])
            self.assertFalse(plan['production_integration'])
            self.assertEqual(['plan.json'], sorted(p.name for p in output.iterdir()))


if __name__ == '__main__':
    unittest.main()
