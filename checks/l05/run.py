#!/usr/bin/env python3
"""Compile and exercise the submitted StateWork against independent oracles."""
import hashlib,json,sys
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools'))
from harness import print_result, Run,InfraError,java_argv,javac_binary,workspace_from_stdin,result
class Miss(Exception): pass
TAPE=[(2,10,2,0),(3,11,700,0),(1,12,5,0),(1,13,-7,0),(4,14,9,2),(2,15,3,0),(1,16,4,0),(9,900,0,0)]
def main():
    mode=sys.argv[1] if len(sys.argv)>1 else 'core';run=None
    try:
        workspace=workspace_from_stdin()
        if not (workspace/'StateWork.java').is_file():raise InfraError('workspace needs StateWork.java')
        with Run('wp4',parent=ROOT/'manifest/evidence/wp4',timeout=50,
                 ports=mode not in ('boundary','policy','clock','timers','validation')) as run:
            def wait(c):
                run.until(lambda:c.poll() is not None,description=c.name+' exit')
                return run.wait(c)
            def command(name,argv):
                c=run.start(name,argv,cwd=ROOT);code=wait(c)
                if code:
                    tail=c.log.read_text(errors='replace')[-3000:]
                    if name=='compile' or 'MISS:' in tail:raise Miss(name+': '+tail)
                    raise InfraError(name+': '+tail)
                return c
            command('setup',['bash',str(ROOT/'tools/setup.sh'),'--check'])
            classes=run.path/'classes';classes.mkdir()
            command('compile',[javac_binary(),'-cp',str(ROOT/'labs/lib/aeron-all-1.54.1.jar'),'-d',str(classes),str(workspace/'StateWork.java')]+[str(p) for p in sorted((ROOT/'labs/l05/fixture').glob('*.java'))])
            def java(name,main,*args):return command(name,java_argv(main,*args,classpath=[classes]))
            if mode in ('core','boundary'):java('boundary','BoundaryProbe',run.path/'boundary-aeron')
            if mode in ('core','policy','clock','timers','validation'):
                for action in (['validation','policy'] if mode=='core' else ([] if mode=='boundary' else [mode])):
                    java(action,'OpsProbe',action)
            if mode=='lifecycle':java('lifecycle-policy','OpsProbe','policy')
            if mode=='unit-mismatch':java('clock-policy','OpsProbe','clock')
            if mode not in ('boundary','policy','clock','timers','validation'):
                where=run.path/'cluster';where.mkdir()
                def start(generation,variant):
                    (where/'stop').unlink(missing_ok=True)
                    nodes=[run.start('node%d-g%d'%(i,generation),java_argv('ClusterNode',i,where,run.ports.base,generation,variant,classpath=[classes]),cwd=ROOT) for i in range(3)]
                    for c in nodes:run.wait_event(c,'EMPTY_START')
                    return nodes
                def barriers(nodes,bid):
                    for c in nodes:run.wait_event(c,'BARRIER',id=bid)
                def stop(nodes):
                    (where/'stop').touch()
                    for c in nodes:
                        if wait(c)!=0:raise InfraError('node shutdown failed '+str(c.log))
                def validate(nodes,bid,tape,initial=None):
                    found=[];timestamps=[]
                    for c in nodes:
                        events=c.events(); state=list(initial) if initial else [0,0,1,0,0,17]
                        applies=[e for e in events if e['event']=='APPLY' and e['id'] in {r[1] for r in tape}]
                        if [e['id'] for e in applies]!=[r[1] for r in tape]:raise Miss('exact applied tape IDs/order mismatch')
                        stamps=[]
                        for row,e in zip(tape,applies):
                            op,rid,value,aux=row;valid=rid!=13
                            expected_before=','.join(map(str,state))
                            if valid and op!=9:
                                state[4]=e['timestamp']
                                if op==1:state[0]+=1;state[1]+=value*state[2]
                                elif op==2:state[2]=value
                                elif op==3:state[3]=value
                                elif op==4:
                                    for v in sorted((value,aux)):state[5]=state[5]*31+v
                            expected_fields={'op':op,'accepted':valid,'before':expected_before,'state':','.join(map(str,state))}
                            for field,expected in expected_fields.items():
                                if e[field]!=expected:
                                    raise Miss('state transition id=%s node=%s: %s expected=%s actual=%s; use logged time/config, stable order and validate BEFORE mutation'%(rid,e['node'],field,expected,e[field]))
                            if e['position']>e['commit']:raise Miss('service executed beyond commit')
                            stamps.append(e['timestamp'])
                        event=[e for e in events if e['event']=='BARRIER' and e['id']==bid][-1]
                        canonical=','.join(map(str,state));digest=hashlib.sha256(canonical.encode()).hexdigest()
                        if (event['state'],event['count'],event['total'],event['digest'])!=(canonical,state[0],state[1],digest):raise Miss('barrier state/digest mismatch')
                        found.append((bid,state[0],state[1],digest));timestamps.append(stamps)
                    if len(set(found))!=1 or timestamps[1:]!=timestamps[:-1]:raise Miss('replicas differ at shared application barrier')
                    return state,found[0],timestamps[0]
                nodes=start(0,'lifecycle' if mode=='lifecycle' else 'normal')
                if mode=='lifecycle':
                    for c in nodes:run.wait_event(c,'LIFECYCLE_REJECTED')
                client=java('tape','TapeClient',run.path/'client',run.ports.base,'tape')
                barriers(nodes,900)
                # Preserve the raw divergent barrier evidence even when the oracle rejects the learner.
                captured=[next(e for e in c.events() if e['event']=='BARRIER' and e['id']==900) for c in nodes]
                (run.path/'barriers.json').write_text(json.dumps(captured,indent=2)+'\n')
                state,first,stamps=validate(nodes,900,TAPE)
                egress=[e for c in nodes for e in c.events() if e['event']=='EGRESS']
                if not any(e['role']=='FOLLOWER' and e['result']==1 for e in egress):raise InfraError('missing follower mock observation')
                if not any(e['role']=='LEADER' and e['result']>1 for e in egress):raise InfraError('missing leader physical egress observation')
                stop(nodes)
                if mode=='unit-mismatch':
                    nodes=start(1,'unit-mismatch')
                    for c in nodes:
                        run.until(lambda c=c:'incompatible timestamp units' in c.log.read_text(errors='replace'),description='release rejects changed log time unit')
                    stop(nodes)
                    # Repair by restoring original clock, then prove replay.
                    nodes=start(2,'normal')
                else:nodes=start(1,'normal')
                barriers(nodes,900)
                replay_state,replay,replay_stamps=validate(nodes,900,TAPE)
                if first!=replay or stamps!=replay_stamps:raise Miss('fresh replay changed state or timestamps')
                java('suffix','TapeClient',run.path/'client-new',run.ports.base,'suffix')
                barriers(nodes,901)
                validate(nodes,901,[(1,20,8,0),(9,901,0,0)],initial=replay_state)
                stop(nodes)
            value=result(0,[{'id':mode,'pass':True,'detail':'Submitted work passed independent assertions.'}],artifacts=str(run.path.relative_to(ROOT)))
    except Miss as e:value=result(1,[{'id':mode,'pass':False,'detail':str(e)+'; repair the named criterion and rerun.'}],artifacts=str(run.path.relative_to(ROOT)) if run else '')
    except (InfraError,OSError,ValueError) as e:value=result(2,[{'id':mode,'pass':False,'detail':str(e)}],artifacts=str(run.path.relative_to(ROOT)) if run else '')
    print_result(value);return value['exit_code']
if __name__=='__main__':sys.exit(main())
