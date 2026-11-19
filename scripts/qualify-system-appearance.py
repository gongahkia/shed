#!/usr/bin/env python3
"""Opt-in event feasibility only. --execute-transitions briefly changes macOS appearance and restores it."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('markdown_baseline', ROOT / 'scripts/benchmark-markdown.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
TRANSITION_LIMIT = 10


def system_mode(value=None):
    action = 'get dark mode' if value is None else 'set dark mode to ' + str(value).lower()
    command = ['/usr/bin/osascript', '-e', 'with timeout of 3 seconds', '-e',
               'tell application "System Events" to tell appearance preferences to ' + action, '-e', 'end timeout']
    result = subprocess.run(command, capture_output=True, text=True, timeout=5, check=True)
    if value is None:
        if result.stdout.strip() not in ('true', 'false'):
            raise RuntimeError('Unexpected macOS appearance response')
        return result.stdout.strip() == 'true'


def auto_preference():
    result = subprocess.run(['/usr/bin/defaults', 'read', '-g', 'AppleInterfaceStyleSwitchesAutomatically'], capture_output=True, text=True, timeout=5)
    if result.returncode not in (0, 1):
        raise RuntimeError('Cannot read automatic-appearance preference')
    if result.returncode == 0 and result.stdout.strip() not in ('0', '1'):
        raise RuntimeError('Unexpected automatic-appearance preference')
    return result.stdout.strip() if result.returncode == 0 else None


class Child:
    def __init__(self, command, folder):
        self.command, self.rows, self.condition = command, [], threading.Condition()
        self.log = (folder / 'stdout.log').open('w')
        env = os.environ.copy()
        for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH']:
            env.pop(key, None)
        self.process = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, bufsize=1, env=env)
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()
        try:
            self.wait(lambda row: row.get('event') == 'ready', timeout=15)
        except BaseException:
            self.close()
            raise

    def read(self):
        try:
            for line in self.process.stdout:
                self.log.write(line)
                self.log.flush()
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                with self.condition:
                    self.rows.append(row)
                    self.condition.notify_all()
        finally:
            with self.condition:
                self.condition.notify_all()

    def mark(self):
        with self.condition:
            return len(self.rows)

    def wait(self, predicate, after=0, timeout=6):
        until = time.monotonic() + timeout
        with self.condition:
            while True:
                for row in self.rows[after:]:
                    if row.get('event') == 'error':
                        raise RuntimeError(str(row))
                    if predicate(row):
                        return row
                left = until - time.monotonic()
                if left <= 0 or self.process.poll() is not None:
                    raise TimeoutError('Expected event unavailable: ' + str(self.command))
                self.condition.wait(timeout=left)

    def command_and_wait(self, command, native=False):
        after = self.mark()
        self.process.stdin.write(command + '\n')
        self.process.stdin.flush()
        return self.wait(lambda row: row.get('request') == command and row.get('event') == ('ack' if native else 'status'), after)

    def close(self):
        if self.process.poll() is None:
            try:
                self.process.stdin.write('exit\n')
                self.process.stdin.flush()
                self.process.wait(timeout=6)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                self.process.terminate()
                try:
                    self.process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=2)
        self.reader.join(timeout=2)
        self.log.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/shed-2.0.0.jar')
    parser.add_argument('--execute-transitions', action='store_true', help='Explicitly allow temporary real macOS light/dark transitions')
    parser.add_argument('--generate-only', action='store_true', help='Write the bounded plan without Java, AppKit, or OS changes')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output == ROOT or ROOT in output.parents:
        parser.error('Output must be a NEW directory outside the checkout')
    if not args.generate_only and not args.execute_transitions:
        parser.error('Real transitions require explicit --execute-transitions')
    output.mkdir(parents=True)
    plan = {'transition_limit': TRANSITION_LIMIT, 'mechanisms': ['AppKit effectiveAppearance KVO', 'Java 21 public listeners with default/system startup controls'],
            'production_integration': False, 'restoration': 'original dark-mode value and automatic-appearance key presence/value'}
    (output / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
    if args.generate_only:
        return 0
    if sys.platform != 'darwin':
        parser.error('Runtime feasibility is macOS-only; production manual fallback remains unchanged')
    jar = args.jar.resolve(strict=True)
    jdk = Path(subprocess.check_output([str(ROOT / 'scripts/resolve-toolchain.sh'), 'home'], text=True).strip())
    native = ROOT / 'src/benchmark/native/SystemAppearanceObserver.m'
    java = ROOT / 'src/benchmark/java/shed/SystemAppearanceAwtProbe.java'
    build_commands = [
        ['xcrun', 'clang', '-fobjc-arc', '-fblocks', '-framework', 'AppKit', str(native), '-o', str(output / 'native-observer')],
        [str(jdk / 'bin/javac'), '-cp', str(jar), '-d', str(output / 'classes'), str(java)],
    ]
    for index, command in enumerate(build_commands):
        result = subprocess.run(command, capture_output=True, text=True, timeout=40)
        (output / f'build-{index}.log').write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('Probe build failed: ' + str(command))
    report = {'plan': plan, 'environment_before': base.environment(), 'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'sources': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [native, java]},
              'build_commands': build_commands, 'children': [], 'transitions': [], 'observations': [], 'rss_samples': [], 'errors': []}
    original_mode, original_auto = system_mode(), auto_preference()
    report['original_dark'], report['original_auto'] = original_mode, original_auto
    children, handles = [], {}

    def save():
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')

    def rss(phase):
        for label, child in handles.items():
            result = subprocess.run(['/bin/ps', '-o', 'rss=', '-p', str(child.process.pid)], capture_output=True, text=True, timeout=3)
            if result.returncode == 0 and result.stdout.strip():
                report['rss_samples'].append({'label': label, 'phase': phase, 'mib': float(result.stdout) / 1024})

    def start(label, command):
        folder = output / label
        (folder / 'home').mkdir(parents=True)
        began = time.monotonic()
        child = Child(command, folder)
        children.append((label, child))
        handles[label] = child
        report['observations'].append({'label': label, 'operation': 'launch_to_ready', 'ms': (time.monotonic() - began) * 1000})
        return child

    def start_java(label, following):
        command = [str(jdk / 'bin/java'), '-Xms64m', '-Xmx256m', '-Djava.awt.headless=false', f'-Duser.home={output / label / "home"}']
        if following:
            command.append('-Dapple.awt.application.appearance=system')
        command += ['-cp', os.pathsep.join([str(output / 'classes'), str(jar)]), 'shed.SystemAppearanceAwtProbe']
        return start(label, command)

    def transition(dark, label, observing=True):
        if len(report['transitions']) >= TRANSITION_LIMIT:
            raise RuntimeError('Bounded transition limit reached')
        mark = observer.mark()
        began = time.time() * 1000
        system_mode(dark)
        if system_mode() != dark:
            raise RuntimeError('System appearance mutation did not take effect')
        row = {'label': label, 'dark': dark, 'request_epoch_ms': began, 'confirmed_epoch_ms': time.time() * 1000}
        report['transitions'].append(row)
        if observing:
            event = observer.wait(lambda e: e.get('event') == 'appearance' and e.get('effective') == ('NSAppearanceNameDarkAqua' if dark else 'NSAppearanceNameAqua'), mark)
            row['native_kvo_event'] = event
            row['request_to_kvo_ms'] = event['epoch_ms'] - began
        for name, child in list(handles.items()):
            if name != 'native' and child.process.poll() is None:
                state = child.command_and_wait(label)
                report['observations'].append({'label': name, 'operation': label, **state})
                if not state['appkit_main_thread']:
                    raise RuntimeError('Native read was not on AppKit main thread')
        rss(label)
        save()

    try:
        if original_mode:
            system_mode(False)
        observer = start('native', [str(output / 'native-observer')])
        default = start_java('java-default-light', False)
        following = start_java('java-system-light', True)
        rss('initial')
        for index, dark in enumerate([True, False, True, False]):
            transition(dark, f'follow-{index}')
        report['observations'].append({'label': 'native', 'operation': 'pin-light', **observer.command_and_wait('pin-light', True)})
        transition(True, 'pinned-native-during-dark', observing=False)
        report['observations'].append({'label': 'native', 'operation': 'pinned-status', **observer.command_and_wait('pinned-status', True)})
        off = observer.command_and_wait('unregister', True)
        transition(False, 'unregistered-during-light', observing=False)
        after_off = observer.command_and_wait('unregistered-status', True)
        if off['callbacks'] != after_off['callbacks']:
            raise RuntimeError('Native callback arrived after unregistration')
        observer.command_and_wait('follow', True)
        observer.command_and_wait('register', True)
        before_duplicate = observer.command_and_wait('register', True)
        report['observations'].append({'label': 'native', 'operation': 'idempotent-register', **before_duplicate})
        report['observations'].append({'label': 'java-default-light', 'operation': 'late-property', **default.command_and_wait('property-system')})
        default.command_and_wait('reopen')
        following.command_and_wait('reopen')
        for name in ['java-default-light', 'java-system-light']:
            handles.pop(name).close()
        transition(True, 'initial-dark-native')
        start_java('java-default-dark', False)
        start_java('java-system-dark', True)
        rss('initial_dark')
        transition(False, 'return-to-light')
        report['observations'].append({'label': 'native', 'operation': 'final-unregister', **observer.command_and_wait('unregister', True)})
        report['state'] = 'MEASUREMENTS_COMPLETE'
    except BaseException as error:
        report['errors'].append(repr(error))
        report['state'] = 'FAILED'
    finally:
        try:
            system_mode(original_mode)
            current_auto = auto_preference()
            if current_auto != original_auto:
                command = ['/usr/bin/defaults', 'delete', '-g', 'AppleInterfaceStyleSwitchesAutomatically'] if original_auto is None else [
                    '/usr/bin/defaults', 'write', '-g', 'AppleInterfaceStyleSwitchesAutomatically', '-bool', 'true' if original_auto == '1' else 'false']
                subprocess.run(command, capture_output=True, text=True, check=True, timeout=5)
            report['restored_dark'], report['restored_auto'] = system_mode(), auto_preference()
            report['restored'] = report['restored_dark'] == original_mode and report['restored_auto'] == original_auto
        except BaseException as error:
            report['errors'].append('RESTORATION: ' + repr(error))
            report['restored'] = False
        for label, child in reversed(children):
            child.close()
            report['children'].append({'label': label, 'command': child.command, 'exit': child.process.returncode, 'events': child.rows})
        report['environment_after'] = base.environment()
        save()
    print(json.dumps({'state': report['state'], 'restored': report['restored'], 'errors': report['errors']}, indent=2))
    return 0 if report['state'] == 'MEASUREMENTS_COMPLETE' and report['restored'] and not report['errors'] and all(child['exit'] == 0 for child in report['children']) else 1


if __name__ == '__main__':
    sys.exit(main())
