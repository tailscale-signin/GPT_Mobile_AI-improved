"""Bounded offline/reload qualification of the production singleflight and journal paths."""
import asyncio
import json
import sqlite3
import threading
import time
import unittest
from contextlib import closing
from unittest.mock import patch

import test_gateway_v14_jobs as journal_tests
from v14.jobs import DurableCommitError


class BoundedConnectionSoakTests(unittest.TestCase):
    setUpClass = classmethod(journal_tests.JournalTests.setUpClass.__func__)
    setUp = journal_tests.JournalTests.setUp
    client = journal_tests.JournalTests.client

    def test_resume_allows_next_disconnect_transition_to_be_persisted(self):
        job_id = 'resumed-detach'
        soft, hard = threading.Event(), threading.Event()
        self.runtime.register_gateway_job(job_id, 'local', 'interactive', soft, hard)
        self.runtime.mark_gateway_job_disconnected(job_id)
        with closing(sqlite3.connect(self.db)) as db:
            saved = json.loads(db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0])
        self.assertTrue(saved['disconnected'])
        self.runtime.register_gateway_job(job_id, 'local', 'interactive', soft, hard)
        self.assertFalse(self.runtime.job_registry[job_id]['disconnected'])
        with patch.object(self.runtime, 'durable_persist_job', wraps=self.runtime.durable_persist_job) as persist:
            self.runtime.mark_gateway_job_disconnected(job_id)
            persist.assert_called_once_with(job_id)
        with closing(sqlite3.connect(self.db)) as db:
            saved = json.loads(db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0])
        self.assertTrue(saved['disconnected'])
        self.assertTrue(self.runtime.gateway_continue_disconnected(job_id))
        self.assertFalse(soft.is_set())

    def test_failed_disconnect_commit_restores_state_and_can_be_retried(self):
        job_id = 'detach-commit-failure'
        self.runtime.register_gateway_job(job_id, 'local', 'interactive', threading.Event(), threading.Event())
        original = dict(self.runtime.job_registry[job_id])
        with patch.object(self.runtime, '_durable_connect', side_effect=OSError('injected journal failure')):
            with self.assertRaises(DurableCommitError):
                self.runtime.mark_gateway_job_disconnected(job_id)
        self.assertEqual(original, self.runtime.job_registry[job_id])
        with closing(sqlite3.connect(self.db)) as db:
            saved = json.loads(db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0])
        self.assertFalse(saved['disconnected'])
        self.runtime.mark_gateway_job_disconnected(job_id)
        with closing(sqlite3.connect(self.db)) as db:
            saved = json.loads(db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0])
        self.assertTrue(saved['disconnected'])
        self.assertEqual(self.runtime.job_registry[job_id]['updated_at'], saved['updated_at'])

    def test_late_disconnect_leaves_terminal_result_and_retention_timestamp_unchanged(self):
        job_id = 'late-detach'
        result = {'choices': [{'message': {'role': 'assistant', 'content': 'Saved reply'}}]}
        self.runtime.register_gateway_job(job_id, 'local', 'interactive', threading.Event(), threading.Event())
        self.runtime.finish_gateway_job(job_id, 200, result)
        original = dict(self.runtime.job_registry[job_id])
        with closing(sqlite3.connect(self.db)) as db:
            saved = db.execute('SELECT public_json,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
        with patch.object(self.runtime, 'durable_persist_job', wraps=self.runtime.durable_persist_job) as persist:
            self.runtime.mark_gateway_job_disconnected(job_id)
            self.runtime.mark_gateway_job_disconnected('missing-job')
            persist.assert_not_called()
        self.assertEqual(original, self.runtime.job_registry[job_id])
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(saved, db.execute('SELECT public_json,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone())

    def test_bounded_disconnect_and_journal_reload_soak_keeps_exactly_one_result_per_job(self):
        executions, expected = [], {}
        disconnect_checks = 0

        async def disconnected_batch(round_index):
            release = asyncio.Event()
            workers = []

            async def bounded_phase(name, operation):
                # The journal is synchronous: a whole-batch timer can expire
                # after many healthy commits and surface at the next sleep(0).
                # Keep a bounded deadline for each distinct operation, including
                # an elapsed check when synchronous work delays timer delivery.
                deadline = 15
                started = time.monotonic()
                try:
                    await asyncio.wait_for(operation, deadline)
                except TimeoutError as exc:
                    raise AssertionError(f'Soak round {round_index} {name} exceeded {deadline}s') from exc
                finally:
                    elapsed = time.monotonic() - started
                    print(f'Bounded soak phase: round={round_index} phase={name} '
                          f'elapsed_seconds={elapsed:.3f} deadline_seconds={deadline}', flush=True)
                self.assertLess(elapsed, deadline, f'Soak round {round_index} {name} exceeded its deadline')

            async def admit():
                for index in range(8):
                    job_id = f'soak-{round_index}-{index}'
                    soft, hard = threading.Event(), threading.Event()
                    result = {'choices': [{'message': {'role': 'assistant', 'content': 'Reply for ' + job_id}}]}
                    expected[job_id] = result
                    self.runtime.register_gateway_job(job_id, 'local', 'interactive', soft, hard)
                    self.store.claim(job_id, 'local:single-user')

                    async def complete_once(saved_id=job_id, saved_result=result):
                        executions.append(saved_id)
                        await release.wait()
                        self.runtime.finish_gateway_job(saved_id, 200, saved_result)

                    worker = asyncio.create_task(complete_once())
                    workers.append((job_id, worker, soft))
                    self.runtime.singleflight_workers[job_id] = {'task': worker, 'clients': 0, 'job_mode': 'interactive'}

            async def disconnect():
                nonlocal disconnect_checks
                with patch.object(self.runtime, 'SINGLEFLIGHT_ORPHAN_GRACE_SECONDS', 0):
                    for _ in range(3):
                        for job_id, worker, soft in workers:
                            self.runtime.mark_gateway_job_disconnected(job_id)
                            await self.runtime.cancel_orphaned_singleflight_after_grace(job_id, worker, soft)
                            self.assertFalse(soft.is_set())
                            self.assertFalse(worker.done())
                            disconnect_checks += 1

            async def complete():
                release.set()
                await asyncio.gather(*(worker for _, worker, _ in workers))

            try:
                await bounded_phase('admission', admit())
                await bounded_phase('disconnect', disconnect())
            finally:
                try:
                    await bounded_phase('completion', complete())
                finally:
                    self.runtime.singleflight_workers.clear()

        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as model:
            for round_index in range(6):
                writes_before = self.runtime.durable_job_metrics['writes']
                asyncio.run(disconnected_batch(round_index))
                # One admission, one detach transition and one completion for
                # each job. Every repeated detach still exercises orphan grace.
                self.assertEqual(24, self.runtime.durable_job_metrics['writes'] - writes_before)
                # Exercise production SQLite restoration; process death is separately
                # covered by test_process_crash_preserves_completion_and_marks_active_job_failed.
                self.runtime.job_registry.clear()
                self.runtime.durable_job_initialized = False
                self.assertTrue(self.runtime.init_durable_job_store())
                for job_id, result in expected.items():
                    for _ in range(2):
                        response = client.get('/gateway/jobs/' + job_id + '/result')
                        self.assertEqual(200, response.status_code, response.text)
                        self.assertEqual(result, response.json()['result'])
            model.assert_not_called()
        self.assertEqual(48, len(executions))
        self.assertEqual(48, len(set(executions)))
        self.assertEqual(144, disconnect_checks)
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(48, db.execute("SELECT COUNT(*) FROM gateway_jobs WHERE status='completed'").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM gateway_jobs WHERE status!='completed'").fetchone()[0])
