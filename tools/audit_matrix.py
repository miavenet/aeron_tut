#!/usr/bin/env python3
"""Release matrix: each fault uses its criterion check; every check has a solution control."""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OVERRIDE = {
    ('l01', 'throw-handler'): 'handler',
    ('l01', 'mutate-before-abort'): 'controlled',
    ('l04', 'zero-start'): 'nonzero',
    ('l04', 'no-deadline'): 'lifecycle',
    ('l04', 'shared-merge'): 'merge',
    ('l04', 'shared-response'): 'isolation',
    ('l04', 'wrong-mtu'): 'geometry',
    ('l04', 'wrong-checksum'): 'storage',
    ('l04', 'truncate-original'): 'tool',
    ('l04', 'trust-help'): 'tool',
    ('l05', 'wrong-unit'): 'clock',
    ('l05', 'cancel-race'): 'timers',
    ('l05', 'stale-timer'): 'timers',
    ('l08', 'wrong-cached-reply'): 'policy',
    ('l08', 'value-error'): 'policy',
}

def run(script, case):
    try:
        p = subprocess.run([str(script)], input=str(case.relative_to(ROOT)) + '\n',
                           text=True, capture_output=True, cwd=ROOT, timeout=180)
    except subprocess.TimeoutExpired:
        return 'INFRA', 2, 'timeout'
    try:
        result = [json.loads(line) for line in p.stdout.splitlines() if line.startswith('{')][-1]
        return result['status'], p.returncode, result.get('criteria', [])
    except (ValueError, IndexError, KeyError):
        return 'NO_RESULT', p.returncode, (p.stdout + p.stderr)[-300:]

records = []
for number in range(1, 9):
    lesson = f'l{number:02}'
    core = next((ROOT / 'checks').glob(f'chk_{lesson}_*.sh'))
    lab = ROOT / 'labs' / lesson
    focused = sorted(p for p in (ROOT / 'checks' / lesson).glob('*.sh')
                     if p.name != 'prepare.sh')
    for script in [core, *focused]:
        records.append((lesson, 'solution', script, lab / 'solution', 'HIT'))
    for variant in ('starter', 'completion', 'independent'):
        records.append((lesson, variant, core, lab / variant, 'MISS'))
    for case in sorted((lab / 'faults').iterdir()):
        name = OVERRIDE.get((lesson, case.name))
        script = ROOT / 'checks' / lesson / (name + '.sh') if name else core
        records.append((lesson, 'fault/' + case.name, script, case, 'MISS'))

problems, blocked, counts = [], [], {'HIT': 0, 'MISS': 0, 'INFRA': 0}
by_lesson = {}
for lesson, label, script, case, expected in records:
    status, code, detail = run(script, case)
    counts[status] = counts.get(status, 0) + 1
    lesson_counts = by_lesson.setdefault(lesson, {'HIT': 0, 'MISS': 0, 'INFRA': 0})
    lesson_counts[status] = lesson_counts.get(status, 0) + 1
    case_id = f'{lesson}/{label} via {script.relative_to(ROOT)}'
    if status == 'INFRA':
        blocked.append(case_id)
    elif status != expected or code != (0 if expected == 'HIT' else 1):
        problems.append(f'{case_id}: expected {expected}, got {status}/{code}: {detail}')
    print(json.dumps({'case': case_id, 'status': status, 'expected': expected}), flush=True)
print(json.dumps({'status': 'FAIL' if problems else 'BLOCKED' if blocked else 'PASS',
                  'cases': len(records), 'counts': counts, 'by_lesson': by_lesson,
                  'problems': problems,
                  'blocked_cases': blocked}, sort_keys=True), flush=True)
sys.exit(1 if problems else 2 if blocked else 0)
