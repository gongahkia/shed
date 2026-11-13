#!/usr/bin/env python3
"""Opt-in regression against ONLY the packaged JAR plus an independently compiled smoke probe."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--jdk', type=Path, help='Compiler JDK 21 home; otherwise use the repository resolver')
    parser.add_argument('--java', type=Path, help='Optional bundled-runtime java; compile with the existing JDK 21')
    parser.add_argument('--output', type=Path, help='New JSON report path; scratch files are always temporary')
    parser.add_argument('--timeout', type=int, default=90)
    args = parser.parse_args()
    if not 10 <= args.timeout <= 180:
        parser.error('Timeout must be 10..180 seconds')
    if args.output and args.output.exists():
        parser.error('Report already exists; choose a new output path')
    jar = args.jar.resolve(strict=True)
    jdk = args.jdk.resolve(strict=True) if args.jdk else Path(subprocess.check_output(
        [str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    suffix = '.exe' if os.name == 'nt' else ''
    probe = ROOT / 'src/benchmark/java/shed/PackagedMarkdownSmoke.java'
    env = os.environ.copy()
    for name in ('CLASSPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(name, None)  # An ambient development classpath/runtime override cannot rescue the package.
    with tempfile.TemporaryDirectory(prefix='shed-packaged-markdown-') as scratch:
        work = Path(scratch)
        classes = work / 'classes'
        classes.mkdir()
        home = work / 'home'
        home.mkdir()
        subprocess.run([str(jdk / ('bin/javac' + suffix)), '-cp', str(jar), '-d', str(classes), str(probe)],
                       check=True, timeout=30, env=env)
        command = [str(args.java.resolve(strict=True) if args.java else jdk / ('bin/java' + suffix)),
                   '-Xms64m', '-Xmx512m', '-Djava.awt.headless=true', f'-Duser.home={home}',
                   '-cp', os.pathsep.join((str(classes), str(jar))), 'shed.PackagedMarkdownSmoke', str(jar), str(work)]
        report = {'jar_sha256': sha256(jar), 'jar_bytes': jar.stat().st_size,
                  'probe_sha256': sha256(probe), 'command': command, 'state': 'ERROR'}
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=args.timeout, env=env, cwd=work)
            report.update(exit=result.returncode, stdout=result.stdout, stderr=result.stderr)
            report['events'] = [json.loads(line[6:]) for line in result.stdout.splitlines() if line.startswith('SMOKE ')]
            if result.returncode == 0 and 'SMOKE_PASS' in result.stdout.splitlines():
                report['state'] = 'PASS'
        except subprocess.TimeoutExpired:
            report['state'] = 'TIMEOUT'
        if args.output:
            args.output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
        return 0 if report['state'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
