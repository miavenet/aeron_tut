#!/usr/bin/env python3
"""Script the real lesson 1 driver without a model, including MISS -> retry -> HIT."""
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from drive import Course

course = Course(ROOT/'lessons')
lesson = [course.nodes[cid] for cid in course.sequence if course.nodes[cid]['lesson'] == 'lesson-01']
with tempfile.TemporaryDirectory(prefix='aeron-session-') as tmp:
    tmp = Path(tmp)
    work = tmp/'work'
    work.mkdir()
    shutil.copy2(ROOT/'labs/l01/starter/Ownership.java', work/'Ownership.java')
    progress = tmp/'progress.json'
    def lines_for(node, workspace=None):
        if node['type'] == 'concept':
            return (['I predict a failed or accepted operation.'] if node['fields'].get('Prediction') else []) + ['The boundary depends on ownership and successful resolution.']
        if node['fields'].get('AcceptIf','').startswith('check('):
            return [str(workspace), 'The owner resolves each claim and preserves exact bytes.', 'high', 'yes', 'yes', 'continue']
        return [':skip','high','continue'] + ([] if node['type'] == 'pretest' else ['I will apply the key rule before mutation.'])
    def run(lines):
        answers = tmp/'answers.txt'
        answers.write_text('\n'.join(lines)+'\n')
        p = subprocess.run([sys.executable,'-B',str(ROOT/'drive.py'),str(ROOT/'lessons'),
                            '--today','2030-01-01','--progress',str(progress),'--auto',str(answers),
                            '--minutes','200','--max-cards','15'],cwd=ROOT,capture_output=True,text=True,timeout=180)
        if p.returncode:
            raise AssertionError(p.stdout+'\n'+p.stderr)
        return p.stdout
    first = []
    for node in lesson:
        if node['id'] == 'L01-C1':
            first += [str(work), 'The owner resolves each claim and preserves exact bytes.', 'high']
            break
        first += lines_for(node)
    output1 = run(first)
    state = json.loads(progress.read_text())
    assert state['current']['card']=='L01-C1' and state['current']['grade']=='MISS', state['current']
    shutil.copy2(ROOT/'labs/l01/solution/Ownership.java',work/'Ownership.java')
    rest = ['retry','continue','I must resolve failed claims by aborting them.']
    after = False
    for node in lesson:
        if node['id']=='L01-C1':
            after=True
            continue
        if after:
            rest += lines_for(node, ROOT/'labs/l01/solution')
    output2 = run(rest)
    state = json.loads(progress.read_text())
    assert 'HIT on retry' in output2, output2
    assert 'lesson-01' in state['completed_lessons'], state['completed_lessons']
    assert len([h for h in state['history'] if h['mode']=='learn' and h['lesson']=='lesson-01'])==len(lesson)
    print('actual lesson 1: 15/15 cards completed; lab MISS -> retry -> HIT; first grade retained as MISS')
