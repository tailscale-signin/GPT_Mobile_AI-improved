"""Real journal/engine/HTTP contracts; only external GitHub/model I/O is mocked."""
import asyncio
import base64
import copy
import hashlib
import json
import sqlite3
import threading
import unittest
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.requests import Request

import test_gateway_v14_engineering as engineering_fixture
import test_gateway_v14_jobs as journal_fixture
import export_continuity_contract_fixtures as wire_fixtures
from gateway_security import DeviceStore, current_device
from v14.config import Config
from v14.continuity_routes import install_continuity_routes
from v14.continuity import profile_metadata
from v14.engineering import EngineeringService, EngineeringStore, validate_request
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha
from v14.execution_binding import ExecutionBindingError
from v14.identity import Admission
from v14.jobs import DurableCommitError
from v14.service import ServiceState


class ConversationTests(unittest.TestCase):
    setUp = engineering_fixture.DurableEngineeringTests.setUp
    tearDown = engineering_fixture.DurableEngineeringTests.tearDown

    def create(self, command='request'):
        return self.store.create('owner', dict(self.request, request_id=command, scope='read', profile_name='Accepted profile'))

    def test_lost_create_response_reconciles_without_another_job_or_model_dispatch(self):
        job = self.create()
        first = self.store.continuity.command('owner', 'request')
        again = self.create()
        self.assertEqual(job['id'], again['id'])
        self.assertEqual(first, self.store.continuity.command('owner', 'request'))
        self.assertEqual('accepted', first['status'])
        self.assertEqual([], self.backend.calls)
        self.assertEqual(1, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_jobs').fetchone()[0]))
        with self.assertRaises(ExecutionBindingError) as error:
            self.store.continuity.command('different-owner', 'request')
        self.assertEqual(404, error.exception.status)
        with self.assertRaises(ValueError):
            self.store.create('owner', dict(self.request, scope='read', profile_name='Accepted profile', objective='Changed command'))

    def test_catalog_cursor_survives_restart_is_scoped_and_excludes_later_admissions(self):
        jobs = [self.create('page-' + str(index)) for index in range(5)]
        identity = self.store.continuity.identity('owner', 'boot-one', 'user')
        first = self.store.continuity.catalog('owner', limit=2)
        self.create('arrived-after-snapshot')
        restarted = EngineeringStore(self.runtime, lambda: self.time)
        second_identity = restarted.continuity.identity('owner', 'boot-two', 'user')
        self.assertEqual(identity['gateway_id'], second_identity['gateway_id'])
        self.assertNotEqual(identity['instance_id'], second_identity['instance_id'])
        seen = [row['job_id'] for row in first['items']]
        page = first
        while page['has_more']:
            page = restarted.continuity.catalog('owner', page['next_cursor'], 2)
            seen.extend(row['job_id'] for row in page['items'])
        self.assertEqual({job['id'] for job in jobs}, set(seen))
        self.assertEqual(5, len(seen))
        with self.assertRaises(ExecutionBindingError):
            restarted.continuity.catalog('another-owner', first['next_cursor'], 2)
        with self.assertRaises(ExecutionBindingError):
            restarted.continuity.catalog('owner', first['next_cursor'], 2, kinds=('chat',))

    def test_message_replay_cursor_tails_new_messages_and_terminal_delivery_is_idempotent(self):
        job = self.create()
        conversation_id = job['conversation_id']
        first = self.store.continuity.messages('owner', conversation_id)
        self.assertFalse(first['has_more'])
        self.assertTrue(first['next_cursor'])
        self.assertEqual([1], [message['sequence'] for message in first['items']])
        cancelled = self.store.control(job['id'], 'owner', 'cancel', 0)
        self.store.tx(lambda db: self.store.continuity.record_engineering(db, cancelled, 'CANCELLED', 1))
        tail = self.store.continuity.messages('owner', conversation_id, first['next_cursor'])
        self.assertEqual(['final'], [message['kind'] for message in tail['items']])
        self.assertEqual([2], [message['sequence'] for message in tail['items']])
        empty = self.store.continuity.messages('owner', conversation_id, tail['next_cursor'])
        self.assertEqual([], empty['items'])
        self.assertEqual(tail['next_cursor'], empty['next_cursor'])
        self.store.recover()
        replay = self.store.continuity.messages('owner', conversation_id)
        self.assertEqual(2, len(replay['items']))
        self.assertEqual(1, sum(message['kind'] == 'final' for message in replay['items']))

    def test_followup_revision_fence_exact_binding_and_lost_response(self):
        job = self.create()
        cid = job['conversation_id']
        before = self.store.continuity.conversation('owner', cid)
        self.store.control(job['id'], 'owner', 'cancel', 0)
        command = {'command_id': 'follow-up', 'expected_revision': before['revision'], 'content': 'Explain the retained result', 'scope': 'plan'}
        with self.assertRaises(ExecutionBindingError) as stale:
            self.store.continuity.followup_source('owner', cid, command)
        self.assertEqual('CONVERSATION_REVISION_CHANGED', stale.exception.code)
        command['expected_revision'] = self.store.continuity.conversation('owner', cid)['revision']
        source, existing, digest = self.store.continuity.followup_source('owner', cid, command)
        self.assertIsNone(existing)
        for name in ('profile_id', 'model', 'repository', 'branch', 'execution_binding_id'):
            self.assertEqual(job['request'][name], source[name])
        admitted = self.store.create('owner', source, followup=command, command_hash=digest)
        replay_source, replay_job, _ = self.store.continuity.followup_source('owner', cid, command)
        self.assertIsNone(replay_source)
        self.assertEqual(admitted['id'], replay_job)
        self.assertEqual(cid, admitted['conversation_id'])
        self.assertEqual('plan', admitted['request']['scope'])
        self.assertEqual([], self.backend.calls)
        with self.assertRaises(ExecutionBindingError) as conflict:
            self.store.continuity.followup_source('owner', cid, dict(command, scope='implement'))
        self.assertEqual('COMMAND_ID_CONFLICT', conflict.exception.code)

    def test_followup_rechecks_revision_inside_the_job_admission_transaction(self):
        job = self.create()
        self.store.control(job['id'], 'owner', 'cancel', 0)
        cid = job['conversation_id']
        command = {'command_id': 'racing-send', 'expected_revision': self.store.continuity.conversation('owner', cid)['revision'], 'content': 'Read more', 'scope': 'read'}
        source, _, digest = self.store.continuity.followup_source('owner', cid, command)
        self.store.tx(lambda db: self.store.continuity.append(db, cid, job['id'], 'newer-event', 'assistant', 'status', 'Newer retained event', self.time))
        with self.assertRaises(ExecutionBindingError):
            self.store.create('owner', source, followup=command, command_hash=digest)
        self.assertEqual(1, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_jobs').fetchone()[0]))

    def test_followup_reads_confirmed_working_head_without_rebinding_original_source(self):
        request = dict(self.request, scope='implement', branch='feature/source', base='feature/source',
                       source_branch='feature/source', source_sha='a' * 40)
        job = self.store.create('owner', request)
        claimed, lease, _ = self.store.claim(job['id'])
        claimed['data']['head_sha'] = 'b' * 40  # External write receipt boundary.
        self.store.complete(claimed, lease, 'NEEDS_INPUT', {'head_sha': 'b' * 40})
        cid = job['conversation_id']
        command = {'command_id': 'explain-change', 'expected_revision': self.store.continuity.conversation('owner', cid)['revision'],
                   'content': 'Explain the confirmed change', 'scope': 'read'}
        source, _, digest = self.store.continuity.followup_source('owner', cid, command)
        followup = self.store.create('owner', source, followup=command, command_hash=digest)
        self.assertEqual('a' * 40, followup['request']['source_sha'])
        self.assertEqual('feature/source', followup['request']['source_branch'])
        self.assertEqual('b' * 40, followup['continuation_head_sha'])
        self.assertEqual(job['request']['branch'], followup['continuation_branch'])
        backend = GitHubEngineeringBackend(self.runtime)
        backend.identity = lambda job: {'id': 7}
        backend.api = lambda *args, **kwargs: {'default_branch': 'main', 'permissions': {'pull': True}}
        backend.ref = lambda job, branch: 'c' * 40 if branch == 'feature/source' else None
        seen = []
        backend.tree = lambda job, head: seen.append(head) or {'README.md': {'mode': '100644', 'sha': 'd' * 40}}
        backend.model = lambda *args: {'paths': ['README.md'], 'plan': 'Explain the retained change'}
        self.assertEqual('b' * 40, backend.step(followup, 'PLAN')['head_sha'])
        self.assertEqual(['b' * 40], seen)
        implementing = copy.deepcopy(followup)
        implementing['request']['scope'] = 'implement'
        backend.api = lambda *args, **kwargs: {'default_branch': 'main', 'permissions': {'pull': True, 'push': True}}
        with self.assertRaises(ExecutionBindingError) as mismatch:
            backend.step(implementing, 'PLAN')
        self.assertEqual('WORKING_REVISION_CHANGED', mismatch.exception.code)

    def test_explicit_retention_gap_is_incomplete_and_does_not_reset_cursor(self):
        job = self.create()
        self.store.control(job['id'], 'owner', 'cancel', 0)
        cid = job['conversation_id']
        self.store.tx(lambda db: db.execute('DELETE FROM gateway_conversation_messages WHERE conversation_id=? AND sequence=1', (cid,)))
        page = self.store.continuity.messages('owner', cid)
        self.assertTrue(page['gap'])
        self.assertFalse(page['history_complete'])
        self.assertEqual('retention_gap', page['history_limitation'])
        self.assertEqual([2], [message['sequence'] for message in page['items']])
        self.assertEqual([], self.store.continuity.messages('owner', cid, page['next_cursor'])['items'])

    def test_large_logical_message_replays_bounded_parts_after_interruption_without_duplicate_final(self):
        job = self.create()
        cid = job['conversation_id']
        original = ('A retained answer 🌍\n' * 120000) + '\x00' * 270000
        self.assertGreater(len(original.encode('utf-8')), 2_000_000)
        def append(db, content=original):
            return self.store.continuity.append(db, cid, job['id'], 'large-final', 'assistant', 'final', content, self.time)
        self.assertTrue(self.store.tx(append))
        self.assertFalse(self.store.tx(append))
        with self.assertRaises(ValueError):
            self.store.tx(lambda db: append(db, original + 'changed'))
        first = self.store.continuity.messages('owner', cid, limit=2)
        replay_first = self.store.continuity.messages('owner', cid, limit=2)
        self.assertEqual(first, replay_first)
        # Simulate a client crash after committing its first physical page.
        restarted = EngineeringStore(self.runtime, lambda: self.time)
        pages, parts = [first], list(first['items'])
        while pages[-1]['has_more']:
            page = restarted.continuity.messages('owner', cid, pages[-1]['next_cursor'])
            pages.append(page)
            parts.extend(page['items'])
        for page in pages:
            self.assertLess(len(json.dumps(page, ensure_ascii=False, separators=(',', ':')).encode()), 2_000_000)
            self.assertFalse(page['gap'])
        finals = [item for item in parts if item['kind'] == 'final']
        self.assertGreater(len(finals), 1)
        self.assertEqual(list(range(len(finals))), [part['part_index'] for part in finals])
        self.assertEqual({len(finals)}, {part['part_count'] for part in finals})
        self.assertEqual(1, len({part['logical_message_id'] for part in finals}))
        self.assertEqual(len(parts), len({part['sequence'] for part in parts}))
        self.assertTrue(all(len(part['content'].encode('utf-8')) <= 256 * 1024 for part in finals))
        restored = ''.join(part['content'] for part in finals)
        self.assertEqual(original, restored)
        self.assertEqual({hashlib.sha256(restored.encode()).hexdigest()}, {part['content_sha256'] for part in finals})
        self.assertEqual(original, self.store.tx(lambda db: db.execute(
            'SELECT content FROM gateway_logical_messages WHERE logical_message_id=?', (finals[0]['logical_message_id'],)).fetchone()[0]))
        self.assertEqual([], restarted.continuity.messages('owner', cid, pages[-1]['next_cursor'])['items'])

    def test_terminal_page_reports_a_missing_trailing_part_immediately(self):
        job = self.create()
        cid = job['conversation_id']
        self.store.tx(lambda db: self.store.continuity.append(db, cid, job['id'], 'partial-final',
            'assistant', 'final', 'x' * 300000, self.time))
        watermark = self.store.continuity.conversation('owner', cid)['message_watermark']
        self.store.tx(lambda db: db.execute('DELETE FROM gateway_conversation_messages WHERE conversation_id=? AND sequence=?', (cid, watermark)))
        page = self.store.continuity.messages('owner', cid)
        self.assertFalse(page['has_more'])
        self.assertTrue(page['gap'])
        self.assertFalse(page['history_complete'])
        self.assertEqual({'missing_ranges': [{'first_sequence': watermark, 'last_sequence': watermark}]}, page['gap_details'])
        final_parts = [item for item in page['items'] if item['kind'] == 'final']
        self.assertLess(len(final_parts), final_parts[0]['part_count'])
        self.assertFalse(self.store.continuity.conversation('owner', cid)['history_complete'])
        tail = self.store.continuity.messages('owner', cid, page['next_cursor'])
        self.assertEqual([], tail['items'])
        self.assertFalse(tail['history_complete'])
        self.assertFalse(tail['gap'])

    def test_real_http_gap_fixture_covers_interior_and_trailing_ranges_without_replay(self):
        destination = Path(self.temp.name) / 'http-wire-fixtures'
        wire_fixtures.export_gaps(destination)
        page = json.loads((destination / 'replay-interior-trailing-gap.json').read_text())
        self.assertEqual([1, 3], [item['sequence'] for item in page['items']])
        self.assertEqual(4, page['latest_sequence'])
        self.assertEqual({'missing_ranges': [{'first_sequence': 2, 'last_sequence': 2},
                                             {'first_sequence': 4, 'last_sequence': 4}]}, page['gap_details'])
        snapshot = json.loads((destination / 'replay-interior-trailing-gap-snapshot.json').read_text())
        tail = json.loads((destination / 'replay-interior-trailing-gap-tail.json').read_text())
        self.assertFalse(snapshot['history_complete'])
        self.assertEqual([], tail['items'])
        self.assertFalse(tail['gap'])
        self.assertFalse(tail['history_complete'])
        self.assertEqual(page['next_cursor'], tail['next_cursor'])

    def test_snapshot_and_command_keep_full_job_shape_and_original_source_binding(self):
        job = self.store.create('owner', dict(self.request, scope='read', branch='feature/source', base='feature/source',
            source_branch='feature/source', source_sha='a' * 40, profile_name='原始 profile'))
        snapshot = self.store.continuity.conversation('owner', job['conversation_id'])
        receipt = self.store.continuity.command('owner', 'request')
        self.assertEqual(job['id'], snapshot['latest_job']['job_id'])
        self.assertEqual('queued', snapshot['latest_job']['scheduling_state'])
        self.assertEqual('feature/source', snapshot['source_branch'])
        self.assertEqual('a' * 40, snapshot['source_sha'])
        self.assertEqual('feature/source', snapshot['working_branch'])
        self.assertEqual(job['execution_binding'], snapshot['execution_binding'])
        self.assertEqual(job['execution_binding'], receipt['job']['execution_binding'])
        self.assertEqual(job['id'], receipt['job']['id'])
        self.assertEqual(12, receipt['job']['chat_id'])
        self.assertEqual(job['created_at'], receipt['job']['created_at'])
        self.assertEqual(job['deadline'], receipt['job']['deadline'])

    def test_http_custom_target_keeps_original_base_distinct_from_source_and_working_branch(self):
        destination = Path(self.temp.name) / 'custom-base-fixtures'
        wire_fixtures.export_custom_base(destination)
        catalog = json.loads((destination / 'custom-base-catalog.json').read_text())
        snapshot = json.loads((destination / 'custom-base-snapshot.json').read_text())
        command = json.loads((destination / 'custom-base-command.json').read_text())
        for value in (catalog['items'][0], snapshot, snapshot['latest_job'], command['job']):
            self.assertEqual('release/base', value['base'])
            self.assertEqual('feature/source', value['source_branch'])
            self.assertEqual('a' * 40, value['source_sha'])
            self.assertEqual('work/custom', value['working_branch'])
            self.assertEqual(snapshot['execution_binding'], value['execution_binding'])
        self.assertEqual('work/custom', command['job']['branch'])
        self.assertEqual('feature/source', snapshot['branch'])
        self.assertIn('follow_up', snapshot['permitted_actions'])

    def test_current_source_branch_read_and_plan_are_valid_and_implementation_gets_owned_target(self):
        for scope in ('read', 'plan'):
            value = validate_request(dict(self.request, branch='feature/source', base='feature/source', source_branch='feature/source', source_sha='a' * 40, scope=scope))
            self.assertEqual('feature/source', value['branch'])
        value = validate_request(dict(self.request, branch='feature/source', base='feature/source', source_branch='feature/source', source_sha='a' * 40, scope='implement'))
        self.assertTrue(value['branch'].startswith('codex/gateway-'))
        self.assertEqual('feature/source', value['base'])
        self.assertEqual('a' * 40, value['source_sha'])
        self.assertEqual(value, validate_request(value))

    def test_read_scope_cannot_progress_to_mutation_even_with_a_tampered_phase(self):
        for phase in ('COMMIT', 'MERGE', 'CI', 'VERIFY', 'IMPLEMENT'):
            with self.subTest(phase=phase):
                job = self.create('tampered-' + phase)
                self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET state=? WHERE id=?', (phase, job['id'])))
                self.service.tick(job['id'])
                blocked = self.store.get(job['id'])
                self.assertEqual('NEEDS_INPUT', blocked['state'])
                self.assertEqual('ENGINEERING_SCOPE_READ_ONLY', blocked['data']['blocked_reason']['code'])
                self.assertFalse(blocked['data']['blocked_reason']['dispatched'])
                self.assertEqual([], self.backend.calls)

    def test_actual_read_and_plan_backend_finish_on_pinned_source_without_a_write_or_pr_engine(self):
        self.time = __import__('time').time()
        probe = self.service.capabilities._probe
        backend = GitHubEngineeringBackend(self.runtime)
        service = EngineeringService(self.store, backend)
        service.capabilities._probe = probe
        source = 'A documented read-only behavior.\n'
        sha = blob_sha(source)
        requests, models = [], []
        def api(path, method='GET', body=None, job=None, text=False):
            requests.append((method, path))
            self.assertEqual('GET', method)
            if path == '/user':
                return {'id': 7}
            if path == '/repos/owner/repo':
                return {'default_branch': 'main', 'permissions': {'pull': True, 'push': False}}
            if '/git/ref/heads/feature%2Fsource' in path:
                return {'object': {'sha': 'a' * 40}}
            if '/git/commits/' in path:
                return {'tree': {'sha': 'b' * 40}}
            if '/git/trees/' in path:
                return {'truncated': False, 'tree': [{'path': 'README.md', 'type': 'blob', 'mode': '100644', 'sha': sha}]}
            if '/git/blobs/' in path:
                return {'encoding': 'base64', 'size': len(source), 'content': base64.b64encode(source.encode()).decode()}
            self.fail('Unexpected repository operation: ' + path)
        def selected_model(job, role, payload, cancellation):
            models.append((role, payload['model']))
            value = {'paths': ['README.md'], 'plan': 'Inspect documented behavior'} if role == 'planner' else {
                'answer': 'The source documents read-only behavior.', 'paths': ['README.md'], 'limitations': ['Runtime behavior was not executed']}
            return {'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(value)}}]}
        backend.api = api
        backend.model_executor = selected_model
        for scope in ('read', 'plan'):
            with self.subTest(scope=scope):
                job = self.store.create('owner', dict(self.request, request_id=scope, branch='feature/source', base='feature/source',
                    source_branch='feature/source', source_sha='a' * 40, scope=scope))
                service.tick(job['id'])
                service.tick(job['id'])
                completed = self.store.get(job['id'])
                self.assertEqual('COMPLETED', completed['state'], completed['message'])
                report = completed['data']['final_report']
                self.assertEqual('COMPLETED_' + scope.upper(), report['status'])
                self.assertEqual('a' * 40, report['head_sha'])
                self.assertEqual(['README.md'], report['inspected_paths'])
                self.assertEqual(0, report['repository_mutations'])
                self.assertFalse(report['tests_executed'])
                self.assertFalse(report['merge_performed'])
                self.assertEqual(0, self.store.tx(lambda db: db.execute(
                    "SELECT COUNT(*) FROM engineering_receipts WHERE job_id=? AND phase IN ('COMMIT','OPEN_PR','PROMOTE','MERGE')", (job['id'],)).fetchone()[0]))
        self.assertEqual([('planner', 'local'), ('final report', 'local')] * 2, models)
        self.assertTrue(requests)
        self.assertFalse(any('/pulls' in path for _, path in requests))

    def test_http_catalog_replay_and_command_lookup_never_dispatch_and_enforce_current_scopes(self):
        engineering_fixture.DurableEngineeringTests.admit(self, 'tailnet:owner')
        job = self.store.create('tailnet:owner', dict(self.request, scope='read'))
        config = Config(mode='trusted_proxy', trusted_users={'alice': {'principalId': 'owner', 'scopes': ['engineering']},
                                                            'bob': {'principalId': 'other', 'scopes': ['engineering']}})
        app = FastAPI()
        app.add_middleware(Admission, config=config, store=DeviceStore(self.temp.name + '/auth.db'))
        install_continuity_routes(app, self.service, ServiceState(), config)
        with TestClient(app, base_url='http://127.0.0.1:8090', client=('127.0.0.1', 1234)) as client:
            owner = {'tailscale-user-login': 'alice', 'x-gateway-principal': 'forged'}
            identity = client.get('/v1/gateway/identity', headers=owner)
            self.assertEqual('tailnet:owner', identity.json()['principal_id'])
            catalog = client.get('/v1/gateway/job-catalog', headers=owner)
            self.assertEqual([job['id']], [item['job_id'] for item in catalog.json()['items']])
            snapshot = client.get('/v1/gateway/conversations/' + job['conversation_id'], headers=owner)
            self.assertTrue(snapshot.json()['history_complete'])
            messages = client.get('/v1/gateway/conversations/' + job['conversation_id'] + '/messages', headers=owner)
            self.assertEqual(1, len(messages.json()['items']))
            self.assertEqual(200, client.get('/v1/gateway/commands/request', headers=owner).status_code)
            other = {'tailscale-user-login': 'bob'}
            self.assertEqual(404, client.get('/v1/gateway/conversations/' + job['conversation_id'], headers=other).status_code)
            self.assertEqual(404, client.get('/v1/gateway/commands/request', headers=other).status_code)
            config.trusted_users['alice']['scopes'] = ['jobs']
            self.assertEqual([], client.get('/v1/gateway/job-catalog', headers=owner).json()['items'])
            self.assertEqual(403, client.get('/v1/gateway/conversations/' + job['conversation_id'], headers=owner).status_code)
        self.assertEqual([], self.backend.calls)


class OrdinaryLifecycleTests(unittest.TestCase):
    setUpClass = classmethod(journal_fixture.JournalTests.setUpClass.__func__)
    setUp = journal_fixture.JournalTests.setUp
    client = journal_fixture.JournalTests.client

    def test_captured_unicode_profile_and_full_visible_input_transcript_survive_recovery(self):
        name = '  原始 profile 🌍  '
        metadata = {'X-Gateway-Profile-ID': 'profile_original-1',
                    'X-Gateway-Profile-Name-Base64': base64.urlsafe_b64encode(name.encode()).decode().rstrip('=')}
        transcript = [{'role': 'system', 'content': 'Internal prompt is not visible conversation history'},
                      {'role': 'user', 'content': 'Original question'},
                      {'role': 'assistant', 'content': 'Earlier visible response'},
                      {'role': 'user', 'content': 'Follow-up question'}]
        result = {'choices': [{'message': {'role': 'assistant', 'content': 'Saved final answer'}, 'finish_reason': 'stop'}]}
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', return_value=(200, result)) as model:
            response = client.post('/v1/chat/completions', headers=metadata,
                json={'model': 'original-model', 'stream': False, 'messages': transcript})
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(1, model.call_count)
            catalog = client.get('/v1/gateway/job-catalog').json()
            item = catalog['items'][0]
            self.assertEqual(name, item['profile_name'])
            self.assertEqual('profile_original-1', item['profile_id'])
            self.assertEqual('original-model', item['model'])
            self.assertTrue(item['history_complete'])
            snapshot = client.get('/v1/gateway/conversations/' + item['conversation_id']).json()
            self.assertEqual(item['job_id'], snapshot['latest_job']['job_id'])
            self.assertEqual('completed', snapshot['latest_job']['scheduling_state'])
            messages = client.get('/v1/gateway/conversations/' + item['conversation_id'] + '/messages').json()
            visible = [message['content'] for message in messages['items'] if message['kind'] != 'status']
            self.assertEqual([message['content'] for message in transcript[1:]] + ['Saved final answer'], visible)
        self.runtime.job_registry.clear()
        self.runtime.durable_job_initialized = False
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as forbidden:
            restored = client.get('/v1/gateway/conversations/' + item['conversation_id']).json()
            self.assertEqual(name, restored['profile_name'])
            self.assertEqual('original-model', restored['model'])
            self.assertTrue(restored['history_complete'])
            forbidden.assert_not_called()

    def test_profile_headers_validate_alphabet_bounds_encoding_and_controls_without_granting_authority(self):
        self.assertEqual({'profile_id': '', 'profile_name': ''}, profile_metadata({}))
        self.assertEqual('a' * 160, profile_metadata({'x-gateway-profile-id': 'a' * 160})['profile_id'])
        for headers in ({'x-gateway-profile-id': 'a' * 161}, {'x-gateway-profile-id': 'owner:admin'},
                        {'x-gateway-profile-name-base64': 'YQ=='}, {'x-gateway-profile-name-base64': '+'},
                        {'x-gateway-profile-name-base64': base64.urlsafe_b64encode(b'\n').decode().rstrip('=')},
                        {'x-gateway-profile-name-base64': base64.urlsafe_b64encode(b'a' * 1025).decode().rstrip('=')},
                        {'x-gateway-profile-name-base64': base64.urlsafe_b64encode(b'\xff').decode().rstrip('=')}):
            with self.subTest(headers=headers), self.assertRaises(ValueError):
                profile_metadata(headers)

    def test_disabled_journal_does_not_advertise_persistent_gateway_identity(self):
        with patch.object(self.runtime, 'GATEWAY_DURABLE_JOBS', False), patch.object(self.gateway.engineering.bindings, 'installation_id') as read:
            result = self.gateway.capabilities()
            self.assertIsNone(result['gatewayId'])
            self.assertFalse(result['continuity']['available'])
            self.assertFalse(result['features']['durableEngineeringJobs'])
            read.assert_not_called()

    def test_legacy_catalog_retains_explicit_history_and_unknown_original_profile_limits(self):
        journal_fixture.JournalTests.legacy_database(self)
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as no_dispatch:
            catalog = client.get('/v1/gateway/job-catalog', headers={'X-Gateway-Profile-ID': 'current-reader-profile'}).json()
            self.assertEqual(1, len(catalog['items']))
            item = catalog['items'][0]
            self.assertEqual('', item['profile_id'])
            self.assertEqual('', item['profile_name'])
            self.assertFalse(item['original_profile_known'])
            self.assertEqual('unknown_original_profile', item['original_profile_limitation'])
            self.assertFalse(item['history_complete'])
            self.assertEqual('legacy_snapshot_only', item['history_limitation'])
            snapshot = client.get('/v1/gateway/conversations/' + item['conversation_id']).json()
            self.assertEqual('', snapshot['profile_id'])
            self.assertFalse(snapshot['history_complete'])
            self.assertEqual('completed', snapshot['latest_job']['phase'])
            no_dispatch.assert_not_called()

    def test_readiness_accepts_busy_queue_only_with_selected_model_and_healthy_durable_admission(self):
        from v14.health import readiness
        runtime = self.runtime
        self.assertTrue(runtime.init_durable_job_store())
        state = ServiceState(max_workers=1, max_queued=1)
        state.workers['busy'] = (None, None)
        state.admitted.add('busy')
        observed = {'networkReachable': True, 'modelAvailable': True, 'generationReady': True,
                    'metadataAccess': 'available', 'backend': 'ready'}
        with patch('v14.health._backend_readiness.inspect', return_value=observed):
            ready = readiness(runtime, state, 'selected')
            self.assertTrue(ready['capabilities']['canQueue'])
            self.assertTrue(ready['capabilities']['canAccept'])
        for change in ('model_missing', 'journal_failed', 'draining', 'queue_full'):
            with self.subTest(change=change), patch('v14.health._backend_readiness.inspect', return_value=dict(observed,
                    modelAvailable=change != 'model_missing')), \
                    patch.object(runtime, 'durable_write_healthy', change != 'journal_failed'), \
                    patch.object(state, 'draining', change == 'draining'), \
                    patch.object(state, 'admitted', {'busy', 'queued'} if change == 'queue_full' else {'busy'}):
                result = readiness(runtime, state, 'selected')
                self.assertFalse(result['capabilities']['canQueue'])
                self.assertFalse(result['capabilities']['canAccept'])

    def test_nonstream_http_waiter_cancellation_does_not_lose_worker_commit(self):
        started, release = threading.Event(), threading.Event()
        calls = []
        result = {'choices': [{'message': {'role': 'assistant', 'content': 'Finished after the client left'}, 'finish_reason': 'stop'}]}
        def model(*args):
            calls.append('dispatch')
            started.set()
            if not release.wait(3):
                raise AssertionError('Test worker was not released')
            return 200, result
        async def scenario():
            payload = json.dumps({'model': 'local', 'stream': False, 'messages': [{'role': 'user', 'content': 'Continue this owned task'}]}).encode()
            async def receive():
                return {'type': 'http.request', 'body': payload, 'more_body': False}
            request = Request({'type': 'http', 'method': 'POST', 'path': '/v1/chat/completions', 'headers': []}, receive)
            marker = current_device.set('local:single-user')
            try:
                caller = asyncio.create_task(self.runtime.chat_completions(request))
                self.assertTrue(await asyncio.to_thread(started.wait, 2))
                caller.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await caller
                job_id = next(iter(self.runtime.job_registry))
                self.assertTrue(self.runtime.job_registry[job_id]['disconnected'])
                # Completion belongs to durable admission even if a future cache
                # policy or a concurrent cache reset removes the active object.
                self.runtime.job_registry.pop(job_id)
                release.set()
                await asyncio.wait_for(self.runtime.singleflight_workers[job_id]['task'], 3)
                self.assertEqual('completed', self.runtime.job_registry[job_id]['status'])
                return job_id
            finally:
                release.set()
                current_device.reset(marker)
        with patch.object(self.runtime, 'process_chat_payload', side_effect=model):
            job_id = asyncio.run(scenario())
        self.assertEqual(['dispatch'], calls)
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(result, json.loads(db.execute('SELECT result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0]))
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM gateway_conversation_messages WHERE job_id=? AND kind='final'", (job_id,)).fetchone()[0])

    def test_completion_rejects_missing_identity_and_an_older_worker_generation(self):
        runtime = self.runtime
        with self.assertRaises(DurableCommitError):
            runtime.finish_gateway_job('never-admitted', 200, {'content': 'not accepted'}, expected_generation='unknown')
        original = runtime.register_gateway_job('generation-fence', 'local', 'interactive', threading.Event(), threading.Event())
        old_generation = original['generation']
        runtime.job_registry['generation-fence']['generation'] = 'newer-owned-generation'
        runtime.durable_persist_job('generation-fence')
        runtime.job_registry.pop('generation-fence')
        with self.assertRaises(DurableCommitError):
            runtime.finish_gateway_job('generation-fence', 200, {'content': 'stale answer'}, expected_generation=old_generation)
        with closing(sqlite3.connect(self.db)) as db:
            public, result = db.execute('SELECT public_json,result_json FROM gateway_jobs WHERE job_id=?', ('generation-fence',)).fetchone()
            self.assertEqual('newer-owned-generation', json.loads(public)['generation'])
            self.assertIsNone(result)

    def test_worker_contention_is_queued_in_the_real_journal_before_model_dispatch(self):
        runtime = self.runtime
        state = ServiceState(max_workers=1, max_queued=1)
        running, queued, release = threading.Event(), threading.Event(), threading.Event()
        dispatched, completed, errors = [], [], []
        def model(payload, *args):
            dispatched.append(payload['_gateway_job_id'])
            if payload['_gateway_job_id'] == 'busy-first':
                running.set()
                if not release.wait(3):
                    raise AssertionError('Worker was not released')
            return 200, {'choices': [{'message': {'role': 'assistant', 'content': payload['_gateway_job_id']}, 'finish_reason': 'stop'}]}
        with patch.object(runtime, 'process_chat_payload', side_effect=model), \
                patch.object(runtime, 'register_gateway_job', runtime.register_gateway_job), \
                patch.object(runtime, 'finish_gateway_job', runtime.finish_gateway_job):
            state.install(runtime)
            marker = current_device.set('local:single-user')
            try:
                identities = {name: runtime.register_gateway_job(name, 'local', 'interactive', threading.Event(), threading.Event(),
                    admission_snapshot={'profile_id': 'original', 'profile_name': 'Original', 'messages': [{'role': 'user', 'content': name}]})
                    for name in ('busy-first', 'durable-second')}
            finally:
                current_device.reset(marker)
            def run(name):
                try:
                    def progress(event):
                        runtime.update_gateway_job(name, event)
                        if name == 'durable-second' and event.get('scheduling_state') == 'queued':
                            queued.set()
                    status, result = runtime.process_chat_payload({'_gateway_job_id': name}, progress_callback=progress)
                    runtime.finish_gateway_job(name, status, result, expected_generation=identities[name]['generation'])
                    completed.append(name)
                except BaseException as error:
                    errors.append(error)
            first = threading.Thread(target=run, args=('busy-first',))
            second = threading.Thread(target=run, args=('durable-second',))
            first.start()
            try:
                self.assertTrue(running.wait(1))
                second.start()
                self.assertTrue(queued.wait(1))
                with closing(sqlite3.connect(self.db)) as db:
                    saved = json.loads(db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', ('durable-second',)).fetchone()[0])
                    self.assertEqual('queued', saved['scheduling_state'])
                    self.assertIsNone(saved['started_at'])
                self.assertEqual(['busy-first'], dispatched)
                release.set()
                first.join(3); second.join(3)
                self.assertFalse(first.is_alive() or second.is_alive())
                self.assertEqual([], errors)
                self.assertEqual(['busy-first', 'durable-second'], dispatched)
                self.assertEqual({'busy-first', 'durable-second'}, set(completed))
                with closing(sqlite3.connect(self.db)) as db:
                    self.assertEqual(2, db.execute("SELECT COUNT(*) FROM gateway_jobs WHERE status='completed'").fetchone()[0])
                self.assertEqual(0, state.snapshot()['queuedWorkers'])
            finally:
                release.set()
                first.join(3)
                if second.ident is not None:
                    second.join(3)

    def test_registry_and_durable_retention_pin_queued_running_and_client_wait_jobs(self):
        runtime = self.runtime
        now = __import__('time').time()
        marker = current_device.set('local:single-user')
        try:
            for name, state in [('queued', 'queued'), ('running', 'running'), ('waiting', 'waiting_for_client_tool'), ('old-final', 'completed')]:
                runtime.register_gateway_job(name, 'local', 'interactive', threading.Event(), threading.Event())
                runtime.job_registry[name].update(status=state, created_at=0, updated_at=0, finished_at=0 if state == 'completed' else None)
                runtime.durable_persist_job(name)
            with patch.object(runtime, 'JOB_REGISTRY_MAX_ITEMS', 1), patch.object(runtime, 'DURABLE_JOB_RETENTION_SECONDS', 1):
                runtime.cleanup_job_registry()
                runtime.durable_cleanup_old_jobs()
            self.assertEqual({'queued', 'running', 'waiting'}, set(runtime.job_registry))
            with closing(sqlite3.connect(self.db)) as db:
                self.assertEqual({'queued', 'running', 'waiting'}, {row[0] for row in db.execute('SELECT job_id FROM gateway_jobs')})
        finally:
            current_device.reset(marker)

    def test_durably_accepted_worker_contention_queues_then_dispatches_once(self):
        state = ServiceState(max_workers=1, max_queued=1)
        running, release, queued = threading.Event(), threading.Event(), threading.Event()
        calls = []
        def model(payload, *args):
            calls.append(payload['_gateway_job_id'])
            if payload['_gateway_job_id'] == 'first':
                running.set()
                if not release.wait(3):
                    raise AssertionError('Queue test did not release its worker')
            return 200, {}
        runtime = SimpleNamespace(process_chat_payload=model, GATEWAY_DURABLE_JOBS=True, gateway_draining=False,
            register_gateway_job=lambda job_id, *args: {'job_id': job_id}, finish_gateway_job=lambda *args: None)
        state.install(runtime)
        runtime.register_gateway_job('first')
        runtime.register_gateway_job('second')
        with self.assertRaises(Exception):
            runtime.register_gateway_job('not-admitted')
        self.assertNotIn('not-admitted', state.admitted)
        results = []
        def progress(event):
            if event.get('scheduling_state') == 'queued':
                queued.set()
        first = threading.Thread(target=lambda: results.append(runtime.process_chat_payload({'_gateway_job_id': 'first'})))
        second = threading.Thread(target=lambda: results.append(runtime.process_chat_payload({'_gateway_job_id': 'second'}, progress_callback=progress)))
        first.start()
        try:
            self.assertTrue(running.wait(1))
            second.start()
            self.assertTrue(queued.wait(1))
            self.assertEqual(['first'], calls)
            self.assertEqual(1, state.snapshot()['queuedWorkers'])
            release.set()
            first.join(2); second.join(2)
            self.assertFalse(first.is_alive() or second.is_alive())
            self.assertEqual(['first', 'second'], calls)
            self.assertEqual([200, 200], sorted(row[0] for row in results))
        finally:
            release.set()
            first.join(2)
            if second.ident is not None:
                second.join(2)

    def test_restart_marks_ordinary_queue_interrupted_without_replaying_it(self):
        runtime = self.runtime
        runtime.register_gateway_job('queued-before-restart', 'local', 'interactive', threading.Event(), threading.Event())
        runtime.job_registry['queued-before-restart']['status'] = 'queued'
        runtime.durable_persist_job('queued-before-restart')
        runtime.job_registry.clear()
        runtime.durable_job_initialized = False
        with patch.object(runtime, 'process_chat_payload') as dispatch:
            self.assertTrue(runtime.init_durable_job_store())
            saved = runtime.job_registry['queued-before-restart']
            self.assertEqual('failed', saved['status'])
            self.assertEqual('interrupted', saved['scheduling_state'])
            dispatch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
