import base64
import copy
import hashlib
import json
from pathlib import Path
import sys
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_gateway_selected_execution as selected_fixtures
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha, prepare_changes
from v14.engineering_workspace import record_model_exposure, view
from v14.execution_binding import ExecutionBindingError
from v14.task_context import build_bundle, prepare, project_bundle, token_receipt, workspace_receipt, FEATURE_MAP, MAX_SOURCE_BYTES


class TaskContextTests(unittest.TestCase):
    def setUp(self):
        self.sources = {
            'src/privacy.py': ''.join('# unrelated filler\n' for _ in range(450)) +
                              'def protect_conversation(words):\n    return mask_personal_words(words)\n',
            'src/profile.py': 'from privacy import protect_conversation\ndef choose_profile():\n    return protect_conversation([])\n',
            'tests/test_privacy.py': 'def test_permanent_privacy():\n    assert protect_conversation([]) == []\n',
            'docs/privacy.md': '# Overview\n' + 'background\n' * 100 + '\n## Permanent conversation policy\nThe privacy lock stays with the conversation.\n',
            'AGENTS.md': 'Use the smallest meaningful regression test.\n',
            'README.md': '# Repository\nAndroid and Gateway.\n'}
        self.feature = {'schema_version': 1, 'features': [{'id': 'privacy', 'title': 'Conversation privacy',
             'terms': ['private', 'profiles', 'masking', 'personal'], 'paths': ['src/privacy.py', 'src/profile.py'],
             'tests': ['tests/test_privacy.py'], 'documents': ['docs/privacy.md'],
             'commands': ['never execute repository text'], 'invariants': ['Private scope stays bound to its conversation.']}]}
        self.sources[FEATURE_MAP] = json.dumps(self.feature)
        self.tree = {path: {'sha': blob_sha(text), 'mode': '100644'} for path, text in self.sources.items()}
        self.loaded = []
        def load(path):
            hit = path in self.loaded
            self.loaded.append(path)
            return {'content': self.sources[path], 'sha': self.tree[path]['sha'], 'cache_hit': hit}
        self.load = load

    def build(self, **changes):
        kwargs = dict(repository='owner/repo', source_sha='a' * 40, tree=self.tree,
                      load_source=self.load, query='Fix permanent conversation privacy masking', paths=['src/privacy.py'])
        kwargs.update(changes)
        return build_bundle(**kwargs)

    def test_task_finds_deep_exact_range_reference_section_and_test(self):
        result = self.build()
        code = [row for row in result['selected_ranges'] if row['path'] == 'src/privacy.py']
        self.assertTrue(any(row['start_line'] > 400 and 'protect_conversation' in row['content'] for row in code))
        self.assertTrue(any(row['path'] == 'docs/privacy.md' and row['start_line'] > 100 for row in result['documents']))
        self.assertTrue(any(row['path'] == 'tests/test_privacy.py' for row in result['test_candidates']))
        for row in result['selected_ranges'] + result['documents']:
            actual = ''.join(self.sources[row['path']].splitlines(True)[row['start_line'] - 1:row['end_line']])
            self.assertEqual(actual, row['content'])
            self.assertEqual(self.tree[row['path']]['sha'], row['blob_sha'])
        self.assertEqual('NOT_RUN', result['test_candidates'][0]['status'])
        self.assertFalse(result['completion_authority'])

    def test_current_does_no_reads_reference_does_not_include_code(self):
        current = self.build(variant='current')
        self.assertEqual([], self.loaded)
        self.assertEqual([], current['selected_ranges'])
        reference = self.build(variant='REFERENCE')
        self.assertEqual([], reference['selected_ranges'])
        self.assertFalse(any(path.endswith('.py') for path in self.loaded))
        self.assertTrue(reference['documents'])

    def test_unknown_variant_or_untyped_fixture_digest_rejected(self):
        with self.assertRaises(ValueError):
            self.build(variant='AUTOMAGIC')
        with self.assertRaises(ValueError):
            self.build(source_sha='a' * 64)
        fixture = self.build(source_sha='a' * 64, source_identity_kind='FROZEN_FIXTURE_MANIFEST')
        self.assertEqual('FROZEN_FIXTURE', fixture['freshness'])

    def test_foreign_and_stale_graph_nodes_never_supply_source(self):
        graph = {'nodes': [{'path': '../foreign.py', 'blob_sha': 'f' * 40, 'line': 1},
                           {'path': 'src/privacy.py', 'blob_sha': 'f' * 40, 'line': 451, 'name': 'private'}]}
        result = self.build(graph=graph)
        self.assertNotIn('../foreign.py', self.loaded)
        self.assertFalse(any(row['reason'] == 'graph_symbol' for row in result['selected_ranges']))

    def test_corrupt_blob_is_not_admitted_and_permission_revocation_propagates(self):
        def corrupt(path):
            return {'sha': self.tree[path]['sha'], 'content': 'wrong source'}
        with self.assertRaises(ValueError):
            self.build(load_source=corrupt)
        def denied(path):
            raise PermissionError('Access revoked')
        with self.assertRaises(PermissionError):
            self.build(load_source=denied)

    def test_optional_oversize_or_malformed_manifest_keeps_task_source_available(self):
        for content, reason in [(' ' * (MAX_SOURCE_BYTES + 1), 'SOURCE_UNAVAILABLE'),
                                ('{not valid JSON', 'INVALID_FEATURE_MAP')]:
            with self.subTest(reason=reason):
                self.sources[FEATURE_MAP] = content
                self.tree[FEATURE_MAP]['sha'] = blob_sha(content)
                result = self.build()
                self.assertTrue(any(row['path'] == 'src/privacy.py' for row in result['selected_ranges']))
                self.assertIn({'path': FEATURE_MAP, 'reason': reason}, result['omissions'])
                self.assertEqual([], result['features'])
                self.assertFalse(result['coverage']['selection_complete'])

    def test_manifest_budget_failure_does_not_hide_identity_or_authorization_failure(self):
        self.sources[FEATURE_MAP] = ' ' * (MAX_SOURCE_BYTES + 1)
        with self.assertRaisesRegex(ValueError, 'pinned blob'):
            self.build()
        self.tree[FEATURE_MAP]['sha'] = blob_sha(self.sources[FEATURE_MAP])
        def revoked(path):
            if path == FEATURE_MAP:
                raise PermissionError('Access revoked')
            return self.load(path)
        with self.assertRaises(PermissionError):
            self.build(load_source=revoked)

    def test_selected_source_identity_failure_is_not_an_optional_miss(self):
        def corrupt_source(path):
            result = self.load(path)
            if path == 'src/privacy.py':
                result['content'] = 'wrong source'
            return result
        with self.assertRaisesRegex(ValueError, 'pinned blob'):
            self.build(load_source=corrupt_source)

    def test_cancelled_context_never_fetches_source(self):
        cancel = threading.Event()
        cancel.set()
        with self.assertRaises(InterruptedError):
            self.build(cancel=cancel)
        self.assertEqual([], self.loaded)

    def test_output_is_bounded_and_reports_omissions(self):
        result = self.build(max_bytes=2048)
        self.assertLessEqual(len(json.dumps(result, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()), 2048)
        self.assertFalse(result['coverage']['selection_complete'])
        self.assertIsNone(result['token_count'])

    def test_warm_loader_reuse_has_fresh_sha_and_truthful_token_counts(self):
        self.build()
        result = self.build(count_tokens=lambda text: (123, 'SELECTED_TOKENIZER'))
        self.assertTrue(result['cache']['cache_hit'])
        self.assertEqual(123, result['token_count'])
        self.assertEqual('MEASURED_SELECTED_TOKENIZER', result['token_count_status'])
        self.assertIsNone(token_receipt('source', lambda text: (6, 'CONSERVATIVE_UTF8_BOUND'))['token_count'])

    def test_local_semantic_variant_is_optional_and_never_silently_claimed(self):
        fallback = self.build(variant='SEMANTIC')
        self.assertEqual('LEXICAL_FALLBACK', fallback['retrieval'])
        called = Mock(side_effect=lambda query, rows: {key: 1.0 for key, row in rows})
        actual = self.build(variant='SEMANTIC', semantic_scores=called)
        called.assert_called_once()
        self.assertEqual('LOCAL_SEMANTIC_AND_LEXICAL', actual['retrieval'])

    def test_empty_nonfinite_or_unrelated_semantic_scores_keep_lexical_receipt(self):
        for scores in ({}, {'unrelated': 1.0}, {'unrelated': float('nan')}, {'unrelated': True}):
            with self.subTest(scores=scores):
                result = self.build(variant='SEMANTIC', semantic_scores=lambda *args: scores)
                self.assertEqual('LEXICAL_FALLBACK', result['retrieval'])
        result = self.build(variant='SEMANTIC', semantic_scores=lambda query, rows: {rows[0][0]: float('inf')})
        self.assertEqual('LEXICAL_FALLBACK', result['retrieval'])
        result = self.build(variant='SEMANTIC', semantic_scores=lambda query, rows: {rows[0][0]: 0.5})
        self.assertEqual('LOCAL_SEMANTIC_AND_LEXICAL', result['retrieval'])

    def test_stale_manifest_invariants_are_removed_while_paths_remain_clues(self):
        feature = copy.deepcopy(self.feature)
        feature['file_blobs'] = {'src/privacy.py': {'blob_sha': 'f' * 40}}
        self.sources[FEATURE_MAP] = json.dumps(feature)
        self.tree[FEATURE_MAP]['sha'] = blob_sha(self.sources[FEATURE_MAP])
        result = self.build()
        self.assertEqual('STALE_REFERENCES', result['features'][0]['freshness'])
        self.assertEqual([], result['features'][0]['invariants'])
        self.assertTrue(result['selected_ranges'])

    def test_deleted_reference_cannot_make_remaining_manifest_paths_look_fresh(self):
        feature = copy.deepcopy(self.feature)
        feature['features'][0]['tests'].append('tests/deleted_test.py')
        feature['file_blobs'] = {path: {'blob_sha': row['sha']} for path, row in self.tree.items()}
        self.sources[FEATURE_MAP] = json.dumps(feature)
        self.tree[FEATURE_MAP]['sha'] = blob_sha(self.sources[FEATURE_MAP])
        result = self.build()
        self.assertEqual('STALE_REFERENCES', result['features'][0]['freshness'])
        self.assertEqual([], result['features'][0]['invariants'])
        self.assertFalse(any(row['path'] == 'tests/deleted_test.py' for row in result['test_candidates']))

    def test_session_only_uses_current_source_without_embedding_or_history(self):
        job = {'id': 'one', 'request': {'repository': 'owner/repo', 'objective': 'privacy', 'memory_mode': 'SESSION_ONLY'},
               'data': {'head_sha': 'a' * 40, 'tree': self.tree, 'files': {path: {'sha': self.tree[path]['sha'], 'content': text}
                                                                        for path, text in self.sources.items()}}}
        backend = SimpleNamespace(cancellation=lambda job: threading.Event(), task_context_semantic_scores=Mock())
        result = prepare(backend, job, 'coding agent', {}, {}, chosen='SEMANTIC')
        backend.task_context_semantic_scores.assert_not_called()
        self.assertTrue(result['selected_ranges'])
        view = workspace_receipt(job['data'])
        self.assertTrue(view['source_current'])
        self.assertNotIn('content', json.dumps(view))
        job['data']['head_sha'] = 'b' * 40
        self.assertFalse(workspace_receipt(job['data'])['source_current'])

    def test_real_model_envelope_contains_task_source_without_new_destination(self):
        backend = GitHubEngineeringBackend(SimpleNamespace())
        job = {'id': 'one', 'deadline': time.time() + 60, 'request': {'repository': 'owner/repo', 'model': 'selected',
               'objective': 'privacy masking'}, 'data': {'head_sha': 'a' * 40, 'tree': self.tree, 'files': {
               path: {'sha': self.tree[path]['sha'], 'content': text} for path, text in self.sources.items()}}}
        backend.api = Mock(return_value={'permissions': {'pull': True}})
        payloads = []
        def model(job, role, payload, cancellation):
            payloads.append(payload)
            return {'choices': [{'finish_reason': 'stop', 'message': {'content': '{"paths":[],"plan":"Inspect"}'}}]}
        backend.model_executor = model
        backend._model_once(job, 'planner', {'paths': ['src/privacy.py']})
        envelope = json.loads(payloads[0]['messages'][-1]['content'])
        self.assertEqual('selected', payloads[0]['model'])
        self.assertIn('protect_conversation', json.dumps(envelope['task_context']))
        job['request']['context_variant'] = 'CURRENT'
        backend._model_once(job, 'planner', {})
        self.assertNotIn('task_context', json.loads(payloads[1]['messages'][-1]['content']))

    def test_prepared_and_projected_anchors_grant_no_source_authority(self):
        bundle = self.build()
        data = {'head_sha': 'a' * 40, 'tree': self.tree,
                'files': {path: {'sha': self.tree[path]['sha'], 'content': text} for path, text in self.sources.items()}}
        proposal = {'changes': [{'path': 'src/privacy.py', 'expected_blob_sha': self.tree['src/privacy.py']['sha'],
                                'edits': [{'old': 'return mask_personal_words(words)', 'new': 'return []'}]}]}
        with self.assertRaises(ValueError):
            prepare_changes(proposal, data['files'], data['tree'], head_sha=data['head_sha'])
        projected = project_bundle(bundle, 1024)
        self.assertLessEqual(len(json.dumps(projected, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()), 1024)
        # A compacted identity contains no source. Actual source that fits is
        # never silently shortened or relabelled with a wider original range.
        for row in projected['selected_ranges'] + projected['documents']:
            if 'content' in row:
                self.assertEqual(''.join(self.sources[row['path']].splitlines(True)[row['start_line'] - 1:row['end_line']]), row['content'])
        omitted = copy.deepcopy(bundle)
        for row in omitted['selected_ranges'] + omitted['documents']:
            row.pop('content')
        record_model_exposure(data, {'messages': [{'content': json.dumps({'task_context': omitted})}]})
        with self.assertRaises(ValueError):
            prepare_changes(proposal, data['files'], data['tree'], head_sha=data['head_sha'])
        record_model_exposure(data, {'messages': [{'content': json.dumps({'task_context': bundle})}]})
        changed = prepare_changes(proposal, data['files'], data['tree'], head_sha=data['head_sha'])
        self.assertTrue(changed[0]['content'].endswith('    return []\n'))

    def test_foreign_revision_and_partial_or_inexact_selected_ranges_are_rejected(self):
        bundle = self.build()
        source = next(row for row in bundle['selected_ranges'] if row['path'] == 'src/privacy.py')
        for field, value in [('blob_sha', 'f' * 40), ('start_line', 1), ('end_line', 99999),
                             ('content', source['content'][:-1]), ('content_sha256', 'f' * 64)]:
            with self.subTest(field=field):
                data = {'head_sha': 'a' * 40, 'tree': self.tree, 'files': {
                    path: {'sha': self.tree[path]['sha'], 'content': text} for path, text in self.sources.items()}}
                candidate = dict(bundle, selected_ranges=[dict(source, **{field: value})], documents=[])
                record_model_exposure(data, {'messages': [{'content': json.dumps({'task_context': candidate})}]})
                self.assertNotIn('source_exposure', data['files']['src/privacy.py'])
        data = {'head_sha': 'b' * 40, 'tree': self.tree, 'files': {
                path: {'sha': self.tree[path]['sha'], 'content': text} for path, text in self.sources.items()}}
        record_model_exposure(data, {'messages': [{'content': json.dumps({'task_context': bundle})}]})
        self.assertFalse(any('source_exposure' in row for row in data['files'].values()))


class BoundTaskContextTests(unittest.TestCase):
    setUp = selected_fixtures.SelectedExecutionTests.setUp
    tearDown = selected_fixtures.SelectedExecutionTests.tearDown
    runtime_for = selected_fixtures.SelectedExecutionTests.runtime_for
    service_for = selected_fixtures.SelectedExecutionTests.service_for

    def source(self):
        source = ('# filler\n' * 200 + 'unseen_anchor = 1\n' + '# filler\n' * 250 +
                  'def deep_target():\n    return 1\n')
        sha = blob_sha(source)
        self.job['request']['objective'] = 'Repair deep_target'
        self.job['data'].update(head_sha='a' * 40, plan={'paths': ['source.py']},
            tree={'source.py': {'sha': sha, 'mode': '100644'}},
            files={'source.py': {'sha': sha, 'content': source, 'preview_characters': 0}})
        return {'changes': [{'path': 'source.py', 'expected_blob_sha': sha,
                             'edits': [{'old': 'return 1', 'new': 'return 2'}]}]}

    def apply(self, proposal):
        data = self.job['data']
        return prepare_changes(proposal, data['files'], data['tree'], head_sha=data['head_sha'])

    def test_successful_bound_request_records_only_actual_selected_ranges(self):
        proposal = self.source()
        prepare(self.backend, self.job, 'coding agent', view(self.job['data']), {})
        with self.assertRaises(ValueError):
            self.apply(proposal)
        self.backend.model(self.job, 'coding agent', view(self.job['data']))
        receipt = workspace_receipt(self.job['data'])
        self.assertEqual('VERIFIED_RESPONSE', receipt['delivery_status'])
        self.assertTrue(receipt['admission']['selected_ranges'])
        self.assertNotIn('content', json.dumps(receipt))
        self.assertTrue(self.apply(proposal)[0]['content'].endswith('    return 2\n'))
        proposal['changes'][0]['edits'] = [{'old': 'unseen_anchor = 1', 'new': 'unseen_anchor = 2'}]
        with self.assertRaises(ValueError):
            self.apply(proposal)

    def test_compacted_context_does_not_authorize_omitted_source(self):
        proposal = self.source()
        source = self.job['data']['files']['source.py']
        source['content'] += ('# large trailing context ' + 'x' * 290 + '\n') * 30
        source['sha'] = self.job['data']['tree']['source.py']['sha'] = blob_sha(source['content'])
        proposal['changes'][0]['expected_blob_sha'] = source['sha']
        def count(payload, _capability):
            body = json.loads(payload['messages'][-1]['content'])
            bundle = body.get('task_context', {})
            return (300 if not bundle.get('view_complete', True) else 100000, 'SELECTED_TOKENIZER')
        # A constrained selected-model budget invokes the real exact-range
        # projection, which keeps the anchor identity and omits this large body.
        with patch.object(self.service.capabilities, 'count', side_effect=count):
            self.backend.model(self.job, 'coding agent', view(self.job['data']))
        self.assertEqual([], workspace_receipt(self.job['data'])['admission']['selected_ranges'])
        with self.assertRaises(ValueError):
            self.apply(proposal)

    def test_identity_failure_never_records_selected_source_exposure(self):
        proposal = self.source()
        self.answer_model = 'different-model'
        with self.assertRaises(ExecutionBindingError):
            self.backend.model(self.job, 'coding agent', view(self.job['data']))
        self.assertNotEqual('VERIFIED_RESPONSE', workspace_receipt(self.job['data'])['delivery_status'])
        with self.assertRaises(ValueError):
            self.apply(proposal)

    def test_cancelled_response_never_records_selected_source_exposure(self):
        proposal = self.source()
        cancel = threading.Event()
        original = self.runtime.http_post
        def cancelling(*args, **kwargs):
            response = original(*args, **kwargs)
            cancel.set()
            return response
        with patch.object(self.backend, 'cancellation', return_value=cancel), patch.object(self.runtime, 'http_post', side_effect=cancelling):
            with self.assertRaises(InterruptedError):
                self.backend.model(self.job, 'coding agent', view(self.job['data']))
        self.assertNotEqual('VERIFIED_RESPONSE', workspace_receipt(self.job['data'])['delivery_status'])
        with self.assertRaises(ValueError):
            self.apply(proposal)

    def test_export_uses_actual_selected_provider_tokenizer_without_model_generation(self):
        from v14.workspace_context_pack import pack
        self.source()
        with patch.object(self.service.capabilities, '_json', wraps=self.service.capabilities._json) as probe:
            result = pack(self.backend, self.job, {'ranges': [{'path': 'source.py', 'start_line': 452, 'end_line': 453}], 'max_tokens': 400})
        self.assertEqual(300, result['token_count'])
        self.assertEqual('CONTEXT_WITH_USER_MESSAGE_FRAMING', result['token_scope'])
        self.assertTrue(result['token_budget_satisfied'])
        tokenizer_calls = [call for call in probe.call_args_list if call.args[1].endswith('/input_tokens')]
        self.assertEqual(1, len(tokenizer_calls))
        self.assertEqual('A', tokenizer_calls[0].kwargs['body']['model'])
        self.assertEqual(self.endpoint + '/v1/chat/completions/input_tokens', tokenizer_calls[0].args[1])
        self.assertEqual([], self.calls)
        self.assertNotIn('source_exposure', self.job['data']['files']['source.py'])

    def test_unknown_export_tokenizer_rejects_explicit_limit_before_artifact_creation(self):
        from v14.workspace_context_pack import pack
        self.source()
        with patch.object(self.service.capabilities, '_json', side_effect=ExecutionBindingError('EXECUTION_CAPABILITY_UNSUPPORTED', 'No tokenizer')), \
                patch.object(self.backend.private_artifacts, 'put', wraps=self.backend.private_artifacts.put) as write:
            with self.assertRaisesRegex(ValueError, 'supported tokenizer'):
                pack(self.backend, self.job, {'paths': ['source.py'], 'max_tokens': 400})
            write.assert_not_called()
        self.assertNotIn('workspace_context_pack', self.job['data'])

    def test_cancelled_export_does_not_send_source_to_tokenizer(self):
        from v14.workspace_context_pack import pack
        self.source()
        cancel = threading.Event()
        cancel.set()
        with patch.object(self.backend, 'cancellation', return_value=cancel), \
                patch.object(self.service.capabilities, '_json') as probe:
            with self.assertRaises(InterruptedError):
                pack(self.backend, self.job, {'paths': ['source.py'], 'max_tokens': 400})
            probe.assert_not_called()


if __name__ == '__main__':
    unittest.main()
