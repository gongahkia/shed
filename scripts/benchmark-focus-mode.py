#!/usr/bin/env python3
"""Opt-in bounded production focus/normal controls, outside routine Maven tests."""
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
CASES = [(4096, False, False), (102400, False, False), (1048576, False, False),
         (10485760, False, False), (1048576, True, False), (1048576, False, True), (1048576, True, True), (102400, True, False)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--baseline-jar', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    output.mkdir(parents=True)
    fixtures = {size: base.fixture(output / f'source-{size}.md', size, 'mixed') for size in sorted({case[0] for case in CASES})}
    (output / 'fixtures.json').write_text(json.dumps(fixtures, indent=2) + '\n')
    if args.generate_only: return 0
    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    sources = [ROOT / f'src/benchmark/java/shed/{name}.java' for name in ['FocusModePerformanceProbe', 'MarkdownPerformanceProbe', 'MarkdownLexicalProbe']]
    classes = output / 'classes'; classes.mkdir()
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes), *map(str, sources)], check=True)
    artifacts = [('current', jar)]
    if args.baseline_jar: artifacts.insert(0, ('baseline', args.baseline_jar.resolve(strict=True)))
    report = {'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'artifacts': {label: hashlib.sha256(path.read_bytes()).hexdigest() for label, path in artifacts}, 'source_hashes': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
              'environment_before': base.environment(), 'cases': []}
    plan = [(label, artifact, size, wrap, split) for index, (size, wrap, split) in enumerate(CASES)
            for label, artifact in (artifacts if index % 2 == 0 else list(reversed(artifacts)))]
    for label, artifact, size, wrap, split in plan:
        folder = output / f'{label}-{size}-wrap{int(wrap)}-split{int(split)}'; (folder / 'home/.shed').mkdir(parents=True)
        (folder / 'home/.shed/config.toml').write_text('schema_version = 1\n"theme" = "shed-light"\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n')
        command = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=false', f'-Duser.home={folder / "home"}',
                   '-cp', os.pathsep.join([str(classes), str(artifact)]), 'shed.FocusModePerformanceProbe', fixtures[size]['path'], str(wrap).lower(), str(split).lower()]
        case = {'artifact': label, 'bytes': size, 'wrap': wrap, 'split': split, 'command': command, 'rss': [], 'load': os.getloadavg()}
        env = os.environ.copy()
        for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH']: env.pop(key, None)
        start = time.monotonic()
        with (folder / 'stdout.log').open('w') as log:
            child = subprocess.Popen(command, cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
            while child.poll() is None and time.monotonic() - start < 60:
                reading = base.command_output(['/bin/ps', '-o', 'rss=', '-p', str(child.pid)])
                try: case['rss'].append({'epoch_ms': time.time() * 1000, 'mib': float(reading['text']) / 1024})
                except (ValueError, KeyError): pass
                time.sleep(.25)
            if child.poll() is None:
                child.terminate()
                try: child.wait(timeout=2)
                except subprocess.TimeoutExpired: child.kill(); child.wait()
                case['timeout'] = True
        log = (folder / 'stdout.log').read_text(errors='replace')
        case.update(exit=child.returncode, complete='FOCUS_DONE' in log, events=[json.loads(line[6:]) for line in log.splitlines() if line.startswith('FOCUS ')])
        report['cases'].append(case)
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(label, size, wrap, split, case['exit'], case['complete'], flush=True)
    report['environment_after'] = base.environment(); (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if all(case['exit'] == 0 and case['complete'] for case in report['cases']) else 1


if __name__ == '__main__': sys.exit(main())
