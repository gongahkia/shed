#!/usr/bin/env python3
"""Opt-in 7A process-memory/lifecycle observations; never alters application configuration outside scratch homes."""
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


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def execute(jdk, jar, classes, output, label, fixture, small, mode, nmt=False, plain=False, heap=512, jfr=False):
    folder = output / label
    (folder / 'home/.shed').mkdir(parents=True)
    (folder / 'home/.shed/config.toml').write_text('schema_version = 1\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n"markdown.preview.remote.images" = false\n"theme" = "shed-light"\n' + ('"font.family" = "Dialog"\n' if label.startswith('custom') else ''))
    source = None
    if fixture:
        source = folder / ('input' + fixture.suffix)
        shutil.copyfile(fixture, source)
    shutil.copyfile(small, folder / 'small.md')
    (folder / 'swatch.png').write_bytes(base.png_fixture())
    command = [str(jdk / 'bin/java')] + (['-Xms64m', f'-Xmx{heap}m'] if heap else []) + ['-Djava.awt.headless=false', f'-Duser.home={folder / "home"}']
    if nmt:
        command.append('-XX:NativeMemoryTracking=summary')
    if jfr:
        command.append(f'-XX:StartFlightRecording=filename={folder}/profile.jfr,settings=profile,maxsize=64m,dumponexit=true')
    if plain:
        command += ['-jar', str(jar)] + ([str(source)] if source else [])
    else:
        command += ['-cp', os.pathsep.join((str(classes), str(jar))), 'shed.FontMemoryQualificationProbe' if mode.startswith('fonts') else 'shed.MarkdownQualificationProbe',
                    str(source) if source else '-', mode, str(folder / 'small.md'), str(folder), str(nmt).lower()]
    result = {'label': label, 'plain_jar': plain, 'nmt': nmt, 'jfr': jfr, 'heap_mib': heap, 'mode': mode,
              'command': command, 'environment_before': base.environment(), 'rss': [], 'state': 'ERROR'}
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    began = time.monotonic()
    with (folder / 'stdout.log').open('w') as log:
        result['launch_epoch_ms'] = time.time() * 1000
        child = subprocess.Popen(command, cwd=folder, env=env, stdout=log, stderr=subprocess.STDOUT)
        result['pid'] = child.pid
        deadline = 7 if plain else 120
        while child.poll() is None and time.monotonic() - began < deadline:
            observation = base.command_output(['/bin/ps', '-o', 'rss=', '-p', str(child.pid)])
            try:
                result['rss'].append({'seconds': time.monotonic() - began, 'mib': float(observation['text']) / 1024})
            except (KeyError, ValueError):
                pass
            time.sleep(.25)
        if child.poll() is None:
            result['state'] = 'OBSERVED_PLAIN_CONTROL' if plain else 'TIMEOUT'
            if not plain:
                with (folder / 'timeout-threads.txt').open('w') as diagnostic:
                    try:
                        subprocess.run([str(jdk / 'bin/jcmd'), str(child.pid), 'Thread.print'], stdout=diagnostic,
                                       stderr=subprocess.STDOUT, timeout=5)
                    except subprocess.TimeoutExpired:
                        pass
            child.terminate()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
    result['exit'] = child.returncode
    result['wall_seconds'] = time.monotonic() - began
    log = (folder / 'stdout.log').read_text(errors='replace')
    result['metadata'] = [json.loads(line[8:]) for line in log.splitlines() if line.startswith('MD_META ')]
    result['samples'] = [json.loads(line[10:]) for line in log.splitlines() if line.startswith('MD_SAMPLE ')]
    result['summary'] = base.summarize(result['samples'])
    if child.returncode == 0 and 'MD_DONE' in log:
        result['state'] = 'OK'
    result['diagnostics'] = {p.name: p.read_text(errors='replace') for p in folder.glob('*.txt')}
    result['sampled_peak_rss_mib'] = max((r['mib'] for r in result['rss']), default=None)
    result['last_second_rss_mib'] = [r['mib'] for r in result['rss'] if r['seconds'] >= result['wall_seconds'] - 1.5]
    result['environment_after'] = base.environment()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--group', choices=['memory', 'lifecycle', 'constrained', 'default-launch', 'startup', 'fonts', 'unicode', 'index', 'rich', 'preview', 'retention', 'retention-cycles', 'watched-cycles'], default='memory')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    if shutil.disk_usage(output.parent).free < 1024**3:
        parser.error('At least 1 GiB free scratch space is required')
    output.mkdir(parents=True)
    corpus = output / 'fixtures'
    corpus.mkdir()
    fixtures = {}
    for label, size in [('small', 4096), ('100k', 102400), ('1m', 1048576), ('10m', 10485760)]:
        path = corpus / (label + '.md')
        base.fixture(path, size, 'plain')
        fixtures[label] = path
    fixtures['code'] = corpus / 'small.java'
    fixtures['code'].write_text('class Example {\n    int answer = 42;\n}\n')
    if args.generate_only:
        return 0
    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    classes = output / 'classes'
    classes.mkdir()
    sources = [ROOT / f'src/benchmark/java/shed/{name}.java' for name in
               ('EditorWorkflowProbe', 'MarkdownPerformanceProbe', 'MarkdownLexicalProbe', 'MarkdownQualificationProbe')]
    if args.group == 'rich':
        sources.extend(ROOT / f'src/benchmark/java/shed/{name}.java' for name in ('MarkdownMediaProbe', 'MarkdownMediaControlProbe'))
    if args.group == 'fonts':
        sources.append(ROOT / 'src/benchmark/java/shed/FontMemoryQualificationProbe.java')
    subprocess.run([str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(classes)] + list(map(str, sources)), check=True)
    cases = []
    if args.group == 'memory':
        for label in ['empty', 'code', 'small', '100k', '1m', '10m']:
            for plain in [True, False]:
                cases.append((label + ('-plain' if plain else '-probe'), fixtures.get(label), 'baseline', False, plain, 512, False))
        for label in ['empty', '1m']:
            cases.append((label + '-nmt', fixtures.get(label), 'baseline', True, False, 512, False))
        cases.append(('1m-jfr-nmt', fixtures['1m'], 'baseline', True, False, 512, True))
    elif args.group == 'watched-cycles':
        cases = [('watched-small', fixtures['small'], 'watched-cycles', False, False, 512, False),
                 ('watched-1m', fixtures['1m'], 'watched-cycles', False, False, 512, False)]
    elif args.group == 'retention-cycles':
        control = corpus / 'control.java'
        header, comment = 'class Control {}\n', '// alpha beta gamma delta\n'
        control.write_text(header + (comment * (1048576 // len(comment)))[:1048576 - len(header)])
        cases = [('cycles-small', fixtures['small'], 'retention-cycles', False, False, 512, False),
                 ('cycles-1m', fixtures['1m'], 'retention-cycles', False, False, 512, False),
                 ('cycles-java', control, 'retention-cycles', False, False, 512, False)]
    elif args.group == 'retention':
        cases = [('retention-eight-files', fixtures['1m'], 'retention', False, False, 512, False)]
    elif args.group == 'index':
        cases = [('index-nmt', fixtures['1m'], 'index', True, False, 512, False)]
    elif args.group == 'rich':
        cases = [('media-nmt', fixtures['1m'], 'media', True, False, 512, True), ('renderer-control-nmt', fixtures['1m'], 'media-control', True, False, 512, True)]
    elif args.group == 'preview':
        for label,size,shape in [('preview100k',102400,'plain'), ('preview1m',1048576,'plain'), ('preview-table',102400,'table'), ('preview-fence',102400,'fence'), ('preview-rich',4096,'mixed')]:
            path=corpus/(label+'.md');base.fixture(path,size,shape)
            cases.append((label,path,'preview',False,False,512,False))
    elif args.group == 'unicode':
        for label, size in [('unicode100k', 102400), ('unicode1m', 1048576)]:
            path = corpus / (label + '.md')
            base.fixture(path, size, 'unicode')
            cases.append((label, path, 'unicode', False, False, 512, False))
        cases.append(('unicode100k-profile', corpus / 'unicode100k.md', 'unicode', True, False, 512, True))
    elif args.group == 'fonts':
        cases = [('jdk-fonts', None, 'fonts', True, False, 512, False), ('jdk-draw-first', None, 'fonts-draw-first', True, False, 512, False)]
    elif args.group == 'startup':
        for iteration in range(3):
            for label in ['empty', 'code', 'small', '1m', 'custom-small']:
                cases.append((label + '-startup-' + str(iteration), fixtures.get(label.removeprefix('custom-')), 'startup', False, False, 512, False))
    elif args.group == 'default-launch':
        cases = [('default-empty-plain', None, 'baseline', False, True, 0, False),
                 ('default-1m-plain', fixtures['1m'], 'baseline', False, True, 0, False),
                 ('default-empty-nmt', None, 'baseline', True, False, 0, False)]
    elif args.group == 'lifecycle':
        cases = [('lifecycle-control', fixtures['1m'], 'lifecycle', False, False, 512, False),
                 ('lifecycle-nmt', fixtures['1m'], 'lifecycle', True, False, 512, False)]
    else:
        cases = [('128m-1m-persistence', fixtures['1m'], 'persistence', False, False, 128, False),
                 ('128m-10m-persistence', fixtures['10m'], 'persistence', False, False, 128, False)]
    report = {'jar_sha256': sha(jar), 'runner_sha256': sha(__file__),
              'probe_hashes': {str(p.relative_to(ROOT)): sha(p) for p in sources},
              'java': base.command_output([str(jdk / 'bin/java'), '-version']), 'arguments': vars(args).copy(), 'cases': []}
    report['arguments'] = {k: str(v) if isinstance(v, Path) else v for k, v in report['arguments'].items()}
    for label, fixture, mode, nmt, plain, heap, jfr in cases:
        result = execute(jdk, jar, classes, output, label, fixture, fixtures['small'], mode, nmt, plain, heap, jfr)
        report['cases'].append(result)
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(label, result['state'], round(result['wall_seconds'], 2), flush=True)
    return 0 if all(c['state'] in ('OK', 'OBSERVED_PLAIN_CONTROL') for c in report['cases']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
