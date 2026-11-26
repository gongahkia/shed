#!/usr/bin/env python3
"""Opt-in fixed Java workbench/client test; disposable filesystem/preferences, no shell or real model."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
sys.dont_write_bytecode = True
from native_fixture import Fixture, UnsafeFixture, checked, digest

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--chat', action='store_true', help='Exercise the ongoing CLI chat and editor visualization')
    parser.add_argument('--inline', action='store_true', help='With --chat: opt into the interactive completion demo; click the owned editor and avoid typing')
    parser.add_argument('--demo', action='store_true', help='Pause on the owned dialogs and reading stage for a visible demonstration')
    args = parser.parse_args()
    if args.inline and not args.chat:
        parser.error('--inline requires --chat; it is an explicitly interactive fixture')
    jdk, jar = args.jdk.resolve(strict=True), args.jar.resolve(strict=True)
    output = args.output.absolute()
    if output.exists() or output.resolve() != output:
        parser.error('Output must be a new canonical directory')
    for tool in ('java', 'javac'):
        if not (jdk / 'bin' / tool).is_file():
            parser.error('Existing JDK required')
    output.mkdir(parents=True)
    f = Fixture()
    process = None
    report = {'boundary': 'source-reviewed direct Java fixture; not an OS sandbox', 'native_shell': 'NOT_USED', 'jar_sha256': digest(jar)}
    try:
        f.validate()
        project, work, classes = (f.run_root / n for n in ('project', 'live', 'classes'))
        for directory in (project, work, classes):
            directory.mkdir(mode=0o700)
            checked(directory, f.root, directory=True)
        sentinel = f.run_root / 'external-sentinel'
        sentinel.write_text('Synthetic external configuration; keep intact.\n')
        before, stamp = digest(sentinel), sentinel.stat().st_mtime_ns
        env = dict(f.env, PATH=str(f.run_root / 'no-executables'), SHED_PORTABLE_FIXTURE_ROOT=str(f.root), SHED_LIVE_VISUAL_ONLY='1')
        if args.demo:
            env['SHED_DELEGATION_DEMO'] = '1'
        if args.inline:
            env['SHED_INLINE_DEMO'] = '1'
        props = ['-Duser.home=' + env['HOME'], '-Djava.io.tmpdir=' + env['TMPDIR'], '-Djava.util.prefs.userRoot=' + str(f.run_root / 'config')]
        probe = 'AgentCliChatDesktopProbe' if args.chat else 'AgentDelegationDesktopProbe'
        source = ROOT / 'src/benchmark/java/shed' / (probe + '.java')
        report['probe_sha256'] = digest(source)
        compile_cmd = [str(jdk / 'bin/javac'), *['-J' + p for p in props], '-proc:none', '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), str(source)]
        compiled = subprocess.run(compile_cmd, env=env, cwd=f.run_root, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30)
        report.update(compile_exit=compiled.returncode, compile_output=compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise UnsafeFixture('Probe compilation failed')
        desktop_env = dict(env)
        for key in ('DISPLAY', 'XAUTHORITY'):
            if os.environ.get(key):
                desktop_env[key] = os.environ[key]
        command = [str(jdk / 'bin/java'), '-Djava.awt.headless=false', *props, '-cp', str(classes) + os.pathsep + str(jar), 'shed.' + probe, str(work), str(project)]
        report['command'] = command
        with (work / 'stdout.log').open('w') as log:
            process = subprocess.Popen(command, cwd=project, env=desktop_env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
            try:
                report['exit'] = process.wait(timeout=110)
            except subprocess.TimeoutExpired:
                report['timeout'] = True
                raise UnsafeFixture('Delegation fixture deadline exceeded')
        report.update(sentinel_sha256_before=before, sentinel_sha256_after=digest(sentinel), sentinel_metadata_unchanged=stamp == sentinel.stat().st_mtime_ns)
        if report['sentinel_sha256_after'] != before or not report['sentinel_metadata_unchanged']:
            raise UnsafeFixture('Synthetic external sentinel changed')
        result = work / 'result.json'
        if result.exists():
            report['result'] = json.loads(result.read_text())
        report['state'] = 'PASS' if report['exit'] == 0 and report.get('result', {}).get('state') == 'PASS' else 'FAIL'
    except Exception as error:
        report.update(state='FAIL', error=str(error))
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        work = f.run_root / 'live'
        if work.exists():
            for path in work.iterdir():
                if path.is_file() and not path.is_symlink() and path.stat().st_size <= 16_000_000:
                    shutil.copyfile(path, output / path.name)
        try:
            f.close()
            report['fixture_removed'] = not f.root.exists()
        except Exception as error:
            report.update(state='FAIL', cleanup_error=str(error))
        (output / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
    print('delegation fixture:', report['state'], 'evidence:', output)
    return 0 if report['state'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
