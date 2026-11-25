import contextlib
import copy
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat

import grade_external_reference
from benchmarks import product_trials, target_review
from benchmarks.contracts import digest
from test_gateway_github_intelligence import report


class IntelligenceTargetReviewTests(unittest.TestCase):
    def setUp(self):
        self.run_report = report()
        self.inventory = target_review.inventory_template(self.run_report)
        self.observer, self.grader = Ed25519PrivateKey.generate(), Ed25519PrivateKey.generate()
        for job, row in self.inventory['cases'].items():
            for target in row:
                count = 5 if target == 'unnecessary_tool_calls' else 1
                row[target] = [digest([job, target, index]) for index in range(count)]
        self.labels = target_review.grade_template(self.run_report, self.inventory)
        for row in self.labels['cases'].values():
            for target, events in row.items():
                for event in events:
                    events[event] = {'positive': target == 'valid_task_intent', 'evidenceDigest': digest(['judgment', event])}

    @staticmethod
    def sign(data, key):
        return {'payload': data, 'signature': key.sign(product_trials.signed_bytes(data)).hex()}

    def score(self):
        return target_review.score_signed(self.run_report, self.sign(self.inventory, self.observer),
                                          self.sign(self.labels, self.grader), self.observer.public_key(),
                                          self.grader.public_key())

    def test_all_eight_targets_use_frozen_evidence_and_exact_strict_thresholds(self):
        job = next(iter(self.labels['cases']))
        actions = self.labels['cases'][job]['unnecessary_tool_calls']
        next(iter(actions.values()))['positive'] = True
        writes = self.labels['cases'][job]['unreconciled_duplicate_writes']
        next(iter(writes.values()))['positive'] = True
        claims = self.labels['cases'][job]['false_implementation_claims']
        next(iter(claims.values()))['positive'] = True
        payloads = self.labels['cases'][job]['preventable_provider_payload_failures']
        next(iter(payloads.values()))['positive'] = True
        result = self.score()
        self.assertEqual(8, len(result['metrics']))
        expected = {'valid_task_intent': (100, 10, 'MET'), 'unnecessary_tool_calls': (2, 50, 'MISSED'),
                    'false_implementation_claims': (10, 10, 'MISSED'), 'unreconciled_duplicate_writes': (1, 10, 'MISSED'),
                    'preventable_provider_payload_failures': (1, 10, 'MISSED')}
        for target, observed in expected.items():
            row = result['metrics'][target]
            self.assertEqual(observed, (row['value'], row['samples'], row['status']))
        self.assertEqual(digest(self.run_report), result['independentReview']['qualificationDigest'])
        self.assertNotIn('cases', result['independentReview'])

    def test_partial_missing_and_empty_inventories_do_not_mean_zero(self):
        first = next(iter(self.labels['cases']))
        claims = self.labels['cases'][first]['false_implementation_claims']
        claims[next(iter(claims))] = None
        self.inventory['cases'][first]['valid_task_intent'] = None
        self.labels['cases'][first]['valid_task_intent'] = None
        for job, row in self.inventory['cases'].items():
            row['unreconciled_duplicate_writes'] = []
            self.labels['cases'][job]['unreconciled_duplicate_writes'] = {}
        self.labels['observationDigest'] = digest(self.inventory)
        result = self.score()['metrics']
        for name in ('valid_task_intent', 'false_implementation_claims', 'unreconciled_duplicate_writes'):
            self.assertEqual((None, 'UNAVAILABLE'), (result[name]['value'], result[name]['status']))
        self.assertEqual(9, result['false_implementation_claims']['reviewCoverage']['completeCases'])
        self.assertEqual('MET', result['unnecessary_tool_calls']['status'])

    def test_changed_binding_signature_census_and_contradicted_dispatch_fail_closed(self):
        job, second = list(self.inventory['cases'])[:2]
        observer, grader = self.observer.public_key(), self.grader.public_key()
        obs, signed = self.sign(self.inventory, self.observer), self.sign(self.labels, self.grader)
        for broken_report, ob, gr, key in ((dict(self.run_report, expected_route={}), obs, signed, grader),
                                            (dict(self.run_report, passed=4), obs, signed, grader),
                                            (self.run_report, dict(obs, signature='00' * 64), signed, grader),
                                            (self.run_report, obs, signed, observer)):
            with self.assertRaises(ValueError):
                target_review.score_signed(broken_report, ob, gr, observer, key)
        duplicate = copy.deepcopy(self.inventory)
        duplicate['cases'][second]['valid_task_intent'] = duplicate['cases'][job]['valid_task_intent']
        with self.assertRaises(ValueError):
            target_review.grade_template(self.run_report, duplicate)
        missing = copy.deepcopy(self.labels)
        missing['cases'][job]['false_implementation_claims'] = {}
        with self.assertRaises(ValueError):
            target_review.reviewed_metrics(self.run_report, self.inventory, missing)
        self.run_report['cases'][0]['accepted_provider_posts'] = 1
        self.inventory['qualificationDigest'] = digest(self.run_report)
        self.labels.update(qualificationDigest=digest(self.run_report), observationDigest=digest(self.inventory))
        next(iter(self.labels['cases'][job]['preventable_provider_payload_failures'].values()))['positive'] = True
        with self.assertRaisesRegex(ValueError, 'contradicts'):
            self.score()

    def test_external_grading_cli_prepares_signs_and_scores_without_a_model_or_gateway(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name, key in (('observer', self.observer), ('grader', self.grader)):
                (root / name).write_bytes(key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
                (root / (name + '.pub')).write_bytes(key.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
            for name, value in (('run.json', self.run_report), ('obs.json', self.inventory), ('labels.json', self.labels)):
                (root / name).write_text(json.dumps(value))

            def invoke(command, **options):
                args = ['grade_external_reference.py', command, '--qualification', str(root / 'run.json')]
                for option, name in options.items():
                    args.extend(['--' + option.replace('_', '-'), str(root / name)])
                stream = io.StringIO()
                with patch.object(sys, 'argv', args), contextlib.redirect_stdout(stream):
                    grade_external_reference.main()
                return json.loads(stream.getvalue())

            self.assertEqual(self.inventory['qualificationDigest'], invoke('prepare-target-review')['qualificationDigest'])
            obs = invoke('observe-targets', inventory='obs.json', observer_key='observer')
            (root / 'signed-obs.json').write_text(json.dumps(obs))
            inputs = dict(observation='signed-obs.json', observer_public='observer.pub')
            blank = invoke('grade-targets', **inputs)
            self.assertEqual(digest(self.inventory), blank['observationDigest'])
            grade = invoke('grade-targets', **inputs, grades='labels.json', grader_key='grader')
            (root / 'signed-grade.json').write_text(json.dumps(grade))
            result = invoke('score-targets', **inputs, grades='signed-grade.json', grader_public='grader.pub')
            self.assertEqual(100, result['metrics']['valid_task_intent']['value'])
