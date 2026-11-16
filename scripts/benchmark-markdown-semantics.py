#!/usr/bin/env python3
"""Opt-in on-demand semantic costs; reuse Tranche 1 fixture/environment/statistics helpers."""
import argparse
import importlib.util
import json
import os
import re
import signal
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('markdown_benchmark', ROOT / 'scripts/benchmark-markdown.py')
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
SHAPES = (*baseline.SHAPES, 'single-fence')


def fixture(path, size, shape):
    if shape != 'single-fence':
        return baseline.fixture(path, size, shape)
    # Complement the existing corpus with one continuous block and no giant padding line.
    prefix, suffix = b'```text\n', b'```\n'
    line = b'\t<&> code with indentation and escaped HTML characters\n'
    remaining = size - len(prefix) - len(suffix)
    count, padding = divmod(remaining, len(line))
    path.write_bytes(prefix + line * count + (b'x' * (padding - 1) + b'\n' if padding else b'') + suffix)
    return dict(path=str(path), bytes=size, sha256=baseline.digest(path), shape=shape, generator='semantic-single-fence-1',
                complete_code_lines=count, padding_bytes=padding, encoding='ASCII/UTF-8', eof_newline=True)


def child(command, timeout, env, directory):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                               env=env, cwd=directory, start_new_session=os.name == 'posix')
    try:
        stdout, stderr = process.communicate(timeout=timeout)
        return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    except subprocess.TimeoutExpired:
        # Kill only this benchmark's process group, including Darwin time's child JVM.
        if os.name == 'posix':
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
        process.communicate()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--output', type=Path, required=True, help='New report directory; fixtures remain temporary')
    parser.add_argument('--samples', type=int, default=20)
    parser.add_argument('--warmups', type=int, default=3)
    parser.add_argument('--timeout', type=int, default=90)
    parser.add_argument('--sizes', type=int, nargs='+', default=[102400, 1048576, 10485760])
    parser.add_argument('--shapes', nargs='+', choices=SHAPES, default=['mixed'])
    args = parser.parse_args()
    if not 1 <= args.samples <= 100 or not 0 <= args.warmups <= 10 or not 10 <= args.timeout <= 180:
        parser.error('Use 1..100 samples, 0..10 warmups and a 10..180 second per-child timeout')
    if any(size not in (102400, 1048576, 10485760) for size in args.sizes):
        parser.error('Sizes must be 100 KiB, 1 MiB or 10 MiB')
    if len(args.sizes) * len(args.shapes) > 12:
        parser.error('At most 12 child cases per invocation')
    args.output.mkdir(parents=True, exist_ok=False)
    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    probe = ROOT / 'src/benchmark/java/shed/MarkdownSemanticProbe.java'
    environment = baseline.environment()
    environment['java'] = baseline.command_output([str(jdk / 'bin/java'), '-version'])
    failed = False
    env = os.environ.copy()
    for name in ('CLASSPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(name, None)
    with tempfile.TemporaryDirectory(prefix='shed-semantics-') as directory:
        work = Path(directory)
        classes = work / 'classes'
        classes.mkdir()
        home = work / 'home'
        home.mkdir()
        subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes), str(probe)],
                       check=True, timeout=30, env=env)
        for size in args.sizes:
            for shape in args.shapes:
                fixture_path = work / f'{shape}-{size}.md'
                metadata = fixture(fixture_path, size, shape)
                command = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=true',
                           f'-Duser.home={home}',
                           '-cp', os.pathsep.join((str(classes), str(jar))), 'shed.MarkdownSemanticProbe',
                           str(fixture_path), str(args.samples), str(args.warmups)]
                if sys.platform == 'darwin':
                    command = ['/usr/bin/time', '-l'] + command
                report = dict(environment=environment, command=command, fixture=metadata, samples=args.samples,
                              warmups=args.warmups, jar_sha256=baseline.digest(jar), probe_sha256=baseline.digest(probe),
                              runner_sha256=baseline.digest(Path(__file__)), load_at_start=os.getloadavg(), state='ERROR')
                try:
                    result = child(command, args.timeout, env, work)
                    rows = [json.loads(line[9:]) for line in result.stdout.splitlines() if line.startswith('SEMANTIC ')]
                    report.update(exit=result.returncode, stdout=result.stdout, stderr=result.stderr, rows=rows,
                                  summary=baseline.summarize(rows))
                    rss = re.search(r'(\d+)\s+maximum resident set size', result.stderr)
                    if rss:
                        report['process_peak_rss_bytes'] = int(rss[1])  # Darwin time reports bytes.
                    if result.returncode == 0 and 'SEMANTIC_PASS' in result.stdout.splitlines():
                        states = {row['state'] for row in rows}
                        report['state'] = 'UNSUPPORTED' if 'UNSUPPORTED' in states else 'PASS' if not states & {'FAILED', 'CANCELLED'} else 'ERROR'
                except subprocess.TimeoutExpired:
                    report['state'] = 'TIMEOUT'
                report['fixture_unchanged'] = baseline.digest(fixture_path) == metadata['sha256']
                report['load_after'] = os.getloadavg()
                failed = failed or report['state'] not in ('PASS', 'UNSUPPORTED')
                if not report['fixture_unchanged']:
                    raise AssertionError('Fixture changed')
                path = args.output / f'{shape}-{size}.json'
                path.write_text(json.dumps(report, indent=2) + '\n')
                print(json.dumps({'case': path.name, 'state': report['state'], 'summary': report.get('summary')}), flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
