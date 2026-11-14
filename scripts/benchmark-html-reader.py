#!/usr/bin/env python3
"""Opt-in, fixed-512-MiB HTML-reader diagnostics; isolated files/JVM and bounded input/time."""
import argparse,hashlib,importlib.util,json,os,shutil,subprocess,sys,time,html
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('md_benchmark',ROOT/'scripts/benchmark-markdown.py');base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def fixture(path,name,reproducer):
    params={'case':name,'generator':'html-reader-v2'}
    if name=='original':
        if reproducer:
            if reproducer.stat().st_size>1048576:raise ValueError('Input exceeds 1 MiB bound')
            shutil.copyfile(reproducer,path);params['original_sha256']=base.digest(reproducer)
        else:
            parts=[]
            for shape,size in [('table',65536),('fence',262144),('plain',720896)]:
                part=path.parent/(shape+'-part.md');parts.append(base.fixture(part,size,shape))
            path.write_bytes(b''.join(Path(part['path']).read_bytes() for part in parts));params['parts']=parts
    elif name in ('prose','table'):
        params.update(base.fixture(path,1048576 if name=='prose' else 65536,'plain' if name=='prose' else 'table'))
    else:
        sizes={'small':(32,128),'code64':(512,128),'code128':(1024,128),'code256':(2048,128),'single':(1,262144),'special':(512,128),'raw-pre':(1024,128)}
        count,width=sizes[name];seed='\t  code <tag> & "quoted" value 0123456789; ' if name=='special' else '    value = 0123456789abcdef; '
        if name=='special':seed+='α😀 e\u0301 日本語 '
        line=(seed*((width+len(seed)-1)//len(seed)))[:width-1]+'\n';body=line*count
        path.write_text('<html><body><pre><code>'+html.escape(body)+'</code></pre></body></html>' if name=='raw-pre' else '# Reader probe\n\n```text\n'+body+'```\n',encoding='utf-8')
        params.update(code_characters=len(body),lines=count,line_characters=width)
    if name=='prose' or (name=='original' and not reproducer):
        lines=path.read_bytes().splitlines(keepends=True)
        for i,line in enumerate(lines):
            raw=line.rstrip(b'\n')
            if raw and raw==b'p'*len(raw):lines[i]=(b'p '*(len(raw)//2)+b'p'*(len(raw)%2))+line[len(raw):]
        path.write_bytes(b''.join(lines));params['padding_policy']='1C.1 same-length p-space prose'
    if path.stat().st_size>1048576:raise ValueError('Input exceeds 1 MiB bound')
    return {**params,'path':str(path),'bytes':path.stat().st_size,'sha256':base.digest(path)}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,default=ROOT/'target/shed-2.0.0.jar');p.add_argument('--output',type=Path,required=True)
    p.add_argument('--case',choices=['original','prose','small','code64','code128','code256','single','special','raw-pre','table'],required=True);p.add_argument('--reproducer',type=Path)
    p.add_argument('--route',choices=['detached','raw','setText','production'],default='detached');p.add_argument('--mode',choices=['stock','counted','slice','repaired'],default='stock');p.add_argument('--samples',type=int,default=3);p.add_argument('--timeout',type=int,default=30);p.add_argument('--profile',action='store_true')
    args=p.parse_args()
    if not 1<=args.samples<=5 or not 5<=args.timeout<=60:p.error('samples 1..5; timeout 5..60 s')
    if args.route=='production' and (args.mode not in ('stock','repaired') or args.samples!=1):p.error('production uses the JAR implementation and one constructor; choose stock/repaired and samples=1')
    out=args.output.resolve()
    if out.exists() or out==ROOT or ROOT in out.parents:p.error('Use a new directory outside the repository')
    out.mkdir(parents=True);inp=out/'input.md';manifest=fixture(inp,args.case,args.reproducer)
    home=out/'home';(home/'.shed').mkdir(parents=True);(home/'.shed/config.toml').write_text('schema_version = 1\n"session.restore.on.start" = false\n"markdown.preview.remote.images" = false\n')
    temp=out/'tmp';temp.mkdir();java=Path(subprocess.check_output([str(ROOT/'scripts/resolve-toolchain.sh'),'home'],text=True).strip());jar=args.jar.resolve(strict=True);classes=out/'classes';classes.mkdir();source=out/'HtmlReaderMemoryProbe.java';shutil.copyfile(ROOT/'src/benchmark/java/shed/HtmlReaderMemoryProbe.java',source)
    subprocess.run([str(java/'bin/javac'),'-cp',str(jar),'-d',str(classes),str(source)],check=True)
    cmd=[str(java/'bin/java'),'-Xms64m','-Xmx512m','-Djava.awt.headless=false',f'-Duser.home={home}',f'-Djava.io.tmpdir={temp}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags']
    if args.profile:cmd += [f'-XX:StartFlightRecording=filename={out}/profile.jfr,settings=profile,maxsize=64m,dumponexit=true']
    cmd += ['-cp',str(classes)+os.pathsep+str(jar),'shed.HtmlReaderMemoryProbe',str(inp),args.route,args.mode,str(args.samples)]
    d={'fixture':manifest,'command':cmd,'jar_sha256':base.digest(jar),'probe_sha256':base.digest(source),'runner_sha256':base.digest(Path(__file__)),'environment_before':base.environment(),'java':base.command_output([str(java/'bin/java'),'-version']),'state':'ERROR','stages':[],'documents':[],'memory':[],'metadata':[],'source_callbacks':[],'rss_samples_mib':[]}
    start=time.monotonic()
    with (out/'stdout.log').open('w') as log:
        environment={k:v for k,v in os.environ.items() if k not in ('CLASSPATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
        child=subprocess.Popen(cmd,stdout=log,stderr=subprocess.STDOUT,env=environment,cwd=out)
        while child.poll() is None:
            if time.monotonic()-start>args.timeout:
                d['state']='TIMEOUT';child.terminate()
                try:child.wait(timeout=3)
                except subprocess.TimeoutExpired:child.kill();child.wait()
                break
            rss=base.command_output(['ps','-o','rss=','-p',str(child.pid)])
            try:d['rss_samples_mib'].append(float(rss['text'])/1024)
            except(KeyError,ValueError):pass
            time.sleep(.1)
    data=(out/'stdout.log').read_text();d.update(exit=child.returncode,wall_seconds=time.monotonic()-start,completion='HR_DONE' in data,oom='OutOfMemoryError' in data,source_sha256_after=base.digest(inp))
    for line in data.splitlines():
        for prefix,key in [('HR_STAGE ','stages'),('HR_DOCUMENT ','documents'),('HR_META ','metadata'),('HR_MEMORY ','memory'),('HR_SOURCE ','source_callbacks')]:
            if line.startswith(prefix):d[key].append(json.loads(line[len(prefix):]))
    if d['state']!='TIMEOUT' and child.returncode==0 and d['completion']:d['state']='OK'
    d['sampled_peak_rss_mib']=max(d['rss_samples_mib'],default=None);d['environment_after']=base.environment();(out/'report.json').write_text(json.dumps(d,indent=2)+'\n');print(d['state'],'OOM' if d['oom'] else '',d['wall_seconds'],flush=True)
    return 0 if d['state']=='OK' else 1
if __name__=='__main__':raise SystemExit(main())
