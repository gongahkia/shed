"""Experimental builtin-only shell fixtures; no native backend is qualified. Never imported by the production application."""
import hashlib
import json
import os
from pathlib import Path
import platform
import resource
import shutil
import stat
import subprocess
import tempfile
import threading
import time
import uuid


class UnsafeFixture(ValueError):
    pass


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def checked(path, root, directory=False):
    root, path = Path(root), Path(path)
    if not path.is_absolute():
        raise UnsafeFixture('Nonabsolute fixture path')
    try:
        relative = path.relative_to(root)
    except ValueError as error:
        raise UnsafeFixture('Path outside fixture') from error
    if '..' in relative.parts:
        raise UnsafeFixture('Parent traversal in fixture path')
    current = root
    for part in ('', *relative.parts):
        current /= part
        if current.is_symlink():
            raise UnsafeFixture('Symbolic link in fixture path')
        if current.exists() and current.stat().st_uid != os.getuid():
            raise UnsafeFixture('Different filesystem owner')
    if directory:
        if not path.is_dir() or not path.stat().st_mode & stat.S_IWUSR:
            raise UnsafeFixture('Missing or read-only fixture directory')
    elif path.exists() and (not path.is_file() or path.stat().st_nlink != 1):
        raise UnsafeFixture('Nonregular or multiply linked fixture file')
    return path


def _limits():
    # No workers exist when preexec_fn runs; bound even a surviving runner-crash probe.
    resource.setrlimit(resource.RLIMIT_CPU, (1, 2))
    resource.setrlimit(resource.RLIMIT_FSIZE, (65536, 65536))
    resource.setrlimit(resource.RLIMIT_NOFILE, (64, 64))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


SHELLS = {
    'sh': ['/bin/sh', '--noprofile', '--norc', '-p'],
    'bash': ['/bin/bash', '--noprofile', '--norc', '-p'],
    'zsh': ['/bin/zsh', '-d', '-f'],
}
SCENARIOS = {
    'environment': 'printf "%s\\n" "$HOME" "$ZDOTDIR" "$HISTFILE" "$PWD"; printf fixture-ok',
    'history': 'printf "synthetic history\\n" >> "$HISTFILE"; printf fixture-ok',
    'exit': 'exit 7',
    'timeout': 'while :; do :; done',
}


class Fixture:
    def __init__(self, parent=None):
        started = time.perf_counter_ns()
        base = Path(parent) if parent is not None else Path('/private/tmp' if platform.system() == 'Darwin' else '/tmp')
        if not base.is_absolute() or base.resolve() != base or base.is_symlink():
            raise UnsafeFixture('Fixture parent must be canonical')
        self.root = Path(tempfile.mkdtemp(prefix='shed-native-', dir=base))
        self.root.chmod(0o700)
        self.run_root = self.root / 'run'
        for name in ['home', 'config', 'data', 'cache', 'state', 'runtime', 'tmp', 'work', 'logs', 'gnupg']:
            (self.run_root / name).mkdir(parents=True, mode=0o700)
        self.identity = (self.root.stat().st_dev, self.root.stat().st_ino)
        self.token = uuid.uuid4().hex
        marker = self.root / 'owner.json'
        marker.write_text(json.dumps({'id': self.token, 'identity': self.identity}))
        marker.chmod(0o600)
        self.children = []
        self.closed = False
        self.verified = False
        self.metrics = [{'operation': 'creation', 'ns': time.perf_counter_ns() - started}]
        self.env = self._environment()
        self._expected_environment = dict(self.env)

    def _environment(self):
        r = self.run_root
        env = {'HOME': str(r / 'home'), 'ZDOTDIR': str(r / 'home'),
               'XDG_CONFIG_HOME': str(r / 'config'), 'XDG_DATA_HOME': str(r / 'data'), 'XDG_CACHE_HOME': str(r / 'cache'),
               'XDG_STATE_HOME': str(r / 'state'), 'XDG_RUNTIME_DIR': str(r / 'runtime'), 'TMPDIR': str(r / 'tmp'),
               'HISTFILE': str(r / 'home/history'), 'BASH_ENV': str(r / 'home/empty-startup'), 'ENV': str(r / 'home/empty-startup'),
               'GNUPGHOME': str(r / 'gnupg'), 'LANG': 'en_US.UTF-8', 'LC_ALL': 'en_US.UTF-8', 'TZ': 'UTC',
               'TERM': 'dumb', 'TERM_PROGRAM': 'ShedFixture', 'SHELL': '/bin/sh', 'PATH': '/usr/bin:/bin:/usr/sbin:/sbin',
               'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_SYSTEM': '/dev/null', 'GIT_CONFIG_GLOBAL': str(r / 'config/gitconfig'),
               'GIT_TERMINAL_PROMPT': '0', 'GIT_SSH_COMMAND': '/usr/bin/false', 'SSH_ASKPASS': '/usr/bin/false'}
        for name in ['home/empty-startup', 'config/gitconfig']:
            (r / name).write_text('')
            (r / name).chmod(0o600)
        return env

    def validate(self, environment=None, cwd=None):
        started = time.perf_counter_ns()
        if self.closed or self.root.resolve() != self.root or self.root.is_symlink():
            raise UnsafeFixture('Closed or noncanonical fixture root')
        meta = self.root.lstat()
        if (meta.st_dev, meta.st_ino) != self.identity or meta.st_uid != os.getuid() or meta.st_mode & 0o077:
            raise UnsafeFixture('Root identity/permissions changed')
        marker = checked(self.root / 'owner.json', self.root)
        value = json.loads(marker.read_text())
        if value != {'id': self.token, 'identity': list(self.identity)}:
            raise UnsafeFixture('Owner marker changed')
        env = dict(self.env) if environment is None else environment
        if env != self._expected_environment:
            # Do not log variable values: rejected environments may contain secrets.
            changed = sorted(key for key in set(env) | set(self._expected_environment)
                             if env.get(key) != self._expected_environment.get(key))
            raise UnsafeFixture('Environment allowlist violation: ' + ', '.join(changed))
        for key in ['HOME', 'ZDOTDIR', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_CACHE_HOME', 'XDG_STATE_HOME', 'XDG_RUNTIME_DIR', 'TMPDIR', 'GNUPGHOME']:
            checked(env[key], self.run_root, directory=True)
        for key in ['HISTFILE', 'BASH_ENV', 'ENV', 'GIT_CONFIG_GLOBAL']:
            checked(env[key], self.run_root)
        work = self.run_root / 'work'
        if cwd is not None and Path(cwd) != work:
            raise UnsafeFixture('Unexpected working directory')
        checked(work, self.run_root, directory=True)
        if not shutil.rmtree.avoids_symlink_attacks:
            raise UnsafeFixture('Descriptor-relative cleanup unavailable')
        self.metrics.append({'operation': 'guard', 'ns': time.perf_counter_ns() - started})

    def profile(self):
        if platform.system() != 'Darwin' or not Path('/usr/bin/sandbox-exec').is_file():
            raise UnsafeFixture('Only macOS candidate Seatbelt backend available')
        # zsh -df cannot skip the global zshenv. Reject it rather than trusting unknown startup code.
        if Path('/etc/zshenv').exists() or Path('/etc/zshenv').is_symlink():
            raise UnsafeFixture('Global zshenv present; shell configuration unqualified')
        quote = lambda p: json.dumps(str(Path(p).resolve()))
        roots = ['/System', '/usr', '/bin', '/sbin', self.run_root]
        lines = ['(version 1)', '(deny default)', '(allow sysctl-read)', '(allow file-read-metadata)',
                 '(allow process-exec ' + ' '.join('(literal ' + quote(p) + ')' for p in ['/bin/sh', '/bin/bash', '/bin/zsh']) + ')',
                 '(allow file-read* ' + ' '.join('(subpath ' + quote(p) + ')' for p in roots) + ')',
                 '(allow file-read* (literal "/dev/null") (literal "/dev/random") (literal "/dev/urandom"))',
                 '(allow file-write* (subpath ' + quote(self.run_root) + ') (literal "/dev/null"))']
        # No process-fork, networking, Mach services, PTYs or user-home read access.
        path = self.root / 'fixture.sb'
        path.write_text('\n'.join(lines) + '\n')
        path.chmod(0o600)
        return path

    def _execute(self, shell, mode, script, timeout):
        self.validate()
        if threading.active_count() != 1:
            raise UnsafeFixture('Pre-exec limits require a single-threaded fixture runner')
        if shell not in SHELLS or mode not in ['command', 'interactive', 'login'] or not 0 < timeout <= 5:
            raise UnsafeFixture('Unsupported shell, mode or timeout')
        profile = self.profile()
        args = [*SHELLS[shell]]
        if mode == 'interactive':
            args.append('-i')
        elif mode == 'login':
            args.append('-l')
        args.extend(['-c', script])
        log = self.run_root / 'logs' / (uuid.uuid4().hex + '.log')
        started = time.monotonic()
        with log.open('wb') as stream:
            process = subprocess.Popen(['/usr/bin/sandbox-exec', '-f', str(profile), *args], env=self.env,
                                       cwd=self.run_root / 'work', stdin=subprocess.DEVNULL, stdout=stream,
                                       stderr=subprocess.STDOUT, start_new_session=True, preexec_fn=_limits)
            self.children.append(process)
            timed_out = False
            try:
                process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
            finally:
                # A live unreaped Popen owns this exact PID. Forking is denied by the verified profile.
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=3)
        result = {'shell': shell, 'mode': mode, 'exit': process.returncode, 'timeout': timed_out,
                  'elapsed_ms': (time.monotonic() - started) * 1000, 'pid': process.pid,
                  'output': log.read_text(errors='replace')}
        self.metrics.append({'operation': 'shell', **result})
        return result

    def verify_backend(self):
        self.validate()
        sentinel = self.root / 'protected-sentinel'
        sentinel.write_text('synthetic external sentinel\n')
        before = (digest(sentinel), sentinel.stat().st_mtime_ns)
        # These commands use only builtins. The protected sentinel belongs to this fixture,
        # but lies outside its child-writable/readable area.
        probes = {
            'allowed_write': 'printf allowed > "$HOME/allowed"',
            'denied_write': 'printf changed > "../../protected-sentinel"',
            'denied_read': 'read -r value < "../../protected-sentinel"',
            'denied_fork': '(printf forbidden > "$HOME/forked") & wait',
            'denied_exec': 'exec /usr/bin/touch "$HOME/executed"',
        }
        rows = []
        for label, script in probes.items():
            row = self._execute('bash', 'command', script, 3)
            rows.append({'probe': label, **row})
            if label == 'allowed_write':
                ok = row['exit'] == 0 and (self.run_root / 'home/allowed').read_text() == 'allowed'
            elif label in ['denied_write', 'denied_read', 'denied_exec']:
                ok = row['exit'] != 0 and not row['timeout']
            else:
                ok = 'fork' in row['output'].lower() and not (self.run_root / 'home/forked').exists()
            if not ok:
                raise UnsafeFixture('Backend failed synthetic probe: ' + label)
        after = (digest(sentinel), sentinel.stat().st_mtime_ns)
        if before != after or (self.run_root / 'home/executed').exists():
            raise UnsafeFixture('Synthetic protection invariant failed')
        self.verified = True
        return {'probes': rows, 'sentinel_before': before, 'sentinel_after': after}

    def run(self, shell='bash', mode='command', scenario='environment', timeout=3):
        if not self.verified:
            raise UnsafeFixture('Confinement verification required before native fixture')
        if scenario not in SCENARIOS:
            raise UnsafeFixture('Only fixed builtin scenarios are supported; arbitrary commands rejected')
        return self._execute(shell, mode, SCENARIOS[scenario], timeout)

    def close(self):
        if self.closed:
            return
        for process in self.children:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)
        self.validate()
        started = time.perf_counter_ns()
        shutil.rmtree(self.root)
        self.closed = True
        self.metrics.append({'operation': 'cleanup', 'ns': time.perf_counter_ns() - started})
