#!/usr/bin/env python3
"""Portable structural check for the public tutorial."""
import argparse
import json
import re
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from drive import Course, render_t0

sys.dont_write_bytecode = True

class Invalid(ValueError):
    pass

LOCAL = re.compile(r'`((?:checks|labs|tools|manifest)/[^`]+)`')


def verify(root):
    root = Path(root).resolve()
    lesson_dir = root/'lessons'
    if not lesson_dir.is_dir():
        raise Invalid('lessons directory missing')
    try:
        course = Course(lesson_dir)
    except (ValueError, KeyError, TypeError, OSError) as exc:
        raise Invalid(str(exc)) from exc
    cards = []
    checked = set()
    reviewable = set()
    for lesson in sorted(lesson_dir.glob('lesson-*.md')):
        body = lesson.read_text(encoding='utf-8')
        if re.search(r'^\s*(?:Sources?|Story sources?|AcceptIf|RejectIf|Concepts|Minutes):|\b(?:observed on the pinned version|shows this on the pinned version|verified with|verified against|upstream lines? \d+)\b|https?://|#L\d+|^### (?:CARD|CONCEPT).*\[(?:type|topic|objective|minutes|assessment|grading):', body, re.M | re.I):
            raise Invalid(f'{lesson.name}: metadata remains in lesson')
        for node in (n for n in course.nodes.values() if n['lesson'] == lesson.stem and n['type'] != 'concept'):
            cid = node['id']
            if cid in cards:
                raise Invalid(f'duplicate card {cid}')
            cards.append(cid)
            if node['type'] in {'recall','production','independent','apply','application','transfer','analyze','synthesize'}:
                reviewable.add(cid)
            if not node['fields']['Key']:
                raise Invalid(f'{cid}: missing answer key bullets')
            key_at = node['body'].find('Key:')
            if (key_at < 0 or '<details' not in node['body'][:key_at] or
                    '</details>' not in node['body'][key_at:]):
                raise Invalid(f'{cid}: answer key must be folded for paper mode')
            source = node['fields']['Source']
            if not source.strip():
                raise Invalid(f'{cid}: missing source')
            check = node['fields'].get('AcceptIf', '')
            for name in re.findall(r'check\((checks/[\w./-]+)\)', check):
                path = (root/name).resolve()
                if not path.is_relative_to(root/'checks') or not path.is_file() or not path.stat().st_mode & 0o111:
                    raise Invalid(f'{cid}: missing or non-executable check {name}')
                checked.add(name)
            for name in LOCAL.findall(source):
                name = name.split('@',1)[0].split(':',1)[0]
                if not (root/name).is_file():
                    raise Invalid(f'{cid}: missing local source {name}')
    schedule = json.loads((root/'manifest/schedule.json').read_text())
    scheduled = (set(schedule.get('pretest_once',[])) | set(schedule.get('review_cards',[])) |
                 set(schedule.get('auxiliary_delayed_required',[])) | set(schedule.get('exam_cards',{}).values()))
    missing = scheduled - set(cards)
    if missing:
        raise Invalid('schedule names absent cards: '+', '.join(sorted(missing)))
    authored = schedule.get('review_cards', [])
    if len(authored) != len(set(authored)) or set(authored) != reviewable:
        raise Invalid('review_cards differs from reviewable authored cards: missing '
                      + ', '.join(sorted(reviewable-set(authored))) + '; extra '
                      + ', '.join(sorted(set(authored)-reviewable)))
    if not (root/'MANIFEST.md').is_file() or not (root/'PROTOCOL.md').is_file():
        raise Invalid('course manifest or protocol missing')
    if 'delayed_check_scaffolds' not in schedule:
        raise Invalid('schedule lacks delayed_check_scaffolds for runnable review cards')
    study = root/'STUDY.md'
    if not study.is_file() or study.read_text(encoding='utf-8') != render_t0(course):
        raise Invalid('STUDY.md is missing or stale; run python3 -B drive.py lessons --print-t0 > STUDY.md')
    return {'status':'PASS','cards':len(cards),'executable_checks':len(checked),'scheduled_cards':len(scheduled)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--offline-evidence',action='store_true',help='compatibility alias for the local structural check')
    args=parser.parse_args()
    try:
        print(json.dumps(verify(args.root),sort_keys=True))
        return 0
    except (Invalid,OSError,ValueError,KeyError) as exc:
        print('VERIFY FAIL: '+str(exc),file=sys.stderr)
        return 1
if __name__=='__main__':sys.exit(main())
