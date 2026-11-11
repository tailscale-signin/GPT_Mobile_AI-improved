import base64
import copy
import json
import os
import sys
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering import EngineeringSupervisor
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha, prepare_changes
from v14.github_api_governor import GitHubApiGovernor


class EngineeringCredentialTests(unittest.TestCase):
    def setUp(self):
        self.config = {}
        self.runtime = SimpleNamespace(load_mcp_config=lambda: self.config)
        self.backend = GitHubEngineeringBackend(self.runtime)
        environment = patch.dict(os.environ, {}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)

    def connection(self, token='host-github-secret', **values):
        return dict(env={'GITHUB_PERSONAL_ACCESS_TOKEN': token}, **values)

    def test_single_enabled_host_github_connection_is_the_default_and_is_reloaded(self):
        self.config['github-official'] = self.connection()
        self.assertTrue(self.backend.available())
        self.assertEqual('host-github-secret', self.backend.token())
        self.config['github-official']['env']['GITHUB_PERSONAL_ACCESS_TOKEN'] = 'rotated-host-secret'
        self.assertEqual('rotated-host-secret', self.backend.token())
        self.config['github-official']['disabled'] = True
        self.assertFalse(self.backend.available())

    def test_default_does_not_select_arbitrary_mcp_or_process_credentials(self):
        self.config['web-search'] = self.connection()
        with patch.dict(os.environ, {'GITHUB_TOKEN': 'unrelated-process-secret', 'GH_TOKEN': 'another-secret'}):
            self.assertFalse(self.backend.available())
        self.config['github-codefirst'] = {'env': {'GITHUB_TOKEN': 'configured-codefirst-secret'}}
        self.assertEqual('configured-codefirst-secret', self.backend.token())

    def test_ambiguous_default_requires_explicit_selection(self):
        self.config.update({'github-official': self.connection('first-secret'),
                            'github-codefirst': self.connection('second-secret')})
        self.assertFalse(self.backend.available())
        with self.assertRaises(ValueError) as failure:
            self.backend.token()
        self.assertNotIn('first-secret', str(failure.exception))
        self.assertNotIn('second-secret', str(failure.exception))
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'github-codefirst'}):
            self.assertEqual('second-secret', self.backend.token())

    def test_conflicting_credentials_do_not_choose_an_arbitrary_account(self):
        self.config['github-official'] = {'env': {'GITHUB_PERSONAL_ACCESS_TOKEN': 'first-secret',
                                                'GITHUB_TOKEN': 'second-secret'}}
        self.assertFalse(self.backend.available())
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'github-official'}):
            self.assertFalse(self.backend.available())
        self.config['github-official']['env']['GITHUB_TOKEN'] = 'first-secret'
        self.assertEqual('first-secret', self.backend.token())

    def test_explicit_connection_wins_and_never_falls_back_when_invalid(self):
        self.config['github-official'] = self.connection('selected-secret')
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_TOKEN': 'dedicated-secret'}):
            self.assertEqual('dedicated-secret', self.backend.token())
            with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'github-official'}):
                self.assertEqual('selected-secret', self.backend.token())
                for update in ({'disabled': True}, {'enabled': False}, {'env': {}}, {'env': []}):
                    self.config['github-official'] = dict(self.connection(), **update)
                    self.assertFalse(self.backend.available())
            with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'missing'}):
                self.assertFalse(self.backend.available())

    def test_read_only_or_lockdown_host_connection_is_not_reused_for_engineering(self):
        for name in ('GITHUB_READ_ONLY', 'GITHUB_LOCKDOWN_MODE'):
            for value in ('true', '1', True, 'invalid'):
                self.config['github-official'] = {'env': {'GITHUB_TOKEN': 'restricted-secret', name: value}}
                with self.subTest(name=name, value=value):
                    self.assertFalse(self.backend.available())
                    with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'github-official'}):
                        self.assertFalse(self.backend.available())

    def test_inherited_or_command_line_connection_restrictions_are_preserved(self):
        self.config['github-official'] = self.connection()
        with patch.dict(os.environ, {'GITHUB_READ_ONLY': 'true'}):
            self.assertFalse(self.backend.available())
            self.config['github-official']['env']['GITHUB_READ_ONLY'] = 'false'
            self.assertTrue(self.backend.available())
        for args in (['stdio', '--read-only'], ['--lockdown-mode=true'], ['run', '-e', 'GITHUB_READ_ONLY=1', 'github-server']):
            self.config['github-official'] = self.connection(args=args)
            with self.subTest(args=args):
                self.assertFalse(self.backend.available())

    def test_enterprise_connection_credential_is_never_reused_for_public_github_api(self):
        for update in ({'env': {'GITHUB_TOKEN': 'enterprise-secret', 'GITHUB_HOST': 'https://github.example.test'}},
                       {'args': ['stdio', '--gh-host', 'https://github.example.test']},
                       {'args': ['--gh-host=https://github.example.test']},
                       {'args': ['run', '-e', 'GITHUB_HOST=https://github.example.test', 'github-server']}):
            self.config['github-official'] = dict(self.connection(), **update)
            with self.subTest(update=update):
                self.assertFalse(self.backend.available())
                with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'github-official'}):
                    self.assertFalse(self.backend.available())
        self.config['github-official'] = self.connection()
        with patch.dict(os.environ, {'GITHUB_HOST': 'https://github.example.test'}):
            self.assertFalse(self.backend.available())
        self.config['github-official']['env']['GITHUB_HOST'] = 'https://github.com'
        self.assertTrue(self.backend.available())

    def test_invalid_or_blank_explicit_credential_is_not_replaced_by_default(self):
        self.config['github-official'] = self.connection()
        for value in ('', ' ', ' secret', 'secret\n', 'secret\t'):
            with self.subTest(value=repr(value)), patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_TOKEN': value}):
                self.assertFalse(self.backend.available())

    def test_malformed_connections_do_not_raise_from_readiness(self):
        for value in (None, [], 'invalid', {'env': []}, {'env': {'GITHUB_TOKEN': 123}}, {'disabled': True, 'env': {'GITHUB_TOKEN': 'secret'}}):
            self.config['github-official'] = value
            with self.subTest(value=value):
                self.assertFalse(self.backend.available())
                with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'github-official'}):
                    self.assertFalse(self.backend.available())

    def test_graph_reader_uses_exact_read_only_host_without_enabling_engineering_writes(self):
        self.config['github-official'] = {'env': {'GITHUB_TOKEN': 'selected-read-secret', 'GITHUB_READ_ONLY': 'true'}}
        self.config['github-codefirst'] = self.connection('another-host-secret')
        reader = self.backend.graph_reader()
        self.assertFalse(reader.available())
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'github-official'}), patch('requests.request') as request:
            response = request.return_value.__enter__.return_value
            response.status_code = 200
            response.iter_content.return_value = [b'{"id":42}']
            self.assertTrue(reader.available())
            self.assertFalse(self.backend.available())
            self.assertEqual({'id': 42}, reader.api('/user'))
            self.assertEqual('GET', request.call_args.args[0])
            self.assertEqual('https://api.github.com/user', request.call_args.args[1])
            self.assertEqual('Bearer selected-read-secret', request.call_args.kwargs['headers']['Authorization'])
            self.assertFalse(request.call_args.kwargs['allow_redirects'])
            for method in ('POST', 'PUT', 'PATCH', 'DELETE'):
                with self.subTest(method=method), self.assertRaises(PermissionError):
                    reader.api('/repos/owner/repo/issues', method, {'title': 'not authorized'})
            with self.assertRaises(PermissionError):
                reader.api('/user', body={'unexpected': True})
            with self.assertRaises(ValueError):
                self.backend.api('/repos/owner/repo/issues', 'POST', {'title': 'not authorized'})
            self.assertEqual(1, request.call_count)

    def test_graph_reader_rechecks_current_credentials_for_each_page_and_stops_after_revocation(self):
        self.config['github-official'] = {'env': {'GITHUB_TOKEN': 'first-read-secret', 'GITHUB_READ_ONLY': 'true'}}
        reader = self.backend.graph_reader()
        with patch('requests.request') as request:
            response = request.return_value.__enter__.return_value
            response.status_code = 200
            response.iter_content.return_value = [b'[]']
            self.assertEqual([], reader.paged('/repos/owner/repo/pulls?state=open', maximum=100))
            self.assertIn('per_page=100&page=1', request.call_args.args[1])
            self.assertEqual('Bearer first-read-secret', request.call_args.kwargs['headers']['Authorization'])
            self.config['github-official']['env']['GITHUB_TOKEN'] = 'rotated-read-secret'
            self.assertEqual([], reader.paged('/repos/owner/repo/pulls?state=open', maximum=100))
            self.assertEqual('Bearer rotated-read-secret', request.call_args.kwargs['headers']['Authorization'])
            self.config['github-official']['disabled'] = True
            self.assertFalse(reader.available())
            with self.assertRaises(ValueError):
                reader.api('/user')
            self.assertEqual(2, request.call_count)

    def test_graph_reader_accepts_explicit_read_only_flags_but_preserves_lockdown_and_host_restrictions(self):
        reader = self.backend.graph_reader()
        for config in (
            {'env': {'GITHUB_TOKEN': 'read-secret', 'GITHUB_READ_ONLY': 'true'}},
            {'env': {'GITHUB_TOKEN': 'read-secret', 'GITHUB_READ_ONLY': True}},
            self.connection(args=['stdio', '--read-only']),
            self.connection(args=['--read-only=true']),
            self.connection(args=['run', '-e', 'GITHUB_READ_ONLY=1', 'github-server'])
        ):
            self.config['github-official'] = config
            with self.subTest(config=config):
                self.assertTrue(reader.available())
                self.assertFalse(self.backend.available())
        self.config['github-official'] = self.connection()
        with patch.dict(os.environ, {'GITHUB_READ_ONLY': 'true'}):
            self.assertTrue(reader.available())
            self.assertFalse(self.backend.available())
        for config in (
            {'env': {'GITHUB_TOKEN': 'read-secret', 'GITHUB_LOCKDOWN_MODE': 'true'}},
            {'env': {'GITHUB_TOKEN': 'read-secret', 'GITHUB_READ_ONLY': 'invalid'}},
            {'env': {'GITHUB_TOKEN': 'read-secret', 'GITHUB_HOST': 'https://github.enterprise.test'}},
            self.connection(args=['--lockdown-mode']),
            self.connection(args=['run', '-e', 'GITHUB_LOCKDOWN_MODE=true', 'github-server'])
        ):
            self.config['github-official'] = config
            with self.subTest(config=config):
                self.assertFalse(reader.available())
                self.assertFalse(self.backend.available())
        self.config['github-official'] = self.connection()
        with patch.dict(os.environ, {'GITHUB_LOCKDOWN_MODE': 'true'}):
            self.assertFalse(reader.available())

    def test_graph_reader_does_not_replace_missing_ambiguous_conflicting_or_disabled_selection(self):
        reader = self.backend.graph_reader()
        for configs in (
            {}, {'web-search': self.connection()},
            {'github-official': self.connection(), 'github-codefirst': self.connection('another-secret')},
            {'github-official': self.connection(disabled=True)},
            {'github-official': {'env': {'GITHUB_TOKEN': 'first', 'GITHUB_PERSONAL_ACCESS_TOKEN': 'second'}}}
        ):
            self.config.clear()
            self.config.update(configs)
            with self.subTest(configs=configs), patch('requests.request') as request:
                self.assertFalse(reader.available())
                with self.assertRaises(ValueError):
                    reader.api('/user')
                request.assert_not_called()
        self.config.clear()
        self.config['github-official'] = self.connection()
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'missing',
                                     'GATEWAY_ENGINEERING_GITHUB_TOKEN': 'other-dedicated-secret'}):
            self.assertFalse(reader.available())


class EngineeringBackendTests(unittest.TestCase):
    def test_api_waiter_cannot_dispatch_after_its_job_deadline(self):
        backend = GitHubEngineeringBackend(SimpleNamespace(LLAMA_BASE='http://localhost:8080'))
        backend.token = lambda: 'queue-deadline-fixture'
        waiting = threading.Event()
        backend.github_governor = GitHubApiGovernor(clock=lambda: (waiting.set(), time.monotonic())[1])
        now = [1000.0]
        job = {'id': 'deadline-test', 'state': 'READ', 'deadline': 1001.0, 'data': {},
               'request': {'repository': 'owner/repo'}}
        with patch('v14.engineering_backend.time.time', side_effect=lambda: now[0]), \
                patch('v14.engineering_backend.requests.request') as request, ThreadPoolExecutor(max_workers=1) as pool:
            response = request.return_value.__enter__.return_value
            response.status_code = 200
            response.iter_content.return_value = [b'{}']
            with backend.github_governor.lease('queue-deadline-fixture'):
                waiting.clear()
                future = pool.submit(backend.api, '/repos/owner/repo', 'GET', None, job)
                self.assertTrue(waiting.wait(2), 'request must reach the account queue')
                now[0] = 1002.0
            with self.assertRaises(InterruptedError):
                future.result(timeout=2)
            request.assert_not_called()

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
        if path == '/repos/owner/repo':
            return {'permissions': {'pull': True, 'push': True}}
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

    def test_shared_android_gateway_check_contract(self):
        fixture = json.loads((Path(__file__).resolve().parents[1] / 'contracts/github-ci-eligibility-v1.json').read_text())
        for case in fixture['cases']:
            with self.subTest(case=case['name']):
                self.pages = [{key: value for key, value in case.items() if key != 'passed'}]
                self.pages = [self.pages + [dict(self.check, name="baseline")]]
                self.backend.failure_logs = Mock(return_value=[])
                result = self.backend.inspect(self.job)
                self.assertEqual(case['passed'], result['requested_validation_passed'])
                self.assertTrue(result['github_permits_merging'])

    def test_token_rotation_preserves_account_memory_namespace(self):
        backend = GitHubEngineeringBackend(self.runtime)
        backend.api = Mock(return_value={'id': 42, 'login': 'owner'})
        backend.token = Mock(return_value='first')
        self.job['data']['actor_id'] = 42
        backend.identity(self.job)
        original = self.job['data']['permission_scope']
        backend.token = Mock(return_value='rotated')
        backend.identity(self.job)
        self.assertEqual(original, self.job['data']['permission_scope'])

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
        self.backend.model_executor = Mock(return_value={'choices': [{'finish_reason': 'stop', 'message': {'content': '{"approved":true,"issues":[]}'}}]})
        self.backend.model(self.job, 'independent reviewer', {'diff': 'change'})
        args, _ = self.backend.model_executor.call_args
        self.assertEqual('selected-model reviewer', args[1])
        self.assertFalse(args[3].is_set())
        self.assertNotIn('tools', args[2])
        self.assertFalse(args[2]['cache_prompt'])
        self.assertNotIn('max_tokens', args[2])
        self.backend.cancelled = lambda job_id: True
        self.assertTrue(args[3].is_set())

    def test_read_retry_backoff_observes_job_cancellation_and_deadline(self):
        for stop in ('cancel', 'deadline'):
            with self.subTest(stop=stop):
                backend = GitHubEngineeringBackend(self.runtime)
                backend.identity = Mock(return_value={'id': 1})
                job = copy.deepcopy(self.job)
                job['data']['plan'] = {'paths': ['file.py']}
                backend.tree = Mock(return_value={'file.py': {'sha': 'a' * 40, 'mode': '100644'}})
                backend.journal = SimpleNamespace(open=Mock(return_value={}), save=Mock())
                cancelled = threading.Event()
                backend.cancelled = lambda job_id: cancelled.is_set()
                attempts = []
                def read(*args, **kwargs):
                    self.assertIn('/git/blobs/', args[0])
                    attempts.append(1)
                    if stop == 'cancel':
                        cancelled.set()
                    else:
                        job['deadline'] = time.time() - 1
                    raise TimeoutError('injected transport failure')
                backend.api = read
                with patch('v14.batch.retry_delay', return_value=120), ThreadPoolExecutor(1) as pool:
                    result = pool.submit(backend.step, job, 'READ')
                    try:
                        with self.assertRaises(InterruptedError):
                            result.result(timeout=2)
                    finally:
                        backend.stopping.set()
                self.assertEqual([1], attempts)
                backend.journal.save.assert_not_called()

    def test_read_cooldown_reuses_the_saved_batch_after_the_job_revision_changes(self):
        contents = {'first.py': 'already read', 'second.py': 'temporarily limited'}
        tree = {path: {'sha': blob_sha(value), 'mode': '100644'} for path, value in contents.items()}
        by_sha = {tree[path]['sha']: value for path, value in contents.items()}
        receipts = {}
        journal = SimpleNamespace(
            open=lambda principal, job_id, batch_id, plan, binding: copy.deepcopy(receipts.get(batch_id, {})),
            save=lambda principal, job_id, batch_id, value: receipts.update({batch_id: copy.deepcopy(value)})
        )
        job = copy.deepcopy(self.job)
        job['state'] = 'READ'
        job['data'].update(plan={'paths': ['outdated.py']}, ci={'checks_failed': True})
        limited_sha = tree['second.py']['sha']
        calls = []

        def backend(waiting):
            client = GitHubEngineeringBackend(self.runtime)
            client.identity = Mock(return_value={'id': job['data']['actor_id']})
            client.tree = Mock(return_value=tree)
            client.model = Mock(return_value={'paths': list(contents)})
            client.journal = journal

            def read(path, **kwargs):
                sha = path.rsplit('/', 1)[1]
                calls.append(sha)
                if waiting and sha == limited_sha:
                    response = requests.Response()
                    response.status_code = 429
                    response.headers['Retry-After'] = '600'
                    raise requests.HTTPError('fixture cooldown', response=response)
                content = by_sha[sha].encode()
                return {'encoding': 'base64', 'size': len(content), 'content': base64.b64encode(content).decode()}

            client.api = read
            return client

        first = backend(waiting=True)
        with self.assertRaises(requests.HTTPError) as failure:
            first.step(job, 'READ')
        self.assertEqual(429, failure.exception.response.status_code)
        first.model.assert_called_once()
        batch_id, saved = next(iter(receipts.items()))
        saved['1']['error']['retry_at'] = 0

        resumed = json.loads(json.dumps(job))
        resumed['revision'] += 1
        second = backend(waiting=False)
        result = second.step(resumed, 'READ')
        second.model.assert_not_called()
        self.assertEqual([batch_id], list(receipts))
        self.assertEqual(1, calls.count(tree['first.py']['sha']))
        self.assertEqual(2, calls.count(limited_sha))
        self.assertEqual(contents, {path: value['content'] for path, value in result['files'].items()})

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
