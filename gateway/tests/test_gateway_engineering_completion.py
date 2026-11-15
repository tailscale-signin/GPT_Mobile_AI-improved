"""Production-method regressions; fixture execution is not live-model qualification."""
import base64
from copy import deepcopy
import io
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

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmarks import coding, fixtures, sandbox
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha, prepare_changes
from v14.engineering_post_merge import inspect
from v14.engineering_source import prepare_source
from v14.engineering_workspace import act, record_model_exposure, view


class CompletionTests(unittest.TestCase):
    def setUp(self):
        self.backend = GitHubEngineeringBackend(SimpleNamespace())
        self.job = {'id': 'eng_second', 'principal': 'owner', 'deadline': time.time() + 60,
                    'request': {'repository': 'owner/repo', 'branch': 'feature', 'base': 'main'},
                    'data': {'head_sha': 'a' * 40, 'actor_id': 1, 'merge_sha': 'b' * 40}}

    def test_lost_squash_response_recovers_pinned_test_merge_tree_without_write(self):
        base, head, merged, tree = 'c' * 40, 'a' * 40, 'b' * 40, 'd' * 40
        self.job['data']['pr'] = 7
        self.job['approval'] = {'base_sha': base, 'head_sha': head}
        self.backend.identity = Mock()
        self.backend.operations = Mock()
        self.backend.operations.dispatched_intent.return_value = {
            'body': {'sha': head, 'merge_method': 'squash'},
            'context': {'base_sha': base, 'expected_tree': tree}}
        pr = {'merged': True, 'head': {'sha': head, 'ref': 'feature'}, 'base': {'ref': 'main'}, 'merge_commit_sha': merged}
        commit = {'sha': merged, 'parents': [{'sha': base}], 'tree': {'sha': tree}}
        self.backend.api = Mock(side_effect=[pr, commit, pr, dict(commit, tree={'sha': 'e' * 40})])
        receipt = self.backend.reconcile(self.job, 'MERGE')
        self.assertEqual({'method': 'squash', 'merge_sha': merged, 'confirmed': True}, receipt['merge_method_receipt'])
        self.assertNotIn('merge_method_receipt', self.backend.reconcile(self.job, 'MERGE'))
        self.assertTrue(all(len(call.args) == 1 for call in self.backend.api.call_args_list))

    def test_latest_workspace_pages_survive_compaction_ahead_of_twenty_file_prefixes(self):
        from v14.inference_budget import fit_engineering_payload

        head = self.job['data']['head_sha']
        files = {f'file{i}.py': {'content': 'x' * 2400, 'preview_characters': 2400, 'sha': blob_sha('x' * 2400)}
                 for i in range(20)}
        path = 'target.py'
        source = '# earlier\n' * 400 + 'wanted_value = 41\n'
        files[path] = {'content': source, 'preview_characters': 0, 'sha': blob_sha(source)}
        tree = {name: {'mode': '100644', 'sha': value['sha']} for name, value in files.items()}
        self.job['data'].update(files=files, tree=tree, plan={'summary': 'bounded'})
        page = act(self.backend, self.job, {'action': 'read', 'head_sha': head, 'path': path, 'start_line': 401})
        self.job['data']['workspace_receipts'] = [page, {'action': 'check_output', 'head_sha': head, 'content': 'compiler failure\n'}]
        request = {'messages': [{'content': json.dumps({'evidence': view(self.job['data'])})}], 'max_tokens': 512}
        capability = {'context_tokens': 5632, 'limits_verified': True, 'max_output_tokens': 512}
        admitted, _, compaction = fit_engineering_payload(
            request, capability, lambda value: (len(value['messages'][0]['content'].encode()) // 3, 'UTF8_ESTIMATE'))
        self.assertIsNotNone(compaction)
        self.assertIn('compiler failure', admitted['messages'][0]['content'])
        self.assertIn('wanted_value = 41', admitted['messages'][0]['content'])
        record_model_exposure(self.job['data'], admitted)
        changes = prepare_changes({'changes': [{'path': path, 'expected_blob_sha': files[path]['sha'],
                                   'edits': [{'old': 'wanted_value = 41', 'new': 'wanted_value = 42'}]}]},
                                  files, tree, head_sha=head)
        self.assertTrue(changes[0]['content'].endswith('wanted_value = 42\n'))

    def test_specialist_format_repair_preserves_sibling_and_exhausted_observations(self):
        from v14.engineering_specialists import investigate

        head = self.job['data']['head_sha']
        self.job['request']['execution_mode'] = 'full'
        self.job['data']['tree'] = {'answer.py': {'mode': '100644'}}
        self.backend.save_specialists = Mock()
        self.backend.cancellation = Mock(return_value=threading.Event())
        self.backend.model = Mock(side_effect=lambda _, role, evidence: (
            {'workspace_action': {'action': 'paths', 'head_sha': head}} if role == 'source explorer'
            else {'findings': ['fixture'], 'paths': ['answer.py']} if evidence['format_repair'] else {'invalid': True}))
        results = investigate(self.backend, self.job, [{'role': role, 'evidence': {}} for role in ('source explorer', 'test investigator')])
        self.assertEqual(['LIMIT_REACHED', 'COMPLETED'], [result['status'] for result in results])
        self.assertEqual(['answer.py'], results[0]['paths'])
        self.assertEqual(3, len(results[0]['observations']))
        self.assertEqual(2, results[1]['calls'])

    def test_followup_inherits_only_verified_pr_ownership(self):
        self.job.update(continuation_of='eng_first', pr_owner_job_id='eng_first', continuation_actor_id=1,
                        continuation_branch='feature', continuation_pr=7)
        pr = {'number': 7, 'html_url': 'https://github.com/owner/repo/pull/7', 'body': 'Autonomous engineering job `eng_first`.',
              'state': 'open', 'head': {'sha': 'a' * 40, 'ref': 'feature', 'repo': {'full_name': 'owner/repo'}}, 'base': {'ref': 'main'}}
        self.backend.paged = Mock(return_value=[pr])
        self.assertEqual(7, self.backend.find_pr(self.job)['pr'])
        for field, value in [('continuation_actor_id', 2), ('continuation_branch', 'other'), ('continuation_pr', 8)]:
            bad = dict(self.job, **{field: value})
            with self.assertRaises(ValueError):
                self.backend.find_pr(bad)
        pr['head']['sha'] = 'c' * 40
        with self.assertRaises(ValueError):
            self.backend.find_pr(self.job)

    def test_followup_created_pr_uses_inherited_owner_marker(self):
        self.job.update(continuation_of='eng_first', pr_owner_job_id='eng_first', continuation_actor_id=1,
                        continuation_branch='feature', continuation_pr=None)
        self.job['data']['changes'] = [{'path': 'file'}]
        self.backend.paged = Mock(return_value=[])
        captured = {}
        def api(url, method=None, body=None, job=None):
            captured.update(body)
            return {'number': 7, 'html_url': 'https://github.com/owner/repo/pull/7'}
        self.backend.api = api
        self.backend.identity = Mock()
        self.backend.step(self.job, 'OPEN_PR')
        self.assertIn('`eng_first`', captured['body'])
        pr = {'number': 7, 'html_url': 'https://github.com/owner/repo/pull/7', 'body': captured['body'],
              'state': 'open', 'head': {'sha': 'a' * 40, 'ref': 'feature', 'repo': {'full_name': 'owner/repo'}}, 'base': {'ref': 'main'}}
        self.backend.paged.return_value = [pr]
        self.assertEqual(7, self.backend.find_pr(self.job)['pr'])

    def test_missing_workflow_and_other_branch_dispatch_cannot_qualify_merge(self):
        self.backend.ref = Mock(return_value='c' * 40)
        self.backend.api = Mock(return_value={'status': 'ahead', 'merge_base_commit': {'sha': 'b' * 40}})
        path = '.github/workflows/build.yml'
        self.job['data']['post_merge'] = {'contract': {'merge_sha': 'b' * 40, 'branch': 'main', 'workflows': [{'path': path}]}}
        run = {'id': 20, 'workflow_id': 1, 'name': 'Build', 'path': path, 'event': 'push', 'head_sha': 'b' * 40,
               'head_branch': 'main', 'head_repository': {'full_name': 'owner/repo'},
               'run_attempt': 1, 'status': 'completed', 'conclusion': 'failure'}
        unrelated = dict(run, id=21, event='workflow_dispatch', head_branch='other', conclusion='success')
        self.backend.paged = Mock(return_value=[run, unrelated])
        self.assertEqual('FAILED', inspect(self.backend, self.job)['status'])
        self.backend.paged.return_value = [dict(unrelated, path='.github/workflows/auxiliary.yml', event='push', head_branch='main')]
        first = inspect(self.backend, self.job)
        self.assertEqual([path], first['missing_workflows'])
        first['observed_at'] -= 30
        self.job['data']['post_merge'] = first
        self.assertEqual('PENDING', inspect(self.backend, self.job)['status'])

    def test_workflow_contract_is_discovered_from_exact_merge_source(self):
        from v14.engineering_workflows import expected, applies, push_filters, WorkflowContractError
        source = 'name: Test\non:\n  push:\n    branches: [main]\n    paths: ["gateway/**"]\n  workflow_dispatch:\njobs: {}\n'
        blob = blob_sha(source)
        self.job['data']['merge_method_receipt'] = {'method': 'squash', 'merge_sha': 'b' * 40, 'confirmed': True}
        self.backend.tree = Mock(return_value={'.github/workflows/test.yml': {'sha': blob}})
        def api(url, **kwargs):
            if '/commits/' in url:
                return {'sha': 'b' * 40, 'parents': [{'sha': 'a' * 40}]}
            if '/compare/' in url:
                return {'status': 'ahead', 'merge_base_commit': {'sha': 'a' * 40}, 'files': [{'filename': 'gateway/file.py'}]}
            return {'encoding': 'base64', 'size': len(source), 'content': base64.b64encode(source.encode()).decode()}
        self.backend.api = api
        contract = expected(self.backend, self.job)
        self.assertEqual('.github/workflows/test.yml', contract['workflows'][0]['path'])
        self.backend.tree.assert_called_once_with(self.job, 'b' * 40)
        filters = push_filters(source)
        self.assertFalse(applies(filters, 'other', ['gateway/file.py']))
        self.assertFalse(applies(filters, 'main', ['docs/file.md']))
        self.assertTrue(applies(push_filters('on: [push, pull_request]'), 'main', ['file']))
        self.assertFalse(applies(push_filters('on:\n  push:\n    tags: [v1]'), 'main', ['file']))
        with self.assertRaises(WorkflowContractError):
            push_filters('on: *unknown')
        with self.assertRaises(WorkflowContractError):
            applies({'branches': ['[ab]']}, 'a', ['file'])
        for filename in ('page.js', 'page.jsx'):
            with self.assertRaises(WorkflowContractError):
                applies({'paths': ['*.jsx?']}, 'main', [filename])
        for receipt in (None, {'method': 'rebase', 'merge_sha': 'b' * 40, 'confirmed': True}):
            self.job['data']['merge_method_receipt'] = receipt
            with self.assertRaisesRegex(WorkflowContractError, 'push-before'):
                expected(self.backend, self.job)

    def test_cancelled_or_expired_command_is_never_launched(self):
        from v14.engineering_validation import cancellation_scope, run_bounded
        cancel = threading.Event()
        cancel.set()
        with patch('v14.engineering_validation.subprocess.Popen') as launch:
            with cancellation_scope(cancel):
                with self.assertRaises(InterruptedError):
                    run_bounded([sys.executable, '-c', 'pass'], '.', 20)
            with cancellation_scope(threading.Event(), time.time() - 1):
                with self.assertRaises(TimeoutError):
                    run_bounded([sys.executable, '-c', 'pass'], '.', 20)
            launch.assert_not_called()

    def test_large_file_tail_discovered_read_and_anchored_against_exact_blob(self):
        text = ('# filler for a large source file\n' * 20000) + 'answer = 41\n'
        sha = blob_sha(text)
        self.job['data'].update(tree={'large.py': {'sha': sha, 'mode': '100644'}}, files={})
        self.backend.api = Mock(return_value={'encoding': 'base64', 'size': len(text.encode()), 'content': base64.b64encode(text.encode()).decode()})
        found = act(self.backend, self.job, {'action': 'search', 'head_sha': 'a' * 40, 'paths': ['large.py'], 'query': 'answer'})
        self.assertEqual(20001, found['matches'][0]['line'])
        proposal = {'changes': [{'path': 'large.py', 'expected_blob_sha': sha, 'edits': [{'old': 'answer = 41', 'new': 'answer = 42'}]}]}
        with self.assertRaisesRegex(ValueError, 'Read the exact patch anchor'):
            prepare_changes(proposal, self.job['data']['files'], self.job['data']['tree'])
        page = act(self.backend, self.job, {'action': 'read', 'head_sha': 'a' * 40, 'path': 'large.py', 'start_line': 20001})
        self.assertEqual('answer = 41\n', page['content'])
        self.job['data'].update(plan={}, workspace_receipts=[page])
        record_model_exposure(self.job['data'], {'messages': [{'content': json.dumps({'evidence': view(self.job['data'])})}]})
        changes = prepare_changes(proposal, self.job['data']['files'], self.job['data']['tree'])
        self.assertTrue(changes[0]['content'].endswith('answer = 42\n'))
        self.assertEqual(1, self.backend.api.call_count)
        with self.assertRaisesRegex(ValueError, 'pinned head'):
            act(self.backend, self.job, {'action': 'read', 'head_sha': 'c' * 40, 'path': 'large.py'})

    def test_post_merge_needs_exact_revision_stable_inventory_and_green_runs(self):
        self.backend.ref = Mock(return_value='c' * 40)
        self.backend.api = Mock(return_value={'status': 'ahead', 'merge_base_commit': {'sha': 'b' * 40}})
        run = {'id': 20, 'workflow_id': 1, 'name': 'Build', 'event': 'push', 'head_sha': 'b' * 40,
               'run_attempt': 1, 'status': 'completed', 'conclusion': 'success'}
        run.update(path='.github/workflows/build.yml', head_branch='main', head_repository={'full_name': 'owner/repo'})
        self.job['data']['post_merge'] = {'contract': {'merge_sha': 'b' * 40, 'branch': 'main', 'workflows': [{'path': run['path']}]}}
        self.backend.paged = Mock(return_value=[run])
        first = inspect(self.backend, self.job)
        self.assertEqual('PENDING', first['status'])
        first['observed_at'] -= 30
        self.job['data']['post_merge'] = first
        self.assertEqual('PASSED', inspect(self.backend, self.job)['status'])
        run['conclusion'] = 'failure'
        failed = inspect(self.backend, self.job)
        self.assertTrue(failed['merged'])
        self.assertEqual('FAILED', failed['status'])
        run['head_sha'] = 'a' * 40
        self.assertEqual('PENDING', inspect(self.backend, self.job)['status'])
        self.backend.api.return_value['merge_base_commit']['sha'] = 'd' * 40
        self.assertEqual('BLOCKED', inspect(self.backend, self.job)['status'])

    def test_driver_observes_nested_mutation_and_grader_denies_perfect_score(self):
        def mutate(request):
            request['nested']['items'].clear()
            return {'correct': True}
        driver = sandbox.DRIVER.replace("sys.path.insert(0, '/candidate')", '')
        output = io.StringIO()
        with patch.dict(sys.modules, {'service': SimpleNamespace(handle=mutate)}), \
                patch('builtins.open', return_value=io.StringIO('[{"nested":{"items":[1,2]}}]')), patch('sys.stdout', output):
            exec(driver, {})
        self.assertIs(False, json.loads(output.getvalue().splitlines()[-1])['outputs'][0]['input_preserved'])
        task = fixtures.suite('quick')[0]
        text = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source), 'content': task['referenceSources'][path]}
                                       for path, source in task['sources'].items()]})
        def execute(candidate, inputs, *_args, **_kwargs):
            return {'executed': True, 'status': 'COMPLETED', 'outputs': [{'output': fixtures.expected(task, value), 'input_preserved': False} for value in inputs]}
        result = coding.grade(task, text, {'sandbox_image': 'fixture@sha256:' + 'a' * 64}, None, execute=execute)
        self.assertFalse(result['resolved'])
        self.assertEqual('NOT_VERIFIED', result['inputMutationVerification'])
        self.assertEqual('BLOCKED', result['status'])
        self.assertIsNone(result['score'])
        self.assertIsNone(result['dimensions']['safety'])
        self.assertFalse(result['qualified'])
        self.assertTrue(result['outputCorrect'])
        self.assertEqual('PASSED', result['patchScopeVerification'])
        self.assertEqual(50, result['unknownWeight'])

    def test_validation_cancellation_stops_a_silent_command(self):
        from v14.engineering_validation import cancellation_scope, run_bounded
        cancel = threading.Event()
        timer = threading.Timer(.1, cancel.set)
        timer.start()
        try:
            with tempfile.TemporaryDirectory() as directory, cancellation_scope(cancel):
                started = time.monotonic()
                with self.assertRaises(InterruptedError):
                    run_bounded([sys.executable, '-c', 'import time; time.sleep(20)'], directory, 30)
                self.assertLess(time.monotonic() - started, 3)
        finally:
            timer.cancel()

    def test_stop_remains_observed_after_output_eof(self):
        from v14 import engineering_validation as validation
        for kind in ('cancel', 'deadline'):
            with self.subTest(kind=kind):
                cancel, killed = threading.Event(), threading.Event()
                now, waits = [1000.0], []
                process = Mock(stdout=io.BytesIO(b''))
                process.poll.side_effect = lambda: 0 if killed.is_set() else None
                process.kill.side_effect = killed.set
                def wait(timeout):
                    if killed.is_set():
                        return 0
                    waits.append(timeout)
                    # The child has closed its pipe but remains alive. Request
                    # a stop during the first exit wait, without timing races.
                    if kind == 'cancel':
                        cancel.set()
                    else:
                        now[0] = 1002.0
                    raise subprocess.TimeoutExpired('fixed child', timeout)
                process.wait.side_effect = wait
                deadline = 1001.0 if kind == 'deadline' else None
                with patch.object(validation.subprocess, 'Popen', return_value=process), \
                        patch.object(validation.time, 'time', side_effect=lambda: now[0]), \
                        validation.cancellation_scope(cancel, deadline):
                    with self.assertRaises((InterruptedError, TimeoutError)) as caught:
                        validation.run_bounded([sys.executable, '-c', 'pass'], '.', 10)
                self.assertIs(type(caught.exception), InterruptedError if kind == 'cancel' else TimeoutError)
                if kind == 'deadline':
                    self.assertIn('Engineering job deadline', str(caught.exception))
                self.assertEqual(1, len(waits))
                self.assertTrue(killed.is_set())
                self.assertTrue(process.stdout.closed)

    def test_behavior_form_uses_complete_options_validation_without_changing_original(self):
        from desktop.acceptance_builder import add_behavior_check
        from desktop.repository_validation import defaults, validate_options, CUSTOM_PROFILE
        options = defaults(tempfile.gettempdir())
        options['profile'] = CUSTOM_PROFILE
        options['acceptance_templates'] = [{'objectives': ['Repair pagination'], 'requirements': [{
            'id': 'pagination', 'paths': ['gateway/v14/github.py'], 'checks': ['Required verification'],
            'evidence': [{'kind': 'behavior_test', 'name': 'PaginationTests.test_pages'}]}]}]
        validate_options(options)
        before = deepcopy(options)
        fields = dict(objective='Repair pagination', requirement_id='pagination', source_path='gateway/v14/github.py',
                      case_id='PaginationTests.test_pages', report_path='reports/pagination.xml',
                      command_index=0, ci_check='Required verification')
        with self.assertRaises(ValueError):
            add_behavior_check(options, **fields)
        self.assertEqual(before, options)
        options['acceptance_templates'] = []
        result = add_behavior_check(options, **fields)
        self.assertEqual(1, len(result['acceptance_map']))
        self.assertFalse(options['acceptance_map'])

    def test_behavior_form_builds_exact_template_without_claiming_execution(self):
        from desktop.acceptance_builder import add_behavior_check
        from desktop.repository_validation import defaults, CUSTOM_PROFILE
        options = defaults(tempfile.gettempdir())
        result = add_behavior_check(options, objective='Repair pagination', requirement_id='pagination',
            source_path='gateway/v14/github.py', case_id='PaginationTests.test_pages', report_path='reports/pagination.xml',
            command_index=0, ci_check='Required verification')
        self.assertEqual(CUSTOM_PROFILE, result['profile'])
        self.assertEqual([], options['acceptance_map'])
        self.assertEqual('PaginationTests.test_pages', result['acceptance_map'][0]['case_id'])
        self.assertEqual(['Repair pagination'], result['acceptance_templates'][0]['objectives'])
        with self.assertRaises(ValueError):
            add_behavior_check(options, objective='Repair pagination', requirement_id='pagination',
                source_path='../outside', case_id='PaginationTests.test_pages', report_path='reports/pagination.xml',
                command_index=0, ci_check='Required verification')

    def test_stale_mirror_fetches_and_verifies_new_published_parent(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        with tempfile.TemporaryDirectory() as directory:
            source, mirror = Path(directory) / 'source', Path(directory) / 'mirror.git'
            def git(*args):
                return subprocess.check_output(['git', *args], env=env, stderr=subprocess.DEVNULL, text=True).strip()
            git('init', '-q', str(source))
            git('-C', str(source), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'initial')
            git('clone', '--mirror', str(source), str(mirror))
            git('--git-dir=' + str(mirror), 'config', 'remote.origin.url', 'https://github.com/owner/repo.git')
            (source / 'file').write_text('new commit')
            git('-C', str(source), 'add', '.')
            git('-C', str(source), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'published')
            head, tree = git('-C', str(source), 'rev-parse', 'HEAD'), git('-C', str(source), 'rev-parse', 'HEAD^{tree}')
            self.job['data']['head_sha'] = head
            from v14 import engineering_source
            original = engineering_source.run
            def transport(argv, **kwargs):
                if 'fetch' in argv:
                    self.assertIn('https://github.com/owner/repo.git', argv)
                    git('--git-dir=' + str(mirror), 'fetch', str(source), head)
                    return ''
                return original(argv, **kwargs)
            with patch.object(engineering_source, 'load_policy', return_value=({}, mirror, None, None, None)), \
                    patch.object(engineering_source, 'run', side_effect=transport):
                result = prepare_source(self.job, None, tree, threading.Event())
                self.assertEqual({'head_sha': head, 'tree_sha': tree, 'status': 'VERIFIED'}, result)
                with self.assertRaisesRegex(ValueError, 'differs'):
                    prepare_source(self.job, None, 'f' * 40, threading.Event())
