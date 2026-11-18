#!/usr/bin/env python3
"""Grade the submitted fictional frame and state machine without a model."""
import importlib.util
import json
import re
import struct
import sys
import zlib
from copy import deepcopy
from statistics import median
from pathlib import Path
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from harness import print_result, InfraError, result, workspace_from_stdin

class Miss(Exception):
    pass


def expect(condition, message):
    if not condition:
        raise Miss(message)


def golden(seq, epoch, kind, origin, request, value):
    payload = struct.pack('<II', request, value)
    prefix = struct.pack('<IHHII', seq, epoch, kind, origin, len(payload))
    return prefix + struct.pack('<I', zlib.crc32(prefix + payload)) + payload


def load(workspace):
    path = workspace/'adapter.py'
    if not path.is_file():
        raise InfraError('workspace needs adapter.py')
    spec = importlib.util.spec_from_file_location('submitted_adapter', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def adapter_check(a):
    for seq,epoch,kind,origin,request,value in [(1,7,1,3,1,5),(2,8,2,4,2,2),(5,7,1,4,3,9)]:
        payload=struct.pack('<II',request,value)
        actual=a.encode(seq,epoch,kind,origin,payload)
        expected=golden(seq,epoch,kind,origin,request,value)
        if actual!=expected:
            difference=next((i for i,(x,y) in enumerate(zip(actual,expected)) if x!=y),min(len(actual),len(expected)))
            raise Miss(f'independent golden frame differs at byte {difference}; expected {expected.hex()}, actual {actual.hex()}')
    try:a.encode(1,7,1,3,bytes(257))
    except ValueError:pass
    else:raise Miss('oversized payload accepted')


def policy_check(a):
    frames=[golden(1,7,1,3,1,5),golden(2,7,2,3,2,2),
            golden(3,7,1,3,3,6),golden(4,7,1,3,1,5),golden(5,7,1,4,1,7)]
    state=a.fresh()
    expected_statuses = ['APPLIED', 'APPLIED', 'APPLIED', 'CACHED', 'APPLIED']
    expected_results = [5, 5, 17, 5, 31]
    for index, (frame, status, reply) in enumerate(
            zip(frames, expected_statuses, expected_results), 1):
        observed = a.apply(state, frame)
        expect(observed == status,
               f'frame {index}: expected {status}, actual {observed}')
        expect(state['last_result'] == reply,
               f'frame {index}: expected reply {reply}, '
               f'actual {state["last_result"]}')
    expect((state['total'],state['mutations'],state['version'],state['last_result'])==(31,3,2,31),
           'replay state differs from expected total, mutations, version, or result')
    expect(state['results'][(3,1)][2]==5 and len(state['results'])==4,
           'original reply or distinct request keys lost')
    replay=a.fresh()
    for frame in frames:a.apply(replay,frame)
    expect(replay==state,'fresh replay state differs')
    def rejected_without_mutation(state, frame, reason):
        expected = deepcopy(state)
        expected['halted'] = True
        expect(a.apply(state, frame) == reason, reason + ' result differs')
        expect(state == expected, reason + ' changed application state')
        latched = deepcopy(state)
        expect(a.apply(state, frames[0]) == 'HALTED', reason + ' did not latch halt')
        expect(state == latched, 'HALTED processing changed state')
    bad_cases=[('CHECKSUM',frames[0][:-1]+bytes([frames[0][-1]^1])),
               ('EPOCH',golden(1,8,1,3,1,5)),('GAP',golden(2,7,1,3,1,5)),
               ('LENGTH',frames[0][:-1]),('LENGTH',b''),
               ('INPUT',golden(1,7,3,3,1,5)),
               ('INPUT',golden(1,7,1,3,0,5)),
               ('INPUT',golden(1,7,2,3,1,3))]
    for reason,frame in bad_cases:
        rejected_without_mutation(a.fresh(), frame, reason)
    s=a.fresh();a.apply(s,frames[0]);rejected_without_mutation(s,frames[0],'DUP')
    s=a.fresh();a.apply(s,frames[0]);rejected_without_mutation(s,golden(2,7,1,3,1,6),'CONFLICT')


def model_check(a):
    expect(a.release('local',[1,5,9],7,7)==1,'local release')
    expect(a.release('pair',[1,5,9],7,7)==5,'designated pair release')
    expect(a.release('quorum',[1,5,9],7,7)==5,'quorum release')
    expect(a.release('pair',[1,None,9],7,7) is None,'pair must wait for designated member')
    expect(a.release('quorum',[1,None,9],7,7)==9,'quorum may use surviving follower')
    expect(a.release('quorum',[1,None,None],7,7) is None,'quorum requires second record')
    for mode in ('local','pair','quorum'):
        expect(a.release(mode,[1,5,9],6,7) is None,mode+': stale writer epoch must fence release')
        expect(a.release(mode,[None,5,9],7,7) is None,mode+': local record is required')
    expect(a.release('pair',[9,1,5],7,7)==9,'pair must await both required records')
    expect(a.release('quorum',[9,1,5],7,7)==9,'quorum must await local and one follower')


PROMOTION_TERMS=('epoch','quorum','gate','record','sync','follower','copies','copy','majority','replica','fence','ack')


def prose_like(value):
    """Reject filler: need >=4 alphabetic words, >=3 distinct, each plausible (vowel, no long character run)."""
    words=re.findall(r'[A-Za-z]+',value.lower())
    real=[w for w in words if len(w)>=2 and re.search(r'[aeiouy]',w) and not re.search(r'(.)\1{2,}',w)
          and len(set(w))>=2 and len(w)<=24]
    letters=sum(map(len,words))
    return (len(real)>=4 and len(set(real))>=3 and len(set(real))*2>=len(real)
            and letters>=0.6*len(value.replace(' ','')))


def migration_check(workspace):
    try:
        data=json.loads((workspace/'migration.json').read_text())
        schema=json.loads((ROOT/'labs/l08/fixture/migration-schema.json').read_text())
    except (ValueError,OSError) as e:raise Miss('migration JSON missing or invalid: '+str(e))
    expect(data.get('schema_version')==1,'migration schema version')
    rows=data.get('changes')
    expect(isinstance(rows,list) and len(rows)==6,'six boundary rows required')
    by={row.get('id'):row for row in rows if isinstance(row,dict)}
    expect(len(by)==6,'unique boundary IDs required')
    for ref in schema['changes']:
        row=by.get(ref['id'],{})
        for field in ('seam','status','test'):
            expect(row.get(field)==ref[field],ref['id']+' '+field+' differs from course fixture')
        values={}
        for field in ('current','acceptance','commit','ack','replay_source','failure_domain'):
            value=row.get(field)
            expect(isinstance(value,str) and len(value.strip())>=15 and 'TODO' not in value
                   and not value.startswith(('A course fixture or design proposal','State where ','Name the '))
                   and prose_like(value),
                   ref['id']+' needs a concrete '+field+' (a real sentence of several different words)')
            values[field]=' '.join(value.lower().split())
        expect(len(set(values.values()))==len(values),ref['id']+' fields must not repeat the same text')
    promotion=by['promotion']['commit'].lower()
    expect(any(t in promotion for t in PROMOTION_TERMS),
           'promotion commit must name the gate: a quorum, record, copy, sync or epoch term')
    return rows


def performance_check(a,workspace):
    try:cfg=json.loads((workspace/'performance.json').read_text())
    except (ValueError,OSError) as e:raise Miss('performance JSON missing or invalid: '+str(e))
    samples=cfg.get('samples')
    expect(isinstance(samples,list) and 1<=len(samples)<=10000 and
           all(type(x) is int and x>=0 for x in samples),'nonnegative integer samples required')
    try:stats=a.metrics(samples,cfg.get('accepted'),cfg.get('delivered'))
    except (ValueError,TypeError) as e:raise Miss('sample accounting: '+str(e))
    ordered=sorted(samples)
    n=len(samples)
    expected=(n,median(samples),ordered[(99*n+99)//100-1],max(samples))
    for name,actual,want in zip(('count','median','p99','maximum'),tuple(stats),expected):
        expect(actual==want,f'{name} differs: expected {want}, actual {actual}')
    expect(tuple(a.metrics([4,10],2,2))==(2,7,10,10),
           'even-count median and nearest-rank p99 differ: expected (2, 7, 10, 10)')
    expect(tuple(a.metrics([4,8,6,10,5],5,5))==(5,6,10,10),
           'odd-count median and nearest-rank p99 differ: expected (5, 6, 10, 10)')
    for changed in ((len(samples)+1,len(samples)),(len(samples),len(samples)-1)):
        try:a.metrics(samples,*changed)
        except ValueError:pass
        else:raise Miss('missing samples must fail accounting')


def main():
    mode=sys.argv[1] if len(sys.argv)>1 else 'core'
    if mode not in ('core','adapter','policy','model','migration','performance'):
        raise SystemExit('unknown check mode')
    try:
        workspace=workspace_from_stdin()
        a=load(workspace)
        if mode in ('adapter','core'):adapter_check(a)
        if mode in ('policy','core'):policy_check(a)
        if mode in ('model','core'):model_check(a)
        if mode in ('migration','core'):migration_check(workspace)
        if mode in ('performance','core'):performance_check(a,workspace)
        code=0;detail='Submitted workspace passed '+mode+' checks; grade design explanations against lesson keys.'
    except Miss as e:code=1;detail=str(e)
    except (AttributeError,SyntaxError,TypeError,KeyError,IndexError,
            ValueError,NameError,ArithmeticError,AssertionError,struct.error) as e:
        code=1;detail='Submitted implementation error: '+str(e)
    except (InfraError,OSError,ImportError) as e:
        code=2;detail=str(e)
    print_result(result(code,[{'id':mode,'pass':code==0,'detail':detail}]))
    return code
if __name__=='__main__':sys.exit(main())
