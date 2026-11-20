#!/usr/bin/env python3
"""Opt-in bounded whole-IDE controls. New scratch output, local peers, no production changes."""
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
SOURCES = ['WholeIdeServicesProbe', 'WholeIdeDesktopProbe', 'MarkdownPerformanceProbe', 'MarkdownLexicalProbe', 'OwnedWindowCapture']
CASES = [('empty', 'basic', 'none'), ('project', 'basic', 'project-1000'),
         ('code-small-1', 'basic', 'Main.java'), ('code-small-2', 'basic', 'Main.java'), ('code-small-3', 'basic', 'Main.java'),
         ('markdown-load-1', 'basic', 'source-102400.md'), ('markdown-load-2', 'basic', 'source-102400.md'), ('markdown-load-3', 'basic', 'source-102400.md'),
         ('code-1m', 'basic', 'source-1048576.java'), ('code-10m', 'basic', 'source-10485760.java'),
         ('interference', 'interference', 'Main.java'), ('mixed-visual', 'visual', 'mixed.md')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--group', choices=['services', 'desktop', 'all'], default='all')
    parser.add_argument('--case', choices=[case[0] for case in CASES], help='Select one declared desktop case')
    parser.add_argument('--capture-owned', action='store_true', help='Attempt only the synthetic child-owned window; never request OS permissions')
    parser.add_argument('--plan-only', action='store_true', help='Write finite plan without fixtures, toolchain, JVM or native access')
    args = parser.parse_args()
    if args.case and args.group == 'services':
        parser.error('--case requires desktop work')
    selected = [case for case in CASES if args.case is None or case[0] == args.case]
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    output.mkdir(parents=True)
    plan = {'group': args.group, 'cases': selected if args.group != 'services' else [], 'project_file_counts': [100, 1000, 5000],
            'child_timeout_seconds': 120, 'heap': '64..512 MiB', 'capture_owned': args.capture_owned,
            'production_mutations': False, 'native_permission_requests': False}
    (output / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
    if args.plan_only:
        return 0
    rejection = {'result': 'BLOCKED', 'reason': 'B0.1: native process/PTY confinement unqualified; no runtime launched'}
    (output / 'blocked.json').write_text(json.dumps(rejection, indent=2) + '\n')
    print(rejection['reason'], file=sys.stderr)
    return 2

    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    spec = importlib.util.spec_from_file_location('markdown_controls', ROOT / 'scripts/benchmark-markdown.py')
    base = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(base)
    report = {'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'environment_before': base.environment(),
              'jdk': base.command_output([str(jdk / 'bin/java'), '-version']), 'cases': [], 'sources': {}}
    classes = output / 'classes'
    classes.mkdir()
    sources = [ROOT / f'src/benchmark/java/shed/{name}.java' for name in SOURCES]
    for p in sources:
        report['sources'][str(p.relative_to(ROOT))] = hashlib.sha256(p.read_bytes()).hexdigest()
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes), *map(str, sources)], check=True, timeout=40)
    fixtures = output / 'fixtures'
    fixtures.mkdir()
    for count in [100, 1000, 5000]:
        root = fixtures / f'project-{count}'
        for i in range(count):
            p = root / f'dir-{i // 100:03}' / f'File{i:05}.java'
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(f'class File{i} {{}}\n' + ('unique-b0-target\n' if i == count-1 else 'ordinary source\n'))
    for size in [4096, 1048576, 10485760]:
        line = b'// ordinary code control alpha beta gamma delta\n'
        path = fixtures / ('Main.java' if size == 4096 else f'source-{size}.java')
        path.write_bytes((line * (size // len(line) + 1))[:size])
    base.fixture(fixtures / 'source-102400.md', 102400, 'mixed')
    (fixtures / 'mixed.md').write_text('# Source alpha\nUnicode: e\u0301, क्ष, 👩\u200d💻, 中文\n' * 10)
    git_env = os.environ.copy()
    git_env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
    subprocess.run(['/usr/bin/git', 'init', '--quiet', '--template=', str(fixtures / 'project-1000')], env=git_env, check=True, timeout=5)
    report['fixtures'] = {str(p.relative_to(fixtures)): {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                          for p in fixtures.rglob('*') if p.is_file() and '.git' not in p.parts}
    env = os.environ.copy()
    for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH']:
        env.pop(key, None)
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)

    def child(label, probe, arguments):
        run = output / label
        (run / 'home/.shed').mkdir(parents=True)
        (run / 'home/.shed/config.toml').write_text('schema_version = 1\n"theme" = "shed-light"\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n')
        command = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=false', f'-Duser.home={run / "home"}',
                   f'-Db0.capture.owned={str(args.capture_owned).lower()}', '-cp', os.pathsep.join([str(classes), str(jar)]), f'shed.{probe}', *arguments]
        case = {'label': label, 'command': command, 'load_before': os.getloadavg(), 'rss': [], 'spawn_epoch_ms': time.time() * 1000}
        start = time.monotonic()
        with (run / 'stdout.log').open('w') as log:
            process = subprocess.Popen(command, cwd=fixtures, env=env, stdout=log, stderr=subprocess.STDOUT)
            while process.poll() is None and time.monotonic() - start < 120:
                reading = base.command_output(['/bin/ps', '-o', 'rss=', '-p', str(process.pid)])
                try:
                    case['rss'].append({'elapsed_s': time.monotonic()-start, 'mib': float(reading['text']) / 1024})
                except (ValueError, KeyError):
                    pass
                time.sleep(.25)
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                case['timeout'] = True
        text = (run / 'stdout.log').read_text(errors='replace')
        case.update(exit=process.returncode, elapsed_s=time.monotonic()-start, load_after=os.getloadavg(),
                    events=[json.loads(line[3:]) for line in text.splitlines() if line.startswith('B0 ')],
                    logged_exceptions=[line for line in text.splitlines() if 'Exception' in line or 'Error' in line])
        report['cases'].append(case)
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(label, case['exit'], len(case['events']), flush=True)
        return case['exit'] == 0

    if args.group in ['services', 'all']:
        child('services', 'WholeIdeServicesProbe', [str(fixtures)])
    if args.group in ['desktop', 'all']:
        for label, mode, target in selected:
            child(label, 'WholeIdeDesktopProbe', [mode, target if target == 'none' else str(fixtures / target), str(fixtures)])
    report['environment_after'] = base.environment()
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if all(c['exit'] == 0 for c in report['cases']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
