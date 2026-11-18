#!/usr/bin/env python3
"""Run submitted ArchiveWork and emit schema-version-1 HIT/MISS/INFRA results."""
import json,sys,time
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from harness import print_result, Run,InfraError,java_argv,javac_binary,workspace_from_stdin,result

def wait(run,child):
    run.until(lambda:child.poll() is not None,description=child.name+' status')
    return run.wait(child)

class Miss(Exception):pass

def main():
    mode=sys.argv[1] if len(sys.argv)>1 else 'core'
    run=None
    try:
        workspace=workspace_from_stdin()
        if not (workspace/'ArchiveWork.java').is_file():raise InfraError('workspace needs ArchiveWork.java')
        with Run('wp3',parent=ROOT/'manifest/evidence/wp3',timeout=35,
                 ports=mode not in ('config','opening')) as run:
            def command(name,argv,expected=0):
                c=run.start(name,argv,cwd=ROOT);code=wait(run,c)
                if code!=expected:
                    text=c.log.read_text(errors='replace')[-3000:]
                    if code==1 and ('MISS:' in text or name=='compile'):raise Miss(name+': '+text)
                    raise InfraError(name+': exit '+str(code)+' '+text)
                return c
            command('setup',['bash',str(ROOT/'tools/setup.sh'),'--check'])
            classes=run.path/'classes';classes.mkdir()
            command('compile',[javac_binary(),'-cp',str(ROOT/'labs/lib/aeron-all-1.54.1.jar'),'-d',str(classes),str(workspace/'ArchiveWork.java')]+[str(p) for p in sorted((ROOT/'labs/l04/fixture').glob('*.java'))])
            def java(name,action,where,option='normal'):
                base=run.ports.base if run.ports else 0
                return command(name,java_argv('ArchiveLab',action,where,base,option,classpath=[classes],jvm_args=zip_flags))
            zip_flags=['--add-opens=java.base/java.util.zip=ALL-UNNAMED'] if mode=='storage' else []
            def start(where,option='normal',restart=False):
                if restart:(where/'stop').unlink()
                else:
                    where.mkdir();(where/'archive').mkdir();(where/'archive'/'sentinel').write_text('keep me\n')
                child=run.start(where.name+('-restart' if restart else '-server'),java_argv('ArchiveLab','server',where,run.ports.base,option,classpath=[classes],jvm_args=zip_flags),cwd=ROOT)
                run.wait_event(child,'READY')
                if not (where/'archive'/'sentinel').exists():raise Miss('deleteArchiveOnStart erased sentinel')
                return child
            def stop(child,where):
                (where/'stop').touch()
                if wait(run,child)!=0:raise InfraError('server shutdown failed')
            if mode in ('core','config'):
                java('config','config',run.path)
            if mode=='opening':
                c=command('missing-zip-opening',java_argv('ArchiveLab','checksum-probe',run.path,0,classpath=[classes]),expected=1)
                if 'does not "opens java.util.zip"' not in c.log.read_text():raise InfraError('Unexpected checksum opening failure')
                zip_flags=['--add-opens=java.base/java.util.zip=ALL-UNNAMED']
                java('with-zip-opening','checksum-probe',run.path)
            elif mode=='config':pass
            else:
                if mode=='core':
                    empty=run.path/'empty';s=start(empty,'empty');java('empty-record','record',empty,'empty');stop(s,empty)
                live=run.path/'live';s=start(live,'checksum' if mode=='storage' else 'normal')
                if mode in ('core','nonzero','lifecycle','geometry','storage','tool'):
                    java('record','record',live,'nonzero' if mode=='nonzero' else 'normal')
                    # command() has reaped the publisher and its supervisor before replay.
                    java('replay','replay',live)
                if mode in ('isolation','merge','lifecycle','geometry'):java(mode,mode,live)
                stop(s,live)
                if mode=='core':
                    s=start(live,restart=True);java('restart-replay','replay',live);stop(s,live)
                if mode in ('storage','tool'):
                    command('describe',java_argv('io.aeron.archive.ArchiveTool',live/'archive','describe',classpath=[classes]))
                    java(mode,mode,live)
                if mode=='tool':
                    c=command('capacity-dispatch',java_argv('io.aeron.archive.ArchiveTool',live/'archive','capacity',classpath=[classes]),expected=255)
                    if 'ERR: Invalid command' not in c.log.read_text():raise InfraError('capacity dispatch outcome changed')
            value=result(0,[{'id':mode,'pass':True,'detail':'Submitted workspace passed live assertions.'}],artifacts=str(run.path.relative_to(ROOT)))
    except Miss as e:value=result(1,[{'id':mode,'pass':False,'detail':str(e)+'; repair the named criterion and rerun.'}],artifacts=str(run.path.relative_to(ROOT)) if run else '')
    except (InfraError,OSError,ValueError) as e:value=result(2,[{'id':mode,'pass':False,'detail':str(e)}],artifacts=str(run.path.relative_to(ROOT)) if run else '')
    print_result(value);return value['exit_code']
if __name__=='__main__':sys.exit(main())
