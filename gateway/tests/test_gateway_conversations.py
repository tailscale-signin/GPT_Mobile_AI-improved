"""Behavioral regressions for durable browser, admission and detached execution."""
import asyncio
import copy
import hashlib
import importlib
import json
import sqlite3
import threading
import time
import unittest
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

import test_gateway_v14_jobs as journal_fixtures
import test_gateway_v14_engineering as engineering_fixtures
from v14.conversations import Conversations, ConversationConflict, admission_context
from v14.job_catalog import catalog, install_routes
from v14.service import ServiceState
from v14.identity import Admission
from v14.config import Config
from v14.execution_binding import ExecutionBindingError


class ConversationHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway = importlib.import_module('gateway_v14')

    setUp = journal_fixtures.JournalTests.setUp
    client = journal_fixtures.JournalTests.client
    completion = journal_fixtures.JournalTests.completion

    @staticmethod
    def payload(command='turn-one', conversation='android-conversation', text='Inspect the repository', persistent=True):
        return {'model': 'local', 'stream': False,
                'gateway': {'client_conversation_id': conversation, 'command_id': command,
                            'profile_id': 'selected-profile', 'store_conversation': persistent},
                'messages': [{'role': 'user', 'content': text}]}

    def test_browser_retains_full_visible_turns_and_one_publication_per_command(self):
        payload = self.payload()
        payload['messages'] = [{'role': 'system', 'content': 'private control instruction'},
                               {'role': 'user', 'content': 'Earlier question'},
                               {'role': 'assistant', 'content': 'Earlier answer', 'reasoning_content': 'private reasoning'},
                               {'role': 'user', 'content': 'Current question'}]
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            first = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(200, first.status_code, first.text)
            conversation_id = first.headers['x-gateway-conversation-id']
            self.assertEqual('local:single-user', first.headers['x-gateway-principal-id'])
            receipt = client.get('/gateway/commands/turn-one').json()
            self.assertEqual(conversation_id, receipt['conversation_id'])
            replay = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(200, replay.status_code, replay.text)
            self.assertEqual(1, model.call_count)
            followup = self.payload('turn-two', text='The next question')
            second = client.post('/v1/chat/completions', json=followup)
            self.assertEqual(200, second.status_code, second.text)
            self.assertEqual(conversation_id, second.headers['x-gateway-conversation-id'])
            self.assertEqual(2, model.call_count)
            items = client.get('/gateway/conversations').json()['items']
            self.assertEqual([conversation_id], [item['conversation_id'] for item in items])
            self.assertEqual('selected-profile', items[0]['profile_id'])
            messages, cursor = [], None
            while True:
                page = client.get('/gateway/conversations/' + conversation_id + '/messages', params=dict(limit=2, **({'after_cursor': cursor} if cursor else {}))).json()
                messages.extend(page['messages'])
                if not page['has_more']:
                    break
                cursor = page['next_cursor']
            self.assertEqual(['Earlier question', 'Earlier answer', 'Current question', 'SQLite reply', 'The next question', 'SQLite reply'], [item['content'] for item in messages])
            self.assertEqual(6, len({item['message_id'] for item in messages}))
            serialized = json.dumps(messages)
            self.assertNotIn('private reasoning', serialized)
            self.assertNotIn('private control instruction', serialized)
            unchanged = client.get('/gateway/conversations/' + conversation_id + '/messages', params={'after_cursor': page['next_cursor']}).json()
            self.assertEqual([], unchanged['messages'])
            conflict = client.post('/v1/chat/completions', json=dict(payload, messages=[{'role': 'user', 'content': 'Changed same command'}]))
            self.assertEqual(409, conflict.status_code)
            self.assertEqual(2, model.call_count)

    def test_temporary_chat_writes_no_conversation_prompt_result_or_events(self):
        payload = self.payload(persistent=False, text='Temporary private text')
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            response = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual('false', response.headers['x-gateway-detach-safe'])
            self.assertNotIn('x-gateway-conversation-id', response.headers)
            self.assertEqual([], client.get('/gateway/conversations').json()['items'])
            self.assertEqual([], client.get('/gateway/job-catalog').json()['items'])
            self.assertEqual(404, client.get('/gateway/commands/turn-one').status_code)
            with closing(sqlite3.connect(self.db)) as db:
                dump = '\n'.join(db.iterdump())
            self.assertNotIn('Temporary private text', dump)
            self.assertNotIn('SQLite reply', dump)

    def test_installation_identity_persists_without_reusing_process_epoch(self):
        with self.client() as client:
            saved = client.get('/gateway/conversations').json()['gateway_id']
        self.runtime.durable_job_initialized = False
        self.runtime.job_registry.clear()
        self.assertEqual(saved, Conversations(self.runtime).gateway_id())
        first, second = ServiceState(), ServiceState()
        self.assertNotEqual(first.instance_id, second.instance_id)

    def test_catalog_pages_are_scoped_bounded_and_keep_terminal_transcripts(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            for index in range(3):
                value = self.payload('command-' + str(index), 'conversation-' + str(index), 'Question ' + str(index))
                self.assertEqual(200, client.post('/v1/chat/completions', json=value).status_code)
            first = client.get('/gateway/job-catalog?limit=2').json()
            self.assertEqual(3, first['counts']['recent'])
            self.assertTrue(first['has_more'])
            self.assertFalse(first['complete'])
            second = client.get('/gateway/job-catalog', params={'limit': 2, 'cursor': first['next_cursor']}).json()
            self.assertTrue(second['complete'])
            self.assertEqual(3, len({row['job_id'] for row in first['items'] + second['items']}))
            self.assertEqual(400, client.get('/gateway/job-catalog?limit=101').status_code)
            # Reading both kinds of catalog never submits model work.
            self.assertEqual(3, model.call_count)
            with patch.object(self.runtime, 'DURABLE_JOB_RETENTION_SECONDS', -1):
                self.runtime.durable_cleanup_old_jobs()
            self.assertEqual(3, len(client.get('/gateway/conversations').json()['items']))

    def test_active_registry_and_journal_entries_survive_terminal_cache_pressure(self):
        self.runtime.init_durable_job_store()
        soft, hard = threading.Event(), threading.Event()
        self.runtime.register_gateway_job('long-running', 'local', 'interactive', soft, hard)
        self.runtime.job_registry['long-running']['created_at'] = 1
        self.runtime.job_registry['long-running']['updated_at'] = 1
        self.runtime.durable_persist_job('long-running')
        for index in range(520):
            self.runtime.job_registry['done-' + str(index)] = {'job_id': 'done-' + str(index), 'status': 'completed', 'created_at': 2 + index, 'finished_at': time.time()}
        self.runtime.cleanup_job_registry()
        self.runtime.durable_cleanup_old_jobs()
        self.assertIn('long-running', self.runtime.job_registry)
        with closing(sqlite3.connect(self.db)) as db:
            self.assertIsNotNone(db.execute("SELECT job_id FROM gateway_jobs WHERE job_id='long-running'").fetchone())
        self.runtime.finish_gateway_job('long-running', 200, {'choices': [{'message': {'content': 'Retained final'}}]})
        self.runtime.finish_gateway_job('long-running', 500, None, error='late observer')
        with closing(sqlite3.connect(self.db)) as db:
            status, result = db.execute("SELECT status,result_json FROM gateway_jobs WHERE job_id='long-running'").fetchone()
        self.assertEqual('completed', status)
        self.assertIn('Retained final', result)

    def test_nonstream_observer_cancellation_does_not_own_final_commit(self):
        started, release = threading.Event(), threading.Event()
        def completion(payload, cancel, progress, hard):
            started.set()
            self.assertTrue(release.wait(5))
            self.assertFalse(hard.is_set())
            return self.completion(payload, cancel, progress, hard)
        async def run():
            transport = httpx.ASGITransport(app=self.gateway.app, client=('127.0.0.1', 2222))
            async with httpx.AsyncClient(transport=transport, base_url='http://127.0.0.1:8090') as client:
                request = asyncio.create_task(client.post('/v1/chat/completions', json=self.payload()))
                self.assertTrue(await asyncio.to_thread(started.wait, 5))
                request.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await request
                release.set()
                worker = next(iter(self.runtime.singleflight_workers.values()))['task']
                await asyncio.shield(worker)
                result = await client.get('/gateway/commands/turn-one')
                job_id = result.json()['job_id']
                saved = await client.get('/gateway/jobs/' + job_id + '/result')
                self.assertEqual('SQLite reply', saved.json()['result']['choices'][0]['message']['content'])
                messages = (await client.get('/gateway/conversations/' + result.json()['conversation_id'] + '/messages')).json()['messages']
                self.assertEqual(1, sum(message['role'] == 'assistant' and message['content'] == 'SQLite reply' for message in messages))
        with self.client(), patch.object(self.runtime, 'process_chat_payload', side_effect=completion):
            asyncio.run(run())

    def test_accepted_generation_waits_for_capacity_after_phone_disconnect(self):
        release, started, second_started = threading.Event(), threading.Event(), threading.Event()
        calls = []
        def model(payload, cancel, progress, hard):
            calls.append(payload['messages'][-1]['content'])
            if len(calls) == 1:
                started.set()
                self.assertTrue(release.wait(5))
            else:
                second_started.set()
            return self.completion(payload, cancel, progress, hard)
        async def run():
            transport = httpx.ASGITransport(app=self.gateway.app, client=('127.0.0.1', 2222))
            async with httpx.AsyncClient(transport=transport, base_url='http://127.0.0.1:8090') as client:
                first = asyncio.create_task(client.post('/v1/chat/completions', json=self.payload('first', 'one', 'First')))
                self.assertTrue(await asyncio.to_thread(started.wait, 5))
                second = asyncio.create_task(client.post('/v1/chat/completions', json=self.payload('second', 'two', 'Second')))
                receipt = None
                for _ in range(100):
                    response = await client.get('/gateway/commands/second')
                    if response.status_code == 200:
                        receipt = response.json()
                        break
                    await asyncio.sleep(.01)
                self.assertIsNotNone(receipt)
                rows = (await client.get('/gateway/job-catalog')).json()['items']
                queued = next(row for row in rows if row['job_id'] == receipt['job_id'])
                self.assertEqual('queued', queued['state'])
                self.assertIsNone(queued['started_at'])
                second.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await second
                self.assertFalse(second_started.is_set())
                release.set()
                await first
                self.assertTrue(await asyncio.to_thread(second_started.wait, 5))
                worker = self.runtime.singleflight_workers[receipt['job_id']]['task']
                await asyncio.shield(worker)
                saved = await client.get('/gateway/jobs/' + receipt['job_id'] + '/result')
                self.assertEqual(200, saved.status_code, saved.text)
                self.assertEqual('SQLite reply', saved.json()['result']['choices'][0]['message']['content'])
        original = self.runtime.process_chat_payload
        try:
            self.runtime.process_chat_payload = model
            capacity = ServiceState(max_workers=1)
            capacity.install(self.runtime)
            with self.client():
                asyncio.run(run())
        finally:
            release.set()
            self.runtime.process_chat_payload = original
        self.assertEqual(['First', 'Second'], calls)

    def test_same_android_room_keeps_each_execution_profile_in_its_original_conversation(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            first = client.post('/v1/chat/completions', json=self.payload('one'))
            payload = self.payload('two')
            payload['gateway']['profile_id'] = 'another-profile'
            second = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(200, second.status_code, second.text)
            self.assertNotEqual(first.headers['x-gateway-conversation-id'], second.headers['x-gateway-conversation-id'])
            payload['gateway'].update(command_id='three', conversation_id=first.headers['x-gateway-conversation-id'])
            rejected = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(409, rejected.status_code, rejected.text)
            rows = client.get('/gateway/conversations').json()['items']
            self.assertEqual({'selected-profile', 'another-profile'}, {row['profile_id'] for row in rows})

    def test_admission_failure_rolls_back_command_and_transcript_together(self):
        with self.client() as client:
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute("CREATE TRIGGER reject_new_job BEFORE INSERT ON gateway_jobs BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            with patch.object(self.runtime, 'process_chat_payload') as model:
                response = client.post('/v1/chat/completions', json=self.payload())
                self.assertEqual(503, response.status_code, response.text)
                model.assert_not_called()
            self.assertEqual(404, client.get('/gateway/commands/turn-one').status_code)
            self.assertEqual([], client.get('/gateway/conversations').json()['items'])
            with closing(sqlite3.connect(self.db)) as db:
                self.assertEqual(0, db.execute('SELECT COUNT(*) FROM gateway_messages').fetchone()[0])

    def test_failed_final_commit_never_publishes_a_completed_transcript(self):
        with self.client() as client:
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute("CREATE TRIGGER reject_final BEFORE UPDATE ON gateway_jobs WHEN NEW.status='completed' BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            with patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
                response = client.post('/v1/chat/completions', json=self.payload())
                self.assertEqual(503, response.status_code, response.text)
            receipt = client.get('/gateway/commands/turn-one').json()
            page = client.get('/gateway/conversations/' + receipt['conversation_id'] + '/messages').json()
            assistant = next(row for row in page['messages'] if row['role'] == 'assistant')
            self.assertNotEqual('completed', assistant['status'])
            self.assertNotIn('SQLite reply', assistant['content'])

    def test_saved_browser_and_receipts_remain_readable_when_sqlite_rejects_writes(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            response = client.post('/v1/chat/completions', json=self.payload())
            self.assertEqual(200, response.status_code, response.text)
            conversation_id = response.headers['x-gateway-conversation-id']
            original_connect, denied = self.runtime._durable_connect, []
            def authorize(action, table, column, database, trigger):
                if action in {sqlite3.SQLITE_INSERT, sqlite3.SQLITE_UPDATE, sqlite3.SQLITE_DELETE}:
                    denied.append((action, table))
                    return sqlite3.SQLITE_DENY
                return sqlite3.SQLITE_OK
            def read_only_connect():
                db = original_connect()
                db.set_authorizer(authorize)
                return db
            with patch.object(self.runtime, '_durable_connect', side_effect=read_only_connect):
                self.assertEqual(1, len(client.get('/gateway/conversations').json()['items']))
                self.assertEqual(conversation_id, client.get('/gateway/conversations/' + conversation_id).json()['conversation_id'])
                messages = client.get('/gateway/conversations/' + conversation_id + '/messages').json()['messages']
                self.assertEqual('SQLite reply', messages[-1]['content'])
                receipt = client.get('/gateway/commands/turn-one').json()
                self.assertEqual(conversation_id, receipt['conversation_id'])
                self.assertEqual(1, len(client.get('/gateway/job-catalog').json()['items']))
                projection = self.runtime.gateway_conversations
                self.assertEqual(receipt['gateway_id'], projection.gateway_id())
                self.assertEqual([], projection.pending_generation())
                self.assertFalse(projection.is_expired('local:single-user', 'generation', receipt['job_id']))
            self.assertEqual([], denied)

    def test_transcript_pages_budget_encoded_json_and_preserve_large_single_messages(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            response = client.post('/v1/chat/completions', json=self.payload())
            conversation_id = response.headers['x-gateway-conversation-id']
            contents = ['"\nπ' * 60000 for _ in range(6)] + ['"\n' * 600000]
            projection = self.runtime.gateway_conversations
            def publish(db):
                for index, content in enumerate(contents):
                    turn = 'large-' + str(index)
                    projection.message(db, conversation_id, turn, 'assistant', content, 'completed', turn + ':assistant')
            projection.tx(publish)
            received, cursor, pages = {}, None, 0
            while True:
                page = client.get('/gateway/conversations/' + conversation_id + '/messages', params={'after_cursor': cursor} if cursor else {})
                self.assertEqual(200, page.status_code, page.text[:200])
                value = page.json()
                self.assertLessEqual(len(page.content), 1_900_000)
                for item in value['messages']:
                    parts = received.setdefault(item['logical_message_id'], [])
                    self.assertEqual(len(parts), item['part_index'])
                    self.assertLessEqual(len(item['content'].encode('utf-8')), 256 * 1024)
                    parts.append(item)
                pages += 1
                self.assertLess(pages, 10)
                if not value['has_more']:
                    break
                cursor = value['next_cursor']
            self.assertGreater(pages, 2)
            restored = []
            for parts in received.values():
                content = ''.join(part['content'] for part in parts)
                self.assertEqual({len(parts)}, {part['part_count'] for part in parts})
                self.assertEqual({hashlib.sha256(content.encode('utf-8')).hexdigest()}, {part['content_sha256'] for part in parts})
                restored.append(content)
            self.assertEqual(['Inspect the repository', 'SQLite reply'] + contents, restored)

    def test_restart_dispatches_only_proved_undispatched_queue_records(self):
        self.runtime.init_durable_job_store()
        for name, dispatched in (('retained-queued', False), ('possibly-dispatched', True)):
            payload = self.payload(name, name)
            marker = admission_context.set({'principal': 'local:single-user', 'command_id': name, 'payload': payload})
            try:
                self.runtime.register_gateway_job(name, 'local', 'interactive', threading.Event(), threading.Event(), task_text='Inspect the repository')
                self.store.claim(name, 'local:single-user')
                if dispatched:
                    job = self.runtime.job_registry[name]
                    job.update(status='running', started_at=time.time(), _claim_dispatch=True)
                    self.runtime.durable_persist_job(name)
            finally:
                admission_context.reset(marker)
        self.runtime.job_registry.clear()
        self.runtime.singleflight_workers.clear()
        self.runtime.durable_job_initialized = False
        self.runtime.init_durable_job_store()
        self.assertEqual('queued', self.runtime.job_registry['retained-queued']['status'])
        self.assertEqual('failed', self.runtime.job_registry['possibly-dispatched']['status'])
        calls = []
        def model(payload, cancel, progress, hard):
            calls.append(payload['_gateway_job_id'])
            return self.completion(payload, cancel, progress, hard)
        original = self.runtime.process_chat_payload
        async def recover():
            capacity = ServiceState(1)
            capacity.install(self.runtime)
            await capacity.recover_queued(self.runtime)
            await asyncio.gather(*list(capacity.recovery_tasks))
            await capacity.recover_queued(self.runtime)
            self.assertFalse(capacity.recovery_tasks)
        try:
            self.runtime.process_chat_payload = model
            asyncio.run(recover())
        finally:
            self.runtime.process_chat_payload = original
        self.assertEqual(['retained-queued'], calls)
        receipt = self.runtime.gateway_conversations.command('local:single-user', 'possibly-dispatched')
        assistant = self.runtime.gateway_conversations.messages('local:single-user', receipt['conversation_id'])['messages'][-1]
        self.assertEqual('failed', assistant['status'])

    def register_pending_generation(self, job_id):
        self.runtime.init_durable_job_store()
        payload = self.payload(job_id, job_id)
        marker = admission_context.set({'principal': 'local:single-user', 'command_id': job_id,
                                        'payload': payload, 'retention': 'full'})
        try:
            self.runtime.register_gateway_job(job_id, 'local', 'interactive', threading.Event(), threading.Event(), task_text='Inspect')
            self.store.claim(job_id, 'local:single-user')
        finally:
            admission_context.reset(marker)
        return payload

    def test_queued_stop_commits_before_acknowledgment_and_never_runs_after_restart(self):
        job_id = 'queued-stop-before-restart'
        self.register_pending_generation(job_id)
        generation = self.runtime.job_registry[job_id]['generation']
        async def run():
            transport = httpx.ASGITransport(app=self.gateway.app, client=('127.0.0.1', 2222))
            async with httpx.AsyncClient(transport=transport, base_url='http://127.0.0.1:8090') as client:
                stopped = await client.post('/gateway/jobs/' + job_id + '/cancel')
                self.assertEqual(200, stopped.status_code, stopped.text)
                self.assertTrue(stopped.json()['canceled'])
                self.assertFalse(stopped.json()['cancellationRequested'])
                self.assertEqual('confirmed', stopped.json()['cancellationOutcome'])
                admission = self.runtime.gateway_conversations.pending_generation(job_id)
                self.assertEqual('finished', admission['dispatch_state'])
                self.assertIsNone(admission['payload_json'])
                self.runtime.job_registry.clear()
                self.runtime.singleflight_workers.clear()
                self.runtime.durable_job_initialized = False
                self.runtime.init_durable_job_store()
                capacity = ServiceState(1)
                capacity.install(self.runtime)
                await capacity.recover_queued(self.runtime)
                self.assertFalse(capacity.recovery_tasks)
                self.assertEqual('cancelled', self.runtime.job_registry[job_id]['status'])
                self.assertEqual(generation, self.runtime.job_registry[job_id]['generation'])
                receipt = (await client.get('/gateway/commands/' + job_id)).json()
                self.assertEqual(job_id, receipt['job_id'])
                page = (await client.get('/gateway/conversations/' + receipt['conversation_id'] + '/messages')).json()
                assistant = next(item for item in page['messages'] if item['role'] == 'assistant')
                self.assertEqual('cancelled', assistant['status'])
                repeated = await client.post('/gateway/jobs/' + job_id + '/cancel')
                self.assertEqual('confirmed', repeated.json()['cancellationOutcome'])
        with patch.object(self.runtime, 'process_chat_payload') as model:
            asyncio.run(run())
            model.assert_not_called()

    def test_queued_stop_fences_worker_between_capacity_reservation_and_dispatch(self):
        job_id = 'queued-stop-at-dispatch'
        payload = self.register_pending_generation(job_id)
        payload['_gateway_job_id'] = job_id
        job = self.runtime.job_registry[job_id]
        receipts = []
        with patch.object(self.runtime, 'process_chat_payload') as model:
            capacity = ServiceState(1)
            def stop_before_claim(principal):
                receipts.append(self.runtime.cancel_gateway_job(job_id))
                return True
            capacity.authorized = stop_before_claim
            capacity.install(self.runtime)
            code, result = self.runtime.process_chat_payload(payload, job['_cancel_event'], lambda event: None, job['_hard_cancel_event'])
            self.assertEqual(499, code, result)
            model.assert_not_called()
        self.assertEqual('confirmed', receipts[0]['cancellationOutcome'])
        self.assertEqual('cancelled', self.runtime.job_registry[job_id]['status'])
        self.assertEqual('finished', self.runtime.gateway_conversations.pending_generation(job_id)['dispatch_state'])
        self.assertEqual({}, capacity.workers)

    def test_stop_after_durable_dispatch_retains_intent_without_claiming_confirmation(self):
        started, release = threading.Event(), threading.Event()
        calls = []
        def model(*args):
            calls.append(args[0]['_gateway_job_id'])
            started.set()
            self.assertTrue(release.wait(5))
            return self.completion(*args)
        async def run():
            transport = httpx.ASGITransport(app=self.gateway.app, client=('127.0.0.1', 2222))
            async with httpx.AsyncClient(transport=transport, base_url='http://127.0.0.1:8090') as client:
                request = asyncio.create_task(client.post('/v1/chat/completions', json=self.payload('dispatched-stop', 'dispatched-stop')))
                try:
                    self.assertTrue(await asyncio.to_thread(started.wait, 5))
                    receipt = (await client.get('/gateway/commands/dispatched-stop')).json()
                    job_id = receipt['job_id']
                    self.assertEqual('dispatched', self.runtime.gateway_conversations.pending_generation(job_id)['dispatch_state'])
                    stopped = await client.post('/gateway/jobs/' + job_id + '/cancel')
                    self.assertEqual(200, stopped.status_code, stopped.text)
                    self.assertFalse(stopped.json()['canceled'])
                    self.assertTrue(stopped.json()['cancellationRequested'])
                    self.assertEqual('requested', stopped.json()['cancellationOutcome'])
                    with closing(sqlite3.connect(self.db)) as db:
                        saved = json.loads(db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0])
                    self.assertTrue(saved['cancellation_requested'])
                    self.assertEqual('running', saved['status'])
                finally:
                    release.set()
                    response = await request
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual([job_id], calls)
        try:
            with patch.object(self.runtime, 'process_chat_payload', side_effect=model):
                ServiceState(1).install(self.runtime)
                asyncio.run(run())
        finally:
            release.set()

    def test_queued_generation_rechecks_revoked_authority_before_first_dispatch(self):
        self.runtime.init_durable_job_store()
        payload = self.payload()
        payload['_gateway_job_id'] = 'revoked-queue'
        marker = admission_context.set({'principal': 'local:single-user', 'command_id': 'turn-one', 'payload': payload})
        try:
            self.runtime.register_gateway_job('revoked-queue', 'local', 'interactive', threading.Event(), threading.Event(), task_text='Inspect')
        finally:
            admission_context.reset(marker)
        original = self.runtime.process_chat_payload
        calls = []
        try:
            self.runtime.process_chat_payload = lambda *args: calls.append(args)
            capacity = ServiceState(1)
            capacity.authorized = lambda principal: False
            capacity.install(self.runtime)
            status, result = self.runtime.process_chat_payload(payload, threading.Event(), lambda event: None, threading.Event())
            self.assertEqual(403, status)
            self.assertEqual('principal_revoked', result['error']['code'])
            self.assertEqual([], calls)
            self.assertEqual('queued', self.runtime.gateway_conversations.pending_generation('revoked-queue')['dispatch_state'])
        finally:
            self.runtime.process_chat_payload = original

    def test_recovered_final_commit_failure_blocks_late_nonstream_reexecution(self):
        self.recovered_final_commit_failure(False)

    def test_recovered_final_commit_failure_blocks_late_stream_reexecution(self):
        self.recovered_final_commit_failure(True)

    def recovered_final_commit_failure(self, stream):
        from v14.jobs import DurableCommitError
        self.runtime.init_durable_job_store()
        job_id = 'retained-before-restart'
        payload = dict(self.payload('retained-command', 'retained-conversation'), stream=stream)
        marker = admission_context.set({'principal': 'local:single-user', 'command_id': 'retained-command',
                                        'payload': payload, 'retention': 'full'})
        try:
            admitted = self.runtime.register_gateway_job(job_id, 'local', 'interactive', threading.Event(), threading.Event(), task_text='Inspect')
            generation = admitted['generation']
            self.store.claim(job_id, 'local:single-user')
        finally:
            admission_context.reset(marker)
        self.runtime.job_registry.clear()
        self.runtime.singleflight_workers.clear()
        self.runtime.durable_job_initialized = False
        self.runtime.init_durable_job_store()
        self.assertEqual('queued', self.runtime.job_registry[job_id]['status'])
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("CREATE TRIGGER reject_recovered_final BEFORE UPDATE ON gateway_jobs WHEN NEW.status='completed' BEGIN SELECT RAISE(ABORT,'disk failure'); END")
        original, calls = self.runtime.process_chat_payload, []
        def model(*args):
            calls.append(args[0]['_gateway_job_id'])
            return self.completion(*args)
        async def run():
            capacity = ServiceState(1)
            capacity.install(self.runtime)
            await capacity.recover_queued(self.runtime)
            outcomes = await asyncio.gather(*list(capacity.recovery_tasks), return_exceptions=True)
            self.assertEqual(1, len(outcomes))
            self.assertIsInstance(outcomes[0], DurableCommitError)
            worker = self.runtime.singleflight_workers[job_id]
            self.assertIsInstance(worker.get('persistence_error'), DurableCommitError)
            worker['finished_at'] = time.time() - self.runtime.SINGLEFLIGHT_COMPLETED_RESULT_TTL_SECONDS - 1
            self.runtime.cleanup_singleflight_state()
            self.assertIs(worker, self.runtime.singleflight_workers[job_id])
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute('DROP TRIGGER reject_recovered_final')
            transport = httpx.ASGITransport(app=self.gateway.app, client=('127.0.0.1', 2222))
            async with httpx.AsyncClient(transport=transport, base_url='http://127.0.0.1:8090') as client:
                response = await client.post('/v1/chat/completions', json=payload, headers={'X-Gateway-Job-ID': job_id})
                self.assertEqual(503, response.status_code, response.text)
                self.assertEqual('durable_commit_failed', response.json()['error']['code'])
                self.assertEqual('unknown', response.json()['error']['dispatch_state'])
                self.assertEqual(job_id, response.headers['x-gateway-job-id'])
                receipt = (await client.get('/gateway/commands/retained-command')).json()
                self.assertEqual(job_id, receipt['job_id'])
                page = (await client.get('/gateway/conversations/' + receipt['conversation_id'] + '/messages')).json()
                assistant = next(message for message in page['messages'] if message['role'] == 'assistant')
                self.assertNotEqual('completed', assistant['status'])
                self.assertNotIn('SQLite reply', assistant['content'])
            self.assertEqual(generation, self.runtime.job_registry[job_id]['generation'])
            self.assertEqual([job_id], calls)
        try:
            self.runtime.process_chat_payload = model
            asyncio.run(run())
        finally:
            self.runtime.process_chat_payload = original

    def test_stale_transcript_cursor_requests_hydration_without_execution(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            response = client.post('/v1/chat/completions', json=self.payload())
            conversation = response.headers['x-gateway-conversation-id']
            url = '/gateway/conversations/' + conversation + '/messages'
            page = client.get(url).json()
            projection = self.runtime.gateway_conversations
            cursor = projection.read(lambda db: projection.decode_cursor(db, page['next_cursor']))
            cursor['after'] += 100
            response = client.get(url, params={'after_cursor': projection.read(lambda db: projection.encode_cursor(db, cursor))})
            self.assertEqual(409, response.status_code, response.text)
            self.assertEqual('cursor_expired', response.json()['error']['code'])
            hydrated = client.get(url).json()
            self.assertEqual(page['messages'], hydrated['messages'])
            self.assertEqual(1, model.call_count)

    def test_tool_stage_commands_keep_one_visible_turn_and_one_assistant_publication(self):
        call = {'id': 'client-lookup', 'type': 'function', 'function': {'name': 'client_lookup', 'arguments': '{}'}}
        handoff = {'role': 'assistant', 'content': None, 'tool_calls': [call]}
        payload = self.payload('first-stage')
        payload['tools'] = [{'type': 'function', 'function': {'name': 'client_lookup', 'parameters': {'type': 'object'}}}]
        replies = iter([(200, {'choices': [{'index': 0, 'message': handoff, 'finish_reason': 'tool_calls'}]}),
                        (200, {'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': 'Final visible answer', 'reasoning_content': 'Private hidden reasoning'}, 'finish_reason': 'stop'}]})])
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=lambda *args: next(replies)) as model:
            ServiceState(2).install(self.runtime)
            first = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(200, first.status_code, first.text)
            conversation_id, job_id = first.headers['x-gateway-conversation-id'], first.headers['x-gateway-job-id']
            before = client.get('/gateway/conversations/' + conversation_id + '/messages').json()['messages']
            self.assertEqual(2, len(before))
            assistant_id = next(row['message_id'] for row in before if row['role'] == 'assistant')
            followup = copy.deepcopy(payload)
            followup['gateway']['command_id'] = 'tool-result-stage'
            followup['messages'].extend([handoff, {'role': 'tool', 'tool_call_id': call['id'], 'content': 'Private client receipt'}])
            # Continuation commands respect the same bounded admission queue.
            # A rejection rolls back its receipt/publication and remains safe to
            # retry with the original exact command once capacity is available.
            projection = self.runtime.gateway_conversations
            projection.tx(lambda db: db.executemany('INSERT INTO gateway_generation_admissions VALUES(?,?,?,?,?,?,?)',
                [('occupied-' + str(index), 'local:single-user', '{}', 'queued', time.time(), time.time() + 60, '') for index in range(64)]))
            blocked = client.post('/v1/chat/completions', json=followup, headers={'X-Gateway-Job-ID': job_id})
            self.assertEqual(429, blocked.status_code, blocked.text)
            self.assertEqual(1, model.call_count)
            self.assertEqual(404, client.get('/gateway/commands/tool-result-stage').status_code)
            self.assertEqual(before, client.get('/gateway/conversations/' + conversation_id + '/messages').json()['messages'])
            projection.tx(lambda db: db.execute("DELETE FROM gateway_generation_admissions WHERE job_id LIKE 'occupied-%'"))
            second = client.post('/v1/chat/completions', json=followup, headers={'X-Gateway-Job-ID': job_id})
            self.assertEqual(200, second.status_code, second.text)
            receipt = client.get('/gateway/commands/tool-result-stage').json()
            self.assertEqual(job_id, receipt['job_id'])
            self.assertEqual(conversation_id, receipt['conversation_id'])
            after = client.get('/gateway/conversations/' + conversation_id + '/messages').json()['messages']
            self.assertEqual(2, len(after))
            assistant = next(row for row in after if row['role'] == 'assistant')
            self.assertEqual(assistant_id, assistant['message_id'])
            self.assertEqual('first-stage:assistant', assistant['publication_id'])
            self.assertEqual('Final visible answer', assistant['content'])
            self.assertNotIn('Private', json.dumps(after))
            self.assertEqual(2, model.call_count)

    def test_retained_queue_never_silently_changes_its_selected_upstream_route(self):
        self.runtime.init_durable_job_store()
        payload = self.payload()
        payload['_gateway_job_id'] = 'bound-queue'
        marker = admission_context.set({'principal': 'local:single-user', 'command_id': 'turn-one', 'payload': payload})
        try:
            self.runtime.register_gateway_job('bound-queue', 'local', 'interactive', threading.Event(), threading.Event(), task_text='Inspect')
        finally:
            admission_context.reset(marker)
        original = self.runtime.process_chat_payload
        calls = []
        try:
            self.runtime.process_chat_payload = lambda *args: calls.append(args)
            ServiceState(1).install(self.runtime)
            with patch.object(self.runtime, 'LLAMA_BASE', 'http://different-provider.invalid'):
                status, result = self.runtime.process_chat_payload(payload, threading.Event(), lambda event: None, threading.Event())
            self.assertEqual(409, status)
            self.assertEqual('queued_execution_binding_changed', result['error']['code'])
            self.assertEqual([], calls)
        finally:
            self.runtime.process_chat_payload = original


class EngineeringConversationTests(unittest.TestCase):
    tearDown = engineering_fixtures.DurableEngineeringTests.tearDown

    def setUp(self):
        engineering_fixtures.DurableEngineeringTests.setUp(self)
        original_connect = self.runtime._durable_connect
        def connect():
            db = original_connect()
            # Match the production runtime, rather than tuple-only fixtures.
            db.row_factory = sqlite3.Row
            return db
        self.runtime._durable_connect = connect

    def test_production_row_factory_accepts_current_owner_and_fences_stale_dispatch(self):
        job = self.store.create('owner', self.request)
        claimed, lease, _ = self.store.claim(job['id'])
        self.store.assert_claim(claimed)
        attempt = {'attempt_id': 'first-attempt', 'binding_id': self.request['execution_binding_id'],
                   'state': 'DISPATCHED', 'dispatched': True, 'reserved_tokens': 100, 'role': 'planner'}
        self.store.record_model_attempt(claimed, attempt)
        self.assertEqual([attempt], self.store.model_attempts(job['id']))
        self.assertTrue(self.store.complete(claimed, lease, 'COMMIT', {}))
        current = self.store.claim(job['id'])[0]
        current['data']['execution_integrity'] = 'VERIFIED'
        operation = self.service.operations.prepare(current, 'PATCH', '/repos/owner/repo/git/refs/heads/feature/job', {'sha': 'a' * 40})
        self.service.operations.dispatch(current, operation)
        self.assertTrue(self.service.operations.page(current)['operations'][0]['dispatched'])
        stale = dict(current, _owner_epoch=current['_owner_epoch'] - 1)
        with self.assertRaises(ExecutionBindingError):
            self.store.assert_claim(stale)
        with self.assertRaises(ExecutionBindingError):
            self.store.record_model_attempt(stale, dict(attempt, attempt_id='stale-attempt'))
        with self.assertRaises(ExecutionBindingError):
            self.service.operations.save(stale, dict(operation, operation_id='stale-operation'))

    def test_old_worker_cannot_confirm_cancellation_of_its_replacement(self):
        job = self.store.create('owner', self.request)
        old, old_lease, _ = self.store.claim(job['id'])
        self.store.recover()
        current, current_lease, _ = self.store.claim(job['id'])
        self.assertNotEqual(old['_owner_epoch'], current['_owner_epoch'])
        self.store.control(job['id'], 'owner', 'cancel', current['revision'])
        self.assertFalse(self.store.complete(old, old_lease, 'READ', {'ignored': True}))
        self.store.confirm_cancellation(job['id'], old['_owner_epoch'])
        self.assertEqual('CANCELLING', self.store.get(job['id'])['state'])
        self.assertFalse(self.store.complete(current, current_lease, 'READ', {'ignored': True}))
        self.assertEqual('CANCELLED', self.store.get(job['id'])['state'])

    def test_read_scope_completes_after_inspection_without_writes_or_merge_claim(self):
        self.request.update(task_scope='read', branch='main', client_conversation_id='same-chat')
        step = self.backend.step
        def read(job, phase):
            result = step(job, phase)
            if phase == 'READ':
                result['answer'] = 'The source uses the selected model. See app/Example.kt.'
            return result
        self.backend.step = read
        job = self.store.create('owner', self.request)
        self.service.tick(job['id'])
        self.service.tick(job['id'])
        saved = self.store.get(job['id'])
        self.assertEqual('COMPLETED', saved['state'])
        self.assertEqual(['PLAN', 'READ'], self.backend.calls)
        self.assertFalse(saved['data']['final_report']['repository_writes_authorized'])
        self.assertEqual('COMPLETED', saved['data']['final_report']['status'])
        messages = self.store.conversations.messages('owner', saved['conversation_id'])['messages']
        self.assertEqual(['Repair the build', 'The source uses the selected model. See app/Example.kt.'], [row['content'] for row in messages])
        self.assertIsNone(self.store.conversations.snapshot('other', saved['conversation_id']))
        self.assertIsNone(self.store.conversations.command('other', self.request['request_id']))

    def test_instruction_waits_for_a_safe_checkpoint_and_deduplicates(self):
        self.request['client_conversation_id'] = 'same-chat'
        job = self.store.create('owner', self.request)
        store = self.store.conversations
        revision = store.snapshot('owner', job['conversation_id'])['revision']
        instruction = {'kind': 'instruction', 'command_id': 'new-instruction', 'content': 'Also preserve the selected model', 'expected_revision': revision}
        first = store.instruction('owner', job['conversation_id'], instruction)
        self.assertEqual(first, store.instruction('owner', job['conversation_id'], instruction))
        self.assertEqual([], self.backend.calls)
        self.service.tick(job['id'])
        saved = self.store.get(job['id'])
        self.assertEqual([{'command_id': 'new-instruction', 'content': instruction['content']}], saved['data']['user_instructions'])
        messages = store.messages('owner', job['conversation_id'])['messages']
        self.assertEqual(1, sum(row['content'] == instruction['content'] for row in messages))
        self.assertEqual('delivered', next(row['status'] for row in messages if row['content'] == instruction['content']))
        with self.assertRaises(ConversationConflict):
            store.instruction('owner', job['conversation_id'], dict(instruction, content='Conflicting change'))

    def test_paused_jobs_do_not_admit_instructions_that_cannot_be_consumed(self):
        job = self.store.create('owner', self.request)
        projection = self.store.conversations
        for phase in ('NEEDS_INPUT', 'MERGE_READY'):
            with self.subTest(phase=phase):
                self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET state=? WHERE id=?', (phase, job['id'])))
                revision = projection.snapshot('owner', job['conversation_id'])['revision']
                instruction = {'kind': 'instruction', 'command_id': 'paused-' + phase, 'content': 'Continue with a changed objective', 'expected_revision': revision}
                with self.assertRaises(ConversationConflict):
                    projection.instruction('owner', job['conversation_id'], instruction)
                self.assertIsNone(projection.command('owner', instruction['command_id']))
                self.assertEqual(2, len(projection.messages('owner', job['conversation_id'])['messages']))
        self.assertEqual([], self.backend.calls)

    def test_scope_and_branch_binding_survive_recreated_store_and_followup_conflict(self):
        self.request.update(task_scope='plan', branch='main', client_conversation_id='same-chat')
        job = self.store.create('owner', self.request)
        receipt = self.store.conversations.command('owner', self.request['request_id'])
        self.assertEqual(job['id'], receipt['job_id'])
        self.assertEqual(job, self.store.create('owner', self.request))
        changed = dict(self.request, request_id='next', command_id='next', branch='other')
        with self.assertRaises(ConversationConflict):
            self.store.create('owner', changed)
        # A read-only persisted job cannot acquire write authority from a phase.
        self.store.tx(lambda db: db.execute("UPDATE engineering_jobs SET state='COMMIT' WHERE id=?", (job['id'],)))
        self.service.tick(job['id'])
        self.assertEqual([], self.backend.calls)
        self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])

    def test_active_engineering_stop_is_confirmed_by_worker_and_rejects_new_instructions(self):
        job = self.store.create('owner', self.request)
        observed = []
        def cancel_during_work(current, phase):
            pending = self.store.control(current['id'], 'owner', 'cancel', current['revision'])
            observed.append(pending['state'])
            self.assertTrue(pending['cancellation_requested'])
            self.assertIsNone(self.store.get(job['id']).get('finished_at'))
            conversation = self.store.conversations.snapshot('owner', current['conversation_id'])
            with self.assertRaises(ConversationConflict):
                self.store.conversations.instruction('owner', current['conversation_id'], {'kind': 'instruction', 'command_id': 'too-late', 'content': 'Continue anyway', 'expected_revision': conversation['revision']})
            return {'head_sha': 'a' * 40}
        self.backend.step = cancel_during_work
        self.service.tick(job['id'])
        self.assertEqual(['CANCELLING'], observed)
        final = self.store.get(job['id'])
        self.assertEqual('CANCELLED', final['state'])
        self.assertFalse(final['cancellation_requested'])
        self.assertEqual('confirmed', final['cancellation_outcome'])
        self.assertIsNotNone(final['finished_at'])
        assistant = self.store.conversations.messages('owner', job['conversation_id'])['messages'][-1]
        self.assertEqual('cancelled', assistant['status'])

    def test_transcript_snapshot_paging_recovers_a_concurrent_publication_update(self):
        job = self.store.create('owner', self.request)
        store = self.store.conversations
        page = store.messages('owner', job['conversation_id'], limit=1)
        self.assertTrue(page['has_more'])
        stable = store.messages('owner', job['conversation_id'])['messages'][-1]['message_id']
        store.tx(lambda db: store.message(db, job['conversation_id'], self.request['request_id'], 'assistant', 'Finished while pages were loading', 'completed', self.request['request_id'] + ':assistant'))
        # The original watermark does not invent an intermediate assistant.
        last = store.messages('owner', job['conversation_id'], page['next_cursor'], limit=1)
        update = store.messages('owner', job['conversation_id'], last['next_cursor'], limit=1)
        self.assertEqual([stable], [row['message_id'] for row in update['messages']])
        self.assertEqual('Finished while pages were loading', update['messages'][0]['content'])


class ScopedConversationRouteTests(unittest.TestCase):
    setUp = engineering_fixtures.DurableEngineeringTests.setUp
    tearDown = engineering_fixtures.DurableEngineeringTests.tearDown

    def test_unified_catalog_and_transcript_reads_keep_principal_and_engineering_scopes(self):
        engineering_fixtures.DurableEngineeringTests.admit(self, 'tailnet:owner')
        self.request['client_conversation_id'] = 'shared-client-key'
        job = self.store.create('tailnet:owner', self.request)
        self.store.tx(lambda db: db.execute('CREATE TABLE IF NOT EXISTS gateway_jobs(public_json TEXT)'))
        permissions = {'owner@example.test': {'principalId': 'owner', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                       'other@example.test': {'principalId': 'other', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                       'reader@example.test': {'principalId': 'owner', 'scopes': ['chat', 'jobs', 'tools']}}
        app = FastAPI()
        app.add_middleware(Admission, config=Config(mode='trusted_proxy', allowed_hosts=('testserver',), allowed_origins=(), trusted_users=permissions))
        install_routes(app, self.store.conversations, self.service, ServiceState(), SimpleNamespace(owned_job_ids=lambda ids, owner: set()))
        with TestClient(app, client=('127.0.0.1', 12000)) as client:
            headers = {'Tailscale-User-Login': 'owner@example.test'}
            listed = client.get('/gateway/job-catalog', headers=headers).json()
            self.assertEqual([job['id']], [row['job_id'] for row in listed['items']])
            self.assertEqual(1, listed['counts']['queued'])
            urls = ['/gateway/conversations/' + job['conversation_id'], '/gateway/conversations/' + job['conversation_id'] + '/messages', '/gateway/commands/' + self.request['request_id']]
            for url in urls:
                self.assertEqual(200, client.get(url, headers=headers).status_code)
                for login in ('other@example.test', 'reader@example.test'):
                    self.assertEqual(404, client.get(url, headers={'Tailscale-User-Login': login}).status_code)
            for login in ('other@example.test', 'reader@example.test'):
                scoped = {'Tailscale-User-Login': login}
                self.assertEqual([], client.get('/gateway/conversations', headers=scoped).json()['items'])
                self.assertEqual([], client.get('/gateway/job-catalog', headers=scoped).json()['items'])
            self.assertEqual([], self.backend.calls)

    def test_read_only_scope_cannot_reach_host_mutation_transport(self):
        from v14.engineering_backend import GitHubEngineeringBackend
        backend = GitHubEngineeringBackend(self.runtime)
        backend.token = lambda: 'fixture-token'
        job = {'id': 'read-job', 'principal': 'owner', 'revision': 1, 'state': 'COMMIT', 'deadline': time.time() + 60,
               'request': {'repository': 'owner/repo', 'task_scope': 'read'}, 'data': {}}
        with patch('v14.engineering_backend.requests.request') as mutation, patch('v14.engineering_backend.requests.get') as identity:
            with self.assertRaises(PermissionError):
                backend.api('/repos/owner/repo/git/refs', 'POST', {'ref': 'refs/heads/feature'}, job)
            mutation.assert_not_called()
            identity.assert_not_called()
