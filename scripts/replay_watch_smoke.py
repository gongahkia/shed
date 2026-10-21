#!/usr/bin/env python3
"""Check native watch loading, failure and recovery with source-bound PNG references.

Requires macOS Accessibility access and Xcode tools, like replay_native_smoke.py.
Writes only to a new output directory and restores the prior frontmost process.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from replay_native_smoke import ROOT, applescript


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=ROOT / "target/release/chiyoda")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("this native check runs on macOS")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("output must be an empty directory")
    binary = str(args.binary.resolve())
    helper = output / "replay-event"
    subprocess.run(["xcrun", "swiftc", str(ROOT / "scripts/replay_event.swift"), "-o", str(helper)], check=True)
    source = json.loads((ROOT / "crates/chiyoda-replay/src/fixtures/main/project.json").read_text())
    references = []
    for index in (1, 2):
        scenario = output / f"reference-{index}.json"
        scenario.write_text(json.dumps(source))
        run = output / f"reference-{index}"
        subprocess.run([binary, "run", str(scenario), "--trace-every", "1", "-o", str(run)], check=True, stdout=subprocess.DEVNULL)
        image = output / f"reference-{index}.png"
        subprocess.run([binary, "view", str(run / "run.json"), "--export-png", str(image)], check=True, stdout=subprocess.DEVNULL)
        references.append((scenario, image))
        source["scenario"]["surfaces"][0]["width_m"] += 4.0
    prior = applescript('tell application "System Events" to get unix id of first process whose frontmost is true')
    watched = output / "watched.json"
    log_path = output / "viewer.stderr"
    checks = []
    with (output / "viewer.stdout").open("w") as stdout, log_path.open("w") as stderr:
        command = [binary, "view", "--watch", str(watched), "--paused", "--snapshot-dir", str(output / "snapshots")]
        process = subprocess.Popen(command, stdout=stdout, stderr=stderr, env=dict(os.environ, CHIYODA_REPLAY_PROFILE="1"))

        def wait_until(predicate, description):
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(log_path.read_text())
                if predicate():
                    return
                time.sleep(0.1)
            raise RuntimeError(f"timed out: {description}; {log_path.read_text()}")

        def key(code):
            subprocess.run([str(helper), str(process.pid), "key", str(code)], check=True)

        def capture():
            before = set((output / "snapshots").glob("*.png"))
            key(35)
            ready = []
            def complete():
                for path in set((output / "snapshots").glob("*.png")) - before:
                    if path.read_bytes().endswith(bytes.fromhex("0000000049454e44ae426082")):
                        ready.append(path)
                        return True
                return False
            wait_until(complete, "complete snapshot")
            return ready[0]

        def check(label, reference=None):
            image = None
            def matches():
                nonlocal image
                image = capture()
                return reference is None or image.read_bytes() == reference.read_bytes()
            wait_until(matches, label)
            checks.append({"check": label, "image": str(image.relative_to(output)),
                           "sha256": hashlib.sha256(image.read_bytes()).hexdigest(),
                           "reference": str(reference.relative_to(output)) if reference else None})
            print(label, flush=True)
            return image

        try:
            wait_until(lambda: "cannot read" in log_path.read_text(), "missing source diagnostic")
            applescript(f'tell application "System Events" to set frontmost of (first process whose unix id is {process.pid}) to true')
            check("missing source leaves only an inactive track")
            watched.write_bytes(references[0][0].read_bytes())
            valid = check("saved valid source matches headless PNG", references[0][1])
            watched.write_text("{")
            wait_until(lambda: "JSON input error" in log_path.read_text(), "invalid source diagnostic")
            check("invalid source preserves exact last-valid pixels", valid)
            watched.write_bytes(references[1][0].read_bytes())
            check("corrected geometry recovers and matches headless PNG", references[1][1])
            key(53)
            process.wait(timeout=10)
            assert process.returncode == 0, log_path.read_text()
            report = {"platform": "macOS", "command": command, "checks": checks,
                      "exit_code": process.returncode, "diagnostics": "viewer.stderr"}
            (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
            subprocess.run(["osascript", "-e", f'tell application "System Events" to set frontmost of (first process whose unix id is {int(prior)}) to true'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    main()
