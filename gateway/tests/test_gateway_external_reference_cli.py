"""Exercise the external-reference operator workflow against the existing Gateway API."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import threading
import unittest
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor

import test_gateway_benchmark_api_v2 as benchmark_api
from benchmarks.contracts import content_digest, digest
from benchmarks import product_trials
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat
from desktop.conversation_client import ClientError, scope_key
from grade_external_reference import grade, main, prepare, public_task


class ReferenceCliTests(unittest.TestCase):
    def setUp(self):
        fixture = benchmark_api.BenchmarkApiV2Tests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.service = fixture.service
        self.service.engineering.store.conversations.gateway_id = lambda: 'gateway'
        self.owner, self.posts, self.response = 'alice', 0, 'normal'
        class LocalClient:
            def identity(inner):
                return {'gateway_id': 'gateway', 'principal_id': self.owner}

            def request(inner, method, path, *, params=None, payload=None):
                headers = {'x-test-owner': self.owner, 'X-Gateway-Installation-ID': 'gateway', 'X-Gateway-Principal-ID': self.owner}
                if method == 'POST':
                    self.posts += 1
                    if self.response == 'undelivered':
                        raise ClientError('Network unavailable')
                reply = fixture.client.request(method, path, params=params, json=payload, headers=headers)
                if method == 'POST' and self.response == 'lost':
                    raise ClientError('Response unavailable')
                self.assertEqual(200, reply.status_code, reply.text)
                return reply.json()
        self.client = LocalClient()
        self.trial = Path(fixture.directory.name) / 'trial.sqlite'
        self.task = public_task(self.client, 'pagination-1', 17, 'python')
        self.task['gatewayOwnerDigest'] = digest(scope_key(self.client.identity()))
        self.proposal = 'malformed completed answer\r\n'
        self.ident = prepare(self.trial, self.task, 'ChatGPT', self.proposal)

    def test_lost_admission_reply_reconciles_actual_grader_outcome_without_provenance(self):
        self.response = 'lost'
        queued = grade(self.client, self.trial, self.proposal)
        self.assertEqual('OBSERVED', queued['submissionState'])
        self.service.run_one(queued['runId'])
        report = grade(self.client, self.trial)
        self.assertEqual('COMPLETED', report['state'])
        self.assertTrue(report['outcomeComplete'])
        self.assertEqual(0, report['resolved'])
        self.assertFalse(report['providerIdentityVerified'])
        self.assertEqual(1, self.posts)
        self.assertEqual(queued['proposal_digest'], report['proposal_digest'])
        self.assertEqual('ONE_EXECUTABLE_FIXTURE', report['scope'])
        run = self.service.store.get(queued['runId'], 'alice')
        receipt = run['cases'][0]['generationReceipt']
        self.assertFalse(receipt['identity_verified'])
        self.assertIsNone(run['cases'][0]['observedModel'])
        self.assertEqual('EXECUTABLE', run['request']['evidence_lane'])

    def test_unobserved_attempt_remains_read_only_on_restart(self):
        self.response = 'undelivered'
        with self.assertRaisesRegex(ValueError, 'unconfirmed'):
            grade(self.client, self.trial, self.proposal)
        self.response = 'normal'
        result = grade(self.client, self.trial, self.proposal)
        self.assertEqual('NOT_OBSERVED', result['submissionState'])
        self.assertTrue(result['submissionAttemptRecorded'])
        self.assertEqual(1, self.posts)
        with self.assertRaises(FileExistsError):
            prepare(self.trial, self.task, 'ChatGPT', self.proposal)

    def test_frozen_input_owner_and_fixture_are_checked_before_admission(self):
        with self.assertRaisesRegex(ValueError, 'candidate differs'):
            grade(self.client, self.trial, self.proposal.replace('\r\n', '\n'))
        self.owner = 'bob'
        with self.assertRaisesRegex(ValueError, 'original Gateway'):
            grade(self.client, self.trial, self.proposal)
        self.owner = 'alice'
        tampered = self.trial.with_name('edited.sqlite')
        prepare(tampered, dict(self.task, prompt='Another prompt'), 'Codex', self.proposal)
        with self.assertRaisesRegex(ValueError, 'public task'):
            grade(self.client, tampered, self.proposal)
        self.assertEqual(0, self.posts)

    def test_cli_grades_both_operator_labels_against_the_same_public_input(self):
        root = str(self.trial.parent)
        task_file = self.trial.with_name('task.json')
        candidate = self.trial.with_name('answer.json')
        candidate.write_bytes(self.proposal.encode())
        def cli(*args):
            output = io.StringIO()
            with patch('grade_external_reference.Client', return_value=self.client), patch('sys.argv', ['grade', *args]), redirect_stdout(output):
                main()
            return output.getvalue()
        cli('task', '--root', root, '--id', 'pagination-1', '--output', str(task_file))
        self.assertEqual(self.task['prompt'], json.loads(task_file.read_bytes())['prompt'])
        results = []
        for source in ('ChatGPT', 'Codex', 'Claude Code', 'GPT Mobile AI'):
            trial = str(self.trial.with_name(source + '.sqlite'))
            cli('prepare', '--source', source, '--candidate', str(candidate), '--task', str(task_file), '--trial', trial)
            cli('submit', '--root', root, '--trial', trial, '--candidate', str(candidate))
            report = json.loads(cli('status', '--root', root, '--trial', trial))
            self.assertEqual(source + ' (unverified external reference)', report['generationSource'])
            self.assertEqual(content_digest(self.proposal), report['proposal_digest'])
            results.append(report)
        self.assertNotEqual(results[0]['runId'], results[1]['runId'])
        for key in ('fixture_digest', 'grader_digest', 'environment_digest'):
            self.assertEqual(results[0][key], results[1][key])

    def test_native_cli_blinds_grade_and_incomplete_cohort_cannot_claim_superiority(self):
        directory = self.trial.parent
        def save(name, value):
            p = directory / name
            p.write_text(json.dumps(value))
            return str(p)
        def invoke(*args):
            output = io.StringIO()
            with patch('grade_external_reference.Client', return_value=self.client), patch('sys.argv', ['grade', *args]), redirect_stdout(output):
                main()
            return json.loads(output.getvalue())
        def pem(name):
            private = Ed25519PrivateKey.generate()
            priv, pub = directory / (name + '.pem'), directory / (name + '.pub')
            priv.write_bytes(private.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
            pub.write_bytes(private.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
            return str(priv), str(pub)
        observer, pub = pem('observer')
        grader, grader_pub = pem('grader')
        case = {k: self.task[k] for k in ('taskDigest', 'graderDigest', 'environmentDigest')}
        definition = dict(orderSeed=digest('order'), versions={p: 'v1' for p in product_trials.PRODUCTS},
                          controlsDigest=digest('controls'), cache='cold', marginPoints=5, repeats=1,
                          maxSeconds=1200, maxCostUsd=1, cases=[dict(case, family='pagination')])
        plan = invoke('freeze-native', '--observer-key', observer, '--definition', save('definition', definition))
        path = save('plan', plan)
        schedule = invoke('schedule-native', '--observer-public', pub, '--plan', path)
        index, slot = next((i, v) for i, v in enumerate(schedule) if v['product'] == 'Claude Code')
        row = dict(slot, scheduleIndex=index, planDigest=digest(plan['payload']), controlsDigest=definition['controlsDigest'],
                   state='DELIVERED', seconds=1, costUsd=.01, cache='cold', interventions=0, version='v1')
        capture = dict(type='result', subtype='success', is_error=False, session_id='s', result=self.proposal)
        observation = invoke('observe-native', '--observer-key', observer, '--plan', path, '--measurements', save('row', row),
                             '--capture', save('capture', capture), '--candidate-out', str(directory / 'candidate'))
        self.assertEqual(self.proposal.encode(), (directory / 'candidate').read_bytes())
        # Grade the same artifact with a fresh executable task/admission.
        trial = directory / 'claude-trial.sqlite'
        prepare(trial, self.task, 'Claude Code', self.proposal)
        queued = grade(self.client, trial, self.proposal)
        self.service.run_one(queued['runId'])
        judged = invoke('grade-native', '--root', str(directory), '--trial', str(trial), '--observer-public', pub,
                        '--plan', path, '--observation', save('observation', observation), '--grader-key', grader)
        self.assertEqual('FAIL', judged['payload']['result'])
        self.assertNotIn('product', judged['payload'])
        results = invoke('compare-native', '--plan', path, '--observer-public', pub, '--grader-public', grader_pub,
                         '--observations', save('observations', [observation]), '--grades', save('grades', [judged]))
        self.assertEqual('INCOMPLETE', results['verdict'])

    def test_concurrent_submissions_share_one_admission(self):
        barrier = threading.Barrier(2)
        def submit():
            barrier.wait(timeout=5)
            return grade(self.client, self.trial, self.proposal)
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = [executor.submit(submit) for _ in range(2)]
            self.assertEqual({self.ident}, {future.result()['request_id'] for future in results})
        self.assertEqual(1, self.posts)


if __name__ == '__main__':
    unittest.main()
