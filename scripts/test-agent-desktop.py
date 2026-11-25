#!/usr/bin/env python3
"""Opt-in live workbench test with fixed Java processes and a disposable local-agent workspace."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
from native_fixture import Fixture, UnsafeFixture, checked

ROOT = Path(__file__).resolve().parents[1]


def git_environment(fixture):
    root=fixture.run_root
    return dict(GIT_CONFIG_GLOBAL=str(root/'empty-git-config'),GIT_CONFIG_SYSTEM=str(root/'empty-git-config'),GIT_CONFIG_NOSYSTEM='1',GIT_TERMINAL_PROMPT='0',GIT_PAGER='',GIT_CONFIG_COUNT='3',GIT_CONFIG_KEY_0='core.hooksPath',GIT_CONFIG_VALUE_0=str(root/'empty-hooks'),GIT_CONFIG_KEY_1='core.fsmonitor',GIT_CONFIG_VALUE_1='false',GIT_CONFIG_KEY_2='commit.gpgsign',GIT_CONFIG_VALUE_2='false',SHED_VISUAL_GIT_FIXTURE='1')


def run_codex(binary, fixture, project, work, environment):
    """Real model writes inside the fixture; no shell, hooks, plugins or user configuration."""
    fixture.validate()
    expected=dict(fixture.env,PATH=str(fixture.run_root/'no-executables'),SHED_PORTABLE_FIXTURE_ROOT=str(fixture.root),SHED_LIVE_VISUAL_ONLY='1',SHED_WATCH_QUALIFICATION='1',SHED_LIVE_CODEX='1',**git_environment(fixture))
    if environment.get('SHED_CONFIG_MOTION')=='1':expected['SHED_CONFIG_MOTION']='1'
    if environment!=expected:raise UnsafeFixture('Unexpected live Codex environment')
    checked(project,fixture.root,directory=True);checked(work,fixture.root,directory=True)
    checked(Path(environment['HOME']),fixture.root,directory=True)
    binary=binary.resolve(strict=True)
    if binary.name!='codex' or not binary.is_file() or binary.is_symlink() or not os.access(binary,os.X_OK): raise UnsafeFixture('Existing native Codex binary required')
    home=Path(environment['HOME']); config=home/'.codex';checked(config,fixture.root);config.mkdir(mode=0o700,exist_ok=True)
    # Only the explicitly needed authentication is copied; never logged or preserved as evidence.
    auth=Path.home()/'.codex/auth.json'
    if not auth.is_file() or auth.is_symlink(): raise UnsafeFixture('Existing Codex authentication unavailable')
    auth_bytes=auth.read_bytes();auth_stamp=auth.stat().st_mtime_ns
    target=config/'auth.json'
    with target.open('xb') as stream:
        target.chmod(0o600);stream.write(auth_bytes)
    current=(project/'Demo.java').read_text()
    prompt=('This is a disposable Shed visualization test. Use ONLY apply_patch. Do not use shells, network, agents or inspect any files. '
            'The exact current Demo.java source follows below. Append a Java comment // LIVE_CODEX_EDIT at EOF. '
            'Create AgentCreated.txt containing "Created by live Codex\n". Delete AgentDelete.txt whose exact contents are "agent delete fixture\n". '
            'Move AgentOld.txt to AgentRenamed.txt and change its line from "original agent rename fixture" to "renamed by live Codex". '
            'Only modify these fixture files. No commits. Finish after the patch.\nDemo.java:\n'+current)
    if environment.get('SHED_CONFIG_MOTION')=='1':
        current=(project/'ModelBlocks.java').read_text()
        prompt=('Use ONLY apply_patch on ModelBlocks.java in this disposable test. Replace BEFORE_ALPHA with AFTER_ALPHA, BEFORE_BETA with AFTER_BETA, and BEFORE_GAMMA with AFTER_GAMMA. Preserve every other character. Do not use shells, network, agents, other files or commits. Here is the exact source:\n'+current)
    command=[str(binary),'-a','never','exec','--ignore-user-config','--ignore-rules','--ephemeral','--sandbox','workspace-write',
             '-C',str(project),'--json','-c','model_reasoning_effort="low"','-c','web_search="disabled"']
    for feature in ['shell_tool','unified_exec','shell_snapshot','multi_agent','multi_agent_v2','apps','plugins','hooks','browser_use','computer_use','image_generation','skill_search']:
        command+=['--disable',feature]
    command+=['-']
    started=time.monotonic();child=None
    try:
        with (work/'codex-log.jsonl').open('w') as log, (work/'codex-errors.log').open('w') as errors:
            child=subprocess.Popen(command,cwd=project,env=environment,stdin=subprocess.PIPE,stdout=log,stderr=errors,text=True)
            try: child.communicate(prompt,timeout=90)
            except subprocess.TimeoutExpired:
                child.terminate()
                try:child.wait(timeout=5)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
                return {'exit':124,'error':'Real Codex deadline exceeded','elapsed_s':time.monotonic()-started}
        events=[];item_types=set()
        with (work/'codex-log.jsonl').open('rb') as stream:raw=stream.read(2*1024*1024+1)
        if len(raw)>2*1024*1024:raise UnsafeFixture('Codex log exceeds evidence bound')
        for line in raw.decode('utf-8',errors='replace').splitlines():
            try:
                event=json.loads(line);item=event.get('item',{});item_types.add(item.get('type',''))
                if item.get('type')=='file_change':events.append({'type':'file_change','changes':item.get('changes',[])})
                elif event.get('type') in ('turn.completed','turn.failed','error'):events.append({'type':event['type'],'usage':event.get('usage'),'message':str(event.get('message',event.get('error','')))[:500]})
                elif item.get('type')=='command_execution':raise UnsafeFixture('Unexpected shell execution in restricted Codex run')
            except json.JSONDecodeError:continue
        changed=(project/'AgentCreated.txt').exists() and not (project/'AgentDelete.txt').exists() and not (project/'AgentOld.txt').exists() and (project/'AgentRenamed.txt').exists() and 'LIVE_CODEX_EDIT' in (project/'Demo.java').read_text()
        if environment.get('SHED_CONFIG_MOTION')=='1':changed=all(word in (project/'ModelBlocks.java').read_text() for word in ['AFTER_ALPHA','AFTER_BETA','AFTER_GAMMA'])
        return {'exit':child.returncode if changed else 1,'cli_exit':child.returncode,'expected_changes_present':changed,'item_types':sorted(item_types),'elapsed_s':time.monotonic()-started,'events':events,'environment':'disposable HOME/XDG/history; only copied auth; ignored user config; shell tools/hooks/plugins disabled','stderr':(work/'codex-errors.log').read_text()[:2000],'auth_source_content_unchanged':auth.read_bytes()==auth_bytes,'auth_source_mtime_unchanged':auth.stat().st_mtime_ns==auth_stamp}
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            try:child.wait(timeout=5)
            except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
        target.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--motion', action='store_true', help='Safe config, file tree/Git pulses and multi-block source following')
    parser.add_argument('--qualification', action='store_true', help='Bounded burst, conflict, theme and split desktop qualification')
    parser.add_argument('--live-codex', type=Path, help='Existing native Codex binary; isolated home/auth copy, shell tools disabled')
    parser.add_argument('--visual-only', action='store_true', help='Qualify live external-edit visualization separately from the existing HTML-preview endpoint')
    parser.add_argument('--git-fixture', action='store_true', help='Direct fixed /usr/bin/git in a disposable repository, no shell or personal config')
    parser.add_argument('--launch-only', action='store_true', help='Fixed direct Java launch-and-return acceptance; no shell')
    parser.add_argument('--animation-demo', action='store_true', help='Paced source changes, reading, search/navigation, refactoring intentions, checks and conflicts')
    parser.add_argument('--replay-payload', type=Path, help='Replay a bounded previously generated proposal through the same external CLI')
    args = parser.parse_args()
    if args.animation_demo and (not args.git_fixture or not args.visual_only or args.launch_only or args.qualification or args.replay_payload or args.live_codex or args.motion):parser.error('Animation demo requires only disposable Git and visual-only execution')
    if args.launch_only and (args.replay_payload or args.qualification or args.live_codex or args.git_fixture): parser.error("Launch-only cannot run editing fixtures")
    if args.qualification and (args.replay_payload or not args.visual_only or not args.git_fixture): parser.error('Watch qualification requires visual-only and disposable Git, independently of proposal replay')
    if args.motion and not args.qualification: parser.error('Motion qualification requires the bounded desktop fixture')
    if args.live_codex and not args.qualification: parser.error('Live Codex requires the qualified disposable workflow')
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
    work = None
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
        (project / 'AgentOld.txt').write_text('original agent rename fixture\n')
        (project / 'AgentDelete.txt').write_text('agent delete fixture\n')
        (project / 'Demo.java').write_text('public class Demo {\n    public static String greet(String name) {\n        return "Hello, " + name;\n    }\n}\n')
        if args.animation_demo:
            markers={5:'CHANGE_TOP',60:'REMOVE_START',89:'REMOVE_END',100:'REWRITE_START',120:'CHANGE_MIDDLE',139:'REWRITE_END',230:'CHANGE_BOTTOM'}
            source='public class Demo {\n'+''.join('// '+markers[i]+'\n' if i in markers else f'// demonstration checkpoint {i:03d}\n' for i in range(240))+'    public static String greet(String name) {\n        return "Hello, " + name;\n    }\n}\n'
            (project/'Demo.java').write_text(source)
            for name,text in {'Notes.txt':'Demo notes\n- Improve greeting\n- Add helper methods\n','RenameMe.txt':'Keep this content when renaming.\n','DeleteMe.txt':'This disposable file will be deleted.\n'}.items():(project/name).write_text(text)
        (f.run_root / 'no-executables').mkdir(mode=0o700)
        env = dict(f.env, PATH=str(f.run_root / 'no-executables'), SHED_PORTABLE_FIXTURE_ROOT=str(f.root))
        if args.git_fixture:
            git = Path('/usr/bin/git')
            if not git.is_file(): raise UnsafeFixture('Fixed system Git unavailable')
            (f.run_root / 'no-executables/git').symlink_to(git)
            (f.run_root / 'empty-hooks').mkdir(mode=0o700)
            (f.run_root / 'empty-git-config').write_text('')
            env.update(git_environment(f))
            tracked=['Welcome.txt','Demo.java','AgentOld.txt','AgentDelete.txt']+(['Notes.txt','RenameMe.txt','DeleteMe.txt'] if args.animation_demo else [])
            for arguments in [['init','--quiet'], ['add','--',*tracked], ['-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','--quiet','-m','Fixture baseline']]:
                completed=subprocess.run([str(git),*arguments],cwd=project,env=env,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=10)
                if completed.returncode: raise UnsafeFixture('Disposable Git setup failed: '+completed.stderr[:1000])
            report['git_fixture']='fixed system git; empty owned config/hooks; terminal prompts and signing disabled'
        if args.visual_only: env['SHED_LIVE_VISUAL_ONLY']='1'
        if args.qualification: env['SHED_WATCH_QUALIFICATION']='1'
        if args.live_codex: env['SHED_LIVE_CODEX']='1'
        if args.motion: env['SHED_CONFIG_MOTION']='1'
        props = ['-Duser.home=' + env['HOME'], '-Djava.io.tmpdir=' + env['TMPDIR'],
                 '-Djava.util.prefs.userRoot=' + str(f.run_root / 'config')]
        probe='ShedVisualizeLaunchProbe' if args.launch_only else 'AgentAnimationDesktopProbe' if args.animation_demo else 'AgentWorkflowDesktopProbe'
        probe_source=ROOT/'src/benchmark/java/shed'/f'{probe}.java'
        report['probe_sha256']=hashlib.sha256(probe_source.read_bytes()).hexdigest()
        compile_command = [str(jdk / 'bin/javac'), *['-J' + p for p in props], '-proc:none', '-encoding', 'UTF-8',
                           '-cp', str(jar), '-d', str(classes), str(probe_source)]
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
                   f'shed.{probe}', str(work), str(project)]
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
            if args.replay_payload:
                deadline = time.monotonic() + 25
                while not (work / 'watch-ready').exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.025)
                if (work / 'watch-ready').exists():
                    (project / 'Demo.java').write_text((project / 'Demo.java').read_text() + '// External CLI write observed by Shed\n')
                    (project / 'Fresh.java').write_text('class Fresh {}\n')
                    (project / 'DeleteMe.txt').unlink()
                    (project / 'RenameMe.txt').rename(project / 'Renamed.txt')
                    deadline = time.monotonic() + 10
                    while not (work / 'watch-first-batch').exists() and process.poll() is None and time.monotonic() < deadline:
                        time.sleep(0.025)
                    if (work / 'watch-first-batch').exists():
                        (project / 'Demo.java').write_text((project / 'Demo.java').read_text() + '// Latest observed source block\n')
                    deadline = time.monotonic() + 12
                    while not (work / 'watch-paused').exists() and process.poll() is None and time.monotonic() < deadline:
                        time.sleep(0.025)
                    if (work / 'watch-paused').exists():
                        (project / 'Fresh.java').write_text('class Fresh { int second; }\n')
                        (work / 'writer-finished').write_text('done')
            if args.live_codex:
                deadline=time.monotonic()+100
                while not (work/'codex-ready').exists() and process.poll() is None and time.monotonic()<deadline: time.sleep(.05)
                if not (work/'codex-ready').exists(): raise UnsafeFixture('Workbench did not reach real Codex phase')
                report['live_codex']=run_codex(args.live_codex, f, project, work, env)
                (work/'codex-finished').write_text('ok' if report['live_codex']['exit']==0 else 'failed')
            process.wait(timeout=310 if args.qualification else 160)
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
        if work is not None:
            if (work/'stdout.log').is_file():report['log']=(work/'stdout.log').read_text()[:524288]
            if (work/'result.json').is_file():report['result']=json.loads((work/'result.json').read_text())
            for picture in sorted(work.glob('*.png')):
                if picture.stat().st_size<=4*1024*1024:shutil.copyfile(picture,output/picture.name)
        f.close()
        report.update(fixture_removed=not f.root.exists(), elapsed_s=time.monotonic() - started)
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('LIVE_DEMO_RESULT ' + str(report.get('exit', 2)) + ' ' + str(output / 'report.json'), flush=True)
    return 0 if report.get('exit') == 0 and report.get('result', {}).get('state') == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
