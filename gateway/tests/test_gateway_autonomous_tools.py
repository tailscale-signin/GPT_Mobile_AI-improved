"""Real Git retrieval and production-method regressions, not live qualification."""
import base64
from copy import deepcopy
import hashlib
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
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha, prepare_changes
from v14.engineering_workspace import act
from v14.execution_budget_receipt import receipt
from v14.inference_budget import plan_tokens
from v14.engineering import validate_request
from v14.engineering_pr_adoption import verify_pull
from v14.execution_binding import ExecutionBindingError


class AutonomousToolsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {k: v for k, v in os.environ.items() if not k.upper().startswith('GIT_')}
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        self.source = self.root / 'source'
        self.source.mkdir()
        self.git('init', '-q')
        # Keep pinned Git fixture bytes identical across host newline conventions.
        for i in range(70):
            (self.source / f'file{i:03}.py').write_bytes(b'target = 1\nsecond = 2\n')
        (self.source / 'many.py').write_bytes(b'target = 1\n' * 100)
        (self.source / 'binary.bin').write_bytes(b'\0target\n')
        self.git('add', '.')
        self.git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture')
        self.head = self.git('rev-parse', 'HEAD')
        tree_sha = self.git('rev-parse', 'HEAD^{tree}')
        tree = {}
        for line in self.git('ls-tree', '-r', 'HEAD').splitlines():
            meta, name = line.split('\t')
            mode, _, sha = meta.split()
            tree[name] = {'mode': mode, 'sha': sha}
        self.policy = {'mirror': str(self.source / '.git'), 'commands': [['python3', '-m', 'unittest']], 'image': 'test@sha256:' + 'a' * 64}
        self.job = {'id': 'fixture', 'principal': 'owner', 'deadline': time.time() + 60,
                    'request': {'repository': 'owner/repo', 'model': 'local', 'branch': 'feature', 'base': 'main'},
                    'data': {'head_sha': self.head, 'tree': tree, 'files': {}, 'plan': {}}}
        self.backend = GitHubEngineeringBackend(SimpleNamespace())
        self.backend.api = Mock(return_value={'tree': {'sha': tree_sha}})
        self.backend.token = Mock(return_value='test')
        self.backend.cancellation = Mock(return_value=threading.Event())
        values = (self.policy, self.source / '.git', self.policy['image'], self.policy['commands'], [])
        for target in ('v14.workspace_search.load_policy', 'v14.engineering_source.load_policy'):
            mocker = patch(target, return_value=values)
            mocker.start()
            self.addCleanup(mocker.stop)
        # Existing configured mirror, with actual commit and tree verification.
        mocker = patch('v14.engineering_source.check_mirror')
        mocker.start()
        self.addCleanup(mocker.stop)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.source), *args], env=self.env, text=True).strip()

    def action(self, **values):
        return act(self.backend, self.job, dict(head_sha=self.head, **values))

    def test_repository_search_pages_all_matches_on_pinned_commit(self):
        # Working-tree changes cannot contaminate committed evidence.
        (self.source / 'file000.py').write_text('uncommitted = 99\n')
        matches, cursor = [], None
        for _ in range(10):
            result = self.action(action='repo_search', query='target', cursor=cursor)
            matches += result['matches']
            cursor = result['next_cursor']
            if cursor is None:
                break
        self.assertIsNone(cursor)
        self.assertEqual(170, len(matches))
        self.assertEqual(170, len({(row['path'], row['line']) for row in matches}))
        self.assertTrue(all(row['blob_sha'] == self.job['data']['tree'][row['path']]['sha'] for row in matches))
        self.assertEqual({}, self.job['data']['files'])  # Search never grants edit authority.

    def test_regex_glob_and_query_bound_cursors(self):
        result = self.action(action='repo_search', query=r'^second = [0-9]+$', mode='regex', glob='file00?.py')
        self.assertEqual(10, len(result['matches']))
        self.assertTrue(all(row['line'] == 2 for row in result['matches']))
        crlf = self.source / 'windows.py'
        crlf.write_bytes(b'first = 1\r\nsecond = 2\r\n')
        self.git('add', 'windows.py')
        self.git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'add CRLF fixture')
        self.head = self.git('rev-parse', 'HEAD')
        self.job['data']['head_sha'] = self.head
        self.job['data']['tree'] = {}
        for row in self.git('ls-tree', '-r', 'HEAD').splitlines():
            metadata, name = row.split('\t')
            mode, _, sha = metadata.split()
            self.job['data']['tree'][name] = {'mode': mode, 'sha': sha}
        self.backend.api.return_value = {'tree': {'sha': self.git('rev-parse', 'HEAD^{tree}')}}
        result = self.action(action='repo_search', query=r'^second = [0-9]+$', mode='regex', glob='windows.py')
        self.assertEqual(1, len(result['matches']))
        page = self.action(action='repo_search', query='target')
        with self.assertRaises(ValueError):
            self.action(action='repo_search', query='changed', cursor=page['next_cursor'])
        with self.assertRaises(ValueError):
            self.action(action='repo_search', query='target', paths=['../outside'])
        with self.assertRaises(ValueError):
            self.action(action='repo_search', query='(', mode='regex')
        with self.assertRaises(ValueError):
            act(self.backend, self.job, {'action': 'repo_search', 'head_sha': 'a' * 40, 'query': 'target'})

    def test_graph_rejects_stale_and_orders_exact_symbol_first(self):
        self.backend.recall_graph = Mock(return_value={'status': 'FOUND', 'graph': {'commit_sha': 'a' * 40}, 'nodes': []})
        self.assertEqual('INDEX_REVISION_MISMATCH', self.action(action='symbols', query='target')['reason'])
        nodes = [{'id': name, 'path': 'file000.py', 'blob_sha': self.job['data']['tree']['file000.py']['sha'], 'name': name, 'line': 1}
                 for name in ('targetCaller', 'target')]
        self.backend.recall_graph.return_value = {'status': 'FOUND', 'graph': {'commit_sha': self.head, 'coverage_complete': True}, 'nodes': nodes}
        self.assertEqual('target', self.action(action='explore', query='target')['graph']['nodes'][0]['name'])

    def test_static_discovery_exposes_missing_policy_without_execution(self):
        source = 'import unittest\nclass Tests(unittest.TestCase):\n    def test_answer(self):\n        self.assertTrue(True)\n'
        sha = blob_sha(source)
        self.job['data']['tree'] = {'test_answer.py': {'sha': sha, 'mode': '100644'}}
        self.backend.api.return_value = {'encoding': 'base64', 'size': len(source), 'content': base64.b64encode(source.encode()).decode()}
        from v14.adaptive_validation import discover
        result = discover(self.backend, self.job, {}, [])
        self.assertFalse(result['executed'])
        self.assertEqual('HOST_POLICY_REQUIRED', result['tests'][0]['policy_state'])
        result = discover(self.backend, self.job, {}, [{'index': 3, 'cases': ['test_answer.Tests.test_answer']}])
        self.assertEqual([3], result['tests'][0]['approved_check_indices'])

    def test_hunks_edit_duplicate_anchor_with_inspected_range_only(self):
        source = 'same\nsame\n'
        sha = blob_sha(source)
        files = {'file': {'sha': sha, 'content': source, 'source_exposure': {'head_sha': self.head, 'blob_sha': sha, 'ranges': [[5, 10]]}}}
        tree = {'file': {'sha': sha, 'mode': '100644'}}
        proposal = {'changes': [{'path': 'file', 'expected_blob_sha': sha, 'hunks': [{'start_line': 2, 'old': 'same\n', 'new': 'fixed\n'}]}]}
        self.assertEqual('same\nfixed\n', prepare_changes(proposal, files, tree, head_sha=self.head)[0]['content'])
        proposal['changes'][0]['hunks'][0]['start_line'] = 1
        with self.assertRaises(ValueError):
            prepare_changes(proposal, files, tree, head_sha=self.head)
        proposal['changes'][0]['hunks'][0].update(start_line=2, old='stale\n')
        with self.assertRaisesRegex(ValueError, 'HUNK_CONFLICT'):
            prepare_changes(proposal, files, tree, head_sha=self.head)

    def test_conflicted_patch_returns_to_same_coding_loop_without_writes(self):
        source = 'answer = 1\n'
        sha = blob_sha(source)
        self.job['data'].update(files={'file': {'sha': sha, 'content': source, 'source_exposure': {
            'head_sha': self.head, 'blob_sha': sha, 'ranges': [[0, len(source)]]}}},
            tree={'file': {'sha': sha, 'mode': '100644'}})
        self.backend.identity = Mock(return_value={'id': 1})
        self.backend.model = Mock(return_value={'changes': [{'path': 'file', 'expected_blob_sha': sha,
            'hunks': [{'start_line': 1, 'old': 'stale = 1', 'new': 'answer = 2'}]}]})
        result = self.backend.step(self.job, 'IMPLEMENT')
        self.assertTrue(result['workspace_continue'])
        self.assertFalse(result['workspace_receipts'][-1]['applied'])
        self.assertNotIn('changes', result)
        self.assertEqual(source, self.job['data']['files']['file']['content'])

    def test_versioned_actions_reject_wrong_revision_multiple_actions_and_truncation(self):
        self.job['request']['objective'] = 'test'
        result = {'protocol_version': 1, 'head_sha': self.head, 'workspace_action': {'action': 'glob', 'head_sha': self.head}}
        self.backend.model_executor = Mock()
        def response(value, reason='stop'):
            return {'choices': [{'finish_reason': reason, 'message': {'content': json.dumps(value)}}]}
        self.backend.model_executor.return_value = response(result)
        self.assertEqual({'workspace_action': result['workspace_action']}, self.backend.model(self.job, 'coding agent', {}))
        for value, reason in [(dict(result, head_sha='b' * 40), 'stop'), (dict(result, changes=[]), 'stop'), (result, 'length')]:
            self.backend.model_executor.return_value = response(value, reason)
            with self.assertRaises(ValueError):
                self.backend.model(self.job, 'coding agent', {})

    def test_4096_token_receipt_chain_preserves_actual_allocation(self):
        cap = {'context_tokens': 4096, 'max_output_tokens': 1024, 'limits_verified': True, 'capability_revision': 'v1'}
        binding = {'binding_id': 'binding', 'requested_model_id': 'local'}
        payload = {'messages': [{'role': 'user', 'content': 'test'}]}
        budget = plan_tokens(payload, cap, lambda _: (500, 'SELECTED_TOKENIZER'), verification_tokens=128)
        parent = receipt(payload, budget, binding, cap)
        child = receipt(payload, budget, binding, cap, parent=parent, stage='verification')
        self.assertEqual(parent['receipt_id'], child['parent_receipt_id'])
        self.assertEqual(2, child['allocation_version'])
        self.assertTrue(child['input_tokens_measured'])
        with self.assertRaises(ExecutionBindingError):
            plan_tokens(dict(payload, max_tokens=8192), cap, lambda _: (500, 'SELECTED_TOKENIZER'))
        with self.assertRaises(ExecutionBindingError):
            receipt(payload, dict(budget, input_tokens=9000), binding, cap)

    def test_explicit_pr_adoption_rechecks_identity_and_never_uses_body_marker(self):
        request = dict(self.job['request'], objective='fix', request_id='adopt', task_scope='implement', source_sha=self.head,
                       source_branch='feature', adopt_pr={'number': 7, 'head_sha': self.head, 'base_sha': 'b' * 40})
        validate_request(request)
        pull = {'number': 7, 'state': 'open', 'html_url': 'https://github.com/owner/repo/pull/7',
                'head': {'sha': self.head, 'ref': 'feature', 'repo': {'full_name': 'owner/repo'}},
                'base': {'sha': 'b' * 40, 'ref': 'main', 'repo': {'full_name': 'owner/repo'}}}
        self.assertEqual(7, verify_pull(request, pull, request['adopt_pr'])['pr'])
        for side, key, value in [('head', 'sha', 'c' * 40), ('base', 'sha', 'c' * 40), ('head', 'repo', {'full_name': 'other/repo'})]:
            bad = deepcopy(pull)
            bad[side][key] = value
            with self.assertRaises(ExecutionBindingError):
                verify_pull(request, bad, request['adopt_pr'])
        with self.assertRaises(ValueError):
            validate_request(dict(request, branch='main'))


if __name__ == '__main__':
    unittest.main()
