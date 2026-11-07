"""Bounded offline/reload qualification of the production singleflight and journal paths."""
import asyncio
import sqlite3
import threading
import unittest
from contextlib import closing
from unittest.mock import patch

import test_gateway_v14_jobs as journal_tests


class BoundedConnectionSoakTests(unittest.TestCase):
    setUpClass = classmethod(journal_tests.JournalTests.setUpClass.__func__)
    setUp = journal_tests.JournalTests.setUp
    client = journal_tests.JournalTests.client

    def test_bounded_disconnect_and_journal_reload_soak_keeps_exactly_one_result_per_job(self):
        executions, expected = [], {}

        async def disconnected_batch(round_index):
            release = asyncio.Event()
            workers = []
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
            try:
                with patch.object(self.runtime, 'SINGLEFLIGHT_ORPHAN_GRACE_SECONDS', 0):
                    for _ in range(3):
                        for job_id, worker, soft in workers:
                            self.runtime.mark_gateway_job_disconnected(job_id)
                            await self.runtime.cancel_orphaned_singleflight_after_grace(job_id, worker, soft)
                            self.assertFalse(soft.is_set())
                            self.assertFalse(worker.done())
            finally:
                release.set()
                await asyncio.gather(*(worker for _, worker, _ in workers))
                self.runtime.singleflight_workers.clear()

        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as model:
            for round_index in range(6):
                asyncio.run(asyncio.wait_for(disconnected_batch(round_index), 15))
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
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(48, db.execute("SELECT COUNT(*) FROM gateway_jobs WHERE status='completed'").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM gateway_jobs WHERE status!='completed'").fetchone()[0])
