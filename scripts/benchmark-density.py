#!/usr/bin/env python3
"""Opt-in paired packaged-editor density qualification; finite child processes and scratch homes only."""
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
# bytes, wrapping, source splits, UI font override, sidebar
CASES = [(4096, False, False, 0, True), (102400, True, False, 0, True),
         (1048576, False, False, 0, False), (1048576, False, True, 0, False),
         (1048576, True, False, 0, False), (4096, False, False, 24, False)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--baseline-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--generate-only', action='store_true')
    parser.add_argument('--pilot', action='store_true', help='Run only the small sidebar pair')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    output.mkdir(parents=True)
    folder = output / 'fixtures'
    folder.mkdir()
    fixtures = {size: base.fixture(folder / f'source-{size}.md', size, 'mixed') for size in sorted({c[0] for c in CASES})}
    (folder / 'tab-control.md').write_text('# Tab control\nbody\n')
    for index in range(30):
        (folder / f'tree-row-{index:02}.txt').write_text('tree control\n')
    (output / 'fixtures.json').write_text(json.dumps(fixtures, indent=2) + '\n')
    if args.generate_only:
        return 0
    jar, baseline = args.jar.resolve(strict=True), args.baseline_jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    sources = [ROOT / f'src/benchmark/java/shed/{name}.java' for name in ['DensityPerformanceProbe', 'MarkdownPerformanceProbe', 'MarkdownLexicalProbe']]
    classes = output / 'classes'
    classes.mkdir()
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes), *map(str, sources)], check=True, timeout=40)
    report = {'environment_before': base.environment(), 'fixtures': fixtures,
              'jars': {label: hashlib.sha256(path.read_bytes()).hexdigest() for label, path in [('baseline', baseline), ('candidate', jar)]},
              'probe_hashes': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}, 'cases': []}
    for index, (size, wrap, split, font, sidebar) in enumerate(CASES[:1] if args.pilot else CASES):
        pair = [('baseline', baseline, False), ('candidate', jar, True)]
        if index % 2:
            pair.reverse()
        for label, artifact, supported in pair:
            run = output / f'{label}-{size}-wrap{int(wrap)}-split{int(split)}-font{font}-tree{int(sidebar)}'
            (run / 'home/.shed').mkdir(parents=True)
            (run / 'home/.shed/config.toml').write_text('schema_version = 1\n"theme" = "shed-light"\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n')
            command = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=false', f'-Duser.home={run / "home"}',
                       '-cp', os.pathsep.join([str(classes), str(artifact)]), 'shed.DensityPerformanceProbe', fixtures[size]['path'],
                       str(supported).lower(), str(wrap).lower(), str(split).lower(), str(run), str(font), str(sidebar).lower()]
            env = os.environ.copy()
            for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH']:
                env.pop(key, None)
            case = {'artifact': label, 'bytes': size, 'wrap': wrap, 'split': split, 'ui_font': font, 'sidebar': sidebar,
                    'command': command, 'rss': [], 'load': os.getloadavg()}
            began = time.monotonic()
            with (run / 'stdout.log').open('w') as log:
                child = subprocess.Popen(command, cwd=run, env=env, stdout=log, stderr=subprocess.STDOUT)
                while child.poll() is None and time.monotonic() - began < 90:
                    reading = base.command_output(['/bin/ps', '-o', 'rss=', '-p', str(child.pid)])
                    try:
                        case['rss'].append({'epoch_ms': time.time() * 1000, 'mib': float(reading['text']) / 1024})
                    except (ValueError, KeyError):
                        pass
                    time.sleep(.25)
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait()
                    case['timeout'] = True
            log = (run / 'stdout.log').read_text(errors='replace')
            case.update(exit=child.returncode, complete='DENSITY_DONE' in log,
                        events=[json.loads(line[8:]) for line in log.splitlines() if line.startswith('DENSITY ')])
            report['cases'].append(case)
            (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
            print(label, size, wrap, split, font, sidebar, case['exit'], case['complete'], flush=True)
            if case['exit'] != 0 or not case['complete']:
                return 1
    report['environment_after'] = base.environment()
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
