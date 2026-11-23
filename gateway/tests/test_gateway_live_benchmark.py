from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from benchmarks import fixtures
from benchmarks.contracts import digest
from benchmarks import live_github


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

    def api(self, path, method='GET', body=None, read_only=False):
        if method != 'GET':
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

    def qualify(self, github, cancel=None):
        grade = {'qualified': True, 'resolved': True, 'score': 1000, 'status': 'PASSED'}
        with patch('benchmarks.live_github.load_settings', return_value={'sandbox_image': self.image}):
            return live_github.qualify(self.service(github), 'test-owner', self.run, self.task,
                                      self.task['referenceSources'], grade, cancel or threading.Event())

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
