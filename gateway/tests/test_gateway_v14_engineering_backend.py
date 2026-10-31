import copy
import json
import sys
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering import EngineeringSupervisor
from v14.engineering_backend import GitHubEngineeringBackend, prepare_changes


class EngineeringBackendTests(unittest.TestCase):
    def setUp(self):
        self.runtime = SimpleNamespace(LLAMA_BASE='http://localhost:8080')
        self.backend = GitHubEngineeringBackend(self.runtime)
        self.backend.identity = lambda job: {'id': 1, 'login': 'owner'}
        self.job = {'id': 'eng_test', 'principal': 'owner', 'revision': 1, 'state': 'CI', 'deadline': time.time() + 1000,
                    'created_at': 1000, 'request': {'repository': 'owner/repo', 'branch': 'feature/job', 'base': 'main', 'model': 'local', 'objective': 'Fix tests'},
                    'data': {'pr': 7, 'head_sha': 'a' * 40, 'actor_id': 1}}
        self.check = {'name': 'Required verification', 'status': 'COMPLETED', 'conclusion': 'SUCCESS', 'isRequired': True}
        self.pr = {'id': 'PR_7', 'state': 'OPEN', 'headRefOid': 'a' * 40, 'baseRefOid': 'b' * 40,
                   'headRefName': 'feature/job', 'baseRefName': 'main', 'headRepository': {'nameWithOwner': 'owner/repo'},
                   'isDraft': False, 'mergeable': 'MERGEABLE', 'mergeStateStatus': 'CLEAN', 'reviewDecision': 'APPROVED'}
        self.pages = [[self.check]]
        self.requests = []
        self.backend.api = self.api

    def api(self, path, method='GET', body=None, job=None, text=False):
        self.requests.append((path, method, body))
        if path == '/graphql':
            page = int(body['variables']['after'] or 0)
            pr = copy.deepcopy(self.pr)
            pr['commits'] = {'nodes': [{'commit': {'oid': pr['headRefOid'], 'statusCheckRollup': {'contexts': {
                'totalCount': sum(map(len, self.pages)), 'nodes': self.pages[page],
                'pageInfo': {'hasNextPage': page < len(self.pages) - 1, 'endCursor': str(page + 1)}}}}}]}
            return {'data': {'repository': {'pullRequest': pr}}}
        if '/files?' in path:
            return [{'filename': 'earlier_fix.kt'}, {'filename': 'current_fix.kt'}]
        if path.endswith('/pulls/7'):
            return {'head': {'sha': self.pr['headRefOid']}, 'base': {'sha': self.pr['baseRefOid'], 'ref': self.pr['baseRefName']}, 'state': 'open'}
        raise AssertionError(path)

    def test_actual_policy_parser_paginates_and_includes_whole_pr_diff(self):
        self.pages = [[dict(self.check, name='check_' + str(i)) for i in range(100)], [self.check]]
        result = self.backend.inspect(self.job)
        self.assertTrue(EngineeringSupervisor.readiness(result))
        self.assertEqual(101, len(result['checks']))
        self.assertEqual(['earlier_fix.kt', 'current_fix.kt'], result['changed_files'])
        self.assertEqual(2, sum(path == '/graphql' for path, _, _ in self.requests))

    def test_missing_skipped_unknown_and_pending_checks_never_grant_readiness(self):
        for checks in ([], [dict(self.check, conclusion='SKIPPED')], [dict(self.check, isRequired=None)], [dict(self.check, conclusion=None, status='IN_PROGRESS')]):
            with self.subTest(checks=checks):
                self.pages = [checks]
                self.assertFalse(EngineeringSupervisor.readiness(self.backend.inspect(self.job)))
        self.pages = [[self.check]]
        for key, value in [('mergeStateStatus', 'BLOCKED'), ('reviewDecision', 'REVIEW_REQUIRED'), ('isDraft', True), ('mergeable', 'CONFLICTING')]:
            with self.subTest(key=key):
                old = self.pr[key]
                self.pr[key] = value
                self.assertFalse(EngineeringSupervisor.readiness(self.backend.inspect(self.job)))
                self.pr[key] = old

    def test_retargeted_pr_or_changed_head_during_paging_is_rejected(self):
        for key, value in [('baseRefName', 'another'), ('headRefName', 'another'), ('headRepository', {'nameWithOwner': 'someone/fork'})]:
            original = self.pr[key]
            self.pr[key] = value
            with self.assertRaises(ValueError):
                self.backend.inspect(self.job)
            self.pr[key] = original
        self.pages = [[self.check], [self.check]]
        def moved(*args, **kwargs):
            if len(self.requests) == 1:
                self.pr['headRefOid'] = 'c' * 40
            return self.api(*args, **kwargs)
        self.requests.clear()
        self.backend.api = moved
        with self.assertRaises(ValueError):
            self.backend.inspect(self.job)

    def test_failed_checks_use_real_logs_for_the_exact_head(self):
        self.pages = [[dict(self.check, conclusion='FAILURE')]]
        self.backend.failure_logs = Mock(return_value=[{'run_id': 1, 'job_id': 2, 'excerpt': 'compiler error'}])
        result = self.backend.inspect(self.job)
        self.assertTrue(result['checks_failed'])
        self.assertFalse(result['checks_passed'])
        self.assertEqual('compiler error', result['failure_logs'][0]['excerpt'])
        self.backend.failure_logs.assert_called_once_with(self.job)

    def test_branch_race_prevents_ref_write_and_unapproved_patch_cannot_publish(self):
        self.job['state'] = 'COMMIT'
        self.job['data'].update(review={'approved': False}, changes=[], summary='Fix')
        with self.assertRaises(ValueError):
            self.backend.step(self.job, 'COMMIT')
        self.job['data']['review']['approved'] = True
        self.backend.ref = Mock(return_value='d' * 40)
        with self.assertRaises(ValueError):
            self.backend.step(self.job, 'COMMIT')
        self.assertFalse(any(method != 'GET' for _, method, _ in self.requests))

    def test_inference_reuses_gateway_admission_and_has_no_tools_or_merge_authority(self):
        response = Mock()
        response.json.return_value = {'choices': [{'finish_reason': 'stop', 'message': {'content': '{"approved":true,"issues":[]}'}}]}
        self.runtime.http_post = Mock(return_value=response)
        self.backend.model(self.job, 'independent reviewer', {'diff': 'change'})
        _, kwargs = self.runtime.http_post.call_args
        self.assertEqual('background', kwargs['_gateway_job_mode'])
        self.assertFalse(kwargs['_gateway_cancel_event'].is_set())
        self.assertNotIn('tools', kwargs['json'])
        self.assertTrue(kwargs['json']['cache_prompt'])
        self.backend.cancelled = lambda job_id: True
        self.assertTrue(kwargs['_gateway_cancel_event'].is_set())
        response.close.assert_called_once()

    def test_log_excerpt_keeps_actual_failure_tail_with_a_download_bound(self):
        response = Mock()
        response.iter_content.return_value = iter([b'old lines\n' * 40000, b'ACTUAL BUILD FAILURE'])
        text = self.backend.read_response(response, True)
        self.assertTrue(text.endswith('ACTUAL BUILD FAILURE'))
        self.assertLess(len(text), 257 * 1024)

    def test_existing_symlink_cannot_be_overwritten_as_a_new_text_file(self):
        tree = {'linked': {'sha': 'f' * 40, 'mode': '120000'}}
        with self.assertRaises(ValueError):
            prepare_changes({'changes': [{'path': 'linked', 'content': 'replacement', 'expected_blob_sha': None}]}, {}, tree)

    def test_token_account_change_blocks_mutation_before_dispatch(self):
        backend = GitHubEngineeringBackend(self.runtime)
        backend.token = lambda: 'not-a-real-token'
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.iter_content.return_value = iter([json.dumps({'id': 99}).encode()])
        with patch('v14.engineering_backend.requests.get', return_value=response), patch('v14.engineering_backend.requests.request') as write:
            with self.assertRaises(ValueError):
                backend.api('/repos/owner/repo/git/refs', 'POST', {}, self.job)
            write.assert_not_called()
