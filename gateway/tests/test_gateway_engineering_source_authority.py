"""Actual read/view/edit and durable-artifact boundaries; no live provider calls."""
import base64
from copy import deepcopy
import json
from pathlib import Path
import sys
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_gateway_selected_execution as selected_fixtures
import test_gateway_v14_engineering as durable_fixtures
from v14.engineering import EngineeringStore, public_job
from v14.engineering_artifacts import EngineeringArtifacts
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha, prepare_changes
from v14.engineering_validation import patch_identity
from v14.engineering_workspace import act, view
from v14.execution_binding import ExecutionBindingError
from v14.graph_context import stage_selection


def deliver(data, shown=None):
    from v14.engineering_workspace import record_model_exposure
    record_model_exposure(data, {'messages': [{'content': json.dumps({'evidence': shown if shown is not None else view(data)})}]})


class SourceAuthorityTests(unittest.TestCase):
    def setUp(self):
        self.backend = GitHubEngineeringBackend(SimpleNamespace())
        self.backend.identity = Mock(return_value={'id': 1})
        self.backend.journal = SimpleNamespace(open=Mock(return_value={}), save=Mock())
        self.job = {'id': 'eng_source', 'principal': 'owner', 'revision': 1, 'deadline': time.time() + 60,
                    'request': {'repository': 'owner/repo', 'branch': 'feature', 'base': 'main'},
                    'data': {'head_sha': 'a' * 40, 'actor_id': 1}}

    def read(self, sources=None):
        sources = sources or {'source.py': 'visible = 1\n'.ljust(5404, 'x') + '\nhidden = 41\n'}
        tree = {path: {'sha': blob_sha(text), 'mode': '100644'} for path, text in sources.items()}
        by_sha = {tree[path]['sha']: text for path, text in sources.items()}
        self.backend.tree = Mock(return_value=tree)
        self.job['data']['plan'] = {'paths': list(sources)}
        def blob(url, **_kwargs):
            raw = by_sha[url.rsplit('/', 1)[1]].encode()
            return {'encoding': 'base64', 'size': len(raw), 'content': base64.b64encode(raw).decode()}
        self.backend.api = Mock(side_effect=blob)
        self.job['data'].update(self.backend.step(self.job, 'READ'))
        return self.job['data']

    def change(self, path='source.py', **values):
        return {'changes': [dict(path=path, expected_blob_sha=self.job['data']['tree'][path]['sha'], **values)]}

    def apply(self, proposal, data=None):
        data = data or self.job['data']
        return prepare_changes(proposal, data['files'], data['tree'], head_sha=data['head_sha'])

    def test_initial_read_then_view_cannot_replace_a_file_with_its_2400_character_excerpt(self):
        data = self.read()
        original = data['files']['source.py']['content']
        self.assertEqual(5417, len(original))
        def propose(_job, _role, evidence):
            shown = evidence['files']['source.py']
            self.assertEqual(2400, len(shown['content']))
            self.assertTrue(shown['partial'])
            return self.change(content=shown['content'])
        self.backend.model = propose
        with self.assertRaises(ValueError):
            self.backend.step(self.job, 'IMPLEMENT')
        self.assertEqual(original, data['files']['source.py']['content'])

    def test_initial_read_then_view_cannot_authorize_an_unseen_anchor(self):
        data = self.read()
        def propose(_job, _role, evidence):
            self.assertNotIn('hidden = 41', evidence['files']['source.py']['content'])
            return self.change(edits=[{'old': 'hidden = 41', 'new': 'hidden = 42'}])
        self.backend.model = propose
        with self.assertRaises(ValueError):
            self.backend.step(self.job, 'IMPLEMENT')
        self.assertTrue(data['files']['source.py']['content'].endswith('hidden = 41\n'))

    def test_reading_and_rendering_do_not_grant_authority_without_model_delivery(self):
        data = self.read()
        view(data)
        with self.assertRaisesRegex(ValueError, 'Read the exact patch anchor'):
            self.apply(self.change(edits=[{'old': 'visible = 1', 'new': 'visible = 2'}]))

    def test_admitted_prefix_allows_its_anchor_but_not_hidden_replacement_or_deletion(self):
        data = self.read()
        deliver(data)
        result = self.apply(self.change(edits=[{'old': 'visible = 1', 'new': 'visible = 2'}]))
        self.assertEqual(data['files']['source.py']['content'].replace('visible = 1', 'visible = 2'), result[0]['content'])
        for proposed in (self.change(edits=[{'old': 'hidden = 41', 'new': 'hidden = 42'}]),
                         self.change(content=view(data)['files']['source.py']['content']), self.change(delete=True)):
            with self.subTest(proposal=proposed), self.assertRaises(ValueError):
                self.apply(proposed)

    def test_initial_read_file_omitted_from_twenty_file_view_is_not_inspected(self):
        data = self.read({f'file-{index:02}.py': f'value = {index}\n' for index in range(21)})
        shown = view(data)
        self.assertNotIn('file-20.py', shown['files'])
        deliver(data, shown)
        with self.assertRaises(ValueError):
            self.apply(self.change('file-20.py', content='unseen replacement'))

    def test_search_and_undelivered_read_do_not_grant_tail_authority(self):
        data = self.read()
        head = data['head_sha']
        search = act(self.backend, self.job, {'action': 'search', 'head_sha': head, 'paths': ['source.py'], 'query': 'hidden'})
        self.assertEqual(3, search['matches'][0]['line'])
        data['workspace_receipts'] = [search]
        deliver(data)
        proposal = self.change(edits=[{'old': 'hidden = 41', 'new': 'hidden = 42'}])
        with self.assertRaises(ValueError):
            self.apply(proposal)
        receipt = act(self.backend, self.job, {'action': 'read', 'head_sha': head, 'path': 'source.py', 'start_line': 3})
        self.assertEqual('hidden = 41\n', receipt['content'])
        with self.assertRaises(ValueError):
            self.apply(proposal)
        data['workspace_receipts'] = [receipt]
        deliver(data)
        self.assertEqual(data['files']['source.py']['content'].replace('hidden = 41', 'hidden = 42'), self.apply(proposal)[0]['content'])
        self.assertEqual(1, self.backend.api.call_count)

    def test_compacted_range_authorizes_only_characters_still_in_the_request(self):
        data = self.read({'source.py': 'header\nvisible = 1; ' + 'x' * 400 + 'hidden = 41\n'})
        data['files']['source.py']['preview_characters'] = 0
        receipt = act(self.backend, self.job, {'action': 'read', 'head_sha': data['head_sha'], 'path': 'source.py', 'start_line': 2})
        receipt['content'] = {'excerpt': 'visible = 1; ', 'complete': False}
        shown = view(data)
        shown['workspace_receipts'] = [receipt]
        deliver(data, shown)
        self.assertIn('visible = 2;', self.apply(self.change(edits=[{'old': 'visible = 1;', 'new': 'visible = 2;'}]))[0]['content'])
        with self.assertRaises(ValueError):
            self.apply(self.change(edits=[{'old': 'hidden = 41', 'new': 'hidden = 42'}]))

    def test_head_blob_and_content_changes_cannot_reuse_an_old_exposure(self):
        data = self.read()
        deliver(data)
        proposal = self.change(edits=[{'old': 'visible = 1', 'new': 'visible = 2'}])
        moved = deepcopy(data)
        moved['head_sha'] = 'b' * 40
        deliver(moved, view(data))
        with self.assertRaises(ValueError):
            self.apply(proposal, moved)
        changed = deepcopy(data)
        changed['files']['source.py']['content'] += 'new revision\n'
        new_sha = blob_sha(changed['files']['source.py']['content'])
        changed['files']['source.py']['sha'] = changed['tree']['source.py']['sha'] = new_sha
        proposal['changes'][0]['expected_blob_sha'] = new_sha
        with self.assertRaises(ValueError):
            self.apply(proposal, changed)
        corrupted = deepcopy(data)
        corrupted['files']['source.py']['content'] += 'unverified bytes'
        proposal['changes'][0]['expected_blob_sha'] = data['tree']['source.py']['sha']
        with self.assertRaisesRegex(ValueError, 'exact existing blob'):
            self.apply(proposal, corrupted)
        with self.assertRaisesRegex(ValueError, 'pinned head'):
            act(self.backend, self.job, {'action': 'read', 'head_sha': 'b' * 40, 'path': 'source.py'})

    def test_foreign_or_inexact_range_receipts_do_not_grant_exposure(self):
        original = self.read()
        original['files']['source.py']['preview_characters'] = 0
        receipt = act(self.backend, self.job, {'action': 'read', 'head_sha': original['head_sha'], 'path': 'source.py', 'start_line': 3})
        for field, value in [('head_sha', 'b' * 40), ('blob_sha', 'c' * 40), ('content', 'invented text'), ('start_line', 1000)]:
            with self.subTest(field=field):
                data = deepcopy(original)
                data['workspace_receipts'] = [dict(receipt, **{field: value})]
                deliver(data)
                with self.assertRaises(ValueError):
                    self.apply(self.change(edits=[{'old': 'hidden = 41', 'new': 'hidden = 42'}]), data)

    def test_complete_source_can_be_replaced_after_full_prefix_or_joined_range_delivery(self):
        data = self.read({'source.py': 'visible = 1\n'})
        self.assertFalse(view(data)['files']['source.py']['partial'])
        deliver(data)
        self.assertEqual('new source\n', self.apply(self.change(content='new source\n'))[0]['content'])
        self.assertIsNone(self.apply(self.change(delete=True))[0]['content'])
        data = self.read()
        for start, count in [(1, 2), (3, 1)]:
            data['workspace_receipts'] = [act(self.backend, self.job, {'action': 'read', 'head_sha': data['head_sha'],
                                          'path': 'source.py', 'start_line': start, 'line_count': count})]
            deliver(data)
        self.assertEqual('complete replacement', self.apply(self.change(content='complete replacement'))[0]['content'])

    def test_legacy_preview_cannot_bypass_new_head_bound_exposure(self):
        data = self.read()
        value = data['files']['source.py']
        value.pop('preview_characters', None)
        value['visible_characters'] = 16000
        with self.assertRaises(ValueError):
            self.apply(self.change(edits=[{'old': 'hidden = 41', 'new': 'hidden = 42'}]))
        deliver(data)
        with self.assertRaises(ValueError):
            self.apply(self.change(edits=[{'old': 'hidden = 41', 'new': 'hidden = 42'}]))

    def test_patch_anchors_remain_unique_nonoverlapping_and_bound_to_original(self):
        data = self.read({'source.py': 'first anchor\nsecond anchor\n'})
        deliver(data)
        result = self.apply(self.change(edits=[{'old': 'first anchor', 'new': 'first'}, {'old': 'second anchor', 'new': 'second'}]))
        self.assertEqual('first\nsecond\n', result[0]['content'])
        for edits in ([{'old': 'anchor', 'new': 'ambiguous'}],
                      [{'old': 'first anchor', 'new': 'first'}, {'old': 'anchor\nsecond', 'new': 'overlap'}],
                      [{'old': 'first anchor', 'new': 'new anchor'}, {'old': 'new anchor', 'new': 'not original'}]):
            with self.subTest(edits=edits), self.assertRaises(ValueError):
                self.apply(self.change(edits=edits))

    def test_text_budget_and_new_file_blob_guards_remain_authoritative(self):
        data = self.read({'source.py': 'visible = 1\n'})
        deliver(data)
        for content in ('x' * (512 * 1024 + 1), 'bad\0text'):
            with self.assertRaises(ValueError):
                self.apply(self.change(content=content))
        created = self.apply({'changes': [{'path': 'new.py', 'expected_blob_sha': None, 'content': 'new = 1\n'}]})
        self.assertEqual('new = 1\n', created[0]['content'])
        with self.assertRaises(ValueError):
            self.apply({'changes': [{'path': 'new.py', 'expected_blob_sha': 'f' * 40, 'content': 'new = 1\n'}]})

    def test_repair_feedback_is_bounded_revision_bound_and_cannot_authorize_proposed_source(self):
        data = self.read()
        data['files']['source.py']['preview_characters'] = 0
        data['changes'] = [{'path': 'source.py', 'expected_blob_sha': data['tree']['source.py']['sha'],
                            'mode': '100644', 'content': 'hidden = 41\n' + 'x' * (3 * 1024 * 1024)}]
        identity = patch_identity(data['head_sha'], data['changes'])
        data['local_validation'] = {'status': 'FAILED', 'patch_identity': identity, 'source_sha': data['head_sha'],
            'commands': [{'command': ['python', '-m', 'unittest'], 'exit_code': 1, 'output_tail': 'actual failure\n' * 1000}]}
        shown = view(data)
        feedback = shown['repair_feedback']
        self.assertTrue(feedback['validation_matches_patch'])
        self.assertEqual(identity, feedback['patch_identity'])
        self.assertFalse(feedback['complete'])
        self.assertTrue(feedback['omissions']['changes'])
        self.assertTrue(feedback['omissions']['local_validation'])
        self.assertLess(len(json.dumps(feedback).encode()), 32000)
        self.assertIn('actual failure', json.dumps(feedback['local_validation']))
        self.assertIn('hidden = 41', json.dumps(feedback['changes']))
        deliver(data, shown)
        with self.assertRaises(ValueError):
            self.apply(self.change(edits=[{'old': 'hidden = 41', 'new': 'hidden = 42'}]))
        for changed in ('head', 'patch', 'validation_source'):
            candidate = deepcopy(data)
            if changed == 'head':
                candidate['head_sha'] = 'b' * 40
            elif changed == 'patch':
                candidate['changes'][0]['content'] += 'another patch'
            else:
                candidate['local_validation']['source_sha'] = 'b' * 40
            with self.subTest(changed=changed):
                self.assertFalse(view(candidate)['repair_feedback']['validation_matches_patch'])


class AdmittedModelSourceTests(unittest.TestCase):
    setUp = selected_fixtures.SelectedExecutionTests.setUp
    tearDown = selected_fixtures.SelectedExecutionTests.tearDown
    runtime_for = selected_fixtures.SelectedExecutionTests.runtime_for
    service_for = selected_fixtures.SelectedExecutionTests.service_for

    def test_actual_selected_model_records_only_compacted_request_exposure(self):
        text = 'visible = 1\n'.ljust(2300, 'x') + 'hidden = 41\n'
        sha = blob_sha(text)
        self.job['data'].update(head_sha='a' * 40, plan={'paths': ['source.py']},
            tree={'source.py': {'sha': sha, 'mode': '100644'}},
            files={'source.py': {'sha': sha, 'content': text, 'preview_characters': 16000}})
        def count(payload, _capability):
            body = json.loads(payload['messages'][-1]['content'])
            value = body['evidence']['files']['source.py']['content']
            return (300 if isinstance(value, dict) and len(value.get('excerpt', '')) <= 2048 else 100000, 'SELECTED_TOKENIZER')
        with patch.object(self.service.capabilities, 'count', side_effect=count):
            self.backend.model(self.job, 'coding agent', view(self.job['data']))
        actual = json.loads(self.calls[-1]['body']['messages'][-1]['content'])['evidence']['files']['source.py']['content']
        self.assertIsInstance(actual, dict)
        self.assertNotIn('hidden = 41', actual['excerpt'])
        data = self.job['data']
        proposal = {'changes': [{'path': 'source.py', 'expected_blob_sha': sha, 'edits': [{'old': 'visible = 1', 'new': 'visible = 2'}]}]}
        result = prepare_changes(proposal, data['files'], data['tree'], head_sha=data['head_sha'])
        self.assertTrue(result[0]['content'].startswith('visible = 2\n'))
        proposal['changes'][0]['edits'][0]['old'] = 'hidden = 41'
        with self.assertRaises(ValueError):
            prepare_changes(proposal, data['files'], data['tree'], head_sha=data['head_sha'])

    def test_failed_review_carries_exact_patch_and_local_failure_into_next_coding_request(self):
        original, proposed = 'answer = 41\n', 'answer = 42\n'
        sha, head = blob_sha(original), 'a' * 40
        changes = [{'path': 'source.py', 'expected_blob_sha': sha, 'mode': '100644', 'content': proposed}]
        self.job['data'].update(head_sha=head, actor_id=7, plan={'paths': ['source.py']}, changes=changes,
            tree={'source.py': {'sha': sha, 'mode': '100644'}},
            files={'source.py': {'sha': sha, 'content': original, 'preview_characters': 16000}})
        validation = {'status': 'FAILED', 'source_sha': head, 'patch_identity': patch_identity(head, changes),
            'commands': [{'command': ['python', '-m', 'unittest', 'test_answer'], 'exit_code': 1,
                          'output_sha256': 'f' * 64, 'output_tail': 'AssertionError: expected 43, received 42'}]}
        self.assertTrue(self.store.complete(self.job, self.job['_execution_lease'], 'REVIEW', {'changes': changes}))
        self.backend.identity = Mock(return_value={'id': 7})
        self.backend.token = Mock(return_value='fixture-token')
        self.backend.api = Mock(return_value={'tree': {'sha': 'b' * 40}, 'permissions': {'pull': True, 'push': True}})
        with patch('v14.engineering_source.prepare_source', return_value={'status': 'READY', 'head_sha': head}), \
                patch('v14.engineering_backend.validate_checkout', return_value=validation):
            self.service.tick(self.job['id'])
        reviewed = self.store.get(self.job['id'])
        self.assertEqual('IMPLEMENT', reviewed['state'])
        self.assertFalse(reviewed['data']['review']['approved'])
        self.assertEqual(validation, reviewed['data']['local_validation'])
        # The fixture's next JSON is deliberately not a patch. The real coding
        # request is still dispatched and captured before that result is rejected.
        self.service.tick(self.job['id'])
        self.assertEqual(2, len(self.calls))
        body = self.calls[-1]['body']
        self.assertIn('coding agent', body['messages'][0]['content'])
        feedback = json.loads(body['messages'][-1]['content'])['evidence']['repair_feedback']
        self.assertEqual(head, feedback['head_sha'])
        self.assertEqual(validation['patch_identity'], feedback['patch_identity'])
        self.assertTrue(feedback['validation_matches_patch'])
        self.assertTrue(feedback['complete'])
        self.assertEqual(changes, feedback['changes'])
        self.assertEqual(validation, feedback['local_validation'])


class DurableSourceChangeTests(unittest.TestCase):
    setUp = durable_fixtures.DurableEngineeringTests.setUp
    tearDown = durable_fixtures.DurableEngineeringTests.tearDown

    def prepared(self):
        job = self.store.claim(self.store.create('owner', self.request)['id'])[0]
        original = 'visible = 1\n' + '# durable unchanged source\n' * 125000
        self.assertGreater(len(original.encode()), 3 * 1024 * 1024)
        self.assertLess(len(original.encode()), 4 * 1024 * 1024)
        sha = blob_sha(original)
        files = {'source.py': {'sha': sha, 'content': original, 'visible_characters': 2400}}
        tree = {'source.py': {'sha': sha, 'mode': '100644'}}
        job['data'].update(head_sha='a' * 40, files=files, tree=tree, plan={'paths': ['source.py']})
        self.assertTrue(self.store.complete(job, job['_execution_lease'], 'IMPLEMENT', {'files': files, 'tree': tree}))
        job = self.store.claim(job['id'])[0]
        self.service.artifacts.restore_stage(job)
        changes = prepare_changes({'changes': [{'path': 'source.py', 'expected_blob_sha': sha,
            'edits': [{'old': 'visible = 1', 'new': 'visible = 2'}]}]}, job['data']['files'], job['data']['tree'])
        job['data']['changes'] = changes
        return job, changes

    def test_three_mebibyte_anchored_edit_persists_implement_output_and_restores_exactly(self):
        job, changes = self.prepared()
        identity = patch_identity(job['data']['head_sha'], changes)
        self.assertGreater(len(json.dumps({'changes': changes}).encode()), 2 * 1024 * 1024)
        self.assertTrue(self.store.complete(job, job['_execution_lease'], 'REVIEW', {'changes': changes}))
        reopened = EngineeringStore(self.runtime, lambda: self.time)
        saved = reopened.get(job['id'])
        self.assertEqual('REVIEW', saved['state'])
        reference = saved['data']['changes'][0]['content']
        self.assertEqual('AVAILABLE', reference['availability'])
        self.assertLess(len(json.dumps(saved).encode()), 20000)
        self.assertEqual(['source.py'], public_job(saved)['changed_files'])
        self.assertEqual(['source.py'], stage_selection(saved, 'selected-model reviewer', {})[1])
        output = reopened.tx(lambda db: json.loads(db.execute('SELECT output_json FROM engineering_receipts WHERE job_id=? AND status=? ORDER BY revision DESC LIMIT 1',
                                                            (job['id'], 'CONFIRMED')).fetchone()[0]))
        self.assertEqual(reference, output['changes'][0]['content'])
        resumed = reopened.claim(job['id'])[0]
        EngineeringArtifacts(reopened).restore_stage(resumed)
        self.assertEqual(changes, resumed['data']['changes'])
        self.assertEqual(identity, patch_identity(resumed['data']['head_sha'], resumed['data']['changes']))
        self.assertNotIn(b'durable unchanged source', (Path(self.temp.name) / 'jobs.db').read_bytes())

    def test_twenty_small_replacements_persist_as_one_bounded_phase_and_restore_exactly(self):
        job = self.store.claim(self.store.create('owner', self.request)['id'])[0]
        sources = {f'source-{index}.py': f'visible_{index} = 1\n' + '# unchanged\n' * 10000 for index in range(20)}
        files = {path: {'sha': blob_sha(content), 'content': content, 'visible_characters': 2400}
                 for path, content in sources.items()}
        tree = {path: {'sha': value['sha'], 'mode': '100644'} for path, value in files.items()}
        job['data'].update(head_sha='a' * 40, files=files, tree=tree, plan={'paths': list(files)})
        self.assertTrue(self.store.complete(job, job['_execution_lease'], 'IMPLEMENT', {'files': files, 'tree': tree}))
        job = self.store.claim(job['id'])[0]
        self.service.artifacts.restore_stage(job)
        changes = prepare_changes({'changes': [{'path': path, 'expected_blob_sha': files[path]['sha'],
            'edits': [{'old': f'visible_{index} = 1', 'new': f'visible_{index} = 2'}]}
            for index, path in enumerate(files)]}, job['data']['files'], job['data']['tree'])
        self.assertTrue(all(len(json.dumps(change['content']).encode()) < 128 * 1024 for change in changes))
        self.assertGreater(len(json.dumps({'changes': changes}).encode()), 2 * 1024 * 1024)
        identity = patch_identity(job['data']['head_sha'], changes)
        job['data']['changes'] = changes
        self.assertTrue(self.store.complete(job, job['_execution_lease'], 'REVIEW', {'changes': changes}))
        reopened = EngineeringStore(self.runtime, lambda: self.time)
        saved = reopened.get(job['id'])
        self.assertEqual('REVIEW', saved['state'])
        self.assertLess(len(json.dumps(saved['data']['changes']).encode()), 128 * 1024)
        self.assertEqual(list(sources), public_job(saved)['changed_files'])
        self.assertEqual(list(sources), stage_selection(saved, 'selected-model reviewer', {})[1])
        self.assertTrue(all(change['content']['availability'] == 'AVAILABLE' for change in saved['data']['changes']))
        output = reopened.tx(lambda db: json.loads(db.execute('SELECT output_json FROM engineering_receipts WHERE job_id=? AND status=? ORDER BY revision DESC LIMIT 1',
                                                            (job['id'], 'CONFIRMED')).fetchone()[0]))
        self.assertEqual(saved['data']['changes'], output['changes'])
        resumed = reopened.claim(job['id'])[0]
        EngineeringArtifacts(reopened).restore_stage(resumed)
        self.assertEqual(changes, resumed['data']['changes'])
        self.assertEqual(identity, patch_identity(resumed['data']['head_sha'], resumed['data']['changes']))

    def test_change_artifact_rejects_owner_digest_and_chunk_corruption(self):
        job, changes = self.prepared()
        self.store.complete(job, job['_execution_lease'], 'REVIEW', {'changes': changes})
        saved = self.store.get(job['id'])
        reference = saved['data']['changes'][0]['content']
        for change in ('owner', 'digest'):
            candidate = deepcopy(saved)
            candidate['data'] = {'changes': deepcopy(saved['data']['changes'])}
            if change == 'owner':
                candidate['principal'] = 'someone-else'
            else:
                candidate['data']['changes'][0]['content']['sha256'] = 'f' * 64
            with self.subTest(change=change), self.assertRaises(ExecutionBindingError):
                self.service.artifacts.restore_stage(candidate)
        self.store.tx(lambda db: db.execute('UPDATE engineering_artifact_chunks SET digest=? WHERE artifact_id=? AND sequence=0',
                                           ('f' * 64, reference['result_ref'])))
        saved['data'] = {'changes': saved['data']['changes']}
        with self.assertRaises(ExecutionBindingError):
            self.service.artifacts.restore_stage(saved)

    def test_artifact_capacity_failure_cannot_confirm_successful_edit(self):
        job, changes = self.prepared()
        prior = self.store.get(job['id'])
        original_files = deepcopy(job['data']['files'])
        operations = self.service.operations.page(prior)
        artifact_ids = self.store.tx(lambda db: {row[0] for row in db.execute(
            'SELECT id FROM engineering_artifacts WHERE job_id=?', (job['id'],))})
        calls = list(self.backend.calls)
        with patch('v14.engineering_artifacts.MAX_ARTIFACT', 2 * 1024 * 1024):
            self.assertTrue(self.store.complete(job, job['_execution_lease'], 'REVIEW', {'changes': changes}))
            reopened = EngineeringStore(self.runtime, lambda: self.time)
            for _ in range(3):
                reopened.recover()
                self.service.tick(job['id'])
        saved = reopened.get(job['id'])
        self.assertEqual('NEEDS_INPUT', saved['state'])
        self.assertEqual(prior['revision'] + 1, saved['revision'])
        self.assertEqual(prior['request'], saved['request'])
        self.assertEqual(calls, self.backend.calls)
        self.assertNotIn(job['id'], reopened.due())
        self.assertIsNone(reopened.claim(job['id']))
        self.assertNotIn('changes', saved['data'])
        self.assertEqual([], public_job(saved)['changed_files'])
        for key in ('head_sha', 'files', 'tree', 'plan'):
            self.assertEqual(prior['data'][key], saved['data'][key])
        restored = deepcopy(saved)
        EngineeringArtifacts(reopened).restore_stage(restored)
        self.assertEqual(original_files, restored['data']['files'])
        self.assertEqual(operations, self.service.operations.page(saved))
        self.assertEqual([], operations['operations'])
        reason = saved['data']['blocked_reason']
        self.assertEqual('EXECUTION_ARTIFACT_CAPACITY', reason['code'])
        self.assertEqual('IMPLEMENT', reason['failed_stage'])
        self.assertFalse(reason['retryable'])
        self.assertFalse(reason['stage_result_retained'])
        self.assertFalse(reason['requires_reconciliation'])
        report = saved['data']['final_report']
        self.assertEqual('PARTIAL_UNVERIFIED', report['status'])
        self.assertFalse(report['terminal_receipt']['acceptance_verified'])
        self.assertEqual(1, report['terminal_receipt']['remaining_criteria'])
        self.assertEqual(saved['revision'], report['terminal_receipt']['checkpoint_revision'])
        # A confirmed checkpoint records the refusal, never the unsuccessful edit.
        receipt = reopened.tx(lambda db: db.execute(
            'SELECT status,output_json FROM engineering_receipts WHERE job_id=? AND revision=?',
            (job['id'], job['revision'])).fetchone())
        self.assertEqual('CONFIRMED', receipt[0])
        self.assertEqual({'error_code': reason['code'], 'checkpoint_result_retained': False,
                          'terminal_receipt': report['terminal_receipt']}, json.loads(receipt[1]))
        # Only the bounded partial report may be new; rejected source chunks roll back.
        retained_ids = reopened.tx(lambda db: {row[0] for row in db.execute(
            'SELECT id FROM engineering_artifacts WHERE job_id=?', (job['id'],))})
        self.assertEqual(artifact_ids | {report['artifact']['result_ref']}, retained_ids)
        self.assertEqual(0, reopened.tx(lambda db: db.execute(
            'SELECT COUNT(*) FROM engineering_artifact_chunks WHERE artifact_id NOT IN '
            '(SELECT id FROM engineering_artifacts)').fetchone()[0]))

    def source_backend(self, sources):
        backend = GitHubEngineeringBackend(self.runtime)
        backend.operations = self.service.operations
        backend.identity = Mock(return_value={'id': 1})
        tree = {path: {'sha': blob_sha(content), 'mode': '100644'} for path, content in sources.items()}
        by_sha = {tree[path]['sha']: content for path, content in sources.items()}
        backend.tree = Mock(return_value=tree)
        def blob(url, **_kwargs):
            raw = by_sha[url.rsplit('/', 1)[1]].encode()
            return {'encoding': 'base64', 'size': len(raw), 'content': base64.b64encode(raw).decode()}
        backend.api = Mock(side_effect=blob)
        return backend

    def test_two_legal_four_mebibyte_sources_read_checkpoint_and_restart_without_journal_overflow(self):
        sources = {'first.py': 'a' * (4 * 1024 * 1024), 'second.py': 'b' * (4 * 1024 * 1024)}
        backend = self.source_backend(sources)
        job = self.store.claim(self.store.create('owner', self.request)['id'])[0]
        job['deadline'] = time.time() + 60
        job['data'].update(head_sha='a' * 40, actor_id=1, plan={'paths': list(sources)})
        output = backend.step(job, 'READ')
        self.assertEqual(sources, {path: value['content'] for path, value in output['files'].items()})
        self.assertEqual(2, backend.api.call_count)
        raw = self.store.tx(lambda db: db.execute('SELECT receipts_json FROM gateway_batches WHERE job_id=?', (job['id'],)).fetchone()[0])
        receipts = json.loads(raw)
        self.assertLess(len(raw.encode()), 4000)
        self.assertEqual(8 * 1024 * 1024, sum(value['source_bytes'] for value in receipts.values()))
        self.assertTrue(all(value['content']['source_sha'] == job['data']['head_sha'] for value in receipts.values()))
        self.assertTrue(all(value['read_head_sha'] == job['data']['head_sha'] for value in receipts.values()))
        job['data'].update(output)
        self.assertTrue(self.store.complete(job, job['_execution_lease'], 'IMPLEMENT', output))
        reopened = EngineeringStore(self.runtime, lambda: self.time)
        saved = reopened.get(job['id'])
        self.assertLess(len(json.dumps(saved).encode()), 20000)
        resumed = reopened.claim(job['id'])[0]
        EngineeringArtifacts(reopened).restore_stage(resumed)
        self.assertEqual(sources, {path: value['content'] for path, value in resumed['data']['files'].items()})
        self.assertEqual(2, backend.api.call_count)

    def test_unchanged_large_blob_can_be_read_after_same_job_head_advances(self):
        source = 'unchanged source\n' * 20000
        backend = self.source_backend({'source.py': source})
        job = self.store.claim(self.store.create('owner', self.request)['id'])[0]
        job['deadline'] = time.time() + 60
        job['data'].update(head_sha='a' * 40, actor_id=1, plan={'paths': ['source.py']})
        first = backend.step(job, 'READ')
        first_receipt = self.store.tx(lambda db: json.loads(db.execute('SELECT receipts_json FROM gateway_batches WHERE job_id=?',
                                                                      (job['id'],)).fetchone()[0]))['0']
        first_reference = first_receipt['content']
        job['data'].update(first)
        self.assertTrue(self.store.complete(job, job['_execution_lease'], 'IMPLEMENT', first))
        job = self.store.claim(job['id'])[0]
        self.service.artifacts.restore_stage(job)
        job['data']['head_sha'] = 'b' * 40
        self.assertTrue(self.store.complete(job, job['_execution_lease'], 'READ', {'head_sha': job['data']['head_sha']}))
        job = self.store.claim(job['id'])[0]
        self.service.artifacts.restore_stage(job)
        second = backend.step(job, 'READ')
        self.assertEqual(source, second['files']['source.py']['content'])
        self.assertEqual(1, backend.api.call_count)
        receipts = self.store.tx(lambda db: [json.loads(row[0])['0'] for row in db.execute(
            'SELECT receipts_json FROM gateway_batches WHERE job_id=?', (job['id'],)).fetchall()])
        current = next(receipt for receipt in receipts if receipt.get('read_head_sha') == 'b' * 40)
        self.assertEqual(first_reference, current['content'])
        self.assertEqual('a' * 40, current['content']['source_sha'])
        self.assertEqual(blob_sha(source), current['sha'])
        self.assertEqual(len(source.encode()), current['source_bytes'])
        job['data'].update(second)
        self.assertTrue(self.store.complete(job, job['_execution_lease'], 'IMPLEMENT', second))
        reopened = EngineeringStore(self.runtime, lambda: self.time)
        resumed = reopened.claim(job['id'])[0]
        EngineeringArtifacts(reopened).restore_stage(resumed)
        self.assertEqual('b' * 40, resumed['data']['head_sha'])
        self.assertEqual(source, resumed['data']['files']['source.py']['content'])

    def test_old_large_blob_cannot_satisfy_changed_tree_even_with_current_head_receipt(self):
        source = 'retained source\n' * 20000
        backend = self.source_backend({'source.py': source})
        job = self.store.claim(self.store.create('owner', self.request)['id'])[0]
        job['deadline'] = time.time() + 60
        job['data'].update(head_sha='a' * 40, actor_id=1, plan={'paths': ['source.py']})
        backend.step(job, 'READ')
        receipts = self.store.tx(lambda db: json.loads(db.execute('SELECT receipts_json FROM gateway_batches WHERE job_id=?',
                                                                 (job['id'],)).fetchone()[0]))
        job['data']['head_sha'] = 'b' * 40
        backend.journal.open = Mock(return_value=deepcopy(receipts))
        with self.assertRaisesRegex(ValueError, 'another pinned revision'):
            backend.step(job, 'READ')
        changed_sha = blob_sha('changed! source\n' + source.split('\n', 1)[1])
        backend.tree = Mock(return_value={'source.py': {'sha': changed_sha, 'mode': '100644'}})
        receipts['0'].update(read_head_sha='b' * 40, sha=changed_sha)
        backend.journal.open = Mock(return_value=receipts)
        with self.assertRaisesRegex(ValueError, 'pinned blob and byte length'):
            backend.step(job, 'READ')
        self.assertEqual(1, backend.api.call_count)

    def test_utf8_byte_budget_rejects_aggregate_that_character_count_would_allow(self):
        sources = {f'file-{index}.py': chr(0x1f600 + index) * (1024 * 1024) for index in range(5)}
        self.assertEqual(5 * 1024 * 1024, sum(map(len, sources.values())))
        self.assertEqual(20 * 1024 * 1024, sum(len(value.encode()) for value in sources.values()))
        backend = self.source_backend(sources)
        job = self.store.claim(self.store.create('owner', self.request)['id'])[0]
        job['deadline'] = time.time() + 60
        job['data'].update(head_sha='a' * 40, actor_id=1, plan={'paths': list(sources)})
        with self.assertRaisesRegex(ValueError, 'retained context budget'):
            backend.step(job, 'READ')
        self.assertNotIn('files', self.store.get(job['id'])['data'])

    def test_source_artifact_owner_revision_digest_and_raw_length_are_bound_on_batch_resume(self):
        source = 'retained source\n' * 20000
        backend = self.source_backend({'source.py': source})
        job = self.store.claim(self.store.create('owner', self.request)['id'])[0]
        job['deadline'] = time.time() + 60
        job['data'].update(head_sha='a' * 40, actor_id=1, plan={'paths': ['source.py']})
        backend.step(job, 'READ')
        raw = self.store.tx(lambda db: db.execute('SELECT receipts_json FROM gateway_batches WHERE job_id=?', (job['id'],)).fetchone()[0])
        original = json.loads(raw)
        for field, value in [('source_sha', 'b' * 40), ('sha256', 'f' * 64), ('owner_scope', 'foreign')]:
            with self.subTest(field=field):
                receipts = deepcopy(original)
                receipts['0']['content'][field] = value
                backend.journal.open = Mock(return_value=receipts)
                with self.assertRaises((ValueError, ExecutionBindingError)):
                    backend.step(job, 'READ')
        for head in (None, 'b' * 40):
            receipts = deepcopy(original)
            receipts['0']['read_head_sha'] = head
            backend.journal.open = Mock(return_value=receipts)
            with self.subTest(read_head=head), self.assertRaisesRegex(ValueError, 'another pinned revision'):
                backend.step(job, 'READ')
        receipts = deepcopy(original)
        receipts['0']['source_bytes'] -= 1
        backend.journal.open = Mock(return_value=receipts)
        with self.assertRaisesRegex(ValueError, 'byte length'):
            backend.step(job, 'READ')
        self.assertEqual(1, backend.api.call_count)


if __name__ == '__main__':
    unittest.main()
