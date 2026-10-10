"""Real SQLite upgrade, streaming admission and restart regressions."""
import asyncio
import importlib
import json
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from contextlib import ExitStack, closing
from pathlib import Path
from unittest.mock import patch

import httpx
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from gateway_security import DeviceStore
from v14.jobs import DurableCommitError


class JournalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway = importlib.import_module('gateway_v14')

    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.temp = self.stack.enter_context(tempfile.TemporaryDirectory())
        self.db = Path(self.temp) / 'jobs.sqlite3'
        self.store = DeviceStore(Path(self.temp) / 'auth.sqlite3')
        self.runtime = self.gateway.runtime
        for name, value in {
            'GATEWAY_JOB_DB_PATH': self.db, 'GATEWAY_DURABLE_JOBS': True,
            'durable_job_initialized': False, 'startup_services_started': False,
            'MCP_PREWARM_ON_STARTUP': False, 'job_registry': {},
            'singleflight_workers': {}, 'singleflight_request_index': {},
            'durable_job_metrics': dict.fromkeys(self.runtime.durable_job_metrics, 0),
            'device_store': self.store,
        }.items():
            self.stack.enter_context(patch.object(self.runtime, name, value))
        self.stack.enter_context(patch('v14.identity.device_store', self.store))
        self.gateway.app.middleware_stack = None
        self.addCleanup(setattr, self.gateway.app, 'middleware_stack', None)

    def client(self):
        return TestClient(self.gateway.app, base_url='http://127.0.0.1:8090', client=('127.0.0.1', 2222))

    def legacy_database(self, missing_finished=False):
        # Reproduce an existing journal without created_at, as in the Windows log.
        finished = '' if missing_finished else ', finished_at REAL'
        now = time.time()
        self.old_result = {'choices': [{'message': {'content': 'Saved answer'}}]}
        self.old_public = {'job_id': 'saved', 'status': 'completed', 'created_at': now - 20,
                           'updated_at': now, 'finished_at': now - 1, 'last_sequence': 7}
        self.old_event = {'sequence': 7, 'event': 'progress', 'message': 'Saved progress'}
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('CREATE TABLE gateway_jobs (job_id TEXT PRIMARY KEY, public_json TEXT NOT NULL, '
                       'result_json TEXT, status TEXT, updated_at REAL' + finished + ')')
            db.execute('CREATE TABLE gateway_events (job_id TEXT NOT NULL, sequence INTEGER NOT NULL, '
                       'event_json TEXT NOT NULL, created_at REAL NOT NULL, PRIMARY KEY(job_id, sequence))')
            db.execute('INSERT INTO gateway_jobs(job_id,public_json,result_json,status,updated_at) VALUES(?,?,?,?,?)',
                       ('saved', json.dumps(self.old_public), json.dumps(self.old_result), 'completed', now))
            db.execute('INSERT INTO gateway_events VALUES(?,?,?,?)',
                       ('saved', 7, json.dumps(self.old_event), now))
        self.store.claim('saved', 'local:single-user')

    def completion(self, payload, cancel, progress, hard_cancel):
        progress({'phase': 'working', 'message': 'Working'})
        return 200, {'id': 'chatcmpl-test', 'model': 'local', 'choices': [
            {'index': 0, 'message': {'role': 'assistant', 'content': 'SQLite reply'}, 'finish_reason': 'stop'}]}

    def chat(self, client, stream=True):
        headers = {'Origin': 'http://127.0.0.1:8090'}
        headers['X-Gateway-CSRF'] = client.get('/gateway/session', headers=headers).json()['csrfToken']
        return client.post('/v1/chat/completions', headers=headers, json={
            'model': 'local', 'messages': [{'role': 'user', 'content': 'Hello'}], 'stream': stream})

    def test_fresh_database_stream_commits_and_restores_result_and_events(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            response = self.chat(client)
            self.assertEqual(response.status_code, 200)
            self.assertIn('SQLite reply', response.text)
            self.assertEqual(response.text.count('data: [DONE]'), 1)
            model.assert_called_once()
        job_id = response.headers['x-gateway-job-id']
        self.runtime.job_registry.clear()
        self.runtime.durable_job_initialized = False
        self.assertTrue(self.runtime.init_durable_job_store())
        restored = self.runtime.job_registry[job_id]
        self.assertEqual(restored['status'], 'completed')
        self.assertEqual(restored['_result']['choices'][0]['message']['content'], 'SQLite reply')
        self.assertEqual(restored['_events'][0]['sequence'], 1)
        self.assertEqual(self.runtime.durable_job_metrics['event_writes'], 1)

    def test_legacy_database_stream_upgrades_without_losing_saved_state(self):
        self.legacy_database(missing_finished=True)
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            response = self.chat(client)
            self.assertEqual(response.status_code, 200)
            self.assertIn('SQLite reply', response.text)
            self.assertEqual(client.get('/gateway/jobs/saved/result').status_code, 200)
        for _ in range(2):
            self.runtime.job_registry.clear()
            self.runtime.durable_job_initialized = False
            self.assertTrue(self.runtime.init_durable_job_store())
            self.assertEqual(self.runtime.job_registry['saved']['_result'], self.old_result)
            self.assertEqual(list(self.runtime.job_registry['saved']['_events']), [self.old_event])
        with closing(sqlite3.connect(self.db)) as db, db:
            public, created, finished = db.execute(
                'SELECT public_json,created_at,finished_at FROM gateway_jobs WHERE job_id="saved"').fetchone()
        self.assertEqual(json.loads(public), self.old_public)
        self.assertEqual(created, self.old_public['created_at'])
        self.assertEqual(finished, self.old_public['finished_at'])

    def test_interrupted_legacy_job_is_restored_without_replay(self):
        self.legacy_database()
        public = dict(self.old_public, status='running', finished_at=None)
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('UPDATE gateway_jobs SET public_json=?,status=?', (json.dumps(public), 'running'))
        with self.client(), patch.object(self.runtime, 'process_chat_payload') as model:
            model.assert_not_called()
            self.assertEqual(self.runtime.job_registry['saved']['status'], 'failed')
        with closing(sqlite3.connect(self.db)) as db, db:
            status, finished = db.execute('SELECT status,finished_at FROM gateway_jobs').fetchone()
        self.assertEqual(status, 'failed')
        self.assertIsNotNone(finished)

    def test_empty_legacy_database_accepts_nonstream_chat(self):
        self.legacy_database()
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('DELETE FROM gateway_events')
            db.execute('DELETE FROM gateway_jobs')
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            response = self.chat(client, stream=False)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['choices'][0]['message']['content'], 'SQLite reply')

    def test_legacy_event_timestamp_upgrade_preserves_event_payload(self):
        self.legacy_database()
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('ALTER TABLE gateway_events DROP COLUMN created_at')
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            self.assertEqual(self.chat(client).status_code, 200)
        with closing(sqlite3.connect(self.db)) as db, db:
            event, created = db.execute('SELECT event_json,created_at FROM gateway_events WHERE job_id="saved"').fetchone()
        self.assertEqual(json.loads(event), self.old_event)
        self.assertIsNone(created)  # Never invent a historical timestamp.

    def test_unsupported_schema_rolls_back_upgrade_and_stops_startup(self):
        self.legacy_database()
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('ALTER TABLE gateway_events RENAME COLUMN event_json TO unknown_payload')
        with self.assertRaises(DurableCommitError):
            with self.client():
                self.fail('Startup must reject an unsupported journal')
        self.assertFalse(self.runtime.durable_job_initialized)
        with closing(sqlite3.connect(self.db)) as db, db:
            columns = {row[1] for row in db.execute('PRAGMA table_info(gateway_jobs)')}
            self.assertNotIn('created_at', columns)
            self.assertEqual(json.loads(db.execute('SELECT public_json FROM gateway_jobs').fetchone()[0]), self.old_public)
            self.assertEqual(json.loads(db.execute('SELECT unknown_payload FROM gateway_events').fetchone()[0]), self.old_event)

    def test_registration_write_failure_returns_503_before_stream_or_model(self):
        with self.client() as client:
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute("CREATE TRIGGER reject_jobs BEFORE INSERT ON gateway_jobs BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            with patch.object(self.runtime, 'process_chat_payload') as model:
                for stream in (True, False):
                    response = self.chat(client, stream=stream)
                    self.assertEqual(response.status_code, 503)
                    self.assertEqual(response.json()['error']['code'], 'durable_commit_failed')
                model.assert_not_called()
            self.assertFalse(self.runtime.job_registry)

    def test_final_commit_failure_emits_error_without_successful_answer(self):
        with self.client() as client:
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute("CREATE TRIGGER reject_completion BEFORE UPDATE ON gateway_jobs WHEN NEW.status='completed' "
                           "BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            with patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
                response = self.chat(client)
            self.assertEqual(response.status_code, 200)
            self.assertIn('gateway_stream_error', response.text)
            self.assertNotIn('SQLite reply', response.text)
            self.assertEqual(response.text.count('data: [DONE]'), 1)
            job_id = response.headers['x-gateway-job-id']
            self.assertNotEqual(self.runtime.job_registry[job_id]['status'], 'completed')
            with closing(sqlite3.connect(self.db)) as db, db:
                status, result = db.execute('SELECT status,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
            self.assertNotEqual(status, 'completed')
            self.assertIsNone(result)

    def test_failed_stream_commit_is_shared_with_nonstream_follower(self):
        started, release = threading.Event(), threading.Event()
        resolve = self.runtime.resolve_singleflight_job_id

        def blocked_model(*args):
            started.set()
            if not release.wait(3):
                raise AssertionError('Follower did not attach')
            return self.completion(*args)

        async def scenario():
            attached = asyncio.Event()
            def observe_follower(*args, **kwargs):
                result = resolve(*args, **kwargs)
                if result[1]:
                    attached.set()
                return result
            transport = httpx.ASGITransport(app=self.gateway.app, client=('127.0.0.1', 2222))
            async with httpx.AsyncClient(transport=transport, base_url='http://127.0.0.1:8090') as client:
                payload = {'model': 'local', 'messages': [{'role': 'user', 'content': 'Hello'}]}
                with patch.object(self.runtime, 'resolve_singleflight_job_id', side_effect=observe_follower):
                    leader = asyncio.create_task(client.post('/v1/chat/completions', json=dict(payload, stream=True)))
                    try:
                        self.assertTrue(await asyncio.to_thread(started.wait, 2))
                        follower = asyncio.create_task(client.post('/v1/chat/completions', json=dict(payload, stream=False)))
                        await asyncio.wait_for(attached.wait(), 2)
                    finally:
                        release.set()
                    return await asyncio.gather(leader, follower)

        with self.client(), patch.object(self.runtime, 'process_chat_payload', side_effect=blocked_model) as model:
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute("CREATE TRIGGER reject_completion BEFORE UPDATE ON gateway_jobs WHEN NEW.status='completed' "
                           "BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            leader, follower = asyncio.run(scenario())
            model.assert_called_once()
            self.assertIn('gateway_stream_error', leader.text)
            self.assertNotIn('SQLite reply', leader.text)
            self.assertEqual(follower.status_code, 503)
            self.assertEqual(follower.json()['error']['code'], 'durable_commit_failed')


if __name__ == '__main__':
    unittest.main()
