#!/usr/bin/env python3
"""Mutation tests for the public structural verifier."""
import shutil
import json
import sys
sys.dont_write_bytecode = True
import tempfile
import unittest
from pathlib import Path
from verify import Invalid, verify

ROOT=Path(__file__).resolve().parents[1]

class VerifyTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='course-verify-')
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        for name in ('lessons','manifest','checks'):
            shutil.copytree(ROOT/name,self.root/name,
                            ignore=shutil.ignore_patterns('evidence') if name=='manifest' else None)
        for name in ('MANIFEST.md','PROTOCOL.md','STUDY.md'):
            shutil.copy2(ROOT/name,self.root/name,follow_symlinks=True)
        # The test fixture uses actual local source paths in the copied course.
        shutil.copytree(ROOT/'labs',self.root/'labs')
        shutil.copytree(ROOT/'tools',self.root/'tools')
    def test_clean(self):
        self.assertEqual('PASS',verify(self.root)['status'])
    def test_missing_key(self):
        path=self.root/'lessons/lesson-08.md'
        path.write_text(path.read_text().replace('Key:\n- Local transport acceptance','No key:\n- Local transport acceptance',1))
        with self.assertRaises(Invalid):verify(self.root)
    def test_missing_check(self):
        (self.root/'checks/l08/policy.sh').unlink()
        with self.assertRaises(Invalid):verify(self.root)
    def test_missing_manifest_row(self):
        path=self.root/'manifest/cards.json'
        data=json.loads(path.read_text())
        del data['cards']['L08-T2']
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(Invalid,'no manifest row'):verify(self.root)
    def test_extra_manifest_row(self):
        path=self.root/'manifest/cards.json'
        data=json.loads(path.read_text())
        data['cards']['L08-EXTRA']=dict(data['cards']['L08-T2'])
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(Invalid,'no card'):verify(self.root)
    def test_malformed_manifest_row(self):
        path=self.root/'manifest/cards.json'
        data=json.loads(path.read_text())
        data['cards']['L08-T2']['minutes']='six'
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(Invalid,'malformed'):verify(self.root)
    def test_bad_check_path_in_manifest(self):
        path=self.root/'manifest/cards.json'
        data=json.loads(path.read_text())
        data['cards']['L08-R1']['accept']='check(checks/l08/missing.sh)'
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(Invalid,'missing or non-executable check'):verify(self.root)
    def test_regex_requires_both_predicates(self):
        path=self.root/'manifest/cards.json'
        data=json.loads(path.read_text())
        data['cards']['L08-R1'].update(grading='regex',accept='regex(/^ok$/)',reject=None)
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(Invalid,'regex needs accept and reject'):verify(self.root)
    def test_missing_scheduled_card(self):
        path=self.root/'lessons/lesson-08.md'
        path.write_text(path.read_text().replace('### CARD L08-R1','### CARD L08-OTHER',1))
        with self.assertRaises(Invalid):verify(self.root)

    def test_review_list_omission_is_rejected(self):
        path=self.root/'manifest/schedule.json'
        schedule=json.loads(path.read_text())
        schedule['review_cards'].remove('L04-INT')
        path.write_text(json.dumps(schedule))
        with self.assertRaisesRegex(Invalid,'review_cards differs'):
            verify(self.root)

    def test_plural_source_heading_is_rejected(self):
        path=self.root/'lessons/lesson-05.md'
        path.write_text(path.read_text()+'\nSources: an upstream line citation\n')
        with self.assertRaisesRegex(Invalid,'metadata remains in lesson'):
            verify(self.root)

    def test_verification_note_is_rejected(self):
        path=self.root/'lessons/lesson-02.md'
        path.write_text(path.read_text().replace('The large message occupies three fragments:',
            'Three fragments, observed on the pinned version:',1))
        with self.assertRaisesRegex(Invalid,'metadata remains in lesson'):
            verify(self.root)

    def test_pinned_fixture_note_is_rejected(self):
        path=self.root/'lessons/lesson-03.md'
        path.write_text(path.read_text().replace('Here is the sequence:',
            'The late-join fixture (`checks/l03/late.sh`) shows this on the pinned version:',1))
        with self.assertRaisesRegex(Invalid,'metadata remains in lesson'):
            verify(self.root)

    def test_story_url_is_rejected(self):
        path=self.root/'lessons/lesson-05.md'
        path.write_text(path.read_text()+'\nRead https://example.org/report for this story.\n')
        with self.assertRaisesRegex(Invalid,'metadata remains in lesson'):
            verify(self.root)

    def test_upstream_line_note_is_rejected(self):
        path=self.root/'lessons/lesson-05.md'
        path.write_text(path.read_text()+'\nChecked upstream line 264.\n')
        with self.assertRaisesRegex(Invalid,'metadata remains in lesson'):
            verify(self.root)

    def test_stale_study_is_rejected(self):
        path=self.root/'STUDY.md'
        path.write_text(path.read_text().replace('## Answer keys','## Keys',1))
        with self.assertRaisesRegex(Invalid,'STUDY.md is missing or stale'):
            verify(self.root)

    def test_lesson_edit_without_study_refresh_is_rejected(self):
        path=self.root/'lessons/lesson-02.md'
        path.write_text(path.read_text()+'\nOne more paragraph.\n')
        with self.assertRaisesRegex(Invalid,'STUDY.md is missing or stale'):
            verify(self.root)

    def test_missing_delayed_scaffold_map_is_rejected(self):
        path=self.root/'manifest/schedule.json'
        schedule=json.loads(path.read_text())
        del schedule['delayed_check_scaffolds']
        path.write_text(json.dumps(schedule))
        with self.assertRaisesRegex(Invalid,'delayed_check_scaffolds'):
            verify(self.root)

    def test_partial_delayed_scaffold_map_is_rejected(self):
        path=self.root/'manifest/schedule.json'
        schedule=json.loads(path.read_text())
        del schedule['delayed_check_scaffolds']['L02-R1']
        path.write_text(json.dumps(schedule))
        with self.assertRaisesRegex(Invalid,'missing L02-R1'):
            verify(self.root)

if __name__=='__main__':unittest.main()
