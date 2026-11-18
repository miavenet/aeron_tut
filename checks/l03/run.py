#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from harness import print_result, Run, InfraError, JAR, java_argv, workspace_from_stdin, result

def wait(run,child):
    run.until(lambda: child.poll() is not None,description=child.name+' status')
    return run.wait(child)

def inspect(run,root):
    props={'aeron.dir':str(root/'driver')}
    for tool in ('AeronStat','StreamStat','LossStat','ErrorStat'):
        args=['watch=false'] if tool=='AeronStat' else []
        child=run.start(tool,java_argv('io.aeron.samples.'+tool,*args,properties=props),output_cap=65536)
        if wait(run,child): raise InfraError(tool+' failed')
        output=child.log.read_text()
        expected={'AeronStat':'rcv-hwm','StreamStat':'streamId=1003','LossStat':'1003','ErrorStat':'distinct errors observed.'}[tool]
        if expected not in output: raise InfraError(tool+' missing expected semantic output')
    files=sorted((root/'driver/publications').glob('*.logbuffer'))
    if not files: raise InfraError('no live publication log discovered')
    child=run.start('LogInspector',java_argv('io.aeron.samples.LogInspector',files[0],'16'),output_cap=65536)
    if wait(run,child): raise InfraError('LogInspector failed')
    if 'DATA Header' not in child.log.read_text(): raise InfraError('no inspected data frame')
    (root/'inspected').write_text('bounded capture complete\n')

def main():
    artifacts=None
    try:
        work=workspace_from_stdin(); source=work/'Diagnostics.java'
        if not source.is_file(): raise InfraError('workspace must contain Diagnostics.java')
        modes=['trace','lag','gap','liveness'] if sys.argv[1]=='core' else [sys.argv[1]]
        with Run('wp2',parent=ROOT/'manifest/evidence/wp2',timeout=40,
                 ports=any(mode in ('gap','lag') for mode in modes)) as run:
            artifacts=str(run.path.relative_to(ROOT))
            child=run.start('setup',[str(ROOT/'tools/setup.sh'),'--check'])
            if wait(run,child):raise InfraError('setup failed')
            classes=run.path/'classes';classes.mkdir()
            javac=str(Path(os.environ['JAVA_HOME'])/'bin/javac') if os.environ.get('JAVA_HOME') else 'javac'
            child=run.start('compile',[javac,'-cp',str(JAR),'-d',str(classes),str(source),str(ROOT/'labs/l03/fixture/DiagCheck.java')])
            if wait(run,child):return result(1,[{'id':'compile','pass':False,'detail':child.log.read_text()[-4000:]}],artifacts=artifacts)
            criteria=[]
            for mode in modes:
                root=run.path/mode;root.mkdir()
                port=run.ports.map['node0.ingress'] if mode in ('gap','lag') else 0
                child=run.start(mode,java_argv('DiagCheck',mode,root,port,classpath=[classes]))
                if mode=='gap':
                    run.until(lambda: child.poll() is not None or any(e.get('event')=='INSPECT_READY' for e in child.events()),description='gap recovery/inspection')
                    if child.poll() is None: inspect(run,root)
                code=wait(run,child)
                if code not in (0,1,2): code=2
                criteria.append({'id':mode,'pass':code==0,'detail':child.log.read_text().strip() if code else 'All required semantic assertions passed.'})
                if code:return result(code,criteria,artifacts=artifacts)
            return result(0,criteria,artifacts=artifacts)
    except (InfraError,OSError,ValueError,KeyboardInterrupt) as e:
        return result(2,[{'id':'environment','pass':False,'detail':str(e)+'; inspect artifacts and rerun; no learner miss'}],artifacts=artifacts)

if __name__=='__main__':
    r=main();print_result(r);sys.exit(r['exit_code'])
