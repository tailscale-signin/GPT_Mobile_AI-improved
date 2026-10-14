"""Fail-closed checks for the v2 evidence ledger and release admission."""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))

import check_unified_reliability_v2 as gate  # noqa: E402


class ReliabilityEvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.scope = self.root / 'docs/implementation/unified-reliability-v2'
        (self.scope / 'evidence').mkdir(parents=True)
        self.tasks = [dict(id=f'V{i:02}', dependencies=[], acceptance_ids=[],
                           status='NOT_STARTED', implementation_commits=[],
                           evidence=[], remaining_work=[]) for i in range(1, 49)]
        self.cases = [dict(id=f'{prefix}{i:02}', outcome='NOT_RUN', commit=None,
                           evidence=[]) for prefix, count in
                      [('LA-QA', 36), ('LC-T', 18), ('CM-T', 26), ('IX', 30)]
                      for i in range(1, count + 1)]
        self.root_patch = patch.object(gate, 'ROOT', self.root)
        self.scope_patch = patch.object(gate, 'SCOPE', self.scope)
        self.root_patch.start()
        self.scope_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.addCleanup(self.scope_patch.stop)
        self.save()

    def save(self):
        (self.scope / 'tasks.json').write_text(json.dumps(self.tasks))
        (self.scope / 'acceptance-tests.json').write_text(json.dumps(self.cases))

    def case(self, case_id='IX01'):
        return next(case for case in self.cases if case['id'] == case_id)

    def passing_fixture(self):
        case = self.case()
        case.update(outcome='PASS', commit='a' * 40, evidence=['evidence/proof.json'])
        receipt = {'commit': 'a' * 40,
                   'acceptance_fixtures': {'IX01': [{'test': 'fixture', 'outcome': 'PASS'}]}}
        (self.scope / 'evidence/proof.json').write_text(json.dumps(receipt))
        self.save()
        return receipt

    def test_incomplete_scope_is_valid_but_cannot_publish(self):
        self.assertEqual((48, 110), gate.validate())
        with self.assertRaisesRegex(ValueError, 'Cannot claim full v2 completion'):
            gate.validate(require_done=True)

    def test_real_matching_fixture_receipt_is_accepted(self):
        self.passing_fixture()
        self.assertEqual((48, 110), gate.validate())

    def test_pass_cannot_refer_to_nonexistent_file(self):
        self.passing_fixture()
        (self.scope / 'evidence/proof.json').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing evidence file'):
            gate.validate()

    def test_pass_cannot_claim_other_commit_or_test_result(self):
        receipt = self.passing_fixture()
        receipt['commit'] = 'b' * 40
        path = self.scope / 'evidence/proof.json'
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, 'receipt commit does not match'):
            gate.validate()
        receipt['commit'] = 'a' * 40
        receipt['acceptance_fixtures']['IX01'][0]['outcome'] = 'FAIL'
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, 'no passing case evidence'):
            gate.validate()

    def test_unsafe_evidence_reference_is_rejected(self):
        self.passing_fixture()
        self.case()['evidence'] = ['../outside.json']
        self.save()
        with self.assertRaisesRegex(ValueError, 'Unsafe evidence reference'):
            gate.validate()

    def test_done_task_requires_prerequisites_all_cases_and_no_remaining_work(self):
        task = self.tasks[0]
        task.update(status='DONE', implementation_commits=['a' * 40],
                    acceptance_ids=['IX01'], evidence=['BASELINE.json'],
                    remaining_work=['Complete review'])
        (self.scope / 'BASELINE.json').write_text('{}')
        self.save()
        with self.assertRaisesRegex(ValueError, 'unfinished work remains'):
            gate.validate()
        task['remaining_work'] = []
        self.save()
        with self.assertRaisesRegex(ValueError, 'required acceptance case not passed'):
            gate.validate()
        self.passing_fixture()
        self.assertEqual((48, 110), gate.validate())
        task['dependencies'] = ['V02']
        self.save()
        with self.assertRaisesRegex(ValueError, 'unfinished prerequisite task'):
            gate.validate()

    def test_a_fake_non_hex_commit_cannot_satisfy_pass(self):
        self.passing_fixture()
        self.case()['commit'] = 'not-a-git-sha'
        self.save()
        with self.assertRaisesRegex(ValueError, 'valid commit hash'):
            gate.validate()


if __name__ == '__main__':
    unittest.main()
