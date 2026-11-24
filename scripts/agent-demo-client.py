#!/usr/bin/env python3
"""Opt-in, fixed Java CLI client for the disposable live-agent acceptance fixture. Never an OS sandbox."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.dont_write_bytecode = True
from native_fixture import checked, UnsafeFixture


def safe_environment(env, run):
    if any(k in env for k in ['SSH_AUTH_SOCK', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']):
        raise UnsafeFixture('Unexpected inherited environment')
    startup = checked(run / 'home/empty-startup', run)
    if env.get('BASH_ENV') != str(startup) or env.get('ENV') != str(startup) or startup.read_bytes() != b'':
        raise UnsafeFixture('Unexpected shell startup configuration')
    for key in ['HOME', 'TMPDIR', 'XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_CACHE_HOME', 'XDG_STATE_HOME']:
        checked(Path(env[key]), run, directory=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', required=True, type=Path)
    parser.add_argument('--operation', required=True, choices=['context', 'open', 'read', 'activity', 'propose'])
    parser.add_argument('--path', default='Demo.java')
    parser.add_argument('--start', type=int, default=0)
    parser.add_argument('--end', type=int, default=0)
    parser.add_argument('--text-file', type=Path)
    args = parser.parse_args()
    session = args.session
    if not session.is_absolute() or session.resolve() != session or session.is_symlink():
        raise UnsafeFixture('Noncanonical live session')
    data = json.loads(session.read_text())
    root = Path(data['fixture'])
    checked(session, root)
    if root.stat().st_mode & 0o777 != 0o700 or root.stat().st_uid != os.getuid():
        raise UnsafeFixture('Unsafe fixture ownership/permissions')
    marker = json.loads(checked(root / 'owner.json', root).read_text())
    if marker['identity'] != [root.stat().st_dev, root.stat().st_ino]:
        raise UnsafeFixture('Fixture identity changed')
    run = root / 'run'
    ready_file = checked(run / 'live/ready.json', run)
    deadline = time.monotonic() + 20
    while not ready_file.exists() and time.monotonic() < deadline:
        time.sleep(0.05)
    if not ready_file.is_file():
        raise UnsafeFixture('Live workbench did not publish readiness')
    ready = json.loads(ready_file.read_text())
    descriptor, credential = (checked(Path(ready[k]), run) for k in ['descriptor', 'credential'])
    jar = Path(data['jar']).resolve(strict=True)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != data['jar_sha256']:
        raise UnsafeFixture('Reviewed artifact changed')
    java = Path(data['java']).resolve(strict=True)
    if java.name != 'java' or java.parent.name != 'bin' or not java.is_file():
        raise UnsafeFixture('Unsupported fixed Java executable')
    env = data['environment']
    safe_environment(env, run)

    def exchange(method=None, params=None):
        command = [str(java), '-Djava.awt.headless=true', '-Duser.home=' + env['HOME'],
                   '-Djava.io.tmpdir=' + env['TMPDIR'], '-Djava.util.prefs.userRoot=' + str(run / 'config'),
                   '-cp', str(jar), 'shed.Texteditor', '--workspace-api', '--control', '--connect', str(descriptor), '--credential', str(credential)]
        if method is not None:
            request = json.dumps({'jsonrpc': '2.0', 'id': 'live-agent', 'method': method, 'params': params}, ensure_ascii=False)
            if len(request.encode()) > 32768:
                raise UnsafeFixture('Request exceeds bounded control limit')
            command += ['--request', request]
        result = subprocess.run(command, cwd=checked(run / 'work', run, directory=True), env=env, stdin=subprocess.DEVNULL,
                                capture_output=True, text=True, timeout=10)
        if len(result.stdout) > 65536 or result.returncode != 0:
            raise RuntimeError('Bounded CLI operation failed: ' + result.stdout[:2000])
        response = json.loads(result.stdout)
        if 'error' in response:
            raise RuntimeError('Control rejected request: ' + json.dumps(response['error']))
        return response['result']

    context = exchange()
    fields = ['schemaVersion', 'instanceId', 'windowId', 'projectId', 'paneId', 'documentId', 'documentRevision',
              'contextRevision', 'caretOffset', 'selectionStart', 'selectionEnd']
    params = {key: context[key] for key in fields}
    if args.operation == 'context':
        response = context
    elif args.operation == 'open':
        params['path'] = args.path
        response = exchange('editor/file/open', params)
    elif args.operation == 'read':
        params.update(offset=0, limit=2048)
        response = exchange('editor/source/read', params)
    elif args.operation == 'activity':
        params.update(start=args.start, end=args.end)
        response = exchange('editor/agent/activity', params)
    else:
        if args.text_file is None:
            parser.error('--text-file is required for a proposed chunk')
        text = checked(args.text_file, run).read_text()
        if len(text) > 8192:
            raise UnsafeFixture('Replacement exceeds bounded chunk limit')
        read = exchange('editor/source/read', dict(params, offset=0, limit=2048))
        if read['truncated'] or not 0 <= args.start <= args.end <= len(read['content']):
            raise UnsafeFixture('Demo requires one bounded ASCII-coordinate document')
        # this acceptance fixture uses ASCII source, so Python and UTF-16 offsets coincide
        if any(ord(c) > 127 for c in read['content']):
            raise UnsafeFixture('Non-ASCII demo offsets require explicit UTF-16 conversion')
        expected = read['content'][:args.start] + text + read['content'][args.end:]
        (run / 'live/expected.txt').write_text(expected)
        params.update(start=args.start, end=args.end, text=text, requestId='live-' + root.name)
        response = exchange('editor/edits/propose', params)
    print(json.dumps(response, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
