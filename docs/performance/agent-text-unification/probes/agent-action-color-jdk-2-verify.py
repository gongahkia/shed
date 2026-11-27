import os,sys,json,subprocess,hashlib,shutil,zipfile,time
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path.cwd();sys.path.insert(0,str(ROOT/'scripts'))
from native_fixture import Fixture,checked,digest
base=ROOT/'target/agent-action-color-jdk-2';jdk=Path('/Users/gongahkia/.local/share/shed-tools/jdk-21');jar=ROOT/'target/shed-2.0.0.jar';report={'boundary':'direct JDK rebuild/reviewed JUnit; no Maven installation or native shell','before_jar_sha256':digest(jar)}
manifest=ROOT/'src/test/resources/shed/portable-fixture-tests.properties';helpers=ROOT/'src/test/resources/shed/portable-fixture-helpers.properties'
for source_manifest in [manifest,helpers]:
 for line in source_manifest.read_text().splitlines():
  name,expected=line.split('=',1)
  if digest(ROOT/'src/test/java/shed'/(name+'.java'))!=expected:raise RuntimeError('Reviewed source changed: '+name)
selected=[line.split('=',1)[0] for line in manifest.read_text().splitlines()]
f=Fixture();started=time.monotonic()
try:
 f.validate();env=dict(f.env,PATH=str(f.run_root/'no-executables'),SHED_PORTABLE_FIXTURE_ROOT=str(f.root));props=['-Duser.home='+env['HOME'],'-Djava.io.tmpdir='+env['TMPDIR'],'-Djava.util.prefs.userRoot='+str(f.run_root/'config'),'-Djava.awt.headless=true']
 classes=f.run_root/'dependencies/classes';classes.mkdir(parents=True);changed=['AgentBufferIntent','AgentChangePlayback','AgentEditPresentation','AgentTerminalDecoration','AgentTextPulse','AgentUiPresentation']
 command=[str(jdk/'bin/javac'),*['-J'+x for x in props],'--release','21','-g','-proc:none','-encoding','UTF-8','-cp',str(jar),'-d',str(classes),*[str(ROOT/'src/main/java/shed'/(name+'.java')) for name in changed]]
 r=subprocess.run(command,cwd=ROOT,env=env,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=40);report['compile_command']=command;report['compile_exit']=r.returncode;report['compile_log']=r.stdout+r.stderr
 if r.returncode:raise RuntimeError('Production compile failed')
 own={p.relative_to(ROOT/'target/classes').as_posix():p.read_bytes() for p in (ROOT/'target/classes').rglob('*.class')}
 if len(own)>2500 or sum(map(len,own.values()))>32*1024*1024:raise RuntimeError('Unexpected class growth')
 matches=[]
 for p in classes.rglob('*.class'):
  name=p.relative_to(classes).as_posix();matches.append(own.get(name)==p.read_bytes());own[name]=p.read_bytes()
 report['freshly_compiled_classes']=len(matches);report['fresh_classes_match_prior_maven_output']=all(matches)
 before=base/'before.jar'
 if before.exists():raise RuntimeError('Refuse to overwrite before artifact')
 shutil.copyfile(jar,before);candidate=base/'candidate.jar'
 if candidate.exists():raise RuntimeError('Candidate exists')
 checked(jar,ROOT);checked(candidate,ROOT)
 with zipfile.ZipFile(before) as old,zipfile.ZipFile(candidate,'x',compression=zipfile.ZIP_DEFLATED) as out:
  if any(n.endswith(('.SF','.RSA','.DSA')) for n in old.namelist()):raise RuntimeError('Signed artifact requires normal packaging')
  missing=set(own)-set(old.namelist())
  if missing:raise RuntimeError('Unexpected new classes require normal packaging')
  for info in old.infolist():out.writestr(info,own[info.filename] if info.filename in own else old.read(info.filename))
 with zipfile.ZipFile(candidate) as z:
  if any(z.read(name)!=value for name,value in own.items()):raise RuntimeError('Artifact class mismatch')
 os.replace(candidate,jar);report['jar_sha256']=digest(jar);report['packaged_main_classes']=len(own)
 # use only the already installed JUnit jars, copied into the disposable environment
 libs=f.run_root/'dependencies/junit';libs.mkdir();repo=Path('/Users/gongahkia/.m2/repository');coordinates=[('org/junit/jupiter/junit-jupiter-api','5.10.2'),('org/junit/jupiter/junit-jupiter-engine','5.10.2'),('org/junit/platform/junit-platform-launcher','1.10.2'),('org/junit/platform/junit-platform-engine','1.10.2'),('org/junit/platform/junit-platform-commons','1.10.2'),('org/opentest4j/opentest4j','1.3.0'),('org/apiguardian/apiguardian-api','1.1.2')]
 jars=[]
 for coordinate,version in coordinates:
  name=coordinate.split('/')[-1]+'-'+version+'.jar';source=repo/coordinate/version/name
  if source.is_symlink() or not source.is_file() or source.stat().st_size>3000000:raise RuntimeError('Unexpected JUnit dependency')
  target=libs/name;shutil.copyfile(source,target);jars.append(str(target))
 tests=f.run_root/'dependencies/test-classes';
 cache=list((ROOT/'target/test-classes').rglob('*'))
 if any(p.is_symlink() for p in cache) or sum(p.stat().st_size for p in cache if p.is_file())>16*1024*1024:raise RuntimeError('Unsupported test artifact tree')
 shutil.copytree(ROOT/'target/test-classes',tests,symlinks=False)
 shutil.copyfile(manifest,tests/'shed/portable-fixture-tests.properties');shutil.copyfile(helpers,tests/'shed/portable-fixture-helpers.properties')
 cp=os.pathsep.join([str(classes),str(tests),str(jar),*jars]);sources=[ROOT/'src/test/java/shed'/(name+'.java') for name in selected];sources+=[ROOT/'src/test/java/shed'/(line.split('=',1)[0]+'.java') for line in helpers.read_text().splitlines()]
 command=[str(jdk/'bin/javac'),*['-J'+x for x in props],'--release','21','-g','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(tests),*map(str,dict.fromkeys(sources)),str(base/'Verify.java'),str(base/'Bootstrap.java')]
 r=subprocess.run(command,cwd=ROOT,env=env,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=40);report['test_compile_exit']=r.returncode;report['test_compile_log']=r.stdout+r.stderr
 if r.returncode:raise RuntimeError('Test compile failed')
 selected_file=f.run_root/'reviewed-tests';selected_file.write_text('\n'.join(selected)+'\n');command=[str(jdk/'bin/java'),*props,'-Dshed.test.lsp.measure=true','-cp',cp,'Bootstrap',str(selected_file)];report['test_command']=command
 r=subprocess.run(command,cwd=ROOT,env=env,stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=180);report['exit']=r.returncode;report['log']=r.stdout+r.stderr;report['state']='PASS' if r.returncode==0 else 'FAIL'
finally:
 f.close();report['fixture_removed']=not f.root.exists();report['elapsed_s']=time.monotonic()-started;(base/'report.json').write_text(json.dumps(report,indent=2));print({k:v for k,v in report.items() if isinstance(v,(int,float,bool,str)) and k not in ['log','compile_log','test_compile_log']})
