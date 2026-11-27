#!/usr/bin/env python3
"""Opt-in alternating desktop comparison; fixed direct Java only, no native VCS client or shell."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import sys
import time

sys.dont_write_bytecode = True
from native_fixture import Fixture, UnsafeFixture, checked, digest

ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / 'src/benchmark/java/shed/SourceControlPerformanceProbe.java'
TYPING_MS = 16.0
MAX_PAIRS = 5


def finite(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError('Missing, negative or nonfinite measurement')
    return value


def order(pairs):
    if not 1 <= pairs <= MAX_PAIRS:
        raise ValueError('Use one to five bounded pairs')
    return [kind for i in range(pairs) for kind in (('baseline', 'candidate') if i % 2 == 0 else ('candidate', 'baseline'))]


def validate_result(result, candidate):
    if result.get('state') != 'PASS' or result.get('native_shell') != 'NOT_USED':
        raise ValueError('Desktop probe did not pass safely')
    rows = result.get('rows', [])
    names = [row.get('operation') for row in rows]
    required = {'typing.hidden.Small.txt', 'typing.hidden.100KiB.txt', 'typing.hidden.1MiB.txt',
                'typing.source_control_open', 'undo', 'redo', 'split_cycle', 'deletion', 'newline', 'caret', 'selection', 'scroll', 'buffer_switch'}
    if candidate:
        required |= {'typing.pending.MERCURIAL', 'typing.pending.SUBVERSION', 'typing.5000_rows.MERCURIAL',
                     'typing.5000_rows.SUBVERSION', 'typing.installing.MERCURIAL', 'typing.installing.SUBVERSION',
                     'typing.failed.MERCURIAL', 'typing.failed.SUBVERSION', 'hidden_polling.MERCURIAL', 'hidden_polling.SUBVERSION', 'scm_disposal'}
    if not required.issubset(names) or len(names) != len(set(names)):
        raise ValueError('Missing or duplicate workload')
    for row in rows:
        if row['operation'].startswith('typing.'):
            samples, queued = row.get('samples_ms', []), row.get('queued_ms', [])
            if len(samples) != 100 or len(queued) != 100 or row.get('warmups') != 5 or len(row.get('warmup_samples_ms', [])) != 5:
                raise ValueError('Typing requires exactly 100 samples and five warmups')
            for sample in samples + queued + row['warmup_samples_ms'] + [row.get('first_ms')]:
                finite(sample)
            ordered = sorted(samples)
            if row.get('p95_ms') != ordered[94] or row.get('queued_p95_ms') != sorted(queued)[94]:
                raise ValueError('Reported typing percentile does not match raw samples')
            if row['operation'].startswith('typing.installing.') and max(samples + row['warmup_samples_ms'] + [row['first_ms']]) > 50:
                raise ValueError('Status installation delayed source input beyond 50 ms')
            if row['p95_ms'] > TYPING_MS or row['queued_p95_ms'] > TYPING_MS:
                raise ValueError('Typing/queued input p95 exceeds 16 ms')
    if candidate:
        for provider in ('MERCURIAL', 'SUBVERSION'):
            hidden = next(r for r in rows if r['operation'] == 'hidden_polling.' + provider)
            if hidden.get('observation_ms', 0) < 2700 or hidden.get('timer_running') is not False or hidden.get('calls_before') != hidden.get('calls_after'):
                raise ValueError('Hidden provider timer or polling remained active')
        disposal = next(r for r in rows if r['operation'] == 'scm_disposal')
        if disposal.get('cycles') != 12 or disposal.get('retained_panels') != 0 or disposal.get('active_client_calls') != 0:
            raise ValueError('SCM disposal retained a panel or client call')
    if result.get('after_close', {}).get('descendants') != 0:
        raise ValueError('Unexpected native descendant')
    finite(result.get('startup_to_completed_source_paint_jvm_ms'))


def compare(runs):
    groups = {kind: [run['result'] for run in runs if run['kind'] == kind] for kind in ('baseline', 'candidate')}
    if not groups['baseline'] or len(groups['baseline']) != len(groups['candidate']):
        raise ValueError('Incomplete matched pairs')
    for kind, results in groups.items():
        for result in results:
            validate_result(result, kind == 'candidate')
    def values(kind, name, key):
        return [finite(next(r for r in result['rows'] if r['operation'] == name)[key]) for result in groups[kind]]
    comparisons = []
    def gate(name, before, after, ratio, allowance):
        b, c = statistics.median(before), statistics.median(after)
        budget = max(b * ratio, b + allowance)
        comparisons.append({'operation': name, 'baseline': b, 'candidate': c, 'limit': budget, 'pass': c <= budget})
    baseline_names = {r['operation'] for r in groups['baseline'][0]['rows'] if 'p95_ms' in r}
    for name in sorted(baseline_names):
        ratio, allowance = (1.25, 5) if name == 'split_cycle' else (1.5, 1 if name.startswith('typing.') else 2)
        gate(name, values('baseline', name, 'p95_ms'), values('candidate', name, 'p95_ms'), ratio, allowance)
    for name in ('open.Small.txt', 'open.100KiB.txt', 'open.1MiB.txt'):
        before = [finite(next(r for r in x['rows'] if r['operation'] == name)['ms']) for x in groups['baseline']]
        after = [finite(next(r for r in x['rows'] if r['operation'] == name)['ms']) for x in groups['candidate']]
        gate(name, before, after, 1.25, 20)
    gate('startup', [x['startup_to_completed_source_paint_jvm_ms'] for x in groups['baseline']],
         [x['startup_to_completed_source_paint_jvm_ms'] for x in groups['candidate']], 1.2, 100)
    for key, allowance in [('used_heap_bytes', 8 * 1024 * 1024), ('threads', 2), ('open_descriptors', 4)]:
        memory = {kind: [next(r for r in x['rows'] if r['operation'] == 'common_settled_memory')['values'] for x in results]
                  for kind, results in groups.items()}
        if all(key in row for rows in memory.values() for row in rows):
            gate(key, [finite(x[key]) for x in memory['baseline']], [finite(x[key]) for x in memory['candidate']], 1, allowance)
    idle = {kind: [next((r for r in result['rows'] if r['operation'] == 'idle_workers'), None) for result in results]
            for kind, results in groups.items()}
    if any(row is not None for values in idle.values() for row in values):
        if any(row is None for values in idle.values() for row in values):
            raise ValueError('Idle profiling must cover both artifacts in every pair')
        for row in idle['candidate']:
            if row['idle_ms'] < 35000 or row['after']['shed_workers'] != 0:
                raise ValueError('Idle workers did not retire')
            if finite(row['resume_edt_submit_ms']) > 16 or finite(row['resume_completion_ms']) > 50:
                raise ValueError('First request after idle exceeded resumption budgets')
        comparisons.append({'operation': 'idle_shed_workers', 'baseline': statistics.median(r['after']['shed_workers'] for r in idle['baseline']),
                            'candidate': statistics.median(r['after']['shed_workers'] for r in idle['candidate']), 'limit': 0, 'pass': True})
        gate('idle_total_threads', [r['after']['memory']['threads'] for r in idle['baseline']], [r['after']['memory']['threads'] for r in idle['candidate']], 1, 0)
    passed = all(row['pass'] for row in comparisons)
    return {'state': 'FAIL' if not passed else 'PASS' if len(groups['baseline']) >= 3 else 'INSUFFICIENT_PAIRS',
            'pairs': len(groups['baseline']), 'comparisons': comparisons,
            'budgets': 'proposed practical regression gates; 16 ms typing target is pre-existing',
            'limits': 'fresh processes, warm OS caches; medians of per-process metrics; no statistical equivalence claim or real Hg/SVN qualification'}


def run_one(jdk, jar, output, compile_only=False, idle_profile=False):
    output.mkdir()
    f, process = Fixture(), None
    report = {'jar_sha256': digest(jar), 'probe_sha256': digest(PROBE), 'native_shell': 'NOT_USED',
              'host_load_before': os.getloadavg(), 'fixture': str(f.root)}
    try:
        f.validate()
        project, work, classes = [f.run_root / name for name in ('project', 'live', 'classes')]
        for directory in (project, work, classes):
            directory.mkdir(mode=0o700)
            checked(directory, f.root, directory=True)
        content = b'ordinary source line 0123456789\n'
        report['fixtures'] = {}
        for name, size in [('Small.txt', 4096), ('100KiB.txt', 102400), ('1MiB.txt', 1048576)]:
            data = (content * (size // len(content) + 1))[:size]
            (project / name).write_bytes(data)
            report['fixtures'][name] = {'bytes': size, 'sha256': hashlib.sha256(data).hexdigest()}
        sentinel = f.run_root / 'external-sentinel'
        sentinel.write_bytes(b'Synthetic external configuration, unchanged.\n')
        before, stamp = digest(sentinel), sentinel.stat().st_mtime_ns
        env = dict(f.env, PATH=str(f.run_root / 'no-executables'), SHED_PORTABLE_FIXTURE_ROOT=str(f.root), SHED_LIVE_VISUAL_ONLY='1')
        props = ['-Duser.home=' + env['HOME'], '-Djava.io.tmpdir=' + env['TMPDIR'], '-Djava.util.prefs.userRoot=' + str(f.run_root / 'config')]
        compile_cmd = [str(jdk / 'bin/javac'), *['-J' + p for p in props], '-proc:none', '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), str(PROBE)]
        compiled = subprocess.run(compile_cmd, env=env, cwd=f.run_root, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30)
        report.update(compile_command=compile_cmd, compile_exit=compiled.returncode, compile_output=compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise UnsafeFixture('Probe compilation failed')
        if idle_profile:
            env['SHED_SCM_IDLE_PROFILE'] = '1'
        if not compile_only:
            for key in ('DISPLAY', 'XAUTHORITY'):
                if os.environ.get(key):
                    env[key] = os.environ[key]
            command = [str(jdk / 'bin/java'), '-Djava.awt.headless=false', *props, '-cp', str(classes) + os.pathsep + str(jar),
                       'shed.SourceControlPerformanceProbe', str(work), str(project)]
            report['command'] = command
            with (work / 'stdout.log').open('w') as log:
                process = subprocess.Popen(command, env=env, cwd=project, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
                report['exit'] = process.wait(timeout=150)
            result = work / 'result.json'
            if not result.is_file() or result.stat().st_size > 4 * 1024 * 1024:
                raise UnsafeFixture('Missing or oversized probe result')
            report['result'] = json.loads(result.read_text())
            for name in report['fixtures']:
                if digest(project / name) != report['fixtures'][name]['sha256']:
                    raise UnsafeFixture('Probe changed fixture disk source')
        report.update(sentinel_sha256_before=before, sentinel_sha256_after=digest(sentinel), sentinel_metadata_unchanged=stamp == sentinel.stat().st_mtime_ns)
        if digest(sentinel) != before or not report['sentinel_metadata_unchanged']:
            raise UnsafeFixture('Synthetic external sentinel changed')
        report['state'] = 'PASS' if compile_only or report['exit'] == 0 and report['result']['state'] == 'PASS' else 'FAIL'
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
        if work.is_dir():
            for name in ('result.json', 'stdout.log'):
                p = work / name
                if p.is_file() and p.stat().st_size <= 4 * 1024 * 1024:
                    shutil.copyfile(p, output / name)
        try:
            f.close()
            report['fixture_removed'] = not f.root.exists()
        except Exception as error:
            report.update(state='FAIL', cleanup_error=str(error))
        report['host_load_after'] = os.getloadavg()
        (output / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def exit_code(state):
    return 0 if state in ('PASS', 'COMPILE_ONLY') else 2 if state == 'INSUFFICIENT_PAIRS' else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--baseline-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pairs', type=int, default=3, choices=range(1, MAX_PAIRS + 1))
    parser.add_argument('--compile-only', action='store_true')
    parser.add_argument('--idle-workers', action='store_true', help='Also measure 35-second idle retirement and first request resumption per process')
    args = parser.parse_args()
    jdk, candidate, baseline = [p.resolve(strict=True) for p in (args.jdk, args.jar, args.baseline_jar)]
    output = args.output.absolute()
    if output.exists() or output.resolve() != output or not all((jdk / 'bin' / t).is_file() for t in ('java', 'javac')):
        parser.error('Existing JDK and new canonical output directory required')
    if digest(candidate) == digest(baseline):
        parser.error('Identical artifacts cannot establish before/after coverage')
    output.mkdir(parents=True)
    report = {'machine': platform.platform(), 'cpu_count': os.cpu_count(), 'compile_only': args.compile_only, 'runs': []}
    try:
        for i, kind in enumerate(order(1 if args.compile_only else args.pairs)):
            print('PERF_START', i, kind, flush=True)
            run = run_one(jdk, baseline if kind == 'baseline' else candidate, output / (str(i) + '-' + kind), args.compile_only, args.idle_workers)
            run['kind'] = kind
            report['runs'].append(run)
            print('PERF_END', i, kind, run['state'], flush=True)
            if run['state'] != 'PASS':
                raise ValueError('Desktop fixture failed; no automatic retry')
        report['comparison'] = {'state': 'COMPILE_ONLY'} if args.compile_only else compare(report['runs'])
        report['state'] = report['comparison']['state']
    except Exception as error:
        report.update(state='FAIL', error=str(error))
    (output / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Source Control comparison:', report['state'], output / 'comparison.json')
    return exit_code(report['state'])


if __name__ == '__main__':
    raise SystemExit(main())
