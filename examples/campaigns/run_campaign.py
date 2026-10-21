#!/usr/bin/env python3
"""Run a bounded campaign through the same tools available to MCP agents."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def progress(message):
    print(f"[campaign] {message}", file=sys.stderr, flush=True)


def stop(process):
    """Stop and reap the tool, including descendants in its own process group."""
    try:
        os.killpg(process.pid, signal.SIGINT)
    except ProcessLookupError:
        pass
    try:
        process.communicate(timeout=2)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.communicate()


def call(binary, workspace, tool, arguments, stage, observe=None):
    progress(stage)
    started = time.monotonic()
    heartbeat = started + 5
    with subprocess.Popen(
        [str(binary), "agent", "call", tool, "--input", "-", "--workspace", str(workspace)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, start_new_session=True,
    ) as process:
        payload = json.dumps(arguments)
        try:
            while True:
                try:
                    stdout, stderr = process.communicate(input=payload, timeout=1)
                    break
                except subprocess.TimeoutExpired:
                    payload = None  # communicate retains the original input while draining pipes.
                    if observe:
                        observe()
                    now = time.monotonic()
                    if now >= heartbeat:
                        progress(f"{stage} — still working ({now - started:.0f}s elapsed)")
                        heartbeat = now + 5
        except BaseException:
            stop(process)
            raise
    if observe:
        observe()
    try:
        envelope = json.loads(stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{tool} returned invalid JSON: {(stderr or stdout).strip()[:1000]}") from error
    if not isinstance(envelope, dict) or not envelope.get("ok") or process.returncode:
        error = envelope.get("error", {}) if isinstance(envelope, dict) else {}
        raise RuntimeError(f"{tool} failed: {error.get('code', 'tool_error')}: {error.get('message', stderr.strip() or 'no error details')}")
    progress(f"{stage} — finished in {time.monotonic() - started:.1f}s")
    return envelope["result"]


class ArchiveProgress:
    def __init__(self, directory):
        self.directory = directory
        self.trials = set()
        self.images = set()

    def __call__(self):
        for path in sorted(self.directory.glob("trials/*/*/trial.json")):
            if path in self.trials:
                continue
            try:
                trial = json.loads(path.read_text())
            except (OSError, json.JSONDecodeError):
                continue  # The tool may still be writing this ledger.
            self.trials.add(path)
            progress(f"Trial saved: {trial['case']} / {trial['candidate']} ({trial['status']}, eligible={trial['eligible']})")
        for path in sorted(self.directory.glob("evidence/*.png.json")):
            if path not in self.images and path.with_suffix("").is_file():
                self.images.add(path)
                progress(f"Annotated image saved: {path.with_suffix('').name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="examples/campaigns/station.json")
    parser.add_argument("--output", default="out/campaign-station")
    parser.add_argument("--binary", default="target/release/chiyoda")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--plan-only", action="store_true")
    mode.add_argument("--verify-only", action="store_true", help="verify an existing --output archive without rerunning it")
    parser.add_argument("--replay", action="store_true", help="reconstruct trials and image pixels during additional final verification")
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parents[2]

    def relative(path):
        path = Path(path)
        return str((path if path.is_absolute() else workspace / path).resolve().relative_to(workspace))

    binary = Path(args.binary)
    if not binary.is_absolute():
        binary = workspace / binary

    if not binary.is_file():
        raise RuntimeError(f"Binary not found: {binary}. Build it with cargo build --release -p chiyoda --locked")

    output = relative(args.output)
    directory = workspace / output

    def invoke(tool, arguments, stage, observe=None):
        return call(binary, workspace, tool, arguments, stage, observe)

    try:
        if args.verify_only:
            if not (directory / "report.json").is_file():
                raise RuntimeError(f"No completed campaign at {output}. Partial files are retained; run a new campaign with a fresh --output directory.")
            result = invoke("chiyoda_campaign_query", {"campaign": output, "view": "summary"}, "Loading saved campaign")
        else:
            if not args.plan_only and directory.exists():
                raise RuntimeError(f"Output already exists: {output}. Use --verify-only to check a completed archive, or choose a fresh --output directory.")
            plan = invoke("chiyoda_campaign_plan", {"config": relative(args.config)}, "Planning campaign")
            if args.plan_only or not plan["runnable"]:
                print(json.dumps(plan, indent=2))
                if not plan["runnable"]:
                    raise SystemExit(1)
                return
            progress(f"Plan ready: {len(plan['cases'])} cases; up to {plan['reserved_trials']} trial pairs. Output: {output}")
            result = invoke("chiyoda_campaign_run", {"config": relative(args.config), "expected_plan_hash": plan["plan_hash"], "output": output}, "Running campaign and exporting evidence", ArchiveProgress(directory))
            progress(f"Campaign saved: {output}/report.md")
        stage = "Verifying measurements and source links"
        if args.replay:
            stage = "Verifying measurements, reconstructing runs and re-rendering images"
            progress("Full replay verification repeats simulation work and can take as long as the campaign.")
        verification = invoke("chiyoda_campaign_verify", {"campaign": output, "replay": args.replay}, stage)
        progress(f"Verification complete. Report: {output}/report.md")
        print(json.dumps({"output": output, "summary": result, "verification": verification}, indent=2))
    except KeyboardInterrupt:
        progress("Cancelled; the tool process has been stopped. Saved files are retained.")
        if (directory / "report.json").is_file():
            progress(f"Campaign already saved. Verify it with --verify-only --output {output} (add --replay for full reconstruction).")
        elif directory.exists():
            progress(f"Partial archive: {output}. Run again with a fresh --output directory.")
        raise SystemExit(130) from None


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        progress(f"Error: {error}")
        raise SystemExit(1) from None
