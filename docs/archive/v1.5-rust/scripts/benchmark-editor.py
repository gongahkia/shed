#!/usr/bin/env python3
"""Measure an installed JAR with a fixed UI workload and an isolated configuration."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import queue
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
BUDGETS = {"window_ms": 2000, "source_ready_ms": 3000, "startup_ms": 10000, "edit_paint_ms": 16, "picker_ms": 50, "heap_used_mib": 384, "rss_mib": 768}


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/shed-2.0.0.jar")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--label", default="working-tree")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.runs < 3:
        parser.error("Use at least 3 fresh-process runs")
    jar = args.jar.resolve(strict=True)
    java_home = Path(subprocess.check_output([str(ROOT / "scripts/resolve-toolchain.sh"), "home"], text=True).strip())
    probe = ROOT / "src/benchmark/java/shed/EditorWorkflowProbe.java"
    samples = []
    with tempfile.TemporaryDirectory(prefix="shed-benchmark-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([str(java_home / "bin/javac"), "-cp", str(jar), "-d", str(classes), str(probe)], check=True)
        fixture = work / "fixture.txt"
        fixture.write_text("".join(f"line {i:06d} alpha beta gamma delta epsilon zeta eta theta iota kappa\n" for i in range(20000)))
        for iteration in range(args.runs):
            home = work / f"home-{iteration}"
            (home / ".shed").mkdir(parents=True)
            (home / ".shed/config.toml").write_text('schema_version = 1\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n')
            command = [str(java_home / "bin/java"), "-Xms64m", "-Xmx512m", "-Djava.awt.headless=false", f"-Duser.home={home}",
                       "-cp", os.pathsep.join((str(classes), str(jar))), "shed.EditorWorkflowProbe", str(fixture)]
            lines = queue.Queue()
            started = time.time() * 1000
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            def drain():
                for line in process.stdout:
                    lines.put(line)
                lines.put(None)
            reader = threading.Thread(target=drain, daemon=True)
            reader.start()
            sample = {}
            logs = []
            try:
                while True:
                    line = lines.get(timeout=120)
                    if line is None:
                        break
                    logs.append(line.rstrip())
                    if line.startswith("SHED_WINDOW "):
                        sample["window_ms"] = float(line.split()[1]) - started
                    elif line.startswith("SHED_SOURCE "):
                        sample["source_ready_ms"] = float(line.split()[1]) - started
                    elif line.startswith("SHED_READY "):
                        sample["startup_ms"] = float(line.split()[1]) - started
                        try:
                            sample["rss_mib"] = int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(process.pid)], text=True).strip()) / 1024
                        except (ValueError, subprocess.SubprocessError, OSError):
                            sample["rss_mib"] = None
                    elif line.startswith("SHED_RESULT "):
                        sample.update(json.loads(line.removeprefix("SHED_RESULT ")))
                if process.wait(timeout=10) != 0 or "edit_paint_ms" not in sample:
                    raise RuntimeError("Probe failed:\n" + "\n".join(logs))
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
                reader.join(timeout=5)
            if sample["language_servers"] != 0:
                raise RuntimeError("Baseline workload unexpectedly started a language server")
            samples.append(sample)
            print(f"{args.label}: run {iteration + 1}/{args.runs}, startup {sample['startup_ms']:.0f} ms", flush=True)
        metrics = {}
        for metric, budget in BUDGETS.items():
            values = []
            for sample in samples:
                value = sample.get(metric)
                values.extend(value if isinstance(value, list) else [] if value is None else [value])
            metrics[metric] = {"p50": percentile(values, .50), "p95": percentile(values, .95), "budget": budget,
                               "pass": percentile(values, .95) <= budget, "samples": len(values)} if values else {"unavailable": True}
        report = {"label": args.label, "measured_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                  "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "probe_sha256": hashlib.sha256(probe.read_bytes()).hexdigest(),
                  "machine": platform.platform(), "cpu_count": os.cpu_count(), "java_heap": "64 MiB initial / 512 MiB maximum",
                  "fixture": {"bytes": fixture.stat().st_size, "lines": 20000, "picker_paths": 50000},
                  "method": "Fresh JVM per run, fresh config, 1-second settle; no OS cache purge. Full UI + file + synchronous paint readiness. Heap sampled without explicit GC. 10 warmups and 25 document-edit-to-synchronous-paint / picker samples per process. No external language servers, no user extensions.",
                  "metrics": metrics, "runs": samples}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(metrics, indent=2))
        return 0 if all(metric.get("pass", True) for metric in metrics.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
