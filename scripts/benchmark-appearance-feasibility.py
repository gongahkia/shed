#!/usr/bin/env python3
"""Opt-in disposable Swing background-alpha feasibility. No production window or settings are changed."""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('markdown_baseline', ROOT / 'scripts/benchmark-markdown.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def plan(group):
    if group == 'pilot': return [(4096, 1, 1)]
    if group == 'matrix': return [(4096, 1, 1), (1048576, 1, 1), (10485760, 1, 1), (1048576, 2, 1), (1048576, 1, 2)]
    if group == 'native': return [(1048576, 2, 1)]
    raise ValueError(group)


def summarize(samples):
    result = {}
    for operation in sorted({s['operation'] for s in samples}):
        for key in ['edt_ms', 'completed_paint_ms']:
            values = sorted(s[key] for s in samples if s['operation'] == operation and s[key] >= 0)
            if values:
                result[f'{operation}/{key}'] = {'n': len(values), 'p50': values[math.ceil(len(values) * .5) - 1],
                    'p95': values[math.ceil(len(values) * .95) - 1], 'max': max(values)}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--group', choices=['pilot', 'matrix', 'native'], default='pilot')
    parser.add_argument('--runs', type=int, default=1)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    if not 1 <= args.runs <= 2:
        parser.error('Bounded 1–2 runs only')
    if shutil.disk_usage(output.parent).free < 1024**3:
        parser.error('At least 1 GiB scratch headroom required')
    output.mkdir(parents=True)
    manifest = {}
    for size, _, _ in plan(args.group):
        if size not in manifest:
            file = output / f'source-{size}.md'; manifest[size] = base.fixture(file, size, 'mixed')
    (output / 'fixtures.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if args.generate_only: return 0
    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    classes = output / 'classes'; classes.mkdir()
    probe = ROOT / 'src/benchmark/java/shed/AppearanceTransparencyProbe.java'
    capture = ROOT / 'src/benchmark/java/shed/OwnedWindowCapture.java'
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes), str(probe), str(capture)], check=True)
    report = {'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'probe_sha256': hashlib.sha256(probe.read_bytes()).hexdigest(),
              'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'capture_sha256': hashlib.sha256(capture.read_bytes()).hexdigest(), 'environment_before': base.environment(),
              'java': base.command_output([str(jdk / 'bin/java'), '-version']), 'arguments': vars(args) | {'jar': str(jar), 'output': str(output)}, 'cases': []}
    for size, panes, windows in plan(args.group):
        for run in range(args.runs):
            for alpha in ([255, 218] if run % 2 == 0 else [218, 255]):
                folder = output / f'{size}-p{panes}-w{windows}-r{run}-a{alpha}'
                (folder / 'home/.shed').mkdir(parents=True)
                (folder / 'home/.shed/config.toml').write_text('schema_version = 1\n"theme" = "shed-light"\n"session.restore.on.start" = false\n')
                cmd = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=false', f'-Duser.home={folder / "home"}']
                if args.group == 'native': cmd.append('-XX:NativeMemoryTracking=summary')
                cmd += ['-cp', os.pathsep.join([str(classes), str(jar)]), 'shed.AppearanceTransparencyProbe',
                        manifest[size]['path'], str(alpha), str(panes), str(windows), str(folder), str(args.group == 'native').lower()]
                env = os.environ.copy()
                for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH']: env.pop(key, None)
                case = {'size': size, 'panes': panes, 'windows': windows, 'run': run, 'alpha': alpha, 'command': cmd,
                        'launch_epoch_ms': time.time() * 1000, 'rss': []}
                started = time.monotonic()
                with (folder / 'stdout.log').open('w') as log:
                    child = subprocess.Popen(cmd, cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
                    while child.poll() is None and time.monotonic() - started < 60:
                        reading = base.command_output(['/bin/ps', '-o', 'rss=', '-p', str(child.pid)])
                        try: case['rss'].append({'seconds': time.monotonic() - started, 'epoch_ms': time.time() * 1000, 'mib': float(reading['text']) / 1024})
                        except (ValueError, KeyError): pass
                        time.sleep(.25)
                    if child.poll() is None:
                        child.terminate()
                        try: child.wait(timeout=3)
                        except subprocess.TimeoutExpired: child.kill(); child.wait()
                        case['timeout'] = True
                text = (folder / 'stdout.log').read_text(errors='replace')
                case.update(exit=child.returncode, wall_seconds=time.monotonic() - started,
                    state='OK' if child.returncode == 0 and 'AP_DONE' in text else 'FAILED',
                    samples=[json.loads(line[10:]) for line in text.splitlines() if line.startswith('AP_SAMPLE ')],
                    metadata=[json.loads(line[8:]) for line in text.splitlines() if line.startswith('AP_META ')])
                case['summary'] = summarize(case['samples']); case['peak_sampled_rss_mib'] = max((r['mib'] for r in case['rss']), default=None)
                case['fixture_sha256_after'] = hashlib.sha256(Path(manifest[size]['path']).read_bytes()).hexdigest()
                report['cases'].append(case); (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
                print(folder.name, case['state'], flush=True)
    report['environment_after'] = base.environment(); (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if all(c['state'] == 'OK' for c in report['cases']) else 1


if __name__ == '__main__': sys.exit(main())
