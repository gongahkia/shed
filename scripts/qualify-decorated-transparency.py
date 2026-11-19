#!/usr/bin/env python3
"""Opt-in Java 21 decorated-window gate. NO-GO returns 3; no production changes."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('markdown_baseline', ROOT / 'scripts/benchmark-markdown.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def verdict(events):
    rows = [event for event in events if event.get('operation') == 'verdict']
    return rows[-1]['value'] if rows else 'INCOMPLETE'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    output.mkdir(parents=True)
    fixture = base.fixture(output / 'source.md', 4096, 'mixed')
    (output / 'fixture.json').write_text(json.dumps(fixture, indent=2) + '\n')
    if args.generate_only: return 0
    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    classes = output / 'classes'; classes.mkdir()
    probe = ROOT / 'src/benchmark/java/shed/DecoratedTransparencyProbe.java'
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes), str(probe)], check=True)
    report = {'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'probe_sha256': hashlib.sha256(probe.read_bytes()).hexdigest(),
              'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'environment': base.environment(), 'cases': []}
    for mode in ['opaque', 'attempt']:
        folder = output / mode; (folder / 'home/.shed').mkdir(parents=True)
        (folder / 'home/.shed/config.toml').write_text('schema_version = 1\n"theme" = "shed-light"\n"session.restore.on.start" = false\n')
        cmd = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=false', f'-Duser.home={folder / "home"}',
               '-cp', os.pathsep.join([str(classes), str(jar)]), 'shed.DecoratedTransparencyProbe', mode, fixture['path'], str(folder)]
        env = os.environ.copy()
        for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH']: env.pop(key, None)
        case = {'mode': mode, 'command': cmd, 'launch_epoch_ms': time.time() * 1000, 'rss': []}; began = time.monotonic()
        with (folder / 'stdout.log').open('w') as log:
            child = subprocess.Popen(cmd, cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
            while child.poll() is None and time.monotonic() - began < 30:
                sample = base.command_output(['/bin/ps', '-o', 'rss=', '-p', str(child.pid)])
                try: case['rss'].append({'epoch_ms': time.time() * 1000, 'mib': float(sample['text']) / 1024})
                except (ValueError, KeyError): pass
                time.sleep(.25)
            if child.poll() is None:
                child.terminate()
                try: child.wait(timeout=2)
                except subprocess.TimeoutExpired: child.kill(); child.wait()
                case['timeout'] = True
        log = (folder / 'stdout.log').read_text(errors='replace')
        case.update(exit=child.returncode, events=[json.loads(line[10:]) for line in log.splitlines() if line.startswith('DECORATED ')],
                    protocol_complete='DECORATED_DONE' in log, peak_sampled_rss_mib=max((r['mib'] for r in case['rss']), default=None))
        report['cases'].append(case)
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(mode, child.returncode, verdict(case['events']), flush=True)
        if child.returncode != 0 or not case['protocol_complete']: return 1
        if mode == 'attempt': return 3 if verdict(case['events']) == 'NO_GO' else 2
    return 2


if __name__ == '__main__': sys.exit(main())
