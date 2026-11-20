#!/usr/bin/env python3
"""Opt-in, bounded paired focus/lifecycle checks against packaged Shed; requires a desktop."""
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
SIZES = [4096, 102400, 1048576]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--baseline-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--generate-only', action='store_true')
    parser.add_argument('--lifecycle-only', action='store_true', help='Measure lifecycle/editing without the separate native Robot focus qualification')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    output.mkdir(parents=True)
    fixtures = {size: base.fixture(output / f'source-{size}.md', size, 'mixed') for size in SIZES}
    (output / 'fixtures.json').write_text(json.dumps(fixtures, indent=2) + '\n')
    if args.generate_only:
        return 0
    artifacts = [('baseline', args.baseline_jar.resolve(strict=True)), ('candidate', args.jar.resolve(strict=True))]
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    sources = [ROOT / f'src/benchmark/java/shed/{name}.java' for name in ['ActivePanePerformanceProbe', 'MarkdownPerformanceProbe', 'MarkdownLexicalProbe']]
    classes = output / 'classes'
    classes.mkdir()
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(artifacts[1][1]), '-d', str(classes), *map(str, sources)], check=True, timeout=40)
    report = {'environment_before': base.environment(), 'fixtures': fixtures, 'native_focus_requested': not args.lifecycle_only,
              'jars': {label: hashlib.sha256(path.read_bytes()).hexdigest() for label, path in artifacts},
              'probe_hashes': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}, 'cases': []}
    for index, size in enumerate(SIZES):
        for label, jar in artifacts if index % 2 == 0 else reversed(artifacts):
            run = output / f'{label}-{size}'
            (run / 'home/.shed').mkdir(parents=True)
            (run / 'home/.shed/config.toml').write_text('schema_version = 1\n"theme" = "shed-light"\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n')
            command = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=false', f'-Duser.home={run / "home"}',
                       '-cp', os.pathsep.join([str(classes), str(jar)]), 'shed.ActivePanePerformanceProbe', fixtures[size]['path'], str(label == 'candidate').lower(), str(run)]
            if args.lifecycle_only:
                command.append('lifecycle')
            env = os.environ.copy()
            for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH']:
                env.pop(key, None)
            case = {'artifact': label, 'bytes': size, 'command': command, 'rss': [], 'load': os.getloadavg()}
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
            text = (run / 'stdout.log').read_text(errors='replace')
            case.update(exit=child.returncode, complete='PANE_DONE' in text,
                        events=[json.loads(line[5:]) for line in text.splitlines() if line.startswith('PANE ')])
            report['cases'].append(case)
            (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
            print(label, size, case['exit'], case['complete'], flush=True)
            if case['exit'] != 0 or not case['complete']:
                return 1
    report['environment_after'] = base.environment()
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
