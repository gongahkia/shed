#!/usr/bin/env python3
"""Opt-in owned Swing gallery or read-only public registry probe. Never launches a shell."""
import argparse
import hashlib
import json
from pathlib import Path
import os
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
from native_fixture import Fixture, UnsafeFixture

ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / 'src/benchmark/java/shed/ThemeMarketplaceProbe.java'


def accepted(report):
    return (report.get('exit') == 0 and report.get('compile_exit') == 0
            and report.get('fixture_removed') is True and report.get('result', {}).get('state') == 'PASS'
            and 'UNCAUGHT_DESKTOP_EXCEPTION' not in report.get('log', ''))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--registry-smoke', action='store_true', help='Explicit public HTTPS search/download, no installation or native window')
    args = parser.parse_args()
    jdk, jar = args.jdk.resolve(strict=True), args.jar.resolve(strict=True)
    output = args.output.absolute()
    if output.exists() or output.is_symlink() or output.resolve() != output:
        parser.error('Output must be a new canonical directory')
    if not jar.is_file() or any(not (jdk / 'bin' / name).is_file() for name in ('java', 'javac')):
        parser.error('Existing JDK and packaged Shed JAR required')
    output.mkdir(parents=True)
    fixture = Fixture()
    child = None
    started = time.monotonic()
    report = {'boundary': 'fixed direct Java; disposable home, preferences and source; not an OS sandbox',
              'native_shell_benchmarks': 'BLOCKED', 'registry_smoke': args.registry_smoke,
              'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'probe_sha256': hashlib.sha256(PROBE.read_bytes()).hexdigest(), 'fixture': str(fixture.root)}
    work = fixture.run_root / 'evidence'
    try:
        fixture.validate()
        work.mkdir()
        project = fixture.run_root / 'project'
        project.mkdir()
        (project / 'Demo.java').write_text('class Demo { String value = "Source remains exact"; }\n')
        classes = fixture.run_root / 'classes'
        classes.mkdir()
        env = dict(fixture.env, SHED_PORTABLE_FIXTURE_ROOT=str(fixture.root), SHED_LIVE_VISUAL_ONLY='1')
        props = ['-Duser.home=' + env['HOME'], '-Djava.io.tmpdir=' + env['TMPDIR'],
                 '-Djava.util.prefs.userRoot=' + str(fixture.run_root / 'config')]
        command = [str(jdk / 'bin/javac'), *['-J' + p for p in props], '-proc:none', '-encoding', 'UTF-8',
                   '-cp', str(jar), '-d', str(classes), str(PROBE)]
        compiled = subprocess.run(command, env=env, cwd=fixture.run_root, stdin=subprocess.DEVNULL,
                                  capture_output=True, text=True, timeout=30)
        report.update(compile_command=command, compile_exit=compiled.returncode, compile_output=compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise UnsafeFixture('Marketplace probe compilation failed')
        if not args.registry_smoke:
            for name in ('DISPLAY', 'XAUTHORITY'):
                if os.environ.get(name):
                    env[name] = os.environ[name]
        command = [str(jdk / 'bin/java'), *props, '-Djava.awt.headless=' + str(args.registry_smoke).lower(),
                   '-cp', str(classes) + os.pathsep + str(jar), 'shed.ThemeMarketplaceProbe', str(work), str(project)]
        if args.registry_smoke:
            command.append('--registry-smoke')
        report['command'] = command
        with (work / 'stdout.log').open('w') as log:
            child = subprocess.Popen(command, env=env, cwd=project, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
            report['pid'] = child.pid
            report['exit'] = child.wait(timeout=100)
    except (OSError, UnsafeFixture, subprocess.TimeoutExpired) as error:
        report.update(exit=2, error=str(error))
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        if (work / 'stdout.log').is_file():
            report['log'] = (work / 'stdout.log').read_text()[:262144]
        for name in ('result', 'registry'):
            path = work / (name + '.json')
            if path.is_file() and path.stat().st_size <= 262144:
                report[name] = json.loads(path.read_text())
        for picture in work.glob('*.png'):
            if picture.stat().st_size > 4 * 1024 * 1024:
                report.update(exit=2, error='Owned capture exceeds size limit')
            else:
                shutil.copyfile(picture, output / picture.name)
        fixture.close()
        report.update(fixture_removed=not fixture.root.exists(), elapsed_s=time.monotonic() - started)
        if not accepted(report):
            report['exit'] = report.get('exit') or 1
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Marketplace probe:', report['exit'], output / 'report.json')
    return report['exit']


if __name__ == '__main__':
    raise SystemExit(main())
