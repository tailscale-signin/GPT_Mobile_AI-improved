"""Integrated continuity regressions on the canonical conversation/job projection.

The superseded continuity-v1 wire is intentionally not a second live API. These
cases retain its behavioral protections and exercise import of its actual schema.
Only external model/GitHub I/O is replaced; admission, SQLite, paging and recovery
use the production implementations.
"""
import asyncio
import base64
import copy
import hashlib
import json
import sqlite3
import tempfile
import threading
import time
import unittest
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

import test_gateway_v14_engineering as engineering_fixture
import test_gateway_v14_jobs as journal_fixture
import test_gateway_conversations as conversation_fixture
from v14.batch import digest
from v14.conversations import Conversations, ConversationConflict, CursorExpired
from v14.engineering import EngineeringStore, EngineeringService, validate_request
from v14.engineering_backend import GitHubEngineeringBackend, blob_sha
from v14.execution_binding import ExecutionBindingError
from v14.job_catalog import catalog, install_routes
from v14.config import Config
from v14.identity import Admission
from v14.jobs import DurableCommitError
from v14.profile_metadata import profile_metadata
from v14.service import ServiceState


class ConversationTests(unittest.TestCase):
    setUp = engineering_fixture.DurableEngineeringTests.setUp
    tearDown = engineering_fixture.DurableEngineeringTests.tearDown

    def create(self, command='request', **extra):
        return self.store.create('owner', dict(self.request, request_id=command, task_scope='read',
                                              profile_name='Accepted profile', **extra))

    def test_followup_and_instruction_identity_preconditions_precede_command_admission(self):
        engineering_fixture.DurableEngineeringTests.admit(self, 'local:single-user')
        job = self.store.create('local:single-user', dict(self.request, task_scope='read'))
        projection, cid = self.store.conversations, job['conversation_id']
        snapshot = projection.snapshot('local:single-user', cid)
        headers = {'X-Gateway-Installation-ID': snapshot['gateway_id'], 'X-Gateway-Principal-ID': 'local:single-user'}
        instruction = {'kind': 'instruction', 'command_id': 'instruction', 'content': 'Explain more', 'expected_revision': snapshot['revision']}
        followup = dict(instruction, kind='message', command_id='followup', engineering_request=dict(self.request, task_scope='read'))
        app = FastAPI()
        app.add_middleware(Admission, config=Config(mode='loopback', allowed_hosts=('testserver',), allowed_origins=()))
        install_routes(app, projection, self.service, ServiceState(), SimpleNamespace(owned_job_ids=lambda ids, owner: set()))
        path = '/gateway/conversations/' + cid + '/messages'
        with TestClient(app, client=('127.0.0.1', 12000)) as client:
            with patch.object(self.store, 'create') as create, patch.object(self.service, 'validate_submission') as validate:
                for command in (instruction, followup):
                    for wrong in (dict(headers, **{'X-Gateway-Installation-ID': 'gw_other'}),
                                  dict(headers, **{'X-Gateway-Principal-ID': 'other-owner'})):
                        response = client.post(path, json=command, headers=wrong)
                        self.assertEqual(409, response.status_code, response.text)
                        self.assertEqual('CONTINUITY_IDENTITY_MISMATCH', response.json()['error']['code'])
                create.assert_not_called()
                validate.assert_not_called()
            self.assertEqual(snapshot['revision'], projection.snapshot('local:single-user', cid)['revision'])
            self.assertIsNone(projection.command('local:single-user', 'instruction'))
            response = client.post(path, json=instruction, headers=headers)
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual('queued_instruction', response.json()['status'])
            self.store.control(job['id'], 'local:single-user', 'cancel', job['revision'])
            followup['expected_revision'] = projection.snapshot('local:single-user', cid)['revision']
            with patch.object(self.service, 'validate_submission'), patch.object(self.service, 'readiness', return_value={'ready': True}):
                response = client.post(path, json=followup, headers=headers)
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual(cid, response.json()['conversation_id'])
        self.assertEqual(2, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_jobs').fetchone()[0]))
        self.assertEqual([], self.backend.calls)

    def test_derived_work_branch_and_command_receipt_reconcile_original_source_exactly(self):
        request = dict(self.request, branch='feature/source', base='feature/source', source_branch='feature/source',
                       source_sha='a' * 40, task_scope='implement', profile_name='原始 profile')
        job = self.store.create('owner', request)
        expected = 'codex/gateway-' + digest([request['request_id'], request['repository'], request['source_sha']])[:20]
        self.assertEqual(expected, job['request']['branch'])
        self.assertEqual(request, dict(self.request, branch='feature/source', base='feature/source', source_branch='feature/source',
                                      source_sha='a' * 40, task_scope='implement', profile_name='原始 profile'))
        self.assertEqual(job['id'], self.store.create('owner', request)['id'])
        receipt = self.store.conversations.command('owner', request['request_id'])
        snapshot = self.store.conversations.snapshot('owner', job['conversation_id'])
        for value in (receipt, snapshot):
            self.assertEqual(expected, value['branch'])
            self.assertEqual(expected, value['working_branch'])
            self.assertEqual('feature/source', value['source_branch'])
            self.assertEqual('feature/source', value['base'])
            self.assertEqual('a' * 40, value['source_sha'])
            self.assertEqual(job['execution_binding'], value['execution_binding'])
        self.assertIsNone(self.store.conversations.command('different-owner', request['request_id']))
        self.assertEqual([], self.backend.calls)
        self.assertEqual(1, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_jobs').fetchone()[0]))

    def test_scope_alias_is_normalized_without_escalating_read_and_rejects_conflicts(self):
        for scope in ('read', 'plan'):
            value = validate_request(dict(self.request, branch='main', base='main', scope=scope))
            self.assertEqual(scope, value['task_scope'])
            self.assertNotIn('scope', value)
            self.assertEqual('main', value['branch'])
        with self.assertRaises(ExecutionBindingError):
            validate_request(dict(self.request, scope='read', task_scope='implement'))
        with self.assertRaises(ValueError):
            validate_request(dict(self.request, branch='main', base='main', task_scope='implement'))

    def test_catalog_monotonic_cursor_survives_restart_and_excludes_backdated_admission(self):
        jobs = [self.create('page-' + str(index)) for index in range(5)]
        self.store.tx(lambda db: db.execute('CREATE TABLE IF NOT EXISTS gateway_jobs(public_json TEXT)'))
        principal = SimpleNamespace(id='owner', scopes={'engineering'}, local_admin=False)
        owner = SimpleNamespace(owned_job_ids=lambda ids, principal: set())
        first = catalog(self.store.conversations, self.service, principal, owner, limit=2)
        self.time -= 100
        later = self.create('later-with-older-clock')
        restarted = EngineeringStore(self.runtime, lambda: self.time)
        page, seen = first, []
        while True:
            seen.extend(item['job_id'] for item in page['items'])
            if not page['has_more']:
                break
            page = catalog(restarted.conversations, self.service, principal, owner, page['next_cursor'], 2)
        self.assertEqual({job['id'] for job in jobs}, set(seen))
        self.assertNotIn(later['id'], seen)
        self.assertEqual(len(jobs), len(seen))
        wrong = SimpleNamespace(id='other', scopes={'engineering'}, local_admin=False)
        with self.assertRaises(ValueError):
            catalog(restarted.conversations, self.service, wrong, owner, first['next_cursor'], 2)
        tampered = first['next_cursor'][:20] + ('A' if first['next_cursor'][20] != 'A' else 'B') + first['next_cursor'][21:]
        with self.assertRaises(CursorExpired):
            catalog(restarted.conversations, self.service, principal, owner, tampered, 2)

    def test_multipart_replay_after_restart_is_bounded_complete_and_idempotent(self):
        job = self.create()
        projection, cid = self.store.conversations, job['conversation_id']
        original = ('A retained answer 🌍\n' * 120000) + '\x00' * 270000
        publish = lambda db: projection.message(db, cid, 'large-final', 'assistant', original, 'completed', 'large-final')
        projection.tx(publish)
        before = projection.snapshot('owner', cid)['revision']
        projection.tx(publish)
        self.assertEqual(before, projection.snapshot('owner', cid)['revision'])
        first = projection.messages('owner', cid, limit=2)
        self.assertEqual(first, projection.messages('owner', cid, limit=2))
        restarted = EngineeringStore(self.runtime, lambda: self.time).conversations
        pages, parts = [first], list(first['messages'])
        while pages[-1]['has_more']:
            page = restarted.messages('owner', cid, pages[-1]['next_cursor'], limit=3)
            pages.append(page)
            parts.extend(page['messages'])
        self.assertGreater(len(pages), 3)
        for page in pages:
            self.assertLess(len(json.dumps(page, ensure_ascii=False, separators=(',', ':')).encode()), 1_900_000)
            self.assertTrue(page['history_complete'])
        finals = [part for part in parts if part['publication_id'] == 'large-final']
        self.assertEqual(list(range(len(finals))), [part['part_index'] for part in finals])
        self.assertEqual({len(finals)}, {part['part_count'] for part in finals})
        self.assertEqual(1, len({part['logical_message_id'] for part in finals}))
        self.assertEqual(len(parts), len({part['message_id'] for part in parts}))
        self.assertTrue(all(len(part['content'].encode('utf-8')) <= 256 * 1024 for part in finals))
        self.assertEqual(original, ''.join(part['content'] for part in finals))
        self.assertEqual({hashlib.sha256(original.encode()).hexdigest()}, {part['content_sha256'] for part in finals})
        tail = restarted.messages('owner', cid, pages[-1]['next_cursor'])
        self.assertEqual([], tail['messages'])
        self.assertEqual(pages[-1]['next_cursor'], tail['next_cursor'])

    def test_retention_gaps_remain_explicit_on_snapshot_page_and_empty_tail(self):
        job = self.create()
        projection, cid = self.store.conversations, job['conversation_id']
        projection.tx(lambda db: projection.message(db, cid, 'large', 'assistant', 'x' * 800000, 'completed', 'large'))
        initial = projection.messages('owner', cid)
        large = next(item for item in initial['messages'] if item['publication_id'] == 'large')
        projection.tx(lambda db: db.execute('DELETE FROM gateway_message_parts WHERE message_id=? AND part_index IN (1,3)', (large['logical_message_id'],)))
        for response in (projection.snapshot('owner', cid), projection.messages('owner', cid),
                         projection.messages('owner', cid, initial['next_cursor'])):
            self.assertFalse(response['history_complete'])
            self.assertTrue(response['gap'])
            self.assertEqual('retention_gap', response['history_limitation'])
            self.assertEqual([{'first_revision': large['revision'], 'last_revision': large['revision']}], response['gap_details']['missing_ranges'])
        self.assertEqual([], projection.list('owner')['items'])
        projection.tx(lambda db: db.execute("DELETE FROM gateway_messages WHERE conversation_id=? AND role='user'", (cid,)))
        self.assertEqual(2, projection.snapshot('owner', cid)['gap_details']['missing_revision_count'])

    def test_mutated_partial_publication_requires_snapshot_hydration(self):
        job = self.create()
        projection, cid = self.store.conversations, job['conversation_id']
        projection.tx(lambda db: projection.message(db, cid, 'mutable', 'assistant', 'a' * 900000, 'running', 'mutable'))
        first = projection.messages('owner', cid, limit=3)
        self.assertTrue(first['has_more'])
        projection.tx(lambda db: projection.message(db, cid, 'mutable', 'assistant', 'Final revised answer', 'completed', 'mutable'))
        with self.assertRaises(CursorExpired):
            projection.messages('owner', cid, first['next_cursor'])
        fresh = projection.messages('owner', cid)
        self.assertEqual('Final revised answer', fresh['messages'][-1]['content'])
        self.assertTrue(fresh['history_complete'])
        self.assertEqual([], self.backend.calls)

    def test_explicit_paused_followup_retires_approval_and_preserves_confirmed_head_and_context(self):
        job = self.create()
        claimed, lease, _ = self.store.claim(job['id'])
        claimed['data'].update(head_sha='b' * 40, answer='A retained observation')
        self.assertTrue(self.store.complete(claimed, lease, 'NEEDS_INPUT', {'head_sha': 'b' * 40, 'answer': 'A retained observation'}))
        cid = job['conversation_id']
        snapshot = self.store.conversations.snapshot('owner', cid)
        followup = dict(self.request, task_scope='read', profile_name='Accepted profile', request_id='followup',
                        conversation_id=cid, expected_revision=snapshot['revision'], objective='Explain the confirmed change')
        with self.assertRaises(ConversationConflict):
            self.store.create('owner', dict(followup, expected_revision=snapshot['revision'] - 1))
        next_job = self.store.create('owner', followup)
        self.assertEqual('CANCELLED', self.store.get(job['id'])['state'])
        self.assertIsNone(self.store.get(job['id'])['approval'])
        self.assertEqual('b' * 40, next_job['continuation_head_sha'])
        self.assertEqual(job['request']['branch'], next_job['continuation_branch'])
        self.assertEqual(cid, next_job['conversation_id'])
        self.assertTrue(next_job['conversation_context']['bounded_history'])
        self.assertIn(job['request']['objective'], str(next_job['conversation_context']))
        self.assertEqual(next_job['id'], self.store.create('owner', followup)['id'])
        self.assertEqual([], self.backend.calls)

    def test_pinned_source_and_derived_target_are_rechecked_before_model_or_writes(self):
        request = dict(self.request, task_scope='implement', branch='source', base='source', source_branch='source', source_sha='a' * 40)
        job = self.store.create('owner', request)
        backend = GitHubEngineeringBackend(self.runtime)
        backend.identity = lambda job: {'id': 7}
        backend.api = lambda *args, **kwargs: {'default_branch': 'main', 'permissions': {'pull': True, 'push': True}}
        with patch.object(backend, 'model') as model:
            backend.ref = lambda job, branch: 'b' * 40 if branch == 'source' else None
            with self.assertRaises(ExecutionBindingError) as changed:
                backend.step(job, 'PLAN')
            self.assertEqual('SOURCE_REVISION_CHANGED', changed.exception.code)
            backend.ref = lambda job, branch: 'a' * 40 if branch == 'source' else 'c' * 40
            with self.assertRaises(ExecutionBindingError) as collision:
                backend.step(job, 'PLAN')
            self.assertEqual('OWNED_BRANCH_COLLISION', collision.exception.code)
            model.assert_not_called()

    def test_followup_reads_retained_head_even_when_branch_deleted_but_mutation_fails(self):
        job = self.create()
        job.update(continuation_head_sha='b' * 40, continuation_branch=job['request']['branch'])
        backend = GitHubEngineeringBackend(self.runtime)
        backend.identity = lambda job: {'id': 7}
        backend.api = lambda *args, **kwargs: {'default_branch': 'main', 'permissions': {'pull': True, 'push': True}}
        backend.ref = lambda job, branch: 'a' * 40 if branch == job['request']['base'] else None
        seen = []
        backend.tree = lambda job, head: seen.append(head) or {'README.md': {'mode': '100644', 'sha': 'd' * 40}}
        backend.model = lambda *args: {'paths': ['README.md'], 'plan': 'Inspect retained commit'}
        self.assertEqual('b' * 40, backend.step(job, 'PLAN')['head_sha'])
        self.assertEqual(['b' * 40], seen)
        implementing = copy.deepcopy(job)
        implementing['request']['task_scope'] = 'implement'
        with self.assertRaises(ExecutionBindingError) as mismatch:
            backend.step(implementing, 'PLAN')
        self.assertEqual('WORKING_REVISION_CHANGED', mismatch.exception.code)

    def test_actual_read_and_plan_inspect_pinned_source_without_repository_mutation(self):
        self.time = time.time()
        backend = GitHubEngineeringBackend(self.runtime)
        service = EngineeringService(self.store, backend)
        service.capabilities._probe = self.service.capabilities._probe
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
            job = self.store.create('owner', dict(self.request, request_id=scope, branch='feature/source', base='feature/source',
                source_branch='feature/source', source_sha='a' * 40, task_scope=scope))
            service.tick(job['id'])
            service.tick(job['id'])
            completed = self.store.get(job['id'])
            self.assertEqual('COMPLETED', completed['state'], completed['message'])
            report = completed['data']['final_report']
            self.assertEqual('COMPLETED', report['status'])
            self.assertEqual(scope, report['task_scope'])
            self.assertEqual('a' * 40, report['head_sha'])
            self.assertEqual(['README.md'], report['inspected_paths'])
            self.assertEqual(0, report['repository_mutations'])
            self.assertFalse(report['tests_executed'])
            self.assertFalse(report['merge_performed'])
            self.assertEqual(0, self.store.tx(lambda db: db.execute(
                "SELECT COUNT(*) FROM engineering_receipts WHERE job_id=? AND phase IN ('COMMIT','OPEN_PR','PROMOTE','MERGE')", (job['id'],)).fetchone()[0]))
        self.assertEqual([('planner', 'local'), ('repository answerer', 'local'), ('planner', 'local'), ('repository planner', 'local')], models)
        self.assertTrue(requests)
        self.assertFalse(any('/pulls' in path for _, path in requests))


class LegacyMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'legacy.sqlite3'
        self.runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True,
            durable_job_lock=threading.RLock(), _durable_connect=lambda: sqlite3.connect(self.path))
        self.projection = Conversations(self.runtime, lambda: 1000.)
        self.cid, self.jid = 'conversation-retained', 'eng_retained'
        self.request = {'repository': 'owner/repo', 'branch': 'work/custom', 'base': 'release/base',
            'source_branch': 'feature/source', 'source_sha': 'a' * 40, 'scope': 'read', 'model': 'local',
            'objective': 'Original actual question', 'request_id': 'legacy-command', 'profile_id': 'original-profile'}
        self.binding = {'profile_id': 'original-profile', 'profile_name': '原始 profile', 'model': 'local',
            'repository': 'owner/repo', 'branch': 'feature/source', 'source_branch': 'feature/source', 'source_sha': 'a' * 40,
            'execution_binding': {'binding_id': 'bind_' + 'a' * 32, 'privacy_scope': 'original-scope'}}
        self.answer = 'Retained visible response 🌍' * 15000
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE engineering_identity(name TEXT PRIMARY KEY,value TEXT NOT NULL)')
            db.execute("INSERT INTO engineering_identity VALUES('installation','gw_original_installation')")
            db.execute('CREATE TABLE engineering_jobs(id TEXT PRIMARY KEY,principal TEXT,request_id TEXT,request_digest TEXT,state TEXT,revision INTEGER,next_at REAL,lease TEXT,data TEXT)')
            job = {'id': self.jid, 'principal': 'owner', 'request': self.request, 'data': {'scope_result': {'answer': self.answer}}, 'execution_binding': self.binding['execution_binding']}
            db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?,?,1,1000,NULL,?)', (self.jid, 'owner', 'legacy-command', 'old-digest', 'COMPLETED', json.dumps(job)))
            db.execute('CREATE TABLE gateway_conversations(id TEXT PRIMARY KEY,principal TEXT,binding TEXT,title TEXT,revision INTEGER,message_sequence INTEGER,created_at REAL,updated_at REAL,latest_job_id TEXT,history_complete INTEGER,history_limitation TEXT)')
            db.execute('INSERT INTO gateway_conversations VALUES(?,?,?,?,4,4,100,200,?,1,NULL)', (self.cid, 'owner', json.dumps(self.binding), 'Actual conversation', self.jid))
            db.execute('CREATE TABLE gateway_commands(principal TEXT,command_id TEXT,digest TEXT,conversation_id TEXT,job_id TEXT,accepted_at REAL,accepted_revision INTEGER,PRIMARY KEY(principal,command_id))')
            db.execute('INSERT INTO gateway_commands VALUES(?,?,?,?,?,100,0)', ('owner', 'legacy-command', 'old-digest', self.cid, self.jid))
            db.execute('CREATE TABLE gateway_continuity_jobs(catalog_sequence INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT,job_id TEXT,principal TEXT,conversation_id TEXT,public_json TEXT)')
            db.execute('INSERT INTO gateway_continuity_jobs(kind,job_id,principal,conversation_id,public_json) VALUES(?,?,?,?,?)', ('engineering', self.jid, 'owner', self.cid, json.dumps({'working_branch': 'work/custom', 'base': 'release/base'})))
            db.execute('CREATE TABLE gateway_logical_messages(logical_message_id TEXT PRIMARY KEY,conversation_id TEXT,job_id TEXT,role TEXT,kind TEXT,content TEXT,content_sha256 TEXT,part_count INTEGER,created_at REAL)')
            db.execute('CREATE TABLE gateway_conversation_messages(conversation_id TEXT,sequence INTEGER,message_id TEXT,job_id TEXT,role TEXT,kind TEXT,content TEXT,created_at REAL,logical_message_id TEXT,part_index INTEGER,part_count INTEGER,content_sha256 TEXT)')
            messages = [('user:' + self.jid, 'user', 'user', self.request['objective']),
                        ('private-tool', 'assistant', 'tool_request', 'Hidden tool arguments'),
                        ('private-reasoning', 'reasoning', 'status', 'Hidden reasoning'),
                        ('answer', 'assistant', 'final', self.answer)]
            for sequence, (key, role, kind, content) in enumerate(messages, 1):
                logical = self.cid + ':' + key
                checksum = hashlib.sha256(content.encode()).hexdigest()
                db.execute('INSERT INTO gateway_logical_messages VALUES(?,?,?,?,?,?,?,1,?)', (logical, self.cid, self.jid, role, kind, content, checksum, 100 + sequence))
                db.execute('INSERT INTO gateway_conversation_messages VALUES(?,?,?,?,?,?,?,?,?,0,1,?)', (self.cid, sequence, logical, self.jid, role, kind, content, 100 + sequence, logical, checksum))

    def test_atomic_import_preserves_identity_binding_receipt_and_complete_actual_text(self):
        self.assertEqual('gw_original_installation', self.projection.gateway_id())
        snapshot = self.projection.snapshot('owner', self.cid)
        receipt = self.projection.command('owner', 'legacy-command')
        for value in (snapshot, receipt):
            self.assertEqual(self.binding['execution_binding'], value['execution_binding'])
            self.assertEqual('feature/source', value['source_branch'])
            self.assertEqual('work/custom', value['working_branch'])
            self.assertEqual('work/custom', value['branch'])
            self.assertEqual('release/base', value['base'])
        self.assertTrue(snapshot['history_complete'])
        page = self.projection.messages('owner', self.cid)
        self.assertEqual(self.request['objective'] + self.answer, ''.join(item['content'] for item in page['messages']))
        self.assertNotIn('Hidden', str(page))
        self.assertGreater(len(page['messages']), 2)
        self.assertEqual(self.cid, self.projection.list('owner')['items'][0]['conversation_id'])
        with closing(sqlite3.connect(self.path)) as db:
            stored = json.loads(db.execute('SELECT data FROM engineering_jobs').fetchone()[0])
            self.assertEqual('read', stored['request']['task_scope'])
            self.assertNotIn('scope', stored['request'])
            self.assertEqual(self.answer, stored['data']['answer'])
            self.assertEqual(4, db.execute('SELECT COUNT(*) FROM gateway_continuity_v1_logical_messages').fetchone()[0])
        reopened = Conversations(self.runtime, lambda: 1000.)
        self.assertEqual(snapshot, reopened.snapshot('owner', self.cid))
        self.assertEqual(receipt, reopened.command('owner', 'legacy-command'))
        self.assertIsNone(reopened.snapshot('other-owner', self.cid))

    def test_incomplete_import_stays_visible_as_limited_history_but_not_in_full_browser(self):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('DELETE FROM gateway_conversation_messages WHERE sequence=2')
        snapshot = self.projection.snapshot('owner', self.cid)
        self.assertFalse(snapshot['history_complete'])
        self.assertEqual('retention_gap', snapshot['history_limitation'])
        self.assertEqual([], self.projection.list('owner')['items'])
        self.assertFalse(self.projection.messages('owner', self.cid)['history_complete'])
        self.assertEqual(self.cid, self.projection.command('owner', 'legacy-command')['conversation_id'])

    def test_import_failure_rolls_back_table_renames_and_never_publishes_partial_migration(self):
        original = self.runtime._durable_connect
        def denied():
            db = original()
            db.set_authorizer(lambda action, table, *rest: sqlite3.SQLITE_DENY if action == sqlite3.SQLITE_INSERT and table == 'gateway_messages' else sqlite3.SQLITE_OK)
            return db
        with patch.object(self.runtime, '_durable_connect', side_effect=denied), self.assertRaises(sqlite3.DatabaseError):
            self.projection.gateway_id()
        with closing(sqlite3.connect(self.path)) as db:
            columns = {row[1] for row in db.execute('PRAGMA table_info(gateway_conversations)')}
            self.assertIn('message_sequence', columns)
            self.assertNotIn('client_key', columns)
            self.assertEqual(4, db.execute('SELECT COUNT(*) FROM gateway_logical_messages').fetchone()[0])
            self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE name='gateway_continuity_v1_conversations'").fetchone())
        self.assertTrue(self.projection.snapshot('owner', self.cid)['history_complete'])

    def test_missing_logical_record_with_retained_physical_parts_is_not_complete_history(self):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("DELETE FROM gateway_logical_messages WHERE kind='final'")
        snapshot = self.projection.snapshot('owner', self.cid)
        self.assertFalse(snapshot['history_complete'])
        self.assertEqual('retention_gap', snapshot['history_limitation'])
        self.assertEqual([], self.projection.list('owner')['items'])
        self.assertEqual(self.request['objective'], self.projection.messages('owner', self.cid)['messages'][0]['content'])
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(self.answer, db.execute("SELECT content FROM gateway_continuity_v1_messages WHERE kind='final'").fetchone()[0])


class OrdinaryLifecycleTests(unittest.TestCase):
    setUpClass = classmethod(journal_fixture.JournalTests.setUpClass.__func__)
    setUp = journal_fixture.JournalTests.setUp
    client = journal_fixture.JournalTests.client
    completion = journal_fixture.JournalTests.completion

    def test_absent_or_none_retention_does_not_create_extra_transcripts_or_task_titles(self):
        jobs = []
        def complete(payload, cancel, progress, hard):
            progress({'phase': 'working', 'message': 'PRIVATE_PROGRESS'})
            return 200, {'choices': [{'message': {'role': 'assistant', 'content': 'PRIVATE_ANSWER'}, 'finish_reason': 'stop'}]}
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=complete) as model:
            for index, (retention, stream) in enumerate(((None, False), (None, True), ('none', False), ('none', True))):
                headers = {'Idempotency-Key': 'private-retention-' + str(index), 'X-Gateway-Profile-ID': 'original-profile'}
                if retention:
                    headers['X-Gateway-Conversation-Retention'] = retention
                body = {'model': 'local', 'stream': stream, 'messages': [{'role': 'user', 'content': 'PRIVATE_QUESTION_' + str(index)}]}
                first = client.post('/v1/chat/completions', json=body, headers=headers)
                self.assertEqual(200, first.status_code, first.text)
                replay = client.post('/v1/chat/completions', json=body, headers=headers)
                self.assertEqual(200, replay.status_code, replay.text)
                self.assertEqual(first.headers['x-gateway-job-id'], replay.headers['x-gateway-job-id'])
                jobs.append(first.headers['x-gateway-job-id'])
            self.assertEqual(4, model.call_count)
            self.assertEqual([], client.get('/gateway/conversations').json()['items'])
            for row in client.get('/gateway/job-catalog').json()['items']:
                self.assertIsNone(row['conversation_id'])
                self.assertEqual('Gateway conversation', row['title'])
                self.assertEqual('original-profile', row['profile_id'])
                self.assertFalse(row['history_complete'])
                self.assertEqual('conversation_retention_disabled', row['history_limitation'])
        self.runtime.job_registry.clear()
        self.runtime.durable_job_initialized = False
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as forbidden:
            self.assertEqual([], client.get('/gateway/conversations').json()['items'])
            for job_id in jobs:
                self.assertIn('PRIVATE_ANSWER', client.get('/gateway/jobs/' + job_id + '/result').text)
            forbidden.assert_not_called()
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM gateway_messages').fetchone()[0])
            self.assertEqual(0, db.execute('SELECT COUNT(*) FROM gateway_conversations').fetchone()[0])

    def test_retention_header_and_canonical_privacy_agree_and_cannot_change_job_policy(self):
        body = {'model': 'local', 'stream': False, 'messages': [{'role': 'user', 'content': 'Original task'}]}
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            for headers in ({'X-Gateway-Conversation-Retention': 'full'}, {'X-Gateway-Conversation-Retention': 'FULL'},
                            [('X-Gateway-Conversation-Retention', 'full'), ('X-Gateway-Conversation-Retention', 'none')]):
                rejected = client.post('/v1/chat/completions', json=body, headers=headers)
                self.assertEqual(400, rejected.status_code, rejected.text)
            for persistent, header in ((False, 'full'), (True, 'none')):
                payload = conversation_fixture.ConversationHTTPTests.payload(persistent=persistent)
                rejected = client.post('/v1/chat/completions', json=payload, headers={'X-Gateway-Conversation-Retention': header})
                self.assertEqual(400, rejected.status_code, rejected.text)
                self.assertEqual('CONVERSATION_RETENTION_CONFLICT', rejected.json()['error']['code'])
            model.assert_not_called()
            headers = {'X-Gateway-Profile-ID': 'original-profile', 'Idempotency-Key': 'retained-policy'}
            first = client.post('/v1/chat/completions', json=body, headers=headers)
            self.assertEqual(200, first.status_code, first.text)
            job_id = first.headers['x-gateway-job-id']
            changed = client.post('/v1/chat/completions', json=body, headers=dict(headers, **{'X-Gateway-Conversation-Retention': 'full'}))
            self.assertEqual(409, changed.status_code, changed.text)
            self.assertEqual('idempotency_conflict', changed.json()['error']['code'])
            self.runtime.job_registry.clear()
            self.runtime.singleflight_workers.clear()
            self.runtime.singleflight_request_index.clear()
            changed = client.post('/v1/chat/completions', json=body, headers={'X-Gateway-Job-ID': job_id, 'X-Gateway-Profile-ID': 'original-profile', 'X-Gateway-Conversation-Retention': 'full'})
            self.assertEqual(409, changed.status_code, changed.text)
            self.assertEqual('job_retention_changed', changed.json()['error']['code'])
            self.assertEqual(1, model.call_count)
            retained = client.post('/v1/chat/completions', json=body, headers={'X-Gateway-Profile-ID': 'original-profile', 'X-Gateway-Conversation-Retention': 'full', 'Idempotency-Key': 'explicit-full'})
            self.assertEqual(200, retained.status_code, retained.text)
            cid = retained.headers['x-gateway-conversation-id']
            self.assertEqual(['Original task', 'SQLite reply'], [item['content'] for item in client.get('/gateway/conversations/' + cid + '/messages').json()['messages']])

    def test_ordinary_identity_preconditions_precede_dispatch_and_explicit_cancel(self):
        with self.client() as client:
            identity = client.get('/gateway/identity').json()
            self.runtime.register_gateway_job('owned-cancel', 'local', 'interactive', threading.Event(), threading.Event())
            self.store.claim('owned-cancel', identity['principal_id'])
            valid = {'X-Gateway-Installation-ID': identity['gateway_id'], 'X-Gateway-Principal-ID': identity['principal_id']}
            invalid = [(dict(valid, **{'X-Gateway-Installation-ID': 'gw_other'}), 409, 'CONTINUITY_IDENTITY_MISMATCH'),
                       (dict(valid, **{'X-Gateway-Principal-ID': 'another-owner'}), 409, 'CONTINUITY_IDENTITY_MISMATCH'),
                       ({'X-Gateway-Installation-ID': identity['gateway_id']}, 400, 'CONTINUITY_IDENTITY_INVALID'),
                       ({'X-Gateway-Principal-ID': identity['principal_id']}, 400, 'CONTINUITY_IDENTITY_INVALID')]
            payload = conversation_fixture.ConversationHTTPTests.payload()
            with patch.object(self.runtime, 'process_chat_payload') as model, patch.object(self.runtime, 'cancel_gateway_job') as cancel:
                for headers, status, code in invalid:
                    for path, body in (('/v1/chat/completions', payload), ('/gateway/jobs/owned-cancel/cancel', {})):
                        response = client.post(path, json=body, headers=headers)
                        self.assertEqual(status, response.status_code, response.text)
                        self.assertEqual(code, response.json()['error']['code'])
                model.assert_not_called()
                cancel.assert_not_called()
            self.assertEqual({'owned-cancel'}, set(self.runtime.job_registry))
            with patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
                accepted = client.post('/v1/chat/completions', json=payload, headers=valid)
                self.assertEqual(200, accepted.status_code, accepted.text)
                self.assertEqual(1, model.call_count)
            capabilities = client.get('/gateway/capabilities').json()
            self.assertTrue(capabilities['features']['mutation_identity_preconditions_v1'])
            self.assertTrue(capabilities['features']['ordinary_conversation_retention_v1'])
            self.assertEqual(['X-Gateway-Installation-ID', 'X-Gateway-Principal-ID'], capabilities['mutationIdentityPreconditions'])
            self.assertEqual({'header': 'X-Gateway-Conversation-Retention', 'values': ['full', 'none'], 'default': 'none'}, capabilities['ordinaryConversationRetention'])

    def test_prior_canonical_command_digest_replays_after_retention_header_upgrade(self):
        payload = conversation_fixture.ConversationHTTPTests.payload()
        ledger = self.runtime.gateway_action_ledger
        original = ledger.admit_request
        def previous_release(principal, route, command, value, job):
            legacy = dict(value)
            legacy.pop('_gateway_conversation_retention', None)
            return original(principal, route, command, legacy, job)
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            with patch.object(ledger, 'admit_request', side_effect=previous_release):
                accepted = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(200, accepted.status_code, accepted.text)
            self.runtime.job_registry.clear()
            self.runtime.singleflight_workers.clear()
            self.runtime.singleflight_request_index.clear()
            replay = client.post('/v1/chat/completions', json=payload, headers={'X-Gateway-Conversation-Retention': 'full'})
            self.assertEqual(200, replay.status_code, replay.text)
            self.assertEqual(accepted.headers['x-gateway-job-id'], replay.headers['x-gateway-job-id'])
            self.assertEqual(accepted.headers['x-gateway-conversation-id'], replay.headers['x-gateway-conversation-id'])
            self.assertEqual(1, model.call_count)

    def test_captured_unicode_profile_is_original_metadata_not_current_reader_authority(self):
        name = '  原始 profile 🌍  '
        payload = conversation_fixture.ConversationHTTPTests.payload()
        payload['gateway']['profile_id'] = 'profile_original-1'
        headers = {'X-Gateway-Profile-ID': 'profile_original-1',
                   'X-Gateway-Profile-Name-Base64': base64.urlsafe_b64encode(name.encode()).decode().rstrip('=')}
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            response = client.post('/v1/chat/completions', headers=headers, json=payload)
            self.assertEqual(200, response.status_code, response.text)
            cid = response.headers['x-gateway-conversation-id']
            snapshot = client.get('/gateway/conversations/' + cid, headers={'X-Gateway-Profile-ID': 'current-reader'}).json()
            self.assertEqual(name, snapshot['profile_name'])
            self.assertEqual('profile_original-1', snapshot['profile_id'])
            self.assertEqual('local:single-user', snapshot['principal_id'])
            conflict = client.post('/v1/chat/completions', headers=dict(headers, **{'X-Gateway-Profile-ID': 'different'}), json=payload)
            self.assertEqual(409, conflict.status_code)
            self.assertEqual(1, model.call_count)
            duplicate = client.get('/gateway/conversations', headers=[('X-Gateway-Profile-ID', 'one'), ('X-Gateway-Profile-ID', 'two')])
            self.assertEqual(400, duplicate.status_code)
        self.runtime.job_registry.clear()
        self.runtime.durable_job_initialized = False
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as forbidden:
            self.assertEqual(name, client.get('/gateway/conversations/' + cid).json()['profile_name'])
            forbidden.assert_not_called()

    def test_profile_headers_validate_encoding_bounds_and_control_characters(self):
        self.assertEqual({'profile_id': None, 'profile_name': None}, profile_metadata({}))
        self.assertEqual('a' * 160, profile_metadata({'x-gateway-profile-id': 'a' * 160})['profile_id'])
        for headers in ({'x-gateway-profile-id': 'a' * 161}, {'x-gateway-profile-id': 'owner:admin'},
                        {'x-gateway-profile-name-base64': 'YQ=='}, {'x-gateway-profile-name-base64': '+'},
                        {'x-gateway-profile-name-base64': base64.urlsafe_b64encode(b'\n').decode().rstrip('=')},
                        {'x-gateway-profile-name-base64': base64.urlsafe_b64encode(b'a' * 1025).decode().rstrip('=')},
                        {'x-gateway-profile-name-base64': base64.urlsafe_b64encode(b'\xff').decode().rstrip('=')}):
            with self.subTest(headers=headers), self.assertRaises(ValueError):
                profile_metadata(headers)

    def test_disabled_storage_does_not_claim_durable_identity_or_conversation_support(self):
        with patch.object(self.runtime, 'GATEWAY_DURABLE_JOBS', False):
            result = self.gateway.capabilities()
            self.assertIsNone(result['gatewayId'])
            self.assertIsNone(result['gateway_id'])
            self.assertFalse(result['features']['durableEngineeringJobs'])
            self.assertFalse(result['features']['conversation_sync_v1'])
            with self.client() as client:
                self.assertEqual(503, client.get('/gateway/identity').status_code)
        self.assertNotIn('continuity', result)

    def test_worker_finalization_restores_only_matching_durable_generation(self):
        runtime = self.runtime
        with self.assertRaises(DurableCommitError):
            runtime.finish_gateway_job('never-admitted', 200, {'content': 'not accepted'}, expected_generation='unknown')
        original = runtime.register_gateway_job('generation-fence', 'local', 'interactive', threading.Event(), threading.Event())
        generation = original['generation']
        runtime.job_registry.pop('generation-fence')
        runtime.finish_gateway_job('generation-fence', 200, {'content': 'retained answer'}, expected_generation=generation)
        self.assertEqual('completed', runtime.job_registry['generation-fence']['status'])
        current = runtime.register_gateway_job('other-generation', 'local', 'interactive', threading.Event(), threading.Event())
        old = current['generation']
        runtime.job_registry['other-generation']['generation'] = 'newer-owned-generation'
        runtime.durable_persist_job('other-generation')
        runtime.job_registry.pop('other-generation')
        with self.assertRaises(DurableCommitError):
            runtime.finish_gateway_job('other-generation', 200, {'content': 'stale answer'}, expected_generation=old)
        with closing(sqlite3.connect(self.db)) as db:
            public, result = db.execute('SELECT public_json,result_json FROM gateway_jobs WHERE job_id=?', ('other-generation',)).fetchone()
            self.assertEqual('newer-owned-generation', json.loads(public)['generation'])
            self.assertIsNone(result)

    def test_busy_readiness_requires_selected_model_healthy_durable_queue_and_capacity(self):
        from v14.health import readiness
        self.assertTrue(self.runtime.init_durable_job_store())
        state = ServiceState(max_workers=1, max_queued=1)
        with patch.object(self.runtime, 'process_chat_payload', self.runtime.process_chat_payload):
            state.install(self.runtime)
            state.workers['busy'] = (None, None)
            observed = {'networkReachable': True, 'modelAvailable': True, 'generationReady': True,
                        'metadataAccess': 'available', 'backend': 'ready'}
            with patch('v14.health._backend_readiness.inspect', return_value=dict(observed)):
                ready = readiness(self.runtime, state, 'selected')
                self.assertTrue(ready['capabilities']['canQueue'])
                self.assertTrue(ready['capabilities']['canAccept'])
            for change in ('model_missing', 'journal_failed', 'draining', 'queue_full'):
                if change == 'queue_full':
                    self.runtime.gateway_conversations.tx(lambda db: db.execute('INSERT INTO gateway_generation_admissions VALUES(?,?,?,?,?,?,?)', ('queued', 'owner', '{}', 'queued', time.time(), time.time() + 60, 'binding')))
                with self.subTest(change=change), patch('v14.health._backend_readiness.inspect', return_value=dict(observed, modelAvailable=change != 'model_missing')), \
                        patch.object(self.runtime, 'durable_write_healthy', change != 'journal_failed'), patch.object(state, 'draining', change == 'draining'):
                    result = readiness(self.runtime, state, 'selected')
                    self.assertFalse(result['capabilities']['canQueue'])
                    self.assertFalse(result['capabilities']['canAccept'])


if __name__ == '__main__':
    unittest.main()
