#!/usr/bin/env python3
"""Exercise the actual macOS replay window using targeted OS input events.

Requires this terminal's macOS Accessibility access and Xcode command line tools.
Creates only a new review directory; the input bundle remains unchanged.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]

def applescript(code):
    return subprocess.check_output(["osascript", "-e", code], text=True).strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--binary", type=Path, default=ROOT / "target/release/chiyoda-replay")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--at-step", type=int, default=0)
    parser.add_argument("--agent")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("this native event check runs on macOS; headless tests are portable")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("output must be an empty directory")
    helper = output / "replay-event"
    subprocess.run(["xcrun", "swiftc", str(ROOT / "scripts/replay_event.swift"), "-o", str(helper)], check=True)
    bundle_path = args.bundle.resolve()
    raw = bundle_path.read_bytes()
    bundle = json.loads(raw)
    trace = bundle["trace"]
    initial = next(frame for frame in trace if frame["step"] == args.at_step)
    person = next((a for a in initial["agents"] if a["id"] == args.agent), initial["agents"][0])
    scenario = bundle["scenario"]["scenario"]
    floor = next(s for s in scenario["surfaces"] if s["id"] == person["surface"])
    command = [str(args.binary.resolve()), str(bundle_path), "--paused", "--at-step", str(args.at_step), "--surface", floor["id"], "--speed", "4", "--snapshot-dir", str(output / "snapshots")]
    if args.candidate:
        command += ["--compare", str(args.candidate.resolve())]
    env = dict(os.environ, CHIYODA_REPLAY_PROFILE="1")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
    checks = []
    pointer = None
    prior = applescript('tell application "System Events" to get unix id of first process whose frontmost is true')
    def event(kind, *values):
        if kind in {"click", "drag", "scroll"}:
            applescript(f'tell application "System Events" to set frontmost of (first process whose unix id is {process.pid}) to true')
            time.sleep(0.2)
        result = subprocess.check_output([str(helper), str(process.pid), kind, *map(str, values)], text=True).strip()
        time.sleep(0.12)
        return result
    def title():
        return applescript(f'tell application "System Events" to tell (first process whose unix id is {process.pid}) to get name of first window')
    def capture(label):
        before = set((output / "snapshots").glob("*.png"))
        event("key", 35)
        for _ in range(60):
            paths = set((output / "snapshots").glob("*.png")) - before
            if paths:
                path = next(iter(paths))
                if path.stat().st_size > 32:
                    checks.append({"check": label, "title": title(), "snapshot": str(path.relative_to(output))})
                    return path
            time.sleep(0.1)
        raise RuntimeError("P did not produce a snapshot")
    def point(x, y):
        bounds = applescript(f'tell application "System Events" to tell (first process whose unix id is {process.pid}) to get {{position, size}} of first window')
        left, top, width, height = map(float, re.findall(r"-?\d+(?:\.\d+)?", bounds))
        return left + x * width / 1200.0, top + (height - 800.0) + y
    def key(code, label):
        event("key", code)
        checks.append({"check": label, "title": title()})
    try:
        for _ in range(200):
            if process.poll() is not None:
                raise RuntimeError(process.communicate())
            present = applescript(f'tell application "System Events" to exists (first process whose unix id is {process.pid})')
            if present == "true":
                try:
                    title()
                    break
                except subprocess.CalledProcessError:
                    pass
            time.sleep(0.2)
        else:
            raise RuntimeError("native viewer did not expose a window")
        applescript(f'tell application "System Events" to set frontmost of (first process whose unix id is {process.pid}) to true')
        pointer = event("position").split()
        capture("initial paused frame")
        if not args.candidate:
            def projected(location, inspected=False):
                width = 852 if inspected else 1136
                scale = min((width - 64) / floor["width_m"], 406 / floor["depth_m"])
                return (32 + width / 2 + (location["x_m"] - floor["origin"]["x_m"] - floor["width_m"] / 2) * scale,
                        317 + (location["y_m"] - floor["origin"]["y_m"] - floor["depth_m"] / 2) * scale)
            event("click", *point(*projected(person)))
            selected = capture("person selected with mouse")
            assert f'inspect {person["id"]}' in title(), title()
            key(2, "hide selected inspector")
            hidden = capture("inspector hidden and plan expanded")
            assert hidden.read_bytes() != selected.read_bytes()
            key(2, "restore selected inspector")
            key(24, "keyboard zoom in")
            assert "zoom 1.25" in title(), title()
            zoomed = capture("keyboard zoom")
            event("drag", *point(458,317), 60, 20)
            panned = capture("right-button pan")
            assert zoomed.read_bytes() != panned.read_bytes(), "pan did not change the plan"
            key(15, "fit camera")
            assert "zoom 1.00" in title(), title()
            event("scroll", *point(458,317), 2)
            capture("wheel zoom")
            assert "zoom 1.00" not in title(), title()
            key(15, "restore fit")
            resources = [r for r in scenario["exits"] if r["surface"] == floor["id"]]
            if resources:
                resource = resources[-1]
                event("down",56)
                event("click", *point(*projected(resource["at"], inspected=True)))
                event("up",56)
                capture("resource selected with shift-click")
                assert f'inspect {resource["id"]}' in title(), title()
            event("click", *point(40,90))
            assert "inspect " not in title(), title()
            key(9, "overview projection")
            assert "overview" in title(), title()
            capture("overview frame")
            key(9, "restore floor plan")
            key(48,"cycle authored floors")
        else:
            candidate = json.loads(args.candidate.read_bytes())
            other_floor = next(f for f in candidate["scenario"]["scenario"]["surfaces"] if f["id"] == floor["id"])
            min_x = min(floor["origin"]["x_m"], other_floor["origin"]["x_m"])
            min_y = min(floor["origin"]["y_m"], other_floor["origin"]["y_m"])
            max_x = max(floor["origin"]["x_m"] + floor["width_m"], other_floor["origin"]["x_m"] + other_floor["width_m"])
            max_y = max(floor["origin"]["y_m"] + floor["depth_m"], other_floor["origin"]["y_m"] + other_floor["depth_m"])
            scale = min(484 / (max_x - min_x), 406 / (max_y - min_y))
            def compared(location, left):
                return (left + 274 + (location["x_m"] - (min_x + max_x)/2)*scale,
                        317 + (location["y_m"] - (min_y + max_y)/2)*scale)
            event("click", *point(*compared(person, 32)))
            capture("baseline person selected")
            assert f'inspect {person["id"]}' in title(), title()
            other_frame = next(f for f in reversed(candidate["trace"]) if f["time_s"] <= initial["time_s"])
            other = next(a for a in other_frame["agents"] if a["id"] == person["id"])
            if other["surface"] == floor["id"]:
                event("click", *point(*compared(other, 620)))
                capture("candidate person selected")
                assert f'inspect {person["id"]}' in title(), title()
            key(24,"shared camera keyboard zoom")
            zoomed = capture("shared zoom")
            assert "zoom 1.25" in title(), title()
            event("drag", *point(894,317), 60, 20)
            panned = capture("shared camera pan from candidate")
            assert zoomed.read_bytes() != panned.read_bytes()
            key(15,"shared fit camera")
            event("scroll", *point(894,317), 2)
            capture("shared wheel zoom from candidate")
            assert "zoom 1.00" not in title(), title()
            key(15,"restore shared fit")
            key(3,"focus selection")
            assert "zoom 2.00" in title(), title()
            capture("focused shared camera")
            key(15,"restore fit after focus")
            key(9,"shared overview")
            capture("shared overview frame")
            key(9,"restore shared plan")
            key(48,"cycle common authored floors")
        key(124,"next recorded frame")
        after_right = capture("stepped right")
        assert int(re.search(r"step-(\d+)",after_right.name)[1]) > args.at_step
        key(123,"previous recorded frame")
        key(30,"increase playback speed")
        assert "8.00" in title(), title()
        key(33,"restore playback speed")
        event("click", *point(400,738))
        sought = capture("timeline seek")
        assert int(re.search(r"step-(\d+)",sought.name)[1]) > args.at_step
        if args.candidate:
            assert "paused" in title()
        event("click", *point(32,738))
        key(49,"play")
        time.sleep(1.0)
        key(49,"pause")
        advanced = capture("playback advanced")
        assert int(re.search(r"step-(\d+)",advanced.name)[1]) > trace[0]["step"]
        event("key",53)
        stdout, stderr = process.communicate(timeout=10)
        assert process.returncode == 0,(stdout,stderr)
        profile = next(json.loads(line.split("=",1)[1]) for line in stderr.splitlines() if line.startswith("replay_profile="))
        report = {"platform":"macOS","input_method":"targeted Core Graphics keys, focused-window mouse events and Accessibility window inspection","source_sha256":hashlib.sha256(raw).hexdigest(),"command":command,"checks":checks,"profile":profile,"exit_code":process.returncode}
        assert bundle_path.read_bytes() == raw
        (output / "report.json").write_text(json.dumps(report,indent=2) + "\n")
        print(json.dumps({"checks":len(checks),"profile":profile,"report":str(output / "report.json")},indent=2))
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        if pointer:
            subprocess.run([str(helper), str(process.pid), "move", *pointer], check=False)
        if prior:
            subprocess.run(["osascript", "-e", f'tell application "System Events" to set frontmost of (first process whose unix id is {int(prior)}) to true'], stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL, check=False)

if __name__ == "__main__":
    main()
