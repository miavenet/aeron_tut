#!/usr/bin/env python3
"""Adversarial, offline oracle tests; no port allocation needed."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_BIN = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
JAVAC = str(JAVA_BIN / 'javac') if JAVA_BIN else 'javac'
JAVA = str(JAVA_BIN / 'java') if JAVA_BIN else 'java'


def check(script, workspace):
    proc = subprocess.run([str(ROOT/script)], input=str(workspace)+'\n', text=True,
                          capture_output=True, cwd=ROOT, timeout=45)
    records = [json.loads(line) for line in proc.stdout.splitlines() if line.startswith('{')]
    if not records:
        raise AssertionError(proc.stdout+'\n'+proc.stderr)
    return proc.returncode, records[-1]


class OracleTests(unittest.TestCase):
    def test_l08_solution_starter_and_all_faults(self):
        script = Path('checks/chk_l08_sequencer.sh')
        code, result = check(script, 'labs/l08/solution')
        self.assertEqual((0, 'HIT'), (code, result['status']))
        for path in [ROOT/'labs/l08/starter', *sorted((ROOT/'labs/l08/faults').iterdir())]:
            with self.subTest(path=path.name):
                code, result = check(script, path)
                self.assertEqual((1, 'MISS'), (code, result['status']))
        for name, mode in [('lower-median','performance'), ('mutate-on-reject','policy'),
                           ('unlatched-conflict','policy'), ('stale-pair-quorum','model'),
                           ('placeholder-migration','migration'), ('type-error','adapter'),
                           ('wrong-cached-reply','policy'), ('value-error','policy')]:
            with self.subTest(name=name):
                code, result = check(Path('checks/l08')/(mode+'.sh'), ROOT/'labs/l08/faults'/name)
                self.assertEqual((1, 'MISS'), (code, result['status']))

    def test_l08_value_error_is_structured_miss(self):
        code, result = check(Path('checks/l08/policy.sh'), ROOT/'labs/l08/faults/value-error')
        self.assertEqual((1, 'MISS'), (code, result['status']))
        self.assertIn('Submitted implementation error', result['criteria'][0]['detail'])

    def test_combined_migration_requires_release_model(self):
        code, result = check(Path('checks/l08/migration-and-model.sh'),
                             'labs/l08/faults/stale-pair-quorum')
        self.assertEqual((1, 'MISS'), (code, result['status']))
        code, result = check(Path('checks/l08/migration-and-model.sh'), 'labs/l08/solution')
        self.assertEqual((0, 'HIT'), (code, result['status']))

    def migration_variant(self, mutate):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            shutil.copytree(ROOT/'labs/l08/transfer', tmp/'w')
            path = tmp/'w/migration.json'
            data = json.loads(path.read_text())
            mutate(data)
            path.write_text(json.dumps(data))
            return check(Path('checks/l08/migration-and-model.sh'), tmp/'w')

    def test_migration_rejects_trivial_filler_and_accepts_real_sentence(self):
        def promo(text):
            return lambda d: next(r for r in d['changes'] if r['id'] == 'promotion').update(commit=text)
        for filler in ('a'*15, 'a'*40, 'aaaaaaa aaaaaaa aaaaaaa', 'xyz xyz xyz xyz xyz', 'bcd fgh jkl mnp qrs',
                       '1234567890123456', 'same same same same same'):
            code, result = self.migration_variant(promo(filler))
            self.assertEqual((1, 'MISS'), (code, result['status']), filler)
        code, result = self.migration_variant(promo('Release only after a quorum of synced copies at the active epoch.'))
        self.assertEqual((0, 'HIT'), (code, result['status']))
        # a real-looking sentence that never names the gate is still refused on the promotion row
        code, result = self.migration_variant(promo('Release happens whenever the operator feels ready today.'))
        self.assertEqual((1, 'MISS'), (code, result['status']))

    def test_migration_rejects_filler_in_every_cell_and_identical_cells(self):
        def fill(d):
            for r in d['changes']:
                for k in ('current', 'acceptance', 'commit', 'ack', 'replay_source', 'failure_domain'):
                    r[k] = 'aaaaaaaaaaaaaaa'
        code, result = self.migration_variant(fill)
        self.assertEqual((1, 'MISS'), (code, result['status']))
        def same(d):
            r = d['changes'][0]
            for k in ('ack', 'replay_source'):
                r[k] = 'Return only a provisional transport result until the gate.'
        code, result = self.migration_variant(same)
        self.assertEqual((1, 'MISS'), (code, result['status']))

    def test_client_inconsistent_reply_mutant_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            for name in ('ClientWork.java','RequestWork.java'):
                shutil.copy2(ROOT/'labs/l06/solution'/name, tmp/name)
            source = (tmp/'ClientWork.java').read_text()
            fixture = ROOT/'labs/l06/fixture/PolicyProbe.java'
            def run():
                compile = subprocess.run([JAVAC,'-d',str(tmp),str(tmp/'ClientWork.java'),
                                          str(tmp/'RequestWork.java'),str(fixture)],
                                         capture_output=True,text=True,timeout=20)
                self.assertEqual(0,compile.returncode,compile.stderr)
                return subprocess.run([JAVA,'-cp',str(tmp),'PolicyProbe','client'],
                                      capture_output=True,text=True,timeout=20)
            self.assertEqual(0,run().returncode)
            (tmp/'ClientWork.java').write_text(source.replace(
                'if(previous!=null && !previous.equals(result))throw new IllegalArgumentException("inconsistent cached reply");',
                '/* inconsistent duplicate is wrongly accepted */'))
            wrong = run()
            self.assertNotEqual(0,wrong.returncode)
            self.assertIn('inconsistent duplicate reply accepted',wrong.stderr)

    def test_l05_policy_rejects_wrong_success_threshold(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            source = (ROOT/'labs/l05/solution/StateWork.java').read_text()
            fixture = ROOT/'labs/l05/fixture/OpsProbe.java'
            def run():
                compile = subprocess.run([JAVAC,'-d',str(tmp),str(tmp/'StateWork.java'),str(fixture)],
                                         capture_output=True,text=True,timeout=20)
                self.assertEqual(0,compile.returncode,compile.stderr)
                return subprocess.run([JAVA,'-cp',str(tmp),'OpsProbe','policy'],
                                      capture_output=True,text=True,timeout=20)
            (tmp/'StateWork.java').write_text(source)
            self.assertEqual(0,run().returncode)
            (tmp/'StateWork.java').write_text(source.replace('return result > 0;', 'return result > 1;'))
            wrong = run()
            self.assertNotEqual(0,wrong.returncode)
            self.assertIn('positive/mock egress success',wrong.stderr)

if __name__ == '__main__':
    unittest.main()
