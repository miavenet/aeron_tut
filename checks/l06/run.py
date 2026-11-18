#!/usr/bin/env python3
"""Owned live fixtures and independent application-state oracles; stdin is learner workspace."""
import hashlib,json,os,signal,sys,time
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools'))
from harness import print_result, Run,InfraError,java_argv,javac_binary,workspace_from_stdin,result
class Miss(Exception):pass

def main():
    mode=sys.argv[1];skip_policy=mode in ('live-dedup','live-snapshot');mode='snapshot' if mode=='live-snapshot' else mode;run=None;summary={}
    try:
        ws=workspace_from_stdin()
        if not all((ws/p).is_file() for p in ['RequestWork.java','ClientWork.java']):raise InfraError('workspace needs RequestWork.java and ClientWork.java')
        with Run('wp5',parent=ROOT/'manifest/evidence/wp5',timeout=110,
                 ports=mode not in ('policy','codec','max-position')) as run:
            def event(c,name,**fields):
                run.wait_event(c,name,**fields)
                return next(e for e in c.events() if e.get('event')==name and all(e.get(k)==v for k,v in fields.items()))
            def wait(c):
                run.until(lambda:c.poll() is not None,description=c.name+' exit');return run.wait(c)
            def command(name,argv,allow=False):
                c=run.start(name,argv,cwd=ROOT);code=wait(c)
                if code and not allow:
                    tail=c.log.read_text(errors='replace')[-3500:]
                    if name=='compile' or 'MISS:' in tail:raise Miss(tail)
                    raise InfraError(name+': '+tail)
                return c
            command('setup',['bash',str(ROOT/'tools/setup.sh'),'--check'])
            classes=run.path/'classes';classes.mkdir()
            command('compile',[javac_binary(),'-cp',str(ROOT/'labs/lib/aeron-all-1.54.1.jar'),'-d',str(classes)]+[str(ws/p) for p in ['RequestWork.java','ClientWork.java']]+[str(p) for p in sorted((ROOT/'labs/l06/fixture').glob('*.java'))])
            def argv(main,*args):return java_argv(main,*args,classpath=[classes])
            # Live wrong-work tests deliberately skip policy to exercise the actual service.
            if not skip_policy:command('policy',argv('PolicyProbe','snapshot' if mode in ('snapshot','codec','no-snapshot','slow-snapshot','incompatible') else 'client'))
            if mode in ('policy','codec','max-position'):
                summary['policy']=True
            else:
                where=run.path/'cluster';where.mkdir();generation=[0,0,0];nodes={};serial=[0]
                def start(i,variant='normal'):
                    (where/('stop'+str(i))).unlink(missing_ok=True)
                    c=run.start('node%d-g%d'%(i,generation[i]),argv('ClusterNode',i,where,run.ports.base,generation[i],variant),cwd=ROOT)
                    generation[i]+=1;nodes[i]=c;return c
                def stop(i,kill=False):
                    c=nodes.pop(i)
                    if kill:
                        status=c.status();os.kill(status['pid'],signal.SIGKILL);wait(c)
                    else:
                        (where/('stop'+str(i))).touch()
                        if wait(c)!=0:raise InfraError('shutdown '+c.name)
                def admin(i,action):
                    serial[0]+=1;return command('admin%d'%serial[0],argv('Admin',where/('node%d/cluster'%i),action))
                def leader():
                    c=admin(next(iter(nodes)),'leader');return next(e['leader'] for e in c.events() if e['event']=='LEADER')
                def client(name,tape,variant='normal',gate=None):
                    f=run.path/(name+'.tape');f.write_text(''.join(','.join(map(str,row))+'\n' for row in tape))
                    return run.start(name,argv('RecoveryClient',run.path/(name+'-aeron'),run.ports.base,f,variant,gate or run.path/(name+'-gate')),cwd=ROOT)
                def done(c):
                    code=wait(c)
                    if code:raise InfraError(c.log.read_text(errors='replace')[-2500:])
                    return event(c,'DONE')
                def barrier(bid,expected,selected=None):
                    events=[event(c,'BARRIER',id=bid) for c in (selected or nodes).values()]
                    fields=['count','total','config','tick','timerId','timerDeadline','dedup']
                    for e in events:
                        if [e[k] for k in fields]!=expected:raise Miss('barrier '+str(bid)+': '+str(e)+' expected '+str(expected))
                    if len({e['digest'] for e in events})!=1:raise Miss('replica state digests differ at application barrier')
                    return events
                if mode=='storage':
                    for i in range(3):start(i,'low-storage')
                    run.until(lambda:any('low storage threshold=' in n.log.read_text(errors='replace') for n in nodes.values()),description='injected low storage refusal')
                    for i in list(nodes):stop(i)
                    summary['low_storage_refused']=True
                for i in range(3):start(i,'slow-snapshot' if mode=='slow-snapshot' else ('session-limit' if mode=='session-limit' else 'normal'))
                for c in nodes.values():run.wait_event(c,'STARTED')
                if mode in ('failover','live-dedup','pause','quorum'):
                    gate=run.path/'release-client';c=client('requests',[(1,41,i,i,0) for i in range(1,6)]+[(9,41,900,0,0)],'lost-kill',gate)
                    run.wait_event(c,'DROPPED',id=2)
                    for n in nodes.values():run.wait_event(n,'APPLY',id=2)
                    old=leader();summary['old_leader']=old;loss_clock=time.monotonic()
                    if mode=='pause':
                        target=nodes[old];os.kill(target.status()['pid'],signal.SIGSTOP)
                        # Resume is always paired, including failure cleanup.
                        try:
                            gate.touch();survivor=next(i for i in nodes if i!=old)
                            run.wait_event(nodes[survivor],'BARRIER',id=900)
                        finally:os.kill(target.status()['pid'],signal.SIGCONT)
                        # A long whole-JVM pause can expire embedded Aeron clients; restart owned member.
                        stop(old)
                        summary['paused_member_restarted']=True
                    else:
                        stop(old,kill=True);gate.touch()
                    finished=done(c)
                    if finished['unique']!=6 or finished['pending']!=0:raise Miss('acknowledge exact unique request set')
                    if old not in nodes:start(old);run.wait_event(nodes[old],'STARTED')
                    barrier(900,[5,15,1,0,-1,0,5])
                    ack2=[e for e in c.events() if e['event']=='ACK' and e['id']==2]
                    if not ack2 or any((e['count'],e['total'])!=(2,3) for e in ack2):raise Miss('lost reply must return original cached result')
                    summary['new_leader']=leader();summary['wire_replies']=finished['wireReplies']
                    summary['kill_or_pause_to_ack_s']=round(time.monotonic()-loss_clock,3)
                    # Drain committed work at 900, connect before removing quorum, then submit fresh barrier.
                    gate2=run.path/'release-quorum';q=client('quorum',[(9,41,901,0,0)],'gated',gate2)
                    run.wait_event(q,'CONNECTED');active=leader();removed=[i for i in nodes if i!=active]
                    for i in removed:stop(i)
                    gate2.touch();run.until(lambda:any(e.get('event')=='OFFER' and e.get('id')==901 and e.get('result',0)>0 for e in q.events()),description='positive local offer for fresh uncommitted barrier',children=(q,))
                    observation_start=time.monotonic();deadline=observation_start+30
                    while True:
                        if any(e.get('event')=='BARRIER' and e.get('id')==901 for n in nodes.values() for e in n.events()):raise Miss('fresh application barrier executed without quorum')
                        if 'inactive follower quorum' in nodes[active].log.read_text(errors='replace'):break
                        if time.monotonic()>deadline:raise InfraError('no-quorum election observation watchdog')
                        time.sleep(.02)
                    summary['no_quorum_observed_s']=round(time.monotonic()-observation_start,3)
                    # Restart the remaining member too: all directories retained, client pending survives.
                    stop(active)
                    for i in range(3):start(i)
                    done(q)
                    barrier(901,[5,15,1,0,-1,0,5])
                elif mode in ('snapshot','no-snapshot','slow-snapshot','incompatible'):
                    prefix=[(2,41,10,3,0),(3,41,11,777,0),(4,41,12,88,9999),(1,41,1,7,0),(1,41,2,2,0),(9,41,900,0,0)]
                    first=client('prefix',prefix);done(first);before=barrier(900,[2,27,3,777,88,9999,5])
                    recordings_before={str(p.relative_to(where)) for p in where.glob('node*/archive/*.rec')}
                    first_session=next(e['session'] for e in first.events() if e['event']=='CONNECTED')
                    if mode!='no-snapshot':
                        ld=leader();follower=next(i for i in nodes if i!=ld)
                        refused=admin(follower,'snapshot')
                        if event(refused,'SNAPSHOT_REQUEST')['accepted']:raise Miss('follower snapshot unexpectedly accepted')
                        if mode=='slow-snapshot':
                            a=run.start('slow-admin',argv('Admin',where/('node%d/cluster'%ld),'snapshot'),cwd=ROOT)
                            for n in nodes.values():run.wait_event(n,'SNAPSHOT_BEGIN')
                            # A deterministic held callback must leave no completed recording pair.
                            for i in nodes:
                                if any(e['event']=='SNAPSHOT_ENTRY' for e in admin(i,'recordings').events()):raise Miss('stalled snapshot completed before release')
                            (where/'release-snapshot').touch();wait(a)
                        else:admin(ld,'snapshot')
                        positions=[]
                        for i,n in nodes.items():
                            run.wait_event(n,'SNAPSHOT_WRITTEN')
                            end=time.monotonic()+30
                            while True:
                                entries=[e for e in admin(i,'recordings').events() if e['event']=='SNAPSHOT_ENTRY']
                                if {e['service'] for e in entries}=={-1,0}:break
                                if time.monotonic()>end:raise InfraError('snapshot recording completion watchdog')
                            if len({e['position'] for e in entries})!=1:raise Miss('service/consensus snapshot position mismatch')
                            positions.append(entries[0]['position'])
                        if len(set(positions))!=1:raise Miss('snapshot positions differ across replicas')
                        summary['snapshot_positions']=positions
                        if not recordings_before.issubset({str(p.relative_to(where)) for p in where.glob('node*/archive/*.rec')}):raise Miss('snapshot removed existing recording segments')
                    suffix=[(1,41,3,4,0),(1,41,4,6,0),(9,41,901,0,0)]
                    done(client('suffix',suffix));saved=barrier(901,[4,57,3,777,88,9999,7])
                    for i in list(nodes):stop(i)
                    if mode=='incompatible':
                        for i in range(3):start(i,'incompatible')
                        for n in nodes.values():run.until(lambda n=n:'incompatible app version' in n.log.read_text(errors='replace'),description='incompatible snapshot startup refused')
                        for i in list(nodes):stop(i)
                    for i in range(3):start(i)
                    for n in nodes.values():
                        loaded=event(n,'EMPTY_START' if mode=='no-snapshot' else 'LOADED')
                        if mode!='no-snapshot' and loaded['digest']!=before[0]['digest']:raise Miss('snapshot load state differs')
                    recovered=barrier(901,[4,57,3,777,88,9999,7])
                    if recovered[0]['digest']!=saved[0]['digest']:raise Miss('suffix recovery digest differs')
                    for n in nodes.values():
                        ids=[e['id'] for e in n.events() if e['event']=='APPLY']
                        if ids!=([10,11,12,1,2,3,4] if mode=='no-snapshot' else [3,4]):raise Miss('actual replay IDs mismatch '+str(ids))
                    fresh=client('after-restart',[(1,41,1,7,0),(1,41,5,8,0),(9,41,902,0,0)]);done(fresh)
                    last=barrier(902,[5,81,3,777,88,9999,8])
                    session=next(e['session'] for e in fresh.events() if e['event']=='CONNECTED')
                    if session==first_session:raise Miss('restart did not create new session')
                    ack=next(e for e in fresh.events() if e['event']=='ACK' and e['id']==1)
                    if (ack['count'],ack['total'])!=(1,21):raise Miss('snapshot must retain original result cache')
                    summary.update(replay_ids=ids,restored_digest=last[0]['digest'],new_session=session)
                elif mode in ('session-limit','polling','wrong-endpoint','storage'):
                    gate=run.path/'idle-release'
                    if mode in ('session-limit','polling'):
                        holder=client('holder',[(9,41,880,0,0)],'hold-session' if mode=='session-limit' else 'idle-session',gate);run.wait_event(holder,'IDLE')
                    if mode=='session-limit':
                        rejected=client('rejected',[(9,41,881,0,0)])
                        run.wait_event(rejected,'CONNECT_RETRY',reason='session-limit');run.stop(rejected)
                        # Keep the slot occupied until rejection is observed, then explicitly close it.
                    if mode=='polling':
                        run.until(lambda:any(e.get('event')=='SESSION_CLOSED' and e.get('reason')=='TIMEOUT' for n in nodes.values() for e in n.events()),description='missing keepalive expires session')
                    if mode=='wrong-endpoint':
                        wrong=client('wrong',[(9,41,882,0,0)],'wrong-endpoint');run.wait_event(wrong,'CONNECT_RETRY',reason='timeout');run.stop(wrong)
                    if mode in ('session-limit','polling'):gate.touch();done(holder)
                    fixed=client('fixed',[(9,41,883,0,0)]);done(fixed);barrier(883,[0,0,1,0,-1,0,0])
                else:raise InfraError('unknown mode '+mode)
                for i in list(nodes):stop(i)
            value=result(0,[{'id':mode,'pass':True,'detail':'Submitted work passed application-state and fault/recovery assertions.'}],artifacts=str(run.path.relative_to(ROOT)),observations=summary)
    except Miss as e:value=result(1,[{'id':mode,'pass':False,'detail':str(e)+'; repair the named criterion and rerun.'}],artifacts=str(run.path.relative_to(ROOT)) if run else '',observations=summary)
    except (InfraError,OSError,ValueError,KeyError) as e:value=result(2,[{'id':mode,'pass':False,'detail':str(e)}],artifacts=str(run.path.relative_to(ROOT)) if run else '',observations=summary)
    print_result(value);return value['exit_code']
if __name__=='__main__':sys.exit(main())
