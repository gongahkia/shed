#!/usr/bin/env python3
"""Opt-in paired public-API paragraph-range prototype. Never installs into the production application."""
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
spec = importlib.util.spec_from_file_location('unicode_diagnostic', ROOT / 'scripts/investigate-unicode-layout.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
base = fixtures.base


def plan(group):
    if group == 'pilot': return [('mixed', 102400)]
    if group in ['gate', 'reads', 'model']: return [('ascii', 1048576), ('mixed', 1048576)]
    if group == 'scripts': return [(name, 102400) for name in ['combining', 'indic', 'emoji', 'zwj', 'cjk', 'bidi']]
    if group == 'long': return [('long', 4096), ('long', 102400)]
    raise ValueError(group)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--group', choices=['pilot', 'gate', 'scripts', 'long', 'reads', 'model'], default='pilot')
    parser.add_argument('--samples', type=int, default=100)
    parser.add_argument('--runs', type=int, default=1)
    parser.add_argument('--wrap', action='store_true')
    parser.add_argument('--profile', action='store_true')
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    if not 1 <= args.samples <= 100 or not 1 <= args.runs <= 2 or len(plan(args.group)) * args.runs * 2 > 24:
        parser.error('Bounds: 1–100 samples, 1–2 runs, at most 24 children')
    if shutil.disk_usage(output.parent).free < 1024**3:
        parser.error('At least 1 GiB scratch headroom is required')
    output.mkdir(parents=True)
    manifest = []
    for category, size in plan(args.group):
        path = output / f'{category}-{size}.md'
        manifest.append(dict(fixtures.fixture(path, category, size), file=str(path)))
    (output / 'fixtures.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if args.generate_only: return 0
    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    classes = output / 'classes'; classes.mkdir()
    sources = [ROOT / f'src/benchmark/java/shed/{name}.java' for name in
               ['EditorWorkflowProbe', 'MarkdownPerformanceProbe', 'MarkdownLexicalProbe', 'UnicodeCandidateProbe']]
    sources.append(ROOT / 'src/test/java/shed/ParagraphRangePrototype.java')
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes), *map(str, sources)], check=True)
    report = {'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'arguments': vars(args) | {'output': str(output), 'jar': str(jar)},
              'java': base.command_output([str(jdk / 'bin/java'), '-version']),
              'hashes': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + [Path(__file__), ROOT / 'scripts/investigate-unicode-layout.py']}, 'cases': []}
    for entry in manifest:
        for run in range(args.runs):
            versions = ['stock', 'candidate'] if run % 2 == 0 else ['candidate', 'stock']
            if args.group == 'model': versions = ['model']
            for version in versions:
                folder = output / f'{Path(entry["file"]).stem}-{run}-{version}'
                (folder / 'home/.shed').mkdir(parents=True)
                (folder / 'home/.shed/config.toml').write_text('schema_version = 1\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n"theme" = "shed-light"\n')
                mode = 'reads-' + version if args.group == 'reads' else version
                command = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx512m', '-Djava.awt.headless=false', f'-Duser.home={folder / "home"}']
                if args.profile:
                    command.append(f'-XX:StartFlightRecording=filename={folder}/profile.jfr,settings=profile,maxsize=64m,dumponexit=true')
                command += ['-cp', os.pathsep.join([str(classes), str(jar)]), 'shed.UnicodeCandidateProbe', entry['file'], mode, str(args.wrap).lower(), str(args.samples)]
                env = os.environ.copy()
                for key in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH']: env.pop(key, None)
                case = {'fixture': entry, 'version': version, 'run': run, 'command': command, 'rss': [], 'environment_before': base.environment()}
                began = time.monotonic()
                with (folder / 'stdout.log').open('w') as log:
                    child = subprocess.Popen(command, cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
                    while child.poll() is None and time.monotonic() - began < 75:
                        reading = base.command_output(['/bin/ps', '-o', 'rss=', '-p', str(child.pid)])
                        try: case['rss'].append({'seconds': time.monotonic() - began, 'mib': float(reading['text']) / 1024})
                        except (ValueError, KeyError): pass
                        time.sleep(.25)
                    if child.poll() is None:
                        child.terminate()
                        try: child.wait(timeout=3)
                        except subprocess.TimeoutExpired: child.kill(); child.wait()
                        case['timeout'] = True
                text = (folder / 'stdout.log').read_text(errors='replace')
                case.update(exit=child.returncode, wall_seconds=time.monotonic() - began,
                            state='OK' if child.returncode == 0 and 'MD_DONE' in text else 'FAILED',
                            samples=[json.loads(s[10:]) for s in text.splitlines() if s.startswith('MD_SAMPLE ')],
                            metadata=[json.loads(s[8:]) for s in text.splitlines() if s.startswith('MD_META ')])
                case['summary'] = base.summarize(case['samples'])
                case['peak_sampled_rss_mib'] = max((v['mib'] for v in case['rss']), default=None)
                case['fixture_sha256_after'] = hashlib.sha256(Path(entry['file']).read_bytes()).hexdigest()
                report['cases'].append(case)
                (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
                print(folder.name, case['state'], flush=True)
    return 0 if all(c['state'] == 'OK' for c in report['cases']) else 1


if __name__ == '__main__': sys.exit(main())
