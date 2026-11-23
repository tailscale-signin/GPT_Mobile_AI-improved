import base64
import copy
from contextlib import nullcontext
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_backend import blob_sha
from v14.execution_binding import canonical, fingerprint
from v14.graph_context import project_graph, rank_graph
from v14 import workspace_workers, workspace_task_plan, workspace_tool_integrations, workspace_next_edit
from v14.workspace_checkpoint import save
from v14.workspace_context_pack import pack
from v14.workspace_intelligence import act, snapshot, summary
from v14.tools import install_exact_routing
from v14.actions import ExternalOutcomeUnknown
from v14.catalog import CatalogCache


class Artifacts:
    def __init__(self):
        self.values = {}
        self.store = SimpleNamespace(assert_claim=Mock())

    def put_json(self, job, value, purpose):
        return self.put(job, [canonical(value).encode()], purpose=purpose, media_type='application/json')

    def put(self, job, chunks, *, purpose, media_type):
        raw = b''.join(chunks)
        key = hashlib.sha256(raw).hexdigest()
        self.values[key] = raw
        return {'result_ref': key, 'sha256': key, 'source_sha': job['data']['head_sha'], 'byte_length': len(raw), 'media_type': media_type}

    def json_value(self, job, reference):
        return json.loads(self.values[reference['result_ref']])


class WorkspaceIntelligenceTests(unittest.TestCase):
    def setUp(self):
        sources = {'src/a.py': 'answer = 1\n', 'src/b.py': 'answer = 2\n', 'AGENTS.md': 'Keep the behavior.'}
        self.job = {'id': 'job-one', 'principal': 'owner', 'request': {'repository': 'owner/repo', 'model': 'selected',
                    'acceptance': [{'id': 'r1', 'evidence': [{'kind': 'behavior_test', 'name': 'test.one'}]},
                                   {'id': 'r2', 'evidence': [{'kind': 'behavior_test', 'name': 'test.two'}]}]},
                    'data': {'head_sha': 'a' * 40, 'plan': {}, 'files': {},
                             'tree': {path: {'mode': '100644', 'sha': blob_sha(text)} for path, text in sources.items()}}}
        blobs = {blob_sha(text): {'encoding': 'base64', 'size': len(text.encode()), 'content': base64.b64encode(text.encode()).decode()}
                 for text in sources.values()}
        self.artifacts = Artifacts()
        self.backend = SimpleNamespace(private_artifacts=self.artifacts, model=Mock(return_value={'findings': 'Done'}),
                                       root=lambda job: '', api=lambda path, **kw: blobs[path.rpartition('/')[2]],
                                       cancellation=lambda job: threading.Event())

    def test_worker_wave_is_parallel_and_keeps_separate_contexts(self):
        from gateway_security import current_device
        barrier = threading.Barrier(3)
        seen, lock = [], threading.Lock()
        def model(job, role, evidence):
            barrier.wait(timeout=3)
            with lock:
                seen.append((evidence['worker_objective'], list(job['data']['tree']), current_device.get()))
            return {'findings': evidence['worker_objective']}
        self.backend.model = model
        rows = [dict(worker_id='w' + str(i), role=role, objective=str(i), budget=2, paths=['src/a.py'])
                for i, role in enumerate(('planner', 'reviewer', 'retrieval'))]
        token = current_device.set('owner')
        try:
            result = workspace_workers.wave(self.backend, self.job, {'workers': rows})
        finally:
            current_device.reset(token)
        self.assertEqual(3, result['parallel_read_workers'])
        self.assertEqual(3, len(seen))
        self.assertTrue(all(set(tree) == {'src/a.py', 'AGENTS.md'} and principal == 'owner' for _, tree, principal in seen))
        self.assertTrue(all(row['state'] == 'COMPLETED' for row in result['workers']))
        self.assertEqual({}, self.job['data']['files'])

    def test_worker_dependencies_advance_only_in_later_wave(self):
        rows = [dict(worker_id='read', role='retrieval', objective='Find source', budget=2),
                dict(worker_id='plan', role='planner', objective='Plan change', budget=2, depends_on=['read'])]
        first = workspace_workers.wave(self.backend, self.job, {'workers': rows})
        self.assertEqual(1, first['advanced_count'])
        self.assertEqual('ACTIVE', first['workers'][1]['state'])
        second = workspace_workers.wave(self.backend, self.job, {'workers': [{'worker_id': 'plan'}]})
        self.assertEqual('COMPLETED', second['workers'][0]['state'])
        self.assertEqual('read', self.backend.model.call_args.args[2]['dependency_summaries'][0]['worker_id'])

    def test_worker_discovery_profile_matches_its_role_without_changing_parent(self):
        parent = {'profile': 'review', 'head_sha': 'a' * 40}
        self.job['data']['workspace_tool_profile'] = dict(parent)
        seen = []
        def model(job, role, evidence):
            seen.append(job['data']['workspace_tool_profile']['profile'])
            return {'findings': 'Checked selected scope'}
        self.backend.model = model
        workspace_workers.wave(self.backend, self.job, {'workers': [
            {'worker_id': 'read', 'role': 'retrieval', 'objective': 'Find source'},
            {'worker_id': 'edit', 'role': 'editor', 'objective': 'Prepare edit'}]})
        self.assertEqual(['retrieve', 'edit'], seen)
        self.assertEqual(parent, self.job['data']['workspace_tool_profile'])

    def test_worker_prompt_context_excludes_parent_source_and_cross_scope_contracts(self):
        scoped = {'id': 'local', 'paths': ['src/a.py'], 'checks': ['local-check']}
        outside = {'id': 'outside', 'paths': ['src/b.py'], 'checks': ['other-check']}
        mixed = {'id': 'mixed', 'paths': ['src/a.py', 'src/b.py'], 'checks': ['both-check']}
        self.job['request']['acceptance'] = [scoped, outside, mixed]
        self.job['conversation_context'] = {'text': 'PARENT_PRIVATE_CONTEXT'}
        self.job['data'].update(plan={'paths': ['src/b.py'], 'plan': 'PARENT_PRIVATE_CONTEXT'},
            changes=[{'path': 'src/b.py', 'content': 'PARENT_PRIVATE_CONTEXT'}],
            local_validation={'output_tail': 'PARENT_PRIVATE_CONTEXT'},
            ci={'output': 'PARENT_PRIVATE_CONTEXT'}, review={'text': 'PARENT_PRIVATE_CONTEXT'},
            progress_notes={'next_action': 'PARENT_PRIVATE_CONTEXT'},
            workspace_task_plan={'text': 'PARENT_PRIVATE_CONTEXT'},
            workspace_summaries=[{'text': 'PARENT_PRIVATE_CONTEXT'}])
        checks = {}
        for name, path in (('inside', 'src/a.py'), ('outside', 'src/b.py')):
            reference = self.artifacts.put_json(self.job, [{'path': path}], 'candidate')
            checks[name] = {'candidate_ref': reference, 'source_sha': 'a' * 40}
        self.job['data']['workspace_checks'] = checks
        parent = copy.deepcopy(self.job)
        def model(local, role, evidence):
            self.assertTrue(local['_workspace_scoped_worker'])
            self.assertNotIn('conversation_context', local)
            self.assertEqual([scoped], local['request']['acceptance'])
            self.assertEqual({'inside'}, set(local['data']['workspace_checks']))
            self.assertEqual({'paths': ['src/a.py'], 'plan': 'Inspect only a'}, evidence['plan'])
            self.assertIsNone(evidence['repair_feedback'])
            self.assertNotIn('PARENT_PRIVATE_CONTEXT', canonical(local))
            self.assertEqual('WORKER_VIEW_ONLY', evidence['acceptance_scope'])
            return {'findings': 'Scoped source remains unobserved until an explicit read'}
        self.backend.model = model
        workspace_workers.advance(self.backend, self.job,
            {'worker_id': 'scoped', 'role': 'reviewer', 'objective': 'Inspect only a', 'paths': ['src/a.py']})
        self.assertNotIn('_workspace_scoped_worker', self.job)
        self.assertEqual(parent['request'], self.job['request'])
        self.assertEqual(parent['conversation_context'], self.job['conversation_context'])
        self.assertEqual(parent['data'], {key: value for key, value in self.job['data'].items() if key != 'workspace_workers'})

    def test_explicit_worker_graph_read_keeps_only_scoped_exact_source_anchors(self):
        tree = self.job['data']['tree']
        self.backend.recall_graph = Mock(return_value={'status': 'FOUND', 'graph': {'commit_sha': 'a' * 40},
            'nodes': [{'id': 'inside', 'path': 'src/a.py', 'blob_sha': tree['src/a.py']['sha']},
                      {'id': 'outside', 'path': 'src/b.py', 'blob_sha': tree['src/b.py']['sha']},
                      {'id': 'wrong-blob', 'path': 'src/a.py', 'blob_sha': 'c' * 40}],
            'edges': [{'source': 'inside', 'target': 'outside'}, {'source': 'inside', 'target': 'wrong-blob'}]})
        self.backend.model.return_value = {'workspace_action': {'action': 'relationships', 'head_sha': 'a' * 40, 'query': 'answer'}}
        workspace_workers.advance(self.backend, self.job,
            {'worker_id': 'scoped', 'role': 'retrieval', 'objective': 'Find dependencies', 'paths': ['src/a.py']})
        saved = self.job['data']['workspace_workers']['scoped']
        context = self.artifacts.json_value(self.job, saved['context_ref'])
        graph = context['receipts'][-1]['graph']
        self.assertEqual(['inside'], [row['id'] for row in graph['nodes']])
        self.assertEqual([], graph['edges'])

    def test_parallel_wave_cannot_overdraw_the_real_shared_dispatch_budget(self):
        from v14.engineering import EngineeringStore
        from v14.execution_binding import ExecutionBindingError
        with tempfile.TemporaryDirectory() as root:
            runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True,
                                      durable_job_lock=threading.RLock(),
                                      _durable_connect=lambda: sqlite3.connect(Path(root) / 'budget.db'))
            store = EngineeringStore(runtime)
            self.artifacts.store = store
            self.job.update(state='IMPLEMENT', revision=2, _execution_lease='lease', _owner_epoch=5)
            self.job['request']['execution_budget'] = {'model_calls': 4}
            def seed(db):
                db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?,?,?,?,?,?)',
                           ('job-one', 'owner', 'request', 'digest', 'IMPLEMENT', 2, 0, 'lease', canonical(self.job)))
                db.execute('INSERT INTO engineering_owner_epochs VALUES(?,?)', ('job-one', 5))
            store.tx(seed)
            barrier = threading.Barrier(3)
            def model(job, role, evidence):
                barrier.wait(timeout=3)
                store.record_model_attempt(job, {'attempt_id': evidence['worker_objective'],
                    'binding_id': 'selected', 'state': 'DISPATCHED', 'dispatched': True,
                    'reserved_tokens': 1, 'role': role})
                return {'findings': 'Observed'}
            self.backend.model = model
            workers = [dict(worker_id='w' + str(i), role='retrieval', objective='call-' + str(i), budget=2) for i in range(3)]
            with self.assertRaises(ExecutionBindingError) as failure:
                workspace_workers.wave(self.backend, self.job, {'workers': workers})
            self.assertEqual('EXECUTION_RUN_BUDGET_EXHAUSTED', failure.exception.code)
            self.assertEqual(1, len(store.model_attempts('job-one')))
            self.assertEqual(1, sum(row['state'] == 'COMPLETED' for row in self.job['data']['workspace_workers'].values()))
            # Three reserved calls remain for review and final reporting.
            self.assertEqual(4, self.job['request']['execution_budget']['model_calls'])

    def test_invalid_wave_admission_is_atomic(self):
        rows = [dict(worker_id='one', role='planner', objective='Plan', budget=2),
                dict(worker_id='two', role='editor', objective='Edit', budget=2, depends_on=['not-admitted'])]
        with self.assertRaises(ValueError):
            workspace_workers.wave(self.backend, self.job, {'workers': rows})
        self.assertNotIn('workspace_workers', self.job['data'])
        self.backend.model.assert_not_called()

    def test_reviewer_cannot_run_or_propose_a_check(self):
        self.backend.model.return_value = {'workspace_action': {'action': 'check', 'head_sha': 'a' * 40}}
        with self.assertRaises(ValueError):
            workspace_workers.advance(self.backend, self.job, dict(worker_id='review', role='reviewer', objective='Review', budget=2))
        row = self.job['data']['workspace_workers']['review']
        self.assertEqual(1, row['calls'])
        self.assertEqual('ACTION_FAILED', row['state'])
        self.assertNotIn('workspace_checks', self.job['data'])

    def test_fixed_scope_route_and_changed_head_reject_before_another_call(self):
        self.backend.model.return_value = {'workspace_action': {'action': 'read', 'head_sha': 'a' * 40, 'path': 'src/b.py'}}
        with self.assertRaises(ValueError):
            workspace_workers.advance(self.backend, self.job, dict(worker_id='read', role='retrieval', objective='Read a', budget=2, paths=['src/a.py']))
        for alteration in ({'paths': ['src/b.py']}, {'model': 'other'}, {'execution_binding_id': 'other'}):
            with self.assertRaises(ValueError):
                workspace_workers.advance(self.backend, self.job, dict(worker_id='read', **alteration))
        self.job['data']['head_sha'] = 'b' * 40
        with self.assertRaises(ValueError):
            workspace_workers.advance(self.backend, self.job, {'worker_id': 'read'})
        self.assertEqual(1, self.backend.model.call_count)

    def test_worker_logical_budget_and_uncertain_call_never_silently_replay(self):
        self.backend.model.return_value = {'workspace_action': {'action': 'paths', 'head_sha': 'a' * 40}}
        item = dict(worker_id='trace', role='retrieval', objective='Inspect', budget=2)
        workspace_workers.advance(self.backend, self.job, item)
        end = workspace_workers.advance(self.backend, self.job, {'worker_id': 'trace'})
        self.assertEqual('BUDGET_REACHED', end['state'])
        workspace_workers.advance(self.backend, self.job, {'worker_id': 'trace'})
        self.assertEqual(2, self.backend.model.call_count)
        self.backend.model.side_effect = TimeoutError('interrupted')
        with self.assertRaises(TimeoutError):
            workspace_workers.advance(self.backend, self.job, dict(item, worker_id='pending'))
        self.assertEqual('RECONCILIATION_REQUIRED', workspace_workers.advance(self.backend, self.job, {'worker_id': 'pending'})['state'])
        self.assertEqual(3, self.backend.model.call_count)

    def test_plan_keeps_every_accepted_requirement_and_rejects_cycles(self):
        base = [{'id': 'one', 'requirement_ids': ['r1']}, {'id': 'two', 'requirement_ids': ['r2'], 'depends_on': ['one']}]
        with self.assertRaises(ValueError):
            workspace_task_plan.act(self.job, {'action': 'task_plan', 'items': base[:1]})
        with self.assertRaises(ValueError):
            workspace_task_plan.act(self.job, {'action': 'task_plan', 'items': [dict(base[0], depends_on=['two']), base[1]]})
        result = workspace_task_plan.act(self.job, {'action': 'task_plan', 'items': base})
        self.assertTrue(result['items'][0]['ready'])
        self.assertFalse(result['items'][1]['ready'])
        self.assertFalse(result['completion_authority'])

    def observe_check(self, name, cases, patch_id='p' * 64, **extra):
        self.job['data']['admission'] = {'policy_sha256': 'policy'}
        self.job['data'].setdefault('workspace_checks', {})[name] = {
            'source_sha': 'a' * 40, 'status': 'PASSED', 'policy_sha256': 'policy', 'patch_identity': patch_id,
            'case_results': [{'name': case, 'status': 'PASSED', 'report_sha256': 'f' * 64} for case in cases], **extra}
        receipt = {'check_id': name, 'receipt_sha256': name, 'head_sha': 'a' * 40, 'patch_identity': patch_id}
        self.job['data'].setdefault('workspace_receipts', []).append(receipt)

    def test_task_completion_uses_real_named_cases_at_one_exact_patch(self):
        self.observe_check('one', ['test.one'])
        workspace_task_plan.act(self.job, {'action': 'task_plan', 'items': [{'id': 'both', 'requirement_ids': ['r1', 'r2']}]})
        action = {'action': 'task_plan_complete', 'id': 'both', 'receipt_sha256': ['one']}
        with self.assertRaises(ValueError):
            workspace_task_plan.act(self.job, action)
        self.observe_check('two', ['test.two'], patch_id='different')
        with self.assertRaises(ValueError):
            workspace_task_plan.act(self.job, dict(action, receipt_sha256=['one', 'two']))
        self.job['data']['workspace_checks']['two']['patch_identity'] = 'p' * 64
        self.job['data']['workspace_receipts'][1]['patch_identity'] = 'p' * 64
        result = workspace_task_plan.act(self.job, dict(action, receipt_sha256=['one', 'two']))
        self.assertEqual(1, result['completed'])
        self.assertEqual('EVIDENCE_OBSERVED', result['items'][0]['state'])
        self.job['data']['head_sha'] = 'b' * 40
        self.assertEqual('STALE', workspace_task_plan.status(self.job)['state'])
        with self.assertRaises(ValueError):
            workspace_task_plan.act(self.job, action)

    def test_worker_check_receipt_can_satisfy_parent_plan_without_copying_edit_exposure(self):
        self.job['data']['admission'] = {'policy_sha256': 'policy'}
        check = {'source_sha': 'a' * 40, 'status': 'PASSED', 'policy_sha256': 'policy', 'patch_identity': 'p' * 64,
                 'case_results': [{'name': name, 'status': 'PASSED', 'report_sha256': 'f' * 64} for name in ('test.one', 'test.two')]}
        receipt = {'check_id': 'candidate', 'receipt_sha256': 'actual-check-receipt', 'head_sha': 'a' * 40,
                   'patch_identity': 'p' * 64, 'action': 'check', 'status': 'PASSED'}
        def checked(backend, local, tool):
            local['data']['workspace_checks'] = {'candidate': check}
            local['data']['workspace_check_executions'] = 1
            return receipt
        self.backend.model.return_value = {'workspace_action': {'action': 'check', 'head_sha': 'a' * 40, 'check_index': 0}}
        with patch.object(workspace_workers, 'act', side_effect=checked):
            result = workspace_workers.advance(self.backend, self.job,
                       {'worker_id': 'editor', 'role': 'editor', 'objective': 'Test candidate', 'paths': ['src/a.py']})
        self.assertEqual('candidate', result['candidate_check_id'])
        self.assertEqual([receipt], self.job['data']['workspace_receipts'])
        self.assertEqual({}, self.job['data']['files'])
        workspace_task_plan.act(self.job, {'action': 'task_plan', 'items': [{'id': 'both', 'requirement_ids': ['r1', 'r2']}]})
        result = workspace_task_plan.act(self.job, {'action': 'task_plan_complete', 'id': 'both', 'receipt_sha256': ['actual-check-receipt']})
        self.assertEqual(1, result['completed'])

    def test_selected_pack_is_bounded_exact_and_does_not_change_edit_exposure(self):
        source = self.job['data']['tree']['src/a.py']['sha']
        for style in ('json', 'xml', 'markdown'):
            result = pack(self.backend, self.job, {'paths': ['src/a.py'], 'format': style, 'max_bytes': 1024})
            self.assertEqual([{'path': 'src/a.py', 'blob_sha': source}], result['files'])
            raw = self.artifacts.values[result['artifact_ref']['result_ref']]
            self.assertIn(b'answer = 1', raw)
            self.assertNotIn(b'answer = 2', raw)
            self.assertLessEqual(len(raw), 1024)
            self.assertIsNone(result['token_count'])
            self.assertNotIn('source_exposure', self.job['data']['files']['src/a.py'])
        with self.assertRaises(ValueError):
            pack(self.backend, self.job, {'paths': ['../private']})

    def test_pack_omits_large_source_with_exact_reference_not_silent_truncation(self):
        raw = 'x' * 10000
        sha = blob_sha(raw)
        self.job['data']['tree']['large.py'] = {'sha': sha, 'mode': '100644'}
        self.job['data']['files']['large.py'] = {'sha': sha, 'content': raw}
        result = pack(self.backend, self.job, {'paths': ['large.py', 'src/a.py'], 'max_bytes': 1024})
        self.assertEqual(1, result['file_count'])
        self.assertEqual(1, result['omitted_count'])
        self.assertEqual(sha, result['omitted'][0]['blob_sha'])
        self.assertFalse(result['complete'])

    def test_pack_exact_ranges_preserve_original_lines_and_blob(self):
        text = 'first\nsecond\nthird\nfourth\n'
        sha = blob_sha(text)
        self.job['data']['tree']['ranges.py'] = {'mode': '100644', 'sha': sha}
        self.job['data']['files']['ranges.py'] = {'sha': sha, 'content': text}
        result = pack(self.backend, self.job, {'ranges': [{'path': 'ranges.py', 'start_line': 2, 'end_line': 3}]})
        exported = self.artifacts.json_value(self.job, result['artifact_ref'])['files'][0]
        self.assertEqual('second\nthird\n', exported['content'])
        self.assertEqual((2, 3, sha), (exported['start_line'], exported['end_line'], exported['blob_sha']))
        disjoint = pack(self.backend, self.job, {'ranges': [{'path': 'ranges.py', 'start_line': 1, 'end_line': 1},
                                                          {'path': 'ranges.py', 'start_line': 4, 'end_line': 4}]})
        self.assertEqual(1, disjoint['file_count'])
        self.assertEqual(2, disjoint['range_count'])
        with self.assertRaises(ValueError):
            pack(self.backend, self.job, {'ranges': [{'path': 'ranges.py', 'start_line': 2, 'end_line': 9}]})

    def test_repomix_compresses_large_raw_input_before_output_budget(self):
        text = 'value = 1\n' * 1000
        sha = blob_sha(text)
        self.job['data']['tree']['large.py'] = {'mode': '100644', 'sha': sha}
        self.job['data']['files']['large.py'] = {'sha': sha, 'content': text}
        with patch('v14.workspace_context_pack._repomix', return_value=(b'compressed source', {'status': 'COMPLETED', 'engine': 'repomix'})) as compress:
            result = pack(self.backend, self.job, {'paths': ['large.py'], 'engine': 'repomix', 'max_bytes': 1024, 'max_input_bytes': 20000})
        self.assertEqual(text, compress.call_args.args[2][0]['content'])
        self.assertEqual(10000, result['raw_input_bytes'])
        self.assertEqual(0, result['omitted_count'])
        self.assertLessEqual(result['byte_length'], 1024)

    def test_pack_token_budget_only_claims_selected_tokenizer_measurement(self):
        with self.assertRaisesRegex(ValueError, 'supported tokenizer'):
            pack(self.backend, self.job, {'paths': ['src/a.py'], 'max_tokens': 128})
        self.assertNotIn('workspace_context_pack', self.job['data'])
        self.backend.count_context_tokens = lambda job, text: (120, 'SELECTED_TOKENIZER')
        measured = pack(self.backend, self.job, {'paths': ['src/a.py'], 'max_tokens': 128})
        self.assertEqual(120, measured['token_count'])
        self.assertTrue(measured['token_budget_satisfied'])
        self.backend.count_context_tokens = lambda job, text: (200, 'SELECTED_TOKENIZER')
        with self.assertRaises(ValueError):
            pack(self.backend, self.job, {'paths': ['src/a.py'], 'max_tokens': 128})

    def test_summary_retains_full_evidence_while_projecting_bounded_context(self):
        receipts = [{'receipt_sha256': str(index), 'head_sha': 'a' * 40, 'content': 'original-' * 1000} for index in range(100)]
        self.job['data']['workspace_receipts'] = copy.deepcopy(receipts)
        result = summary(self.backend, self.job, 1024)
        self.assertLessEqual(len(canonical(result).encode()), 1024)
        self.assertEqual(receipts, self.artifacts.json_value(self.job, result['evidence_ref'])['receipts'])
        self.assertEqual(receipts, self.job['data']['workspace_receipts'])
        self.assertFalse(result['completion_authority'])

    def test_profile_discovery_never_claims_unavailable_checks(self):
        value = act(self.backend, self.job, {'action': 'tool_profile', 'head_sha': 'a' * 40, 'profile': 'edit'})
        self.assertIn('check', value['missing_tools'])
        self.assertNotIn('check', value['tools'])
        self.assertEqual('DISCOVERY_FILTER_ONLY', value['authority'])
        self.assertFalse(value['guidance']['active_instructions'])
        self.assertIn('read', value['tools'])

    def test_board_projection_is_bounded_and_never_exports_raw_edit_source(self):
        paths = ['src/' + '語' * 240 + str(i) + '.py' for i in range(60)]
        self.job['data']['tree'].update({path: {'sha': 'b' * 40, 'mode': '100644'} for path in paths})
        workspace_task_plan.act(self.job, {'action': 'task_plan', 'items': [
            {'id': 'task' + str(i), 'requirement_ids': ['r1', 'r2'], 'paths': paths} for i in range(80)]})
        self.job['data']['workspace_workers'] = {'w' + str(i): {
            'role': 'reviewer', 'objective': '語' * 2000, 'findings': '語' * 3000,
            'paths': paths, 'calls': 2, 'budget': 6, 'state': 'COMPLETED'} for i in range(8)}
        self.job['data']['workspace_next_edits'] = {'edit' + str(i): {
            'next_edit_id': 'edit' + str(i), 'path': 'src/a.py', 'state': 'PROPOSED',
            'source_sha': 'a' * 40, 'diff': '語' * 8000, 'diff_complete': True,
            'proposal_ref': {'result_ref': 'proposal-' + str(i)}, 'diff_ref': {'result_ref': 'diff-' + str(i)},
            'changes': [{'content': 'raw-private-source'}], 'source': 'raw-private-source'} for i in range(24)}
        schema = {'type': 'object', 'description': 'large' * 10000}
        integrations = {'state': 'AVAILABLE', 'integrations': [{'id': name, 'state': 'READY_FOR_DISPATCH',
            'tools': [{'name': name + str(i), 'input_schema': schema} for i in range(32)]}
            for name in ('context7', 'playwright', 'github')]}
        original = copy.deepcopy(self.job['data'])
        with patch.object(workspace_tool_integrations, 'status', return_value=integrations):
            result = snapshot(self.backend, self.job)
        raw = canonical(result).encode()
        self.assertLessEqual(len(raw), 262144)
        self.assertNotIn(b'raw-private-source', raw)
        self.assertFalse(result['projection']['complete'])
        self.assertGreater(result['task_plan']['omitted_items'], 0)
        self.assertTrue(all(row['schema_omitted'] and row['schema_sha256'] == fingerprint(schema)
                            for entry in result['integrations']['integrations'] for row in entry['tools']))
        self.assertTrue(all(len(row['diff'].encode()) <= 2000 and row['diff_ref'] and row['proposal_ref'] for row in result['next_edits']))
        self.assertEqual(original, self.job['data'])

    def local_binding(self):
        self.backend.runtime = SimpleNamespace(LLAMA_BASE='http://127.0.0.1:9000')
        self.job['execution_binding'] = {'provider_kind': 'LLAMA', 'requested_model_id': 'selected',
                                          'required_capability_policy': ['LOCAL_ONLY']}

    def test_next_edit_requires_explicit_local_policy_and_never_switches_model(self):
        request = {'action': 'next_edit', 'path': 'src/a.py', 'instruction': 'Change to two'}
        with self.assertRaises(PermissionError):
            workspace_next_edit.act(self.backend, self.job, request)
        self.local_binding()
        self.backend.runtime.LLAMA_BASE = 'https://remote.invalid'
        with self.assertRaises(PermissionError):
            workspace_next_edit.act(self.backend, self.job, request)
        self.backend.model.assert_not_called()

    def test_next_edit_is_a_reusable_unapplied_exact_blob_proposal(self):
        self.local_binding()
        def model(job, role, evidence):
            source = job['data']['files']['src/a.py']
            source['source_exposure'] = {'blob_sha': source['sha'], 'head_sha': 'a' * 40, 'ranges': [[0, len(source['content'])]]}
            return {'changes': [{'path': 'src/a.py', 'expected_blob_sha': source['sha'],
                                  'edits': [{'old': 'answer = 1', 'new': 'answer = 2'}]}]}
        self.backend.model.side_effect = model
        request = {'action': 'next_edit', 'path': 'src/a.py', 'instruction': 'Change to two'}
        result = workspace_next_edit.act(self.backend, self.job, request)
        self.assertEqual('PROPOSED', result['state'])
        self.assertEqual('NOT_RUN', result['test_status'])
        self.assertFalse(result['applies_automatically'])
        self.assertIn('+answer = 2', result['diff'])
        self.assertNotIn('src/a.py', self.job['data']['files'])
        self.assertEqual(result, workspace_next_edit.act(self.backend, self.job, request))
        self.assertEqual(1, self.backend.model.call_count)

    def test_next_edit_rejects_an_unseen_source_replacement(self):
        self.local_binding()
        self.backend.model.return_value = {'changes': [{'path': 'src/a.py', 'expected_blob_sha': self.job['data']['tree']['src/a.py']['sha'], 'content': 'replaced'}]}
        with self.assertRaises(ValueError):
            workspace_next_edit.act(self.backend, self.job, {'action': 'next_edit', 'path': 'src/a.py', 'instruction': 'Change it'})
        self.assertNotIn('changes', self.job['data'])

    def test_graph_ranking_uses_task_and_dependencies_within_hard_utf8_budget(self):
        graph = {'graph': {'commit_sha': 'a' * 40}, 'nodes': [
            {'id': 'noise', 'name': 'noise', 'path': 'noise.py', 'line': 1, 'excerpt': 'x' * 6000},
            {'id': 'decoder', 'name': 'decodeAudio', 'path': 'voice.py', 'line': 1, 'excerpt': 'é' * 200},
            {'id': 'call', 'name': 'preparePcm', 'path': 'pcm.py', 'line': 5}],
            'edges': [{'source': 'decoder', 'target': 'call', 'relation': 'calls', 'confidence': 'EXTRACTED'}]}
        ranked = rank_graph(graph, 'Repair audio decoder', ['voice.py'])
        self.assertEqual('decoder', ranked['nodes'][0]['id'])
        self.assertEqual('noise', graph['nodes'][0]['id'])
        self.assertTrue(ranked['ranking']['source_level_only'])
        for budget in (128, 256, 512, 1024):
            projected = project_graph(ranked, budget)
            self.assertLessEqual(len(canonical(projected).encode()), budget)
            ids = {node['id'] for node in projected.get('nodes', [])}
            self.assertTrue(all(edge['source'] in ids and edge['target'] in ids for edge in projected.get('edges', [])))

    def test_owned_checkpoint_rejects_old_lease_and_preserves_other_workers(self):
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE engineering_jobs(id,principal,data,state,revision,lease)')
        db.execute('CREATE TABLE engineering_owner_epochs(job_id,epoch)')
        db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?,?,?)', ('job-one','owner',json.dumps({'data': {'workspace_workers': {'other': {'state': 'ACTIVE'}}}}),'IMPLEMENT',2,'lease'))
        db.execute('INSERT INTO engineering_owner_epochs VALUES(?,?)', ('job-one',5))
        self.job.update(state='IMPLEMENT', revision=2, _execution_lease='lease', _owner_epoch=5)
        self.artifacts.store.tx = lambda function: function(db)
        save(self.backend, self.job, 'workspace_workers', {'state': 'COMPLETED'}, member='mine')
        saved = json.loads(db.execute('SELECT data FROM engineering_jobs').fetchone()[0])['data']['workspace_workers']
        self.assertEqual({'other', 'mine'}, set(saved))
        self.job['_owner_epoch'] = 4
        with self.assertRaises(ValueError):
            save(self.backend, self.job, 'workspace_workers', {}, member='mine')
        self.assertEqual('COMPLETED', json.loads(db.execute('SELECT data FROM engineering_jobs').fetchone()[0])['data']['workspace_workers']['mine']['state'])
        db.close()

    def test_repomix_uses_only_selected_files_in_an_offline_immutable_image(self):
        policy = {'workspace_repomix': True}
        self.job['data']['admission'] = {'policy_sha256': fingerprint(policy)}
        image = 'test/validation@sha256:' + 'b' * 64
        def run(argv, cwd, timeout, maximum, destination):
            self.assertIn('--network=none', argv)
            self.assertIn('--pull=never', argv)
            self.assertIn('--read-only', argv)
            self.assertIn(image, argv)
            self.assertIn('--compress', argv)
            self.assertEqual(['src/a.py'], [path.relative_to(cwd / 'selected').as_posix() for path in (cwd / 'selected').rglob('*') if path.is_file()])
            self.assertEqual({}, json.loads((cwd / 'gateway-repomix.json').read_text()))
            destination.write(b'<repomix>selected source</repomix>')
            return {'exit_code': 0}
        with patch('v14.engineering_validation.load_policy', return_value=(policy, None, image, [], [])), patch('v14.engineering_validation.run_bounded', side_effect=run):
            result = pack(self.backend, self.job, {'paths': ['src/a.py'], 'engine': 'repomix', 'format': 'xml'})
        self.assertEqual('repomix', result['engine'])
        self.assertEqual('TREE_SITTER_COMPRESSION', result['source_transform'])
        self.assertIn(b'selected source', self.artifacts.values[result['artifact_ref']['result_ref']])
        with patch('v14.engineering_validation.load_policy', return_value=({}, None, image, [], [])):
            with self.assertRaises(PermissionError):
                pack(self.backend, self.job, {'paths': ['src/a.py'], 'engine': 'repomix'})

    def test_graph_drops_out_of_scope_edges_even_when_full_graph_fits(self):
        source = {'nodes': [{'id': 'a', 'path': 'a.py'}], 'edges': [{'source': 'a', 'target': 'outside'}]}
        result = project_graph(source, 4096)
        self.assertFalse(result['edges'])
        self.assertFalse(result['view_complete'])
        self.assertEqual(1, result['omitted_edges'])
        self.assertEqual(1, len(source['edges']))

    def test_repomix_output_limit_reaps_only_its_container(self):
        policy = {'workspace_repomix': True}
        self.job['data']['admission'] = {'policy_sha256': fingerprint(policy)}
        container = 'c' * 64
        def run(argv, cwd, timeout, maximum, destination):
            cidfile = next(arg.split('=', 1)[1] for arg in argv if arg.startswith('--cidfile='))
            Path(cidfile).write_text(container)
            raise ValueError('Output exceeds the admitted byte budget')
        with patch('v14.engineering_validation.load_policy', return_value=(policy, None, 'image@sha256:' + 'b' * 64, [], [])), \
                patch('v14.engineering_validation.run_bounded', side_effect=run), \
                patch('v14.validation_host.command', side_effect=lambda argv: nullcontext(argv)), \
                patch('v14.validation_host.environment', return_value={}), \
                patch('v14.workspace_context_pack.subprocess.run') as cleanup:
            with self.assertRaisesRegex(ValueError, 'byte budget'):
                pack(self.backend, self.job, {'paths': ['src/a.py'], 'engine': 'repomix'})
        self.assertEqual(['docker', 'rm', '-f', container], cleanup.call_args.args[0])
        self.assertNotIn('workspace_context_pack', self.job['data'])


class IntegrationRoutingTests(unittest.TestCase):
    def setUp(self):
        WorkspaceIntelligenceTests.setUp(self)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.calls = []
        schema = {'type': 'object', 'properties': {'query': {'type': 'string'}}, 'required': ['query'], 'additionalProperties': False}
        self.advertised = [{'name': 'query-docs', 'inputSchema': schema}]
        config = {'docs': {'enabled': True, 'command': 'configured-context7'}}
        runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                  _durable_connect=lambda: sqlite3.connect(Path(self.temp.name) / 'ledger.db'),
                                  load_mcp_config=lambda: copy.deepcopy(config), mcp_cache_lock=threading.RLock(),
                                  mcp_tool_metadata={})
        def mcp_call(server, method, params, **kwargs):
            if method == 'tools/list':
                return {'tools': copy.deepcopy(self.advertised)}
            self.calls.append((server, method, params))
            return {'content': [{'type': 'text', 'text': 'Version-specific documentation'}]}
        runtime.mcp_call = mcp_call
        runtime.gateway_catalogue = CatalogCache(runtime, interactive=1, total=2)
        runtime.get_all_mcp_tools = runtime.gateway_catalogue.get
        install_exact_routing(runtime)
        self.backend.runtime = runtime
        self.config = {'context7': {'enabled': True, 'allow_remote': True, 'tools': ['docs_query-docs']}}
        self.policy = patch.object(workspace_tool_integrations, 'configuration', return_value=self.config)
        self.policy.start()
        self.addCleanup(self.policy.stop)

    def test_exact_mcp_execution_uses_existing_ledger_and_does_not_duplicate(self):
        action = {'integration': 'context7', 'tool': 'docs_query-docs', 'arguments': {'query': '/library/v2 API'}, 'invocation_id': 'docs-1'}
        first = workspace_tool_integrations.call(self.backend, self.job, action)
        self.assertEqual('COMPLETED', first['status'])
        self.assertEqual(first, workspace_tool_integrations.call(self.backend, self.job, action))
        self.assertEqual(1, len(self.calls))
        self.assertEqual('docs', self.calls[0][0])
        self.assertEqual('query-docs', self.calls[0][2]['name'])
        self.assertIn('Version-specific', self.artifacts.json_value(self.job, first['result_ref'])['content'][0]['text'])
        with self.assertRaises(ValueError):
            workspace_tool_integrations.call(self.backend, self.job, dict(action, arguments={'query': 'changed'}))
        self.assertEqual(1, len(self.calls))

    def test_uncertain_mcp_dispatch_remains_blocked_after_restoring_job(self):
        def failed(server, method, params, **kwargs):
            if method == 'tools/list':
                return {'tools': copy.deepcopy(self.advertised)}
            self.calls.append('dispatched')
            raise TimeoutError('lost response')
        self.backend.runtime.mcp_call = failed
        action = {'integration': 'context7', 'tool': 'docs_query-docs', 'arguments': {'query': 'version2'}, 'invocation_id': 'lost'}
        with self.assertRaises(TimeoutError):
            workspace_tool_integrations.call(self.backend, self.job, action)
        restored = copy.deepcopy(self.job)
        with self.assertRaises(ExternalOutcomeUnknown):
            workspace_tool_integrations.call(self.backend, restored, action)
        self.assertEqual(['dispatched'], self.calls)

    def test_disabled_local_only_unknown_tool_and_invalid_schema_never_dispatch(self):
        action = {'integration': 'context7', 'tool': 'docs_query-docs', 'arguments': {'query': 'v2'}, 'invocation_id': 'blocked'}
        self.job['execution_binding'] = {'required_capability_policy': ['LOCAL_ONLY']}
        with self.assertRaises(PermissionError):
            workspace_tool_integrations.call(self.backend, self.job, action)
        self.job.pop('execution_binding')
        with self.assertRaises(PermissionError):
            workspace_tool_integrations.call(self.backend, self.job, dict(action, tool='guessed_tool'))
        with self.assertRaises(ValueError):
            workspace_tool_integrations.call(self.backend, self.job, dict(action, arguments={'unknown': 'data'}))
        self.config['context7']['enabled'] = False
        with self.assertRaises(PermissionError):
            workspace_tool_integrations.call(self.backend, self.job, action)
        self.assertFalse(self.calls)

    def test_denied_status_and_calls_never_start_discovery(self):
        action = {'integration': 'context7', 'tool': 'docs_query-docs', 'arguments': {'query': 'v2'}, 'invocation_id': 'blocked'}
        for denial in ('local_only', 'private', 'disabled', 'remote_denied'):
            with self.subTest(denial=denial):
                job, config = copy.deepcopy(self.job), copy.deepcopy(self.config)
                if denial == 'local_only':
                    job['execution_binding'] = {'required_capability_policy': ['LOCAL_ONLY']}
                elif denial == 'private':
                    job['request']['private'] = True
                elif denial == 'disabled':
                    config['context7']['enabled'] = False
                else:
                    config['context7']['allow_remote'] = False
                with patch.object(workspace_tool_integrations, 'configuration', return_value=config), \
                        patch.object(self.backend.runtime, 'get_all_mcp_tools', wraps=self.backend.runtime.get_all_mcp_tools) as discovery:
                    for discover in (False, True):
                        state = workspace_tool_integrations.status(self.backend, job, discover=discover)
                        observed = next(row for row in state['integrations'] if row['id'] == 'context7')
                        self.assertEqual('DISABLED' if denial == 'disabled' else 'LOCAL_ONLY_POLICY', observed['state'])
                        discovery.assert_not_called()
                    with self.assertRaises(PermissionError):
                        workspace_tool_integrations.call(self.backend, job, action)
                    discovery.assert_not_called()
                    self.assertFalse(self.calls)
                    self.assertNotIn('workspace_integrations', job['data'])

    def test_denied_call_cannot_borrow_another_integrations_discovery_permission(self):
        action = {'integration': 'playwright', 'tool': 'docs_query-docs', 'arguments': {'query': 'v2'}, 'invocation_id': 'blocked'}
        for denial in ('disabled', 'remote_denied', 'unlisted_tool'):
            with self.subTest(denial=denial):
                config = copy.deepcopy(self.config)
                config['playwright'] = {'enabled': denial != 'disabled', 'allow_remote': denial != 'remote_denied',
                                        'tools': [] if denial == 'unlisted_tool' else ['docs_query-docs']}
                with patch.object(workspace_tool_integrations, 'configuration', return_value=config), \
                        patch.object(self.backend.runtime, 'get_all_mcp_tools', wraps=self.backend.runtime.get_all_mcp_tools) as discovery:
                    with self.assertRaises(PermissionError):
                        workspace_tool_integrations.call(self.backend, self.job, action)
                    discovery.assert_not_called()
                    self.assertFalse(self.calls)

    def test_authorized_discovery_retains_passive_status_and_exact_dispatch(self):
        action = {'integration': 'context7', 'tool': 'docs_query-docs', 'arguments': {'query': 'v2'}, 'invocation_id': 'allowed'}
        with patch.object(self.backend.runtime, 'get_all_mcp_tools', wraps=self.backend.runtime.get_all_mcp_tools) as discovery:
            workspace_tool_integrations.status(self.backend, self.job)
            discovery.assert_not_called()
            observed = workspace_tool_integrations.status(self.backend, self.job, discover=True)
            self.assertEqual('READY_FOR_DISPATCH', next(row['state'] for row in observed['integrations'] if row['id'] == 'context7'))
            discovery.assert_called_once()
            self.assertFalse(self.calls)
            result = workspace_tool_integrations.call(self.backend, self.job, action)
            self.assertEqual('COMPLETED', result['status'])
        self.assertEqual([('docs', 'tools/call', {'name': 'query-docs', 'arguments': {'query': 'v2'}})], self.calls)
        self.assertEqual('EXISTING_MCP_DISPATCHER', result['execution_owner'])

    def install_test_tool(self, name, operation, schema):
        runtime = self.backend.runtime
        self.assertEqual('docs_' + operation, name)
        self.advertised = [{'name': operation, 'inputSchema': schema}]
        runtime.mcp_tool_metadata = {}
        runtime.gateway_catalogue = CatalogCache(runtime, interactive=1, total=2)
        runtime.get_all_mcp_tools = runtime.gateway_catalogue.get
        install_exact_routing(runtime)

    def test_browser_navigation_uses_implementation_authority_without_github_write_gate(self):
        schema = {'type': 'object', 'properties': {'url': {'type': 'string'}}, 'required': ['url']}
        self.install_test_tool('docs_browser_navigate', 'browser_navigate', schema)
        self.config['playwright'] = {'enabled': True, 'allow_remote': True, 'allow_mutations': True, 'tools': ['docs_browser_navigate']}
        selected = {'binding_id': 'selected'}
        self.job.update(state='IMPLEMENT', execution_binding=selected)
        self.job['request']['execution_binding_id'] = 'selected'
        self.backend.operations = SimpleNamespace(service=SimpleNamespace(authorized=lambda job: True,
            bindings=SimpleNamespace(read=lambda principal, key: selected)))
        self.backend.before_mutation = Mock(side_effect=AssertionError('Must not bypass a repository phase'))
        action = {'integration': 'playwright', 'tool': 'docs_browser_navigate', 'arguments': {'url': 'http://127.0.0.1:8080'}, 'invocation_id': 'preview'}
        result = workspace_tool_integrations.call(self.backend, self.job, action)
        self.assertEqual('COMPLETED', result['status'])
        self.backend.before_mutation.assert_not_called()
        self.assertEqual(1, len(self.calls))
        self.job['request']['task_scope'] = 'read'
        with self.assertRaises(PermissionError):
            workspace_tool_integrations.call(self.backend, self.job, dict(action, invocation_id='forbidden'))
        self.assertEqual(1, len(self.calls))

    def test_native_github_overlap_and_mutations_are_suppressed(self):
        self.config['github'] = {'enabled': True, 'allow_remote': True, 'allow_mutations': True, 'tools': ['docs_create_pull_request']}
        self.install_test_tool('docs_create_pull_request', 'create_pull_request', {'type': 'object'})
        result = workspace_tool_integrations.status(self.backend, self.job)
        github = next(row for row in result['integrations'] if row['id'] == 'github')
        self.assertEqual('NATIVE_GITHUB_SELECTED', github['state'])
        self.assertFalse(github['mutations_enabled'])
        self.assertFalse(github['tools'])
        with self.assertRaises(PermissionError):
            workspace_tool_integrations.call(self.backend, self.job, {'integration': 'github', 'tool': 'docs_create_pull_request', 'arguments': {}, 'invocation_id': 'bad'})
        self.assertFalse(self.calls)
