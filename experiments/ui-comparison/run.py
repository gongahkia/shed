#!/usr/bin/env python3
"""Isolated prototype build/launch and sequential, fresh-process comparison. Python 3 stdlib only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import time
import threading
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
BUILD = ROOT / "build"


def java_home():
    return subprocess.check_output([str(REPO / "scripts/resolve-toolchain.sh"), "home"], text=True).strip()


def build(flavor):
    if not (REPO / "target/shed-2.0.0.jar").exists():
        raise SystemExit("Run make build at the repository root first.")
    if flavor == "compose":
        subprocess.run([str(ROOT / "gradlew"), "writeClasspath"], check=True)
    else:
        classes = BUILD / "swing-classes"
        shutil.rmtree(classes, ignore_errors=True)
        classes.mkdir(parents=True)
        subprocess.run([java_home() + "/bin/javac", "--release", "21", "-cp", str(REPO / "target/shed-2.0.0.jar"),
                        "-d", str(classes), *map(str, sorted((ROOT / "src/main/java/shed").glob("*.java")))], check=True)
        subprocess.run([java_home() + "/bin/jar", "--create", "--file", str(BUILD / "swing-prototype.jar"), "-C", str(classes), "."], check=True)


def classpath(flavor):
    if flavor == "compose":
        return (BUILD / "runtime-classpath.txt").read_text().strip()
    return os.pathsep.join(map(str, [BUILD / "swing-prototype.jar", REPO / "target/shed-2.0.0.jar"]))


def command(flavor, mode, output):
    extra = ["-Dcompose.swing.render.on.graphics=true"] if flavor == "compose" and os.environ.get("LAB_COMPOSE_OFFSCREEN") == "1" else []
    return [java_home() + "/bin/java", "-Xms64m", "-Xmx512m", *extra, "-cp", classpath(flavor), "shed.LabHost", flavor, mode, str(output)]


def artifacts(flavor):
    return [{"name": p.name, "bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in map(Path, classpath(flavor).split(os.pathsep)) if p.is_file()]


def compare(output, runs):
    output.mkdir(parents=True, exist_ok=True)
    report = {"host": platform.platform(), "java": subprocess.check_output([java_home() + "/bin/java", "-version"], stderr=subprocess.STDOUT, text=True),
              "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
              "working_tree": "uncommitted modernization stages; artifact hashes identify measured builds",
              "method": "Alternating fresh JVMs, same real editor/fixture/1120x792/settings/64–512MiB heap. Ready includes 500ms settle. RSS at LAB_READY via ps. 10 warmups +25 synchronous replace→paintImmediately samples; undo outside interval. No event-queue/native-display/async-convergence latency.",
              "compose_render_mode": "experimental offscreen" if os.environ.get("LAB_COMPOSE_OFFSCREEN") == "1" else "default",
              "artifacts": {f: artifacts(f) for f in ("swing", "compose")}, "samples": []}
    # Hash before timed runs. Do not build, test or inspect large artifacts concurrently.
    for iteration in range(runs):
        for flavor in (["swing", "compose"] if iteration % 2 == 0 else ["compose", "swing"]):
            dest = output / f"{iteration+1:02d}-{flavor}"
            dest.mkdir()
            rss = None
            process_ready = None
            with (dest / "process.log").open("w") as log:
                launched = time.perf_counter()
                proc = subprocess.Popen(command(flavor, "measure", dest), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                watchdog = threading.Timer(180, proc.kill); watchdog.start()
                for line in proc.stdout:
                    log.write(line)
                    if line.startswith("LAB_READY "):
                        process_ready = (time.perf_counter()-launched)*1000
                        try:
                            rss = int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(proc.pid)], text=True).strip()) / 1024
                        except (OSError, ValueError, subprocess.CalledProcessError):
                            pass
                code = proc.wait(); watchdog.cancel()
            if code:
                raise SystemExit(f"{flavor} failed ({code}); see {dest}/process.log")
            sample = json.loads((dest / "measurement.json").read_text())
            sample.update(iteration=iteration+1, rss_mib=rss, process_to_ready_ms=process_ready)
            report["samples"].append(sample)
            print(flavor, iteration+1, "ready", round(sample["ready_ms"]), "ms; RSS", rss, flush=True)
            (output / "comparison.json").write_text(json.dumps(report, indent=2))
    def quantiles(values):
        values = sorted(values)
        if not values:
            return None
        return {"p50": statistics.median(values), "p95": values[max(0, int(len(values)*.95+.999999)-1)]}
    report["summary"] = {}
    for flavor in ("swing", "compose"):
        samples = [s for s in report["samples"] if s["flavor"] == flavor]
        report["summary"][flavor] = {key: quantiles([s[key] for s in samples if s[key] is not None])
                                      for key in ("process_to_ready_ms", "ready_ms", "heap_used_mib", "rss_mib")}
        report["summary"][flavor]["edit_paint_ms"] = quantiles([v for s in samples for v in s["edit_paint_ms"]])
    (output / "comparison.json").write_text(json.dumps(report, indent=2))


def package(flavor, output):
    """Like-for-like portable launch archive, external Java 21; not an OS installer."""
    output.mkdir(parents=True, exist_ok=True)
    entries = list(map(Path, classpath(flavor).split(os.pathsep)))
    if not all(p.is_file() for p in entries):
        raise SystemExit("Build this flavor first; classpath must contain release JARs only")
    manifest = artifacts(flavor)
    (output / f"{flavor}-artifacts.json").write_text(json.dumps(manifest, indent=2))
    cp = ":".join("$lab_dir/lib/" + p.name for p in entries)
    launch = '#!/bin/sh\nset -eu\nlab_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nexec java -Xms64m -Xmx512m -cp "' + cp + '" shed.LabHost ' + flavor + ' "${1:-manual}" "${2:-/tmp/shed-ui-package-' + flavor + '}"\n'
    with zipfile.ZipFile(output / f"{flavor}-prototype.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for p in entries:
            archive.write(p, "lib/" + p.name)
        info = zipfile.ZipInfo("run.sh"); info.external_attr = 0o100755 << 16
        archive.writestr(info, launch)
        archive.writestr("README.txt", "Experiment only. Requires Java 21 on PATH. Extract then ./run.sh. Compose native JAR is host-specific; rebuild per target. Not a bundled JRE or signed OS installer.\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["build", "manual", "capture", "keyboard", "measure", "compare", "package"])
    parser.add_argument("flavor", nargs="?", choices=["swing", "compose"])
    parser.add_argument("--output", type=Path, default=Path("/tmp/shed-ui-comparison-evidence"))
    parser.add_argument("--runs", type=int, default=5)
    args = parser.parse_args()
    if args.action == "compare":
        compare(args.output.resolve(), args.runs)
    else:
        if not args.flavor:
            parser.error("flavor is required")
        if args.action == "build":
            build(args.flavor)
        elif args.action == "package":
            package(args.flavor, args.output.resolve())
        else:
            subprocess.run(command(args.flavor, args.action, args.output.resolve()), check=True)
            if args.action == "capture":
                evidence = json.loads((args.output / "gallery.json").read_text())
                required = ("real_document_activation", "accessible_query", "accessible_invalid_update", "accessible_valid_update",
                            "persisted_on_disk", "accessible_document_action", "editor_local_clipboard", "real_editor_popup")
                failures = [key for key in required if evidence["interaction"].get(key) is not True]
                if failures:
                    raise SystemExit("Interaction checks failed: " + ", ".join(failures))
                if not all(c.get("menu_height", 0) > 0 for c in evidence["captures"]):
                    raise SystemExit("Embedded menu placement changed during capture")
            if args.action in ("keyboard", "capture"):
                native = json.loads((args.output / "keyboard.json").read_text()) if args.action == "keyboard" else evidence["native_keyboard"]
                print("Native keyboard:", native)
                if native.get("status") == "native events exercised on owned focused window":
                    if not all(native.get(k) is True for k in ("activation", "search_typing", "escape_dismissed", "editor_focus_restored")):
                        raise SystemExit("Native interaction assertions failed")
                elif args.action == "keyboard":
                    raise SystemExit(2)
