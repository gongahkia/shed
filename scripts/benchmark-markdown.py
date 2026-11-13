#!/usr/bin/env python3
"""Opt-in Markdown qualification. Isolated child JVMs, disposable fixtures, bounded wall time.
No production instrumentation or dependencies; complements benchmark-editor.py and its probe.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
SHAPES = ('mixed', 'plain', 'long-line', 'nested', 'table', 'fence', 'headings', 'markers')
SIZES = (100 * 1024, 1024**2, 10 * 1024**2, 50 * 1024**2, 100 * 1024**2)
# Reuse the established nearest-rank statistics definition; do not replace its budgets.
spec = importlib.util.spec_from_file_location('editor_benchmark', ROOT / 'scripts/benchmark-editor.py')
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024**2), b''):
            h.update(chunk)
    return h.hexdigest()


def fixture(path, size, shape):
    if shape not in SHAPES or not 1024 <= size <= 100 * 1024**2:
        raise ValueError('Fixture must use a known shape and 1 KiB..100 MiB')
    prose = 'alpha beta gamma delta. ' * 64 + '\n'
    blocks = {
        'plain': '# Section\n\n' + prose + '\n',
        'mixed': ('# Section\n\n' + prose + '\n- unordered\n1. ordered\n- [ ] task\n  - nested\n'
                  '> quote\n> > nested quote\n\n[link](relative.md) and [reference][ref]. `inline code` **bold** *italic*\n'
                  '[ref]: relative.md\n\n| a\\|b | c |\n| --- | --- |\n| value | 42 |\n\n'
                  '```java\nclass Example { int n = 42; }\n```\n\n![local](swatch.png)\n\n'
                  '```mermaid\nflowchart LR\n A --> B\n```\n\nInline $x^2$ and display:\n$$\nx = y + 1\n$$\n\n'),
        'long-line': '# Long line\n' + 'x' * (256 * 1024) + '\n',
        'nested': ''.join('  ' * depth + '- nested item\n' for depth in range(128)),
        'table': '| key | value |\n| --- | --- |\n' + ''.join(f'| item {i} | value {i} |\n' for i in range(2000)) + '\n',
        'fence': '```text\n' + ('code ' * 24 + '\n') * 2000 + '```\n',
        'headings': ''.join(f'## Heading {i}\n[link](file-{i}.md)\n\n' for i in range(256)),
        'markers': ('**b** *i* ~~s~~ `c` [l](x) ![i](swatch.png)\n' * 128 + '\n'),
    }
    data = blocks[shape].encode('ascii')
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as out:
        remaining = size
        while remaining >= len(data):
            out.write(data)
            remaining -= len(data)
        # Preserve complete syntax blocks; the final padding is an ordinary ASCII line.
        if remaining:
            out.write(b'p' * (remaining - 1) + b'\n')
    return {'path': str(path), 'bytes': size, 'sha256': digest(path), 'shape': shape,
            'generator': 1, 'complete_blocks': size // len(data), 'padding_bytes': size % len(data), 'encoding': 'ASCII/UTF-8', 'eof_newline': True}


def command_output(command):
    try:
        p = subprocess.run(command, capture_output=True, text=True, timeout=5)
        return {'exit': p.returncode, 'text': (p.stdout + p.stderr).strip()}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'unavailable': str(error)}


def environment():
    return {'utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'platform': platform.platform(),
            'load': os.getloadavg(), 'cpu_count': os.cpu_count(),
            'hardware': command_output(['sysctl', 'hw.model', 'hw.memsize', 'hw.ncpu', 'machdep.cpu.brand_string']),
            'pressure': command_output(['memory_pressure', '-Q']), 'swap': command_output(['sysctl', 'vm.swapusage']),
            'vm_stat': command_output(['vm_stat']), 'display': command_output(['system_profiler', 'SPDisplaysDataType'])}


def summarize(rows):
    groups = {}
    for row in rows:
        if row.get('state') != 'OK':
            continue
        for key, value in row.items():
            if key.endswith('_ms') and key != 'epoch_ms' and isinstance(value, (int, float)):
                groups.setdefault(row['operation'] + '/' + key, []).append(value)
                operation = row['operation']
                if operation.rsplit('_', 1)[-1] in ('0', '1', '2') and operation.startswith(('insert_', 'undo_', 'redo_', 'delete_', 'newline_')):
                    groups.setdefault(operation.rsplit('_', 1)[0] + '_pooled_positions/' + key, []).append(value)
    return {key: {'n': len(values), 'p50_ms': workflow.percentile(values, .5),
                  'p95_ms': workflow.percentile(values, .95),
                  'p99_ms': workflow.percentile(values, .99) if len(values) >= 100 else None,
                  'max_ms': max(values)} for key, values in groups.items()}


def execute(java, jar, classes, output, case, fixture_path, samples, warmups, timeout, heap, profile, font):
    directory = output / case['name']
    directory.mkdir()
    home = directory / 'home'
    (home / '.shed').mkdir(parents=True)
    (home / '.shed/config.toml').write_text('schema_version = 1\n"keymap.profile" = "plain"\n'
        '"session.restore.on.start" = false\n"markdown.preview.remote.images" = false\n'
        + ('"font.family" = ' + json.dumps(font) + '\n' if font else ''))
    source = directory / 'input.md'
    shutil.copyfile(fixture_path, source)
    shutil.copyfile(fixture_path.parent / 'swatch.png', directory / 'swatch.png')
    command = [str(java / 'bin/java'), '-Xms64m', f'-Xmx{heap}m', '-Djava.awt.headless=false', f'-Duser.home={home}',
               f'-Xlog:gc*,safepoint:file={directory}/gc.log:time,uptime,level,tags']
    if profile:
        command += ['-XX:NativeMemoryTracking=summary', f'-XX:StartFlightRecording=filename={directory}/profile.jfr,settings=profile,maxsize=64m,dumponexit=true']
    command += ['-cp', os.pathsep.join((str(classes), str(jar))), 'shed.MarkdownPerformanceProbe', case['mode'],
                str(source), str(samples), str(warmups), str(case.get('wrap', False)).lower(),
                str(case.get('preview', False)).lower()]
    record = {'case': case, 'command': command, 'profiled': profile, 'state': 'ERROR',
              'environment_before': environment(), 'samples': [], 'rss_samples_mib': []}
    started = time.monotonic()
    with (directory / 'stdout.log').open('w') as log:
        child = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        record['pid'] = child.pid
        timed_out = False
        native_sampled = False
        try:
            while child.poll() is None:
                if time.monotonic() - started > timeout:
                    timed_out = True
                    # Ask only this child for diagnostics; never kill unrelated processes.
                    for tool, arguments, name in [('jcmd', ['Thread.print'], 'timeout-threads.txt'),
                                                  ('jcmd', ['JFR.dump', f'filename={directory}/timeout.jfr'], 'timeout-jfr.txt')]:
                        with (directory / name).open('w') as out:
                            try: subprocess.run([str(java / 'bin' / tool), str(child.pid)] + arguments, stdout=out, stderr=subprocess.STDOUT, timeout=3)
                            except (OSError, subprocess.TimeoutExpired): pass
                    child.terminate()
                    try: child.wait(timeout=3)
                    except subprocess.TimeoutExpired: child.kill(); child.wait()
                    break
                if profile and not native_sampled and 'MD_META ' in (directory / 'stdout.log').read_text(errors='replace'):
                    native_sampled = True
                    with (directory / 'native-memory.txt').open('w') as native:
                        try: subprocess.run([str(java / 'bin/jcmd'), str(child.pid), 'VM.native_memory', 'summary'], stdout=native, stderr=subprocess.STDOUT, timeout=5)
                        except (OSError, subprocess.TimeoutExpired): pass
                rss = command_output(['ps', '-o', 'rss=', '-p', str(child.pid)])
                try: record['rss_samples_mib'].append({'seconds': time.monotonic() - started, 'epoch_ms': time.time()*1000, 'mib': int(rss['text']) / 1024})
                except (ValueError, KeyError): pass
                time.sleep(.25)
        finally:
            if child.poll() is None: child.kill(); child.wait()
    record['wall_seconds'] = time.monotonic() - started
    record['exit'] = child.returncode
    record['state'] = 'TIMEOUT' if timed_out else 'OK' if child.returncode == 0 else 'ERROR'
    for line in (directory / 'stdout.log').read_text(errors='replace').splitlines():
        if line.startswith('MD_SAMPLE '): record['samples'].append(json.loads(line[10:]))
        elif line.startswith('MD_META '): record.setdefault('metadata', []).append(json.loads(line[8:]))
    record['summary'] = summarize(record['samples'])
    rss = [row['mib'] for row in record['rss_samples_mib']]
    record['sampled_peak_rss_mib'] = max(rss) if rss else None
    record['fixture_sha256_after'] = digest(source)
    record['environment_after'] = environment()
    (directory / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New directory outside the checkout')
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--generate-only', action='store_true')
    parser.add_argument('--mode', choices=['startup', 'startup-empty', 'ui', 'model', 'preview', 'assets'], default='model')
    parser.add_argument('--shapes', nargs='+', choices=SHAPES, default=['mixed'])
    parser.add_argument('--sizes', nargs='+', type=int, default=list(SIZES), help='Exact bytes, <=100 MiB each')
    parser.add_argument('--samples', type=int, default=100)
    parser.add_argument('--warmups', type=int, default=10)
    parser.add_argument('--runs', type=int, default=1)
    parser.add_argument('--timeout', type=int, default=60)
    parser.add_argument('--heap', type=int, default=512)
    parser.add_argument('--wrap', action='store_true')
    parser.add_argument('--preview', action='store_true')
    parser.add_argument('--profile', action='store_true')
    parser.add_argument('--font', default='')
    args = parser.parse_args()
    if not 1 <= args.samples <= 200 or not 0 <= args.warmups <= 25 or not 1 <= args.runs <= 5 or not 5 <= args.timeout <= 180 or not 128 <= args.heap <= 768:
        parser.error('Bounds: samples 1..200, warmups 0..25, runs 1..5, timeout 5..180 s, heap 128..768 MiB')
    if any(not 1024 <= n <= 100 * 1024**2 for n in args.sizes) or len(args.sizes) * len(args.shapes) * args.runs > 40:
        parser.error('Fixtures 1 KiB..100 MiB and at most 40 cases per invocation')
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents or output.exists(): parser.error('Output must be a NEW directory outside the checkout')
    output.mkdir(parents=True)
    fixtures = output / 'fixtures'
    fixtures.mkdir()
    # Valid tiny local image; no network access or image tooling required.
    (fixtures / 'swatch.png').write_bytes(base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6V8AAAAASUVORK5CYII='))
    manifest = [fixture(fixtures / f'{shape}-{size}.md', size, shape) for shape in args.shapes for size in args.sizes]
    (output / 'fixtures.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if args.generate_only: return 0
    jar = args.jar.resolve(strict=True)
    java = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    classes = output / 'classes'; classes.mkdir()
    sources = [ROOT / f'src/benchmark/java/shed/{name}.java' for name in ('EditorWorkflowProbe', 'MarkdownPerformanceProbe')]
    subprocess.run([str(java / 'bin/javac'), '-cp', str(jar), '-d', str(classes)] + list(map(str, sources)), check=True)
    report = {'jar_sha256': digest(jar), 'java': command_output([str(java / 'bin/java'), '-version']),
              'probe_hashes': {str(p.relative_to(ROOT)): digest(p) for p in sources}, 'arguments': vars(args).copy(),
              'fixtures': manifest, 'cases': []}
    report['arguments'] = {k: str(v) if isinstance(v, Path) else v for k, v in report['arguments'].items()}
    for entry in manifest:
        for iteration in range(args.runs):
            case = {'name': f"{args.mode}-{entry['shape']}-{entry['bytes']}-{iteration}", 'mode': args.mode,
                    'wrap': args.wrap, 'preview': args.preview, 'fixture': entry}
            result = execute(java, jar, classes, output, case, Path(entry['path']), args.samples, args.warmups,
                             args.timeout, args.heap, args.profile, args.font)
            report['cases'].append(result)
            (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
            print(case['name'], result['state'], result['wall_seconds'], flush=True)
    return 0 if all(c['state'] == 'OK' for c in report['cases']) else 1


if __name__ == '__main__': raise SystemExit(main())
