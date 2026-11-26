import sys,json,subprocess
from pathlib import Path
session=json.loads(Path(__file__).with_name('session.json').read_text())
args=sys.argv[1:]
if not args or args[0] not in ('read','follow','state','watch-state','intent','pause'):raise SystemExit('Unsupported demo control command')
if args[0]=='read':
 if len(args)<2 or args[1].startswith('-'):raise SystemExit('Explicit source path required')
 p=(Path(session['project'])/args[1]).resolve(strict=True)
 if not p.is_relative_to(Path(session['project'])) or p.is_symlink():raise SystemExit('Path outside assigned source tree')
command=[session['java'],*session['props'],'-Djava.awt.headless=true','-cp',session['jar'],'shed.Texteditor','ctl','--connect',session['descriptor'],*args]
r=subprocess.run(command,env=session['environment'],cwd=Path(__file__).parent,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=15)
if len(r.stdout)>65536:raise SystemExit('Control reply exceeds diagnostic limit')
print(r.stdout,end='')
if r.stderr:print(r.stderr[:2048],file=sys.stderr)
raise SystemExit(r.returncode)
