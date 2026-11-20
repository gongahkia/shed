#!/usr/bin/env python3
"""Run only source-reviewed non-native tests in a disposable environment. Not an OS sandbox."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.dont_write_bytecode = True
from native_fixture import Fixture, UnsafeFixture

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'src/test/resources/shed/portable-fixture-tests.properties'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--maven', required=True, type=Path, help='Existing Maven installation, not shell launcher')
    parser.add_argument('--repository', required=True, type=Path, help='Existing offline dependency cache; no settings/credentials read')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--tests', help='Comma-separated reviewed test classes; default all reviewed classes')
    parser.add_argument('--compile-only', action='store_true')
    args = parser.parse_args()
    reviewed = dict(line.split('=', 1) for line in MANIFEST.read_text().splitlines())
    selected = list(reviewed) if args.tests is None else args.tests.split(',')
    if not selected or any(name not in reviewed for name in selected):
        parser.error('Native/unreviewed tests blocked; no full-suite override')
    for name in selected:
        source = ROOT / 'src/test/java/shed' / (name + '.java')
        if hashlib.sha256(source.read_bytes()).hexdigest() != reviewed[name]:
            parser.error('Reviewed source changed: ' + name)
    output = args.output.absolute()
    if output.exists() or output.resolve() != output:
        parser.error('Output must be a new canonical file')
    jdk, maven, repository = (p.resolve(strict=True) for p in [args.jdk, args.maven, args.repository])
    boot = list((maven / 'boot').glob('plexus-classworlds-*.jar'))
    if len(boot) != 1 or not (jdk / 'bin/java').is_file() or not repository.is_dir():
        parser.error('Existing toolchain/cache unavailable')
    f = Fixture()
    report = {'boundary': 'source-reviewed non-native tests, not general process confinement', 'tests': selected,
              'fixture': str(f.root), 'native_benchmarks': 'BLOCKED'}
    start = time.monotonic()
    try:
        f.validate()
        env = dict(f.env, JAVA_HOME=str(jdk), MAVEN_SKIP_RC='1', SHED_PORTABLE_FIXTURE_ROOT=str(f.root))
        settings = f.run_root / 'config/settings.xml'
        settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
        props = [f'-Duser.home={f.env["HOME"]}', f'-Djava.io.tmpdir={f.env["TMPDIR"]}',
                 f'-Djava.util.prefs.userRoot={f.run_root / "config"}', '-Djava.awt.headless=true']
        command = [str(jdk / 'bin/java'), *props, f'-Dmaven.home={maven}', f'-Dclassworlds.conf={maven / "bin/m2.conf"}', f'-Dmaven.multiModuleProjectDirectory={ROOT}',
                   '-cp', str(boot[0]), 'org.codehaus.plexus.classworlds.launcher.Launcher', '-B', '-o',
                   '-s', str(settings), '-gs', str(settings), f'-Dmaven.repo.local={repository}', *props,
                   '-DforkCount=0', '-Dtest=' + ','.join(selected)]
        command += ['-DskipTests', 'test-compile'] if args.compile_only else ['test']
        report['command'] = command
        # This is the existing Maven/JVM build path, not an interactive shell or arbitrary fixture command.
        result = subprocess.run(command, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=180, text=True)
        report.update(exit=result.returncode, log=result.stdout)
    except (UnsafeFixture, subprocess.TimeoutExpired) as error:
        report.update(exit=2, error=str(error))
    finally:
        f.close()
        report.update(elapsed_s=time.monotonic() - start, fixture_removed=not f.root.exists(), metrics=f.metrics)
        output.write_text(json.dumps(report, indent=2) + '\n')
    print('portable Maven result:', report['exit'], 'evidence:', output)
    return report['exit']


if __name__ == '__main__':
    raise SystemExit(main())
