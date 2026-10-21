"""Exercise the example helper's live progress, recovery and process lifecycle."""
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "examples/campaigns/run_campaign.py"


@unittest.skipUnless(os.name == "posix", "the native example uses POSIX process groups")
class CampaignHelperTests(unittest.TestCase):
    def setUp(self):
        (ROOT / "out").mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="campaign-helper-test-", dir=ROOT / "out")
        self.root = Path(self.temporary.name)
        self.output = self.root / "campaign"
        self.relative = str(self.output.relative_to(ROOT))
        self.options = self.root / "options.json"
        self.options.write_text("{}")
        self.binary = self.root / "fake-chiyoda"
        self.binary.write_text(f"#!{sys.executable}\n" + FAKE_TOOL.replace("CONTROL_PATH", repr(str(self.root))))
        self.binary.chmod(0o755)
        self.process = None
        self.stderr = b""

    def tearDown(self):
        if self.process and self.process.poll() is None:
            self.process.send_signal(signal.SIGINT)
            self.process.communicate(timeout=8)
        self.temporary.cleanup()

    def start(self, options=None, *arguments):
        self.options.write_text(json.dumps(options or {}))
        self.process = subprocess.Popen(
            [sys.executable, str(SCRIPT), "--binary", str(self.binary), "--output", self.relative, *arguments],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
        )

    def wait_for(self, message, timeout=9):
        deadline = time.monotonic() + timeout
        while message.encode() not in self.stderr and time.monotonic() < deadline:
            if select.select([self.process.stderr], [], [], 0.1)[0]:
                chunk = os.read(self.process.stderr.fileno(), 65536)
                if not chunk:
                    break
                self.stderr += chunk
        self.assertIn(message, self.stderr.decode())

    def finish(self):
        stdout, stderr = self.process.communicate(timeout=10)
        self.stderr += stderr
        return stdout, self.stderr.decode()

    def calls(self):
        path = self.root / "calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_progress_is_visible_before_completion_and_stdout_stays_json(self):
        self.start({"block": "chiyoda_campaign_run"}, "--replay")
        self.wait_for("Planning campaign")
        self.wait_for("Trial saved: screen / candidate")
        self.wait_for("still working")
        self.assertIsNone(self.process.poll())
        self.assertFalse(select.select([self.process.stdout], [], [], 0)[0])
        (self.root / "continue").touch()
        stdout, stderr = self.finish()
        self.assertEqual(self.process.returncode, 0, stderr)
        result = json.loads(stdout)
        self.assertTrue(result["verification"]["verified"])
        self.assertTrue(result["verification"]["pixels_rederived"])
        self.assertIn("Annotated image saved: 000.png", stderr)
        self.assertIn("Campaign saved:", stderr)
        self.assertIn("reconstructing runs and re-rendering images", stderr)
        self.assertIn("Verification complete.", stderr)

    def test_verify_only_uses_the_archive_without_planning_or_running(self):
        self.output.mkdir()
        (self.output / "report.json").write_text('{"saved":true}')
        self.start({}, "--verify-only", "--replay", "--config", "does-not-exist.json")
        stdout, stderr = self.finish()
        self.assertEqual(self.process.returncode, 0, stderr)
        self.assertTrue(json.loads(stdout)["verification"]["verified"])
        self.assertEqual([c["tool"] for c in self.calls()], ["chiyoda_campaign_query", "chiyoda_campaign_verify"])
        self.assertTrue(self.calls()[-1]["arguments"]["replay"])
        self.assertEqual((self.output / "report.json").read_text(), '{"saved":true}')

    def test_cancelling_verification_stops_descendants_and_keeps_the_saved_report(self):
        self.output.mkdir()
        (self.output / "report.json").write_text("{}")
        self.start({"block": "chiyoda_campaign_verify", "descendant": True}, "--verify-only")
        self.wait_for("still working")
        parent, child = json.loads((self.root / "pids.json").read_text())
        self.process.send_signal(signal.SIGINT)
        stdout, stderr = self.finish()
        self.assertEqual(self.process.returncode, 130, stderr)
        self.assertEqual(stdout, b"")
        self.assertIn("Campaign already saved.", stderr)
        self.assertIn("--verify-only --output " + self.relative, stderr)
        self.assertNotIn("Traceback", stderr)
        self.assertTrue((self.output / "report.json").is_file())
        for pid in [parent, child]:
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)

    def test_cancellation_escalates_for_an_unresponsive_tool_and_preserves_partial_files(self):
        self.start({"block": "chiyoda_campaign_run", "ignore_interrupt": True})
        self.wait_for("Trial saved:")
        parent, _ = json.loads((self.root / "pids.json").read_text())
        self.process.send_signal(signal.SIGINT)
        _, stderr = self.finish()
        self.assertEqual(self.process.returncode, 130, stderr)
        self.assertIn("Partial archive:", stderr)
        self.assertTrue((self.output / "trials/screen/candidate/trial.json").is_file())
        self.assertFalse((self.output / "report.json").exists())
        with self.assertRaises(ProcessLookupError):
            os.kill(parent, 0)

    def test_failed_and_malformed_tool_responses_report_errors_without_tracebacks(self):
        for mode in ["error", "malformed"]:
            with self.subTest(mode=mode):
                self.stderr = b""
                self.start({"failure": mode})
                stdout, stderr = self.finish()
                self.assertEqual(self.process.returncode, 1, stderr)
                self.assertEqual(stdout, b"")
                self.assertIn("Error: chiyoda_campaign_plan", stderr)
                self.assertIn("invalid_plan" if mode == "error" else "invalid JSON", stderr)
                self.assertNotIn("Traceback", stderr)

    def test_existing_outputs_are_preserved_and_missing_binaries_explain_the_build(self):
        self.output.mkdir()
        marker = self.output / "keep"
        marker.write_text("preserve")
        self.start()
        _, stderr = self.finish()
        self.assertEqual(self.process.returncode, 1, stderr)
        self.assertIn("Output already exists", stderr)
        self.assertIn("--verify-only", stderr)
        self.assertEqual(marker.read_text(), "preserve")
        self.assertEqual(self.calls(), [])
        self.binary.unlink()
        self.stderr = b""
        self.start()
        _, stderr = self.finish()
        self.assertEqual(self.process.returncode, 1, stderr)
        self.assertIn("cargo build --release -p chiyoda --locked", stderr)
        self.assertNotIn("Traceback", stderr)


FAKE_TOOL = '''
import json, os, signal, subprocess, sys, time
from pathlib import Path
control = Path(CONTROL_PATH)
options = json.loads((control / "options.json").read_text())
tool = sys.argv[3]
arguments = json.load(sys.stdin)
with (control / "calls.jsonl").open("a") as f:
    f.write(json.dumps({"tool": tool, "arguments": arguments}) + "\\n")
if options.get("failure"):
    if options["failure"] == "malformed":
        print("invalid response")
    else:
        print(json.dumps({"ok":False,"error":{"code":"invalid_plan","message":"test failure"}}))
    sys.exit(1)
workspace = Path(sys.argv[sys.argv.index("--workspace") + 1])
if tool == "chiyoda_campaign_plan":
    result = {"runnable":True,"plan_hash":"hash","cases":[{"id":"screen"}],"reserved_trials":1}
elif tool == "chiyoda_campaign_run":
    output = workspace / arguments["output"]
    trial = output / "trials/screen/candidate"
    trial.mkdir(parents=True)
    (trial / "trial.json").write_text(json.dumps({"case":"screen","candidate":"candidate","status":"executed","eligible":True}))
    evidence = output / "evidence"
    evidence.mkdir()
    (evidence / "000.png").write_bytes(b"test image")
    (evidence / "000.png.json").write_text("{}")
    result = {"executed_trials":1}
elif tool == "chiyoda_campaign_query":
    result = {"executed_trials":1}
else:
    result = {"verified":True,"pixels_rederived":arguments["replay"]}
if options.get("block") == tool:
    child = None
    if options.get("descendant"):
        child = subprocess.Popen([sys.executable, "-c", "import signal,sys,time; signal.signal(signal.SIGINT, lambda *_: sys.exit(130)); print('ready',flush=True); time.sleep(60)"], stdout=subprocess.PIPE)
        child.stdout.readline()
        child.stdout.close()
    def interrupted(*_):
        if child:
            child.wait(timeout=2)
        sys.exit(130)
    signal.signal(signal.SIGINT, signal.SIG_IGN if options.get("ignore_interrupt") else interrupted)
    (control / "pids.json").write_text(json.dumps([os.getpid(), child.pid if child else None]))
    while not (control / "continue").exists():
        time.sleep(0.02)
if tool == "chiyoda_campaign_run":
    (output / "report.json").write_text("{}")
print(json.dumps({"ok":True,"result":result}))
'''


if __name__ == "__main__":
    unittest.main()
