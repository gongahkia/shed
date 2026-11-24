#!/usr/bin/env python3
"""Opt-in live workbench test with fixed Java processes and a disposable local-agent workspace."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
from native_fixture import Fixture, UnsafeFixture

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--replay-payload', type=Path, help='Replay a bounded previously generated proposal through the same external CLI')
    args = parser.parse_args()
    jdk, jar = args.jdk.resolve(strict=True), args.jar.resolve(strict=True)
    output = args.output.absolute()
    if output.exists() or output.resolve() != output or output.is_symlink():
        parser.error('Output must be a new canonical directory')
    for name in ['java', 'javac']:
        if not (jdk / 'bin' / name).is_file():
            parser.error('Existing JDK required')
    output.mkdir(parents=True)
    f = Fixture()
    process = None
    report = {'boundary': 'fixed direct Java workbench/client; disposable files/preferences; not an OS sandbox',
              'native_shell_benchmarks': 'BLOCKED', 'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'jdk': str(jdk), 'fixture': str(f.root)}
    started = time.monotonic()
    try:
        f.validate()
        work = f.run_root / 'live'
        work.mkdir(mode=0o700)
        classes = f.run_root / 'classes'
        classes.mkdir(mode=0o700)
        project = f.run_root / 'project'
        project.mkdir(mode=0o700)
        (project / 'Welcome.txt').write_text('Shed: authenticated agent workflow verification\nDisposable workspace. No personal files.\n')
        (project / 'Demo.java').write_text('public class Demo {\n    public static String greet(String name) {\n        return "Hello, " + name;\n    }\n}\n')
        (f.run_root / 'no-executables').mkdir(mode=0o700)
        env = dict(f.env, PATH=str(f.run_root / 'no-executables'), SHED_PORTABLE_FIXTURE_ROOT=str(f.root))
        props = ['-Duser.home=' + env['HOME'], '-Djava.io.tmpdir=' + env['TMPDIR'],
                 '-Djava.util.prefs.userRoot=' + str(f.run_root / 'config')]
        compile_command = [str(jdk / 'bin/javac'), *['-J' + p for p in props], '-proc:none', '-encoding', 'UTF-8',
                           '-cp', str(jar), '-d', str(classes), str(ROOT / 'src/benchmark/java/shed/AgentWorkflowDesktopProbe.java')]
        compiled = subprocess.run(compile_command, env=env, cwd=f.run_root, stdin=subprocess.DEVNULL,
                                  capture_output=True, text=True, timeout=30)
        report.update(compile_exit=compiled.returncode, compile_output=compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise UnsafeFixture('Live probe compile failed')
        session = f.run_root / 'live-session.json'
        session.write_text(json.dumps({'fixture': str(f.root), 'jar': str(jar), 'jar_sha256': report['jar_sha256'],
                                       'java': str(jdk / 'bin/java'), 'environment': env}))
        session.chmod(0o600)
        command = [str(jdk / 'bin/java'), '-Djava.awt.headless=false', *props, '-cp', str(classes) + ':' + str(jar),
                   'shed.AgentWorkflowDesktopProbe', str(work), str(project)]
        report['command'] = command
        with (work / 'stdout.log').open('w') as log:
            process = subprocess.Popen(command, env=env, cwd=project, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
            report['pid'] = process.pid
            print('LIVE_DEMO_SESSION ' + str(session), flush=True)
            if args.replay_payload:
                payload = args.replay_payload.read_bytes()
                if len(payload) > 8192:
                    raise UnsafeFixture('Replay payload exceeds bounded demo budget')
                target = f.run_root / 'work/codex-chunk.txt'
                target.write_bytes(payload)
                report['replay_payload_sha256'] = hashlib.sha256(payload).hexdigest()
                deadline = time.monotonic() + 20
                while not (work / 'ready.json').exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.05)
                client_results = []
                for operation, extra in [('open', ['--path', 'Demo.java']), ('read', []), ('activity', ['--start', '0', '--end', '17']),
                                          ('propose', ['--start', '0', '--end', '107', '--text-file', str(target)])]:
                    client = subprocess.run([sys.executable, str(ROOT / 'scripts/agent-demo-client.py'), '--session', str(session),
                                             '--operation', operation, *extra], cwd=ROOT, env=dict(env, PYTHONNOUSERSITE='1', PYTHONDONTWRITEBYTECODE='1'),
                                            stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=25)
                    client_results.append({'operation': operation, 'exit': client.returncode, 'stdout': client.stdout[:65536], 'stderr': client.stderr[:4096]})
                    if client.returncode:
                        report['client_results'] = client_results
                        raise UnsafeFixture('Replayed external client failed')
                    if operation == 'open':
                        captured = time.monotonic() + 5
                        while not (work / 'opened-captured').exists() and process.poll() is None and time.monotonic() < captured:
                            time.sleep(0.025)
                report['client_results'] = client_results
            process.wait(timeout=160)
        report['exit'] = process.returncode
        report['log'] = (work / 'stdout.log').read_text()[:524288]
        if (work / 'result.json').exists():
            report['result'] = json.loads((work / 'result.json').read_text())
        if process.returncode == 0:
            compiled_source = subprocess.run([str(jdk / 'bin/javac'), *['-J' + p for p in props], '-proc:none', '-encoding', 'UTF-8',
                                              '-d', str(classes), str(project / 'Demo.java')], env=env, cwd=project,
                                             stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30)
            report['generated_source_compile_exit'] = compiled_source.returncode
            report['generated_source_compile_output'] = compiled_source.stdout + compiled_source.stderr
            if compiled_source.returncode:
                report['exit'] = 1
        for picture in sorted(work.glob('*.png')):
            if picture.stat().st_size > 4 * 1024 * 1024:
                raise UnsafeFixture('Capture exceeds bounded image budget')
            shutil.copyfile(picture, output / picture.name)
        animation = work / 'agent-workflow.gif'
        if animation.exists() and animation.stat().st_size <= 8 * 1024 * 1024:
            shutil.copyfile(animation, output / animation.name)
    except (OSError, UnsafeFixture, subprocess.TimeoutExpired) as failure:
        report.update(exit=2, error=str(failure))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        f.close()
        report.update(fixture_removed=not f.root.exists(), elapsed_s=time.monotonic() - started)
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('LIVE_DEMO_RESULT ' + str(report.get('exit', 2)) + ' ' + str(output / 'report.json'), flush=True)
    return 0 if report.get('exit') == 0 and report.get('result', {}).get('state') == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
