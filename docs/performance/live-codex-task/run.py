import sys,subprocess,json,time,hashlib,shutil,os
from pathlib import Path
sys.dont_write_bytecode=True
repo=Path('/Users/gongahkia/Desktop/coding/projects/shed')
sys.path.insert(0,str(repo/'scripts'))
from native_fixture import Fixture
output=Path(__file__).resolve().parent
checkout=Path('/Users/gongahkia/.t3/worktrees/shed/t3-4c208758-8e993b78e9a9ecbd').resolve(strict=True)
assert subprocess.check_output(['/usr/bin/git','-C',str(checkout),'rev-parse','HEAD'],text=True).strip()=='7e4b959d30446617be3785afa2b8be79fc7461b6'
assert not subprocess.check_output(['/usr/bin/git','-C',str(checkout),'status','--porcelain'],text=True).strip()
project=checkout/'src'
jar=repo/'target/shed-2.0.0.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()==json.loads((repo/'docs/performance/open-vsx-themes/identities.json').read_text())['source_sha256']['target/shed-2.0.0.jar']
jdk=Path('/private/tmp/shed-a222c1-tgamqlld/tools/jdk-21.0.13.1+2/Contents/Home')
f=Fixture();p=None
report={'boundary':'real T3-prepared checkout src subtree authorized by user; disposable app home/preferences; fixed direct Java, no shell/model process launched by this harness; not an OS sandbox','checkout':str(checkout),'project':str(project),'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
try:
 f.validate();work=f.run_root/'work';classes=work/'classes';classes.mkdir()
 source=work/'LiveCodeTaskProbe.java';source.write_text((output/'LiveCodeTaskProbe.java').read_text())
 noexec=f.run_root/'no-executables';noexec.mkdir();(noexec/'git').symlink_to('/usr/bin/git')
 env=dict(f.env,PATH=str(noexec),SHED_PORTABLE_FIXTURE_ROOT=str(f.root),SHED_LIVE_VISUAL_ONLY='1')
 props=['-Duser.home='+f.env['HOME'],'-Djava.io.tmpdir='+f.env['TMPDIR'],'-Djava.util.prefs.userRoot='+str(f.run_root/'config')]
 compile=[str(jdk/'bin/javac'),*['-J'+v for v in props],'-proc:none','-encoding','UTF-8','-cp',str(jar),'-d',str(classes),str(source)]
 r=subprocess.run(compile,env=env,cwd=work,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=30)
 report.update(compile_exit=r.returncode,compile_output=r.stdout+r.stderr)
 if r.returncode:raise RuntimeError('Probe compilation failed')
 gui_env=dict(env)
 for k in ('DISPLAY','XAUTHORITY'):
  if os.environ.get(k):gui_env[k]=os.environ[k]
 command=[str(jdk/'bin/java'),*props,'-Djava.awt.headless=false','-cp',str(classes)+os.pathsep+str(jar),'shed.LiveCodeTaskProbe',str(project),str(work)]
 report['command']=command
 with (work/'stdout.log').open('w') as log:
  p=subprocess.Popen(command,env=gui_env,cwd=work,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT)
  deadline=time.monotonic()+35
  while not (work/'ready.json').exists() and p.poll() is None and time.monotonic()<deadline:time.sleep(.1)
  if not (work/'ready.json').exists():raise RuntimeError('Shed did not establish the bounded watch/control attachment')
  ready=json.loads((work/'ready.json').read_text());ready['probe_pid']=p.pid;ready['work']=str(work)
  session=work/'session.json';session.write_text(json.dumps(dict(ready,java=str(jdk/'bin/java'),jar=str(jar),environment=env,props=props)))
  helper=work/'shed_ctl.py';helper.write_text((output/'shed_ctl.py').read_text())
  ready.update(helper=str(helper),session=str(session));(output/'ready.json').write_text(json.dumps(ready,indent=2)+'\n')
  print('SHED_LIVE_READY '+str(output/'ready.json'),flush=True)
  deadline=time.monotonic()+420
  while p.poll() is None and not (output/'stop').exists() and time.monotonic()<deadline:time.sleep(.2)
  (work/'stop').touch()
  try:p.wait(timeout=8)
  except subprocess.TimeoutExpired:p.terminate();p.wait(timeout=5)
  report['exit']=p.returncode
except Exception as e:report.update(exit=2,error=str(e))
finally:
 if p is not None and p.poll() is None:
  p.kill();p.wait(timeout=5)
 work=f.run_root/'work'
 if (work/'stdout.log').exists():report['log']=(work/'stdout.log').read_text()[:262144]
 for name in ('events.json','result.json'):
  if (work/name).exists():report[name.removesuffix('.json')]=json.loads((work/name).read_text())
 for picture in work.glob('*.png'):
  if picture.stat().st_size<4*1024*1024:shutil.copyfile(picture,output/picture.name)
 f.close();report['fixture_removed']=not f.root.exists();(output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print('SHED_LIVE_FINISHED '+str(report.get('exit')),flush=True)
