#!/usr/bin/env python3
"""Synthetic check: run the submitted pure transform against separate cases."""
import json
from pathlib import Path
import runpy
import sys
try:
    text = sys.stdin.read().strip()
    ref = json.loads(text)['workspace'] if text.startswith('{') else text
    workspace = Path(ref).resolve(strict=True)
    if not workspace.is_dir():
        raise ValueError('not a directory')
    fn = runpy.run_path(str(workspace / 'answer.py'))['transform']
    cases = [[3, 1], [], [-1, 4], [7, 7]]
    expected = [[6, 2], [], [-2, 8], [14, 14]]
    ok = all(fn(list(xs)) == ys for xs, ys in zip(cases, expected))
    code = 0 if ok else 1
    detail = 'all four input tapes doubled in order' if ok else 'Wrong output: preserve order and double every value; rerun the four tapes.'
except (OSError, KeyError, ValueError) as e:
    code, ok, detail = 2, False, str(e)
except Exception as e:
    code, ok, detail = 1, False, 'Repair submitted transform: ' + str(e)
print(json.dumps(dict(schema_version=1, status=['HIT', 'MISS', 'INFRA'][code], exit_code=code,
                     criteria=[dict(id='exact-tapes', **{'pass': ok}, detail=detail)])))
sys.exit(code)
