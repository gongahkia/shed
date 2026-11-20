"""Fast plan/safety checks; no JVM, GUI, processes or project fixtures."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/audit-whole-ide.py'


class WholeIdeToolingTest(unittest.TestCase):
    def invoke(self, output, *flags):
        return subprocess.run([sys.executable, str(SCRIPT), '--jar', 'unused.jar', '--output', str(output), '--plan-only', *flags], capture_output=True, text=True)

    def test_plan_needs_no_runtime_and_is_finite(self):
        with tempfile.TemporaryDirectory() as scratch:
            out = Path(scratch) / 'plan'
            result = self.invoke(out)
            self.assertEqual(result.returncode, 0, result.stderr)
            plan = json.loads((out / 'plan.json').read_text())
            self.assertEqual(len(plan['cases']), 12)
            self.assertEqual(plan['project_file_counts'], [100, 1000, 5000])
            self.assertFalse(plan['capture_owned'])
            self.assertFalse(plan['production_mutations'])
            self.assertFalse(plan['native_permission_requests'])
            self.assertEqual([p.name for p in out.iterdir()], ['plan.json'])

    def test_existing_output_rejects_without_overwriting(self):
        with tempfile.TemporaryDirectory() as scratch:
            out = Path(scratch)
            (out / 'sentinel').write_text('keep')
            self.assertNotEqual(self.invoke(out).returncode, 0)
            self.assertEqual((out / 'sentinel').read_text(), 'keep')

    def test_checkout_output_rejects(self):
        out = ROOT / 'target' / 'b0-plan-not-created'
        self.assertNotEqual(self.invoke(out).returncode, 0)
        self.assertFalse(out.exists())

    def test_services_plan_does_not_include_desktop_work(self):
        with tempfile.TemporaryDirectory() as scratch:
            out = Path(scratch) / 'plan'
            self.assertEqual(self.invoke(out, '--group', 'services').returncode, 0)
            self.assertEqual(json.loads((out / 'plan.json').read_text())['cases'], [])


if __name__ == '__main__':
    unittest.main()
