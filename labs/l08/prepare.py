#!/usr/bin/env python3
"""Copy one exercise variant into work; retain one previous copy per work file."""
import json
import shutil
import sys
from pathlib import Path

root = Path(__file__).resolve().parent
if len(sys.argv) != 2 or sys.argv[1] not in ('starter', 'completion', 'independent'):
    raise SystemExit('usage: python3 labs/l08/prepare.py starter|completion|independent')
mode = sys.argv[1]
backup = root/'work'/'backups'
backup.mkdir(exist_ok=True)
for name in ('adapter.py', 'migration.json', 'performance.json'):
    if (root/'work'/name).exists():
        shutil.copy2(root/'work'/name, backup/name)
    shutil.copy2(root/mode/name, root/'work'/name)
print(json.dumps({'prepared': mode, 'backup': str(backup.relative_to(root))}))
