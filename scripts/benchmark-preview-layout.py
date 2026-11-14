#!/usr/bin/env python3
"""Opt-in Swing HTML feasibility. Imports Shed's existing fixture/environment/statistics tools."""
import argparse, importlib.util, json, os, shutil, subprocess, time, sys
sys.dont_write_bytecode = True
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('markdown_benchmark', ROOT / 'scripts/benchmark-markdown.py')
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, default=ROOT/'target/shed-2.0.0.jar')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--shape', choices=['plain','prose','blocks','compact-blocks','mixed','table','fence','long-line'], default='plain')
    p.add_argument('--size', type=int, default=1048576)
    p.add_argument('--samples', type=int, default=100)
    p.add_argument('--timeout', type=int, default=90)
    p.add_argument('--profile', action='store_true')
    args=p.parse_args()
    if not 1024 <= args.size <= 1048576 or not 5 <= args.samples <= 100 or not 15 <= args.timeout <= 120:
        p.error('Bounds: 1 KiB..1 MiB, samples 5..100, timeout 15..120 s')
    out=args.output.resolve()
    if out.exists() or ROOT==out or ROOT in out.parents:p.error('Use a new directory outside the checkout')
    out.mkdir(parents=True); fixture=out/'input.md'
    if args.shape in ('blocks','compact-blocks'):
        parts=[]
        for name,size,shape in [('table',max(1024,args.size//16),'table'),('code',max(1024,args.size//4),'fence'),('prose',args.size-max(1024,args.size//16)-max(1024,args.size//4),'plain')]:
            if size<1024:p.error('Composite blocks requires at least 16 KiB')
            part=out/(name+'.md');parts.append(base.fixture(part,size,shape))
        if args.shape=='compact-blocks':
            full=Path(parts[1]['path']);lines=full.read_bytes().splitlines(keepends=True)
            compact=out/'compact-code.md';compact.write_bytes(b''.join(lines[:257])+b'```\n')
            parts[1]={'path':str(compact),'bytes':compact.stat().st_size,'sha256':base.digest(compact),'origin':parts[1],'selected_code_lines':256}
            rest=out/'remaining-prose.md';parts[2]=base.fixture(rest,args.size-parts[0]['bytes']-parts[1]['bytes'],'plain')
        fixture.write_bytes(b''.join(Path(x['path']).read_bytes() for x in parts))
        manifest={'path':str(fixture),'bytes':fixture.stat().st_size,'sha256':base.digest(fixture),'shape':'blocks','generator':'1C.1-composite-v1','parts':parts}
    else:
        manifest=base.fixture(fixture,args.size,'plain' if args.shape=='prose' else args.shape)
    if args.shape in ('prose','blocks','compact-blocks'):
        data=fixture.read_bytes();lines=data.splitlines(keepends=True)
        for i,line in enumerate(lines):
            raw=line.rstrip(b'\n')
            if raw and raw==b'p'*len(raw):lines[i]=(b'p '*(len(raw)//2)+b'p'*(len(raw)%2))+line[len(raw):]
        fixture.write_bytes(b''.join(lines));manifest.update(sha256=base.digest(fixture),padding_policy='legacy p-only lines replaced with same-length p-space prose; no size change')
    (out/'swatch.png').write_bytes(base.png_fixture());home=out/'home';(home/'.shed').mkdir(parents=True)
    (home/'.shed/config.toml').write_text('schema_version = 1\n"keymap.profile" = "plain"\n"session.restore.on.start" = false\n"markdown.preview.remote.images" = false\n')
    jdk=Path(subprocess.check_output([str(ROOT/'scripts/resolve-toolchain.sh'),'home'],text=True).strip())
    jar=args.jar.resolve(strict=True);classes=out/'classes';classes.mkdir()
    source=out/'SwingHtmlFeasibilityProbe.java'
    shutil.copyfile(ROOT/'src/benchmark/java/shed/SwingHtmlFeasibilityProbe.java',source)
    subprocess.run([str(jdk/'bin/javac'),'-cp',str(jar),'-d',str(classes),str(source)],check=True)
    cmd=[str(jdk/'bin/java'),'-Xms64m','-Xmx512m','-Djava.awt.headless=false',f'-Duser.home={home}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags']
    if args.profile:cmd += [f'-XX:StartFlightRecording=filename={out}/profile.jfr,settings=profile,maxsize=64m,dumponexit=true']
    cmd += ['-cp',str(classes)+os.pathsep+str(jar),'shed.SwingHtmlFeasibilityProbe',str(fixture),str(args.samples)]
    record={'command':cmd,'jar_sha256':base.digest(jar),'probe_sha256':base.digest(source),'runner_sha256':base.digest(Path(__file__)),'fixture':manifest,'environment_before':base.environment(),'java':base.command_output([str(jdk/'bin/java'),'-version']),'state':'ERROR','samples':[],'metadata':[],'rss_samples_mib':[]}
    start=time.monotonic()
    with (out/'stdout.log').open('w') as log:
        child=subprocess.Popen(cmd,stdout=log,stderr=subprocess.STDOUT);record['pid']=child.pid
        while child.poll() is None:
            if time.monotonic()-start>args.timeout:
                with (out/'timeout-threads.txt').open('w') as f:
                    try:subprocess.run([str(jdk/'bin/jcmd'),str(child.pid),'Thread.print'],stdout=f,stderr=subprocess.STDOUT,timeout=3)
                    except subprocess.TimeoutExpired:pass
                record['state']='TIMEOUT';child.terminate()
                try:child.wait(timeout=3)
                except subprocess.TimeoutExpired:child.kill();child.wait()
                break
            rss=base.command_output(['ps','-o','rss=','-p',str(child.pid)])
            try:record['rss_samples_mib'].append(float(rss['text'])/1024)
            except (KeyError,ValueError):pass
            time.sleep(.25)
    text=(out/'stdout.log').read_text();record['exit']=child.returncode;record['wall_seconds']=time.monotonic()-start
    for line in text.splitlines():
        if line.startswith('HT_SAMPLE '):record['samples'].append(json.loads(line[10:]))
        if line.startswith('HT_META '):record['metadata'].append(json.loads(line[8:]))
    if record['state']!='TIMEOUT' and child.returncode==0 and 'HT_DONE' in text:record['state']='SKIPPED_RESOURCE_GUARD' if any(m.get('operation')=='resource_guard_exit' for m in record['metadata']) else 'OK'
    record['summary']=base.summarize(record['samples']);record['sampled_peak_rss_mib']=max(record['rss_samples_mib'],default=None)
    record['fixture_sha256_after']=base.digest(fixture);record['environment_after']=base.environment()
    (out/'report.json').write_text(json.dumps(record,indent=2)+'\n')
    print(record['state'],record['wall_seconds'],flush=True)
    return 0 if record['state']=='OK' else 1
if __name__=='__main__':raise SystemExit(main())
