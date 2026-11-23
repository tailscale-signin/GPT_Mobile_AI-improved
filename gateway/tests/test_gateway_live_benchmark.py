from contextlib import contextmanager
from io import BytesIO
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import requests

from benchmarks import fixtures
from benchmarks.contracts import digest
from benchmarks import live_github
from v14.engineering_backend import GitHubEngineeringBackend
from v14.execution_binding import ExecutionBindingError
from v14.github_api_governor import GitHubApiGovernor


class NotFound(Exception):
    response = SimpleNamespace(status_code=404)


class Store:
    def __init__(self, run):
        self.run = run

    def owner(self, _ident):
        return 'test-owner'

    def get(self, _ident, _principal=None, cases=False):
        return self.run

    def update(self, _ident, **values):
        self.run.update(values)
        return self.run


class GitHub:
    def __init__(self, *, lost_ref=False, existing_ref=False, conclusion='success', cancel_on_read=None,
                 running=False, lost_cancel=False, cancellation_settles=True, actions_visible=True):
        self.lost_ref, self.conclusion = lost_ref, conclusion
        self.refs = {'existing': True} if existing_ref else {}
        self.writes, self.tree, self.branch, self.workflow = [], [], None, None
        self.cancel_on_read, self.running, self.lost_cancel = cancel_on_read, running, lost_cancel
        self.cancellation_settles, self.actions_visible = cancellation_settles, actions_visible

    def action(self):
        workflow = next(row['path'] for row in self.tree if row['path'].startswith('.github/workflows/'))
        return {'id': 41, 'head_sha': 'd' * 40, 'path': workflow, 'head_branch': self.branch, 'event': 'push',
                'status': 'in_progress' if self.running else 'completed', 'conclusion': None if self.running else self.conclusion,
                'run_attempt': 1}

    def api(self, path, method='GET', body=None, read_only=False, expected_actor=None, before_dispatch=None):
        if expected_actor is not None and expected_actor != 7:
            raise PermissionError('GITHUB_ACCOUNT_CHANGED')
        if method != 'GET':
            if before_dispatch is not None:
                before_dispatch()
            self.writes.append((path, method, body))
            if path.endswith('/git/blobs'):
                if body['content'].startswith('name: Immutable benchmark'):
                    self.workflow = body['content']
                return {'sha': digest(body)[:40]}
            if path.endswith('/git/trees'):
                self.tree = body['tree']
                return {'sha': 'c' * 40}
            if path.endswith('/git/commits'):
                return {'sha': 'd' * 40}
            if path.endswith('/git/refs'):
                self.branch = body['ref'].removeprefix('refs/heads/')
                self.refs[self.branch] = body['sha']
                if self.lost_ref:
                    raise ConnectionError('Response lost after accepted ref creation')
                return {'object': {'sha': body['sha']}}
            if path.endswith('/actions/runs/41/cancel'):
                if self.cancellation_settles:
                    self.running, self.conclusion = False, 'cancelled'
                if self.lost_cancel:
                    raise ConnectionError('Cancel response lost')
                return {}
            raise AssertionError('Unallowed mutation')
        if path == '/user':
            return {'id': 7}
        if path == '/repos/fixture/disposable':
            return {'full_name': 'fixture/disposable', 'permissions': {'push': True}}
        if '/git/commits/' in path:
            return {'sha': 'a' * 40, 'tree': {'sha': 'b' * 40}}
        if '/git/ref/heads/' in path:
            branch = path.split('/git/ref/heads/')[1]
            if self.refs.get('existing'):
                return {'object': {'sha': 'e' * 40}}
            if branch not in self.refs:
                raise NotFound()
            return {'object': {'sha': self.refs[branch]}}
        if '/git/trees/' in path:
            return {'tree': self.tree, 'truncated': False}
        if '/actions/runs?' in path:
            if self.cancel_on_read is not None:
                self.cancel_on_read.set()
            return {'total_count': 1 if self.actions_visible else 0, 'workflow_runs': [self.action()] if self.actions_visible else []}
        if path.endswith('/actions/runs/41'):
            return self.action()
        if '/actions/runs/41/jobs?' in path:
            return {'total_count': 1, 'jobs': [{'id': 42, 'name': 'Independent coding verification',
                    'conclusion': 'success', 'steps': [{'name': 'Verify immutable artifact', 'conclusion': 'success'}]}]}
        raise AssertionError('Unrecognised read: ' + path)


class LiveBenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.task = fixtures.suite('quick', 17)[0]
        self.image = 'python@sha256:' + 'f' * 64
        self.run = {'id': 'bench_' + '1' * 32, 'createdAt': 1_795_000_000,
                    'state': 'RUNNING', 'request': {'repository': 'fixture/disposable',
                    'base_sha': 'a' * 40, 'cohort': 'comparison-cohort',
                    'proposal_digest': 'f' * 64, 'generation_receipt': {'model': 'selected'}}}

    def service(self, github):
        return SimpleNamespace(root=Path('/unused'), store=Store(self.run),
                               engineering=SimpleNamespace(backend=github, authorized=lambda job: job['principal'] == 'test-owner'))

    def qualify(self, github, cancel=None, service=None):
        grade = {'qualified': True, 'resolved': True, 'score': 1000, 'status': 'PASSED'}
        with patch('benchmarks.live_github.load_settings', return_value={'sandbox_image': self.image}):
            return live_github.qualify(service or self.service(github), 'test-owner', self.run, self.task,
                                      self.task['referenceSources'], grade, cancel or threading.Event())

    @contextmanager
    def actual_backend(self, remote):
        """Run the real credential, account, admission and HTTP adapter code."""
        original, rotated = 'synthetic-original-token', 'synthetic-rotated-token'
        self.same_actor_token = 'synthetic-renewed-same-actor-token'
        calls = []
        def exchange(method, url, *, headers, **kwargs):
            path = url.removeprefix('https://api.github.com')
            token = headers['Authorization'].removeprefix('Bearer ')
            self.assertIn(token, (original, rotated, self.same_actor_token))
            actor = 9 if token == rotated else 7
            calls.append({'method': method, 'path': path, 'actor': actor,
                          'credential': 'original' if token == original else 'other' if token == rotated else 'renewed'})
            response = requests.Response()
            response.url, response.status_code = url, 200
            try:
                value = {'id': actor} if path == '/user' else remote.api(path, method, kwargs.get('json'), read_only=method == 'GET')
            except NotFound:
                response.status_code, value = 404, {'message': 'Not Found'}
            response.raw = BytesIO(json.dumps(value).encode())
            return response
        backend = GitHubEngineeringBackend(SimpleNamespace())
        backend.github_governor = GitHubApiGovernor()
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_TOKEN': original,
                                    'GATEWAY_ENGINEERING_GITHUB_SERVER': '', 'GATEWAY_DESKTOP_MANAGED': '0'}), \
                patch('v14.engineering_backend.requests.request', side_effect=exchange), \
                patch('v14.engineering_backend.requests.get', side_effect=lambda url, **kwargs: exchange('GET', url, **kwargs)):
            yield backend, calls, original, rotated

    def retained_remote(self, backend, remote):
        receipt = {'schema': live_github.SCHEMA, 'repository': self.run['request']['repository'],
                   'baseRevision': self.run['request']['base_sha'], 'publishedRevision': 'd' * 40,
                   'actorId': 7, 'branch': 'benchmark/comparison-cohor/' + self.run['id'],
                   'workflowPath': '.github/workflows/benchmark-' + self.run['id'] + '.yml',
                   'mutations': [{'path': '/repos/fixture/disposable/git/refs', 'state': 'COMPLETED'}]}
        self.run['liveGitHubReceipt'] = receipt
        remote.branch = receipt['branch']
        remote.tree = [{'path': receipt['workflowPath']}]
        return self.service(backend)

    def test_actual_http_backend_qualifies_the_same_actor_and_exact_revision(self):
        with self.actual_backend(GitHub()) as (backend, calls, _original, _rotated):
            result = self.qualify(backend)
        self.assertTrue(result['qualified'])
        self.assertTrue(result['liveGitHubReceipt']['remoteReadbackVerified'])
        self.assertEqual(7, result['liveGitHubReceipt']['actorId'])
        self.assertTrue(calls)
        self.assertTrue(all(row['actor'] == 7 for row in calls))
        self.assertEqual(1, sum(row['method'] == 'POST' and row['path'].endswith('/git/refs') for row in calls))

    def test_publication_rotation_after_fence_keeps_captured_account_and_requires_readback(self):
        with self.actual_backend(GitHub()) as (backend, calls, original, rotated):
            service = self.service(backend)
            update = service.store.update
            def rotate(ident, **values):
                saved = update(ident, **values)
                receipt = values.get('liveGitHubReceipt') or {}
                if receipt.get('stage') == 'MUTATION_DISPATCHED' and receipt['mutations'][-1]['path'].endswith('/git/refs'):
                    os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = rotated
                return saved
            with patch.object(service.store, 'update', side_effect=rotate), self.assertRaises(live_github.LiveFixtureInterrupted) as caught:
                self.qualify(backend, service=service)
            writes = [row for row in calls if row['method'] == 'POST' and row['path'].endswith('/git/refs')]
            self.assertEqual([{'method': 'POST', 'path': '/repos/fixture/disposable/git/refs', 'actor': 7, 'credential': 'original'}], writes)
            self.assertEqual('unknown', caught.exception.cancellation['outcome'])
            self.assertFalse(self.run['liveGitHubReceipt']['remoteReadbackVerified'])
            os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = original
            self.assertEqual('terminal', live_github.reconcile_interrupted(service, 'test-owner', self.run)[0]['outcome'])
            self.assertEqual(1, sum(row['method'] == 'POST' and row['path'].endswith('/git/refs') for row in calls))

    def test_cancel_rotation_at_fence_uses_captured_account_and_never_replays_lost_response(self):
        remote = GitHub(running=True, lost_cancel=True)
        with self.actual_backend(remote) as (backend, calls, original, rotated):
            service = self.retained_remote(backend, remote)
            update = service.store.update
            def rotate(ident, **values):
                saved = update(ident, **values)
                if (values.get('liveGitHubReceipt') or {}).get('cancellationMutation'):
                    os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = rotated
                return saved
            with patch.object(service.store, 'update', side_effect=rotate):
                result = live_github.reconcile_interrupted(service, 'test-owner', self.run)
            self.assertEqual('unknown', result[0]['outcome'])
            self.assertEqual({'workflowRunId': 41, 'state': 'DISPATCHED'}, self.run['liveGitHubReceipt']['cancellationMutation'])
            writes = [row for row in calls if row['method'] == 'POST']
            self.assertEqual([{'method': 'POST', 'path': '/repos/fixture/disposable/actions/runs/41/cancel',
                               'actor': 7, 'credential': 'original'}], writes)
            os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = original
            self.assertEqual('terminal', live_github.reconcile_interrupted(service, 'test-owner', self.run)[0]['outcome'])
            self.assertEqual(writes, [row for row in calls if row['method'] == 'POST'])

    def test_changed_actor_denies_reads_mutations_and_cancel_before_the_dispatch_fence(self):
        remote = GitHub(running=True)
        with self.actual_backend(remote) as (backend, calls, original, rotated):
            os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = rotated
            dispatch = Mock()
            for method, path in (('GET', '/repos/fixture/disposable'), ('POST', '/repos/fixture/disposable/git/blobs')):
                with self.subTest(method=method), self.assertRaisesRegex(PermissionError, 'GITHUB_ACCOUNT_CHANGED'):
                    backend.api(path, method, {} if method == 'POST' else None, read_only=method == 'GET',
                                expected_actor=7, before_dispatch=dispatch if method == 'POST' else None)
            dispatch.assert_not_called()
            self.assertTrue(all(row['path'] == '/user' for row in calls))
            service = self.retained_remote(backend, remote)
            self.assertEqual('unknown', live_github.reconcile_interrupted(service, 'test-owner', self.run)[0]['outcome'])
            self.assertNotIn('cancellationMutation', self.run['liveGitHubReceipt'])
            self.assertFalse(any(row['method'] == 'POST' for row in calls))
            os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = original
            self.assertEqual('confirmed', live_github.reconcile_interrupted(service, 'test-owner', self.run)[0]['outcome'])
            self.assertEqual(1, sum(row['method'] == 'POST' for row in calls))

    def test_same_actor_token_rotation_at_fence_remains_qualified(self):
        with self.actual_backend(GitHub()) as (backend, calls, _original, _rotated):
            service = self.service(backend)
            update = service.store.update
            def renew(ident, **values):
                saved = update(ident, **values)
                receipt = values.get('liveGitHubReceipt') or {}
                if receipt.get('stage') == 'MUTATION_DISPATCHED' and receipt['mutations'][-1]['path'].endswith('/git/refs'):
                    os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = self.same_actor_token
                return saved
            with patch.object(service.store, 'update', side_effect=renew):
                result = self.qualify(backend, service=service)
            self.assertTrue(result['qualified'])
            self.assertTrue(result['liveGitHubReceipt']['remoteReadbackVerified'])
            self.assertTrue(all(row['actor'] == 7 for row in calls))
            self.assertEqual(['original'], [row['credential'] for row in calls if row['method'] == 'POST' and row['path'].endswith('/git/refs')])
            self.assertTrue(any(row['credential'] == 'renewed' for row in calls))

    def test_expected_actor_cannot_override_engineering_identity_or_its_mutation_fence(self):
        with self.actual_backend(GitHub()) as (backend, calls, original, rotated):
            job = {'id': 'owned-job', 'state': 'PLAN', 'deadline': time.time() + 60,
                   'request': {'repository': 'fixture/disposable', 'task_scope': 'implement'}, 'data': {'actor_id': 7}}
            path = '/repos/fixture/disposable/git/blobs'
            with self.assertRaisesRegex(PermissionError, 'GITHUB_ACCOUNT_CHANGED'):
                backend.api(path, 'POST', {}, job=job, expected_actor=9)
            self.assertEqual([], calls)
            dispatch = Mock()
            with self.assertRaises(ValueError):
                backend.api(path, 'POST', {}, job=job, expected_actor=7, before_dispatch=dispatch)
            dispatch.assert_not_called()
            self.assertEqual([], calls)
            os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = rotated
            with self.assertRaisesRegex(ValueError, 'GitHub account changed before dispatch'):
                backend.api(path, 'POST', {}, job=job)
            os.environ['GATEWAY_ENGINEERING_GITHUB_TOKEN'] = original
            with self.assertRaises(ExecutionBindingError):
                backend.api(path, 'POST', {}, job=job, expected_actor=7)
            self.assertFalse(any(row['method'] == 'POST' for row in calls))

    def test_dispatch_callback_failure_never_sends_or_repeats_the_mutation(self):
        with self.actual_backend(GitHub()) as (backend, calls, _original, _rotated):
            dispatch = Mock(side_effect=InterruptedError('Stopped at the final authority fence'))
            with self.assertRaises(InterruptedError):
                backend.api('/repos/fixture/disposable/git/blobs', 'POST', {}, expected_actor=7, before_dispatch=dispatch)
            dispatch.assert_called_once_with()
            self.assertFalse(any(row['method'] == 'POST' for row in calls))

    def test_exact_commit_and_required_actions_step_are_read_back(self):
        github = GitHub()
        result = self.qualify(github)
        receipt = result['liveGitHubReceipt']
        self.assertTrue(result['resolved'])
        self.assertTrue(receipt['remoteReadbackVerified'])
        self.assertEqual(receipt['publishedRevision'], receipt['testedRevision'])
        self.assertEqual('VERIFIED', receipt['stage'])
        self.assertTrue(all(method == 'POST' for _, method, _ in github.writes))
        self.assertFalse(any('/pulls' in path or '/releases' in path for path, _, _ in github.writes))
        self.assertTrue(github.branch.startswith('benchmark/'))

    def test_uncertain_ref_creation_is_read_back_without_replaying(self):
        github = GitHub(lost_ref=True)
        result = self.qualify(github)
        self.assertTrue(result['resolved'])
        self.assertTrue(result['liveGitHubReceipt']['lostResponseReconciled'])
        self.assertFalse(result['liveGitHubReceipt']['replayPerformed'])
        self.assertEqual(1, sum(path.endswith('/git/refs') for path, _, _ in github.writes))

    def test_existing_branch_cannot_be_moved_or_claimed(self):
        github = GitHub(existing_ref=True)
        result = self.qualify(github)
        self.assertFalse(result['resolved'])
        self.assertEqual('LIVE_FIXTURE_BRANCH_ALREADY_EXISTS', result['error'])
        self.assertFalse(any(path.endswith('/git/refs') for path, _, _ in github.writes))

    def test_red_actions_never_qualify_a_locally_correct_candidate(self):
        result = self.qualify(GitHub(conclusion='failure'))
        self.assertFalse(result['resolved'])
        self.assertFalse(result['qualified'])
        self.assertIsNone(result['score'])
        self.assertEqual('LIVE_FIXTURE_ACTIONS_NOT_GREEN', result['error'])

    def test_cancel_before_submission_dispatches_nothing(self):
        github = GitHub()
        cancel = threading.Event()
        cancel.set()
        with self.assertRaises(InterruptedError):
            self.qualify(github, cancel)
        self.assertEqual([], github.writes)

    def test_lost_cancel_response_uses_exact_run_readback_without_second_cancel(self):
        cancel = threading.Event()
        github = GitHub(cancel_on_read=cancel, running=True, lost_cancel=True)
        with self.assertRaises(live_github.LiveFixtureInterrupted) as caught:
            self.qualify(github, cancel)
        self.assertEqual('confirmed', caught.exception.cancellation['outcome'])
        self.assertEqual(41, caught.exception.cancellation['workflowRunId'])
        self.assertEqual(1, sum(path.endswith('/cancel') for path, _, _ in github.writes))
        readback = live_github.reconcile_interrupted(self.service(github), 'test-owner', self.run)
        self.assertEqual('terminal', readback[0]['outcome'])
        self.assertEqual(1, sum(path.endswith('/cancel') for path, _, _ in github.writes))

    def test_unsettled_remote_execution_retains_cleanup_obligation_without_replaying(self):
        cancel = threading.Event()
        github = GitHub(cancel_on_read=cancel, running=True, cancellation_settles=False)
        with patch('benchmarks.live_github.time.sleep'), self.assertRaises(live_github.LiveFixtureInterrupted) as caught:
            self.qualify(github, cancel)
        self.assertEqual('unknown', caught.exception.cancellation['outcome'])
        with patch('benchmarks.live_github.time.sleep'):
            readback = live_github.reconcile_interrupted(self.service(github), 'test-owner', self.run)
        self.assertEqual('unknown', readback[0]['outcome'])
        self.assertEqual(1, sum(path.endswith('/cancel') for path, _, _ in github.writes))

    def test_absent_actions_run_after_ref_creation_is_not_shutdown_proof(self):
        cancel = threading.Event()
        github = GitHub(cancel_on_read=cancel, running=True, actions_visible=False)
        with self.assertRaises(live_github.LiveFixtureInterrupted) as caught:
            self.qualify(github, cancel)
        self.assertEqual('unknown', caught.exception.cancellation['outcome'])
        self.assertFalse(any(path.endswith('/cancel') for path, _, _ in github.writes))

    def test_remote_driver_executes_real_behavior_and_rejects_the_broken_candidate(self):
        for sources, succeeds in ((self.task['referenceSources'], True), (self.task['sources'], False)):
            files, workflow = live_github.fixture_bundle(self.task, sources, self.image, self.run['id'])
            self.assertIn('--network=none', files[workflow])
            self.assertIn('persist-credentials: false', files[workflow])
            self.assertNotIn('pull_request_target', files[workflow])
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for path, content in files.items():
                    target = root / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text(content, encoding='utf-8')
                driver = root / '.benchmark-fixtures' / self.run['id'] / 'verify.py'
                result = subprocess.run([sys.executable, '-I', '-B', str(driver)], capture_output=True, text=True, timeout=20)
                self.assertEqual(succeeds, result.returncode == 0, result.stderr)
                if succeeds:
                    self.assertIn(self.task['taskDigest'], result.stdout)


if __name__ == '__main__':
    unittest.main()
