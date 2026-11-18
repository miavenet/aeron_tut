#!/usr/bin/env python3
import json
import sys
r = json.load(sys.stdin)
assert r['model'] == 'claude-haiku-5-5'
# Deterministic fake provider, not a claim about the named model.
correct = r['answer'] == 'a distinct list; input untouched'
print(json.dumps({'covered': [correct] * len(r['key']),
                  'feedback': 'Compare each bullet with the committed answer.'}))
