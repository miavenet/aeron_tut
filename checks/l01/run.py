#!/usr/bin/env python3
"""Compile supplied work, run trusted fixtures, preserve bounded evidence. Exit 0/1/2."""
import json, os, sys
from pathlib import Path
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from harness import print_result, Run, InfraError, java_argv, workspace_from_stdin, result, JAR

def wait(run, child):
    # A process can exit between the supervisor predicate and early-exit checks.
    # Await the status without that early-exit branch, then collect via the supervisor.
    run.until(lambda: child.poll() is not None, description=child.name + " status")
    return run.wait(child)

def main():
    lesson, mode = sys.argv[1:3]
    run_path = None
    try:
        workspace = workspace_from_stdin()
        name = 'Ownership' if lesson == 'l01' else 'Transport'
        source = workspace / (name + '.java')
        if not source.is_file():
            raise InfraError('workspace must contain ' + name + '.java')
        with Run('wp1', parent=ROOT/'manifest/evidence/wp1', timeout=35,
                 ports=(lesson=='l02' and mode not in ('policy', 'geometry'))) as run:
            run_path = str(run.path.relative_to(ROOT))
            setup=run.start('setup',[str(ROOT/'tools/setup.sh'),'--check'])
            if wait(run,setup): raise InfraError('setup failed; see '+str(setup.log))
            classes=run.path/'classes';classes.mkdir()
            javac=str(Path(os.environ['JAVA_HOME'])/'bin/javac') if os.environ.get('JAVA_HOME') else 'javac'
            fixture=ROOT/'labs'/lesson/'fixture'/('IpcCheck.java' if lesson=='l01' else 'UdpCheck.java')
            compile=run.start('compile',[javac,'-cp',str(JAR),'-d',str(classes),str(source),str(fixture)])
            code=wait(run,compile)
            if code: return result(1,[dict(id='compile',pass_=False,detail='Fix compiler errors: '+compile.log.read_text()[-4000:])],artifacts=run_path)
            if lesson=='l01':
                child=run.start('ipc',java_argv('IpcCheck',run.path/'driver',mode,classpath=[classes]))
                code=wait(run,child)
                detail=child.log.read_text()[-5000:]
            elif mode in ('policy','geometry'):
                child=run.start(mode,java_argv('UdpCheck',mode,classpath=[classes]))
                code=wait(run,child);detail=child.log.read_text()[-5000:]
            else:
                policy=run.start('policy',java_argv('UdpCheck','policy',classpath=[classes]))
                code=wait(run,policy)
                detail=policy.log.read_text()[-5000:]
                if code==0:
                    port=run.ports.map['node0.ingress']
                    sender=run.start('sender',java_argv('UdpCheck','sender',run.path,port,classpath=[classes]))
                    run.wait_event(sender,'REJECTED')
                    receiver=run.start('receiver',java_argv('UdpCheck','receiver',run.path,port,classpath=[classes]))
                    # Wait for either failure or BOTH exits. Failure stops the peer via context cleanup.
                    run.until(lambda: any(c.poll() not in (None,0) for c in (sender,receiver)) or all(c.poll() is not None for c in (sender,receiver)),
                              description='sender and receiver completion')
                    codes=[c.poll() for c in (sender,receiver)]
                    code=2 if 2 in codes else (1 if any(c not in (None,0) for c in codes) else 0)
                    detail='\n'.join(c.name+': '+c.log.read_text()[-5000:] for c in (sender,receiver))
            code=code if code in (0,1,2) else 2
            return result(code,[{'id':lesson+'-'+mode,'pass':code==0,'detail':detail.strip() if code else 'All required semantic assertions passed.'}],artifacts=run_path)
    except (InfraError,OSError,ValueError,KeyboardInterrupt) as e:
        return result(2,[{'id':'environment','pass':False,'detail':str(e)+'; inspect artifacts and rerun; no learner grade'}],artifacts=run_path)

if __name__=='__main__':
    r=main()
    for c in r['criteria']:
        if 'pass_' in c:c['pass']=c.pop('pass_')
    print_result(r);sys.exit(r['exit_code'])
