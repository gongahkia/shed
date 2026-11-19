#!/usr/bin/env python3
"""Bounded, opt-in Unicode production/stock-Swing diagnostics. No production configuration is changed."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('markdown_benchmark', ROOT / 'scripts/benchmark-markdown.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
TEXT = {
    'ascii': 'ordinary source prose **bold** [link](note.md).',
    'latin': 'Héading naïve déjà vu café.',
    'combining': 'e\u0301 a\u0308 n\u0303 and ordinary prose.',
    'emoji': '😀 🎉 👍🏽 and ordinary prose.',
    'zwj': '👩‍💻 👨‍👩‍👧‍👦 and ordinary prose.',
    'indic': 'क्ष त्र क्त and ordinary prose.',
    'cjk': '中文 日本語 한국어 and ordinary prose.',
    'mixed': '中文 e\u0301 😀 क्ष العربية **bold** [link](note.md).',
    'bidi': 'English العربية עברית and ordinary prose.',
    'long': '中文 e\u0301 😀 क्ष العربية ',
}


def fixture(path, category, size):
    if category not in TEXT or not 1024 <= size <= 1048576:
        raise ValueError('Unsupported fixture or size')
    block = (TEXT[category] + ('' if category == 'long' else '\n')).encode()
    count, remaining = divmod(size - 1, len(block))
    data = block * count + b'p' * remaining + b'\n'
    with path.open('xb') as output:
        output.write(data)
    text = data.decode()
    return {'category': category, 'bytes': len(data), 'utf16_units': len(text.encode('utf-16-le')) // 2,
            'longest_line_utf16': max(len(line.encode('utf-16-le')) // 2 for line in text.split('\n')),
            'sha256': hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--categories', nargs='+', choices=TEXT, default=['ascii', 'mixed'])
    parser.add_argument('--sizes', nargs='+', type=int, default=[4096, 102400])
    parser.add_argument('--mode', choices=['production', 'stock'], default='production')
    parser.add_argument('--font', choices=['default', 'Dialog'], default='default')
    parser.add_argument('--wrap', action='store_true')
    parser.add_argument('--jfr', action='store_true')
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    if not args.sizes or any(not 1024 <= size <= 1048576 for size in args.sizes) or len(args.sizes) * len(args.categories) > 30:
        parser.error('Bounds: 1 KiB–1 MiB; at most 30 cases per invocation')
    if args.mode == 'stock' and args.font != 'default':
        parser.error('Stock causal control uses the bundled default font')
    if shutil.disk_usage(output.parent).free < 1024**3:
        parser.error('At least 1 GiB of scratch headroom is required')
    output.mkdir(parents=True)
    manifest = []
    for category in args.categories:
        for size in args.sizes:
            path = output / f'{category}-{size}.md'
            manifest.append(dict(fixture(path, category, size), file=str(path)))
    (output / 'fixtures.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if args.generate_only:
        return 0
    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    classes = output / 'classes'
    classes.mkdir()
    sources = [ROOT / f'src/benchmark/java/shed/{name}.java' for name in
               ['EditorWorkflowProbe', 'MarkdownPerformanceProbe', 'MarkdownLexicalProbe', 'UnicodeLayoutProbe']]
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes), *map(str, sources)], check=True)
    report = {'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'arguments': vars(args) | {'jar': str(jar), 'output': str(output)},
              'environment_before': base.environment(), 'java': base.command_output([str(jdk / 'bin/java'), '-version']),
              'source_hashes': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + [Path(__file__)]}, 'cases': []}
    for entry in manifest:
        folder = output / Path(entry['file']).stem
        (folder / 'home/.shed').mkdir(parents=True)
        config = 'schema_version = 1\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n"theme" = "shed-light"\n'
        if args.font == 'Dialog':
            config += '"font.family" = "Dialog"\n'
        (folder / 'home/.shed/config.toml').write_text(config)
        command = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=false', f'-Duser.home={folder / "home"}']
        if args.jfr:
            command.append(f'-XX:StartFlightRecording=filename={folder}/profile.jfr,settings=profile,maxsize=64m,dumponexit=true')
        command += ['-cp', os.pathsep.join([str(classes), str(jar)]), 'shed.UnicodeLayoutProbe', entry['file'], args.mode, str(args.wrap).lower()]
        env = os.environ.copy()
        for key in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH']:
            env.pop(key, None)
        case = {'fixture': entry, 'command': command, 'rss': []}
        began = time.monotonic()
        with (folder / 'stdout.log').open('w') as log:
            child = subprocess.Popen(command, cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
            while child.poll() is None and time.monotonic() - began < 90:
                reading = base.command_output(['/bin/ps', '-o', 'rss=', '-p', str(child.pid)])
                try:
                    case['rss'].append({'seconds': time.monotonic() - began, 'mib': float(reading['text']) / 1024})
                except (ValueError, KeyError):
                    pass
                time.sleep(.25)
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
                case['timeout'] = True
        log = (folder / 'stdout.log').read_text(errors='replace')
        case.update(exit=child.returncode, wall_seconds=time.monotonic() - began,
                    state='OK' if child.returncode == 0 and 'MD_DONE' in log else 'FAILED',
                    metadata=[json.loads(line[8:]) for line in log.splitlines() if line.startswith('MD_META ')],
                    samples=[json.loads(line[10:]) for line in log.splitlines() if line.startswith('MD_SAMPLE ')])
        case['summary'] = base.summarize(case['samples'])
        case['peak_sampled_rss_mib'] = max((r['mib'] for r in case['rss']), default=None)
        report['cases'].append(case)
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(Path(entry['file']).stem, case['state'], flush=True)
    report['environment_after'] = base.environment()
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if all(case['state'] == 'OK' for case in report['cases']) else 1


if __name__ == '__main__':
    sys.exit(main())
