"""Persistent graph indexing runs independently of the observing client."""
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.code_graph.engine import GraphStore
from v14.code_graph.index_jobs import GraphIndexJobs
from v14.engineering import EngineeringStore
from v14.engineering_crypto import EngineeringCipher


class IndexJobTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.database = Path(self.temp.name) / 'jobs.db'
        self.clock = 1000.0
        runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True,
                                  durable_job_lock=threading.RLock(), _durable_connect=lambda: sqlite3.connect(self.database),
                                  gateway_draining=False, gateway_service=SimpleNamespace(lock=threading.RLock(), draining=False))
        self.storage = EngineeringStore(runtime)
        self.actor = 7
        self.authorized = True
        self.batches = 0
        self.seen_scopes = []
        self.service = SimpleNamespace(store=GraphStore(self.storage), public=False, authorized=lambda principal: self.authorized,
                                       authorize=self.authorize, execute=self.execute)
        self.jobs = GraphIndexJobs(self.service, now=lambda: self.clock)
        self.request = {'action': 'index', 'background': True, 'repository': 'private-owner/private-repository',
                        'branch': 'feature', 'commit_sha': 'a' * 40, 'request_id': 'request-1'}
        self.started = patch.object(self.jobs, 'start')
        self.started.start()

    def tearDown(self):
        self.started.stop()
        self.jobs.close()
        self.temp.cleanup()

    def authorize(self, principal, request):
        return f'{principal}-{self.actor}-{request["branch"]}', '/repository', request['commit_sha'], self.actor

    def execute(self, principal, request, *, expected_scope=None):
        self.seen_scopes.append(expected_scope)
        if expected_scope != self.authorize(principal, request)[0]:
            raise PermissionError('Changed scope')
        if request['action'] == 'index':
            self.batches += 1
        return {'graph': {'repository': request['repository'], 'branch': request['branch'],
                          'commit_sha': request['commit_sha'], 'processed_files': self.batches,
                          'expected_files': 2, 'file_statuses': {'indexed': self.batches},
                          'complete': self.batches == 2},
                'has_more': self.batches < 2, 'index_cursor': f'cursor-{self.batches}' if self.batches < 2 else None}

    def enqueue(self, **extra):
        return self.jobs.handle('principal', dict(self.request, **extra))

    def step(self):
        job = self.jobs._take()
        self.assertIsNotNone(job)
        self.jobs._step(job)
        return self.jobs.status('principal', self.request)

    def test_saved_acceptance_and_continuation_survive_observer_disconnection(self):
        ack = self.enqueue()
        self.assertEqual('QUEUED', ack['index_job']['state'])
        self.assertEqual(['request-1'], ack['index_job']['accepted_request_ids'])
        self.assertNotIn(b'private-repository', self.database.read_bytes())
        self.assertEqual('QUEUED', self.step()['state'])
        finished = self.step()
        self.assertEqual('COMPLETED', finished['state'])
        self.assertEqual(2, finished['completed_files'])
        self.assertTrue(finished['coverage_complete'])
        self.assertIsNone(self.jobs._take())
        self.assertEqual(['principal-7-feature'] * 4, self.seen_scopes)

    def test_repeat_request_reconciles_without_restarting_completed_work(self):
        first = self.enqueue()['index_job']
        self.step()
        self.step()
        repeated = self.enqueue()['index_job']
        self.assertEqual(first['id'], repeated['id'])
        self.assertEqual(first['generation'], repeated['generation'])
        self.assertEqual('COMPLETED', repeated['state'])
        self.assertEqual(2, self.batches)

    def test_concurrent_observers_keep_both_request_acknowledgements(self):
        one = self.enqueue()['index_job']
        two = self.enqueue(request_id='request-2')['index_job']
        self.assertEqual(one['id'], two['id'])
        self.assertEqual(['request-1', 'request-2'], two['accepted_request_ids'])
        self.assertEqual(1, two['generation'])
        with self.assertRaises(ValueError):
            self.enqueue(retry_failed=True)

    def test_expired_worker_lease_reconciles_saved_progress_after_restart(self):
        self.enqueue()
        old = self.jobs._take()
        self.batches = 1  # First batch committed; its response/checkpoint was lost.
        replacement = GraphIndexJobs(self.service, now=lambda: self.clock)
        self.assertIsNone(replacement._take())
        self.clock += 301
        recovered = replacement._take()
        replacement._step(recovered)
        self.assertEqual('COMPLETED', replacement.status('principal', self.request)['state'])
        self.assertEqual(2, self.batches)
        self.jobs._finish(old, state='FAILED', error_code='STALE_WORKER')
        self.assertEqual('COMPLETED', replacement.status('principal', self.request)['state'])
        replacement.close()

    def test_cancel_fences_an_inflight_worker_and_preserves_result_identity(self):
        ack = self.enqueue()['index_job']
        old = self.jobs._take()
        cancelled = self.enqueue(job_action='cancel', job_id=ack['id'], job_generation=ack['generation'], request_id='cancel-1')['index_job']
        self.assertEqual('CANCELLED', cancelled['state'])
        self.jobs._step(old)
        self.assertEqual(0, self.batches)
        self.assertEqual('CANCELLED', self.jobs.status('principal', self.request)['state'])

    def test_cannot_cancel_another_principal_revision_or_job(self):
        ack = self.enqueue()['index_job']
        self.assertIsNone(self.jobs.status('other-principal', self.request))
        with self.assertRaises(ValueError):
            self.jobs.handle('other-principal', dict(self.request, job_action='cancel', job_id=ack['id']))
        with self.assertRaises(ValueError):
            self.enqueue(job_action='cancel', job_id='wrong')

    def test_stale_observer_cannot_cancel_a_new_index_generation(self):
        old = self.enqueue()['index_job']
        self.step()
        self.step()
        new = self.enqueue(request_id='retry-1', retry_failed=True)['index_job']
        self.assertGreater(new['generation'], old['generation'])
        with self.assertRaisesRegex(ValueError, 'generation changed'):
            self.enqueue(job_action='cancel', job_id=old['id'], job_generation=old['generation'], request_id='old-cancel')
        self.assertEqual('QUEUED', self.jobs.status('principal', self.request)['state'])
        with self.assertRaisesRegex(ValueError, 'observed index job generation'):
            self.enqueue(job_action='cancel', job_id=new['id'], request_id='missing-generation')

    def test_resume_refreshes_exhausted_batch_budget_and_reconciles_repeated_request(self):
        from v14.code_graph.index_jobs import MAX_BATCHES
        self.enqueue()
        old = self.jobs._take()
        self.jobs._finish(old, state='QUEUED', batches=MAX_BATCHES)
        self.assertIsNone(self.jobs._take())
        paused = self.jobs.status('principal', self.request)
        self.assertEqual('INDEX_BUDGET_REACHED', paused['error_code'])
        request = dict(job_action='resume', job_id=paused['id'], job_generation=paused['generation'], request_id='resume-1')
        resumed = self.enqueue(**request)['index_job']
        self.assertEqual(paused['generation'] + 1, resumed['generation'])
        self.assertEqual(0, resumed['batches'])
        self.assertEqual(resumed, self.enqueue(**request)['index_job'])
        self.assertEqual('QUEUED', self.step()['state'])

    def test_git_account_change_or_principal_revocation_stops_new_batches(self):
        self.enqueue()
        job = self.jobs._take()
        self.actor = 8
        with self.assertRaises(PermissionError):
            self.jobs._step(job)
        self.assertEqual(0, self.batches)
        self.actor = 7
        self.authorized = False
        with self.assertRaises(PermissionError):
            self.jobs._step(job)
        self.assertEqual(0, self.batches)

    def test_transient_failure_is_bounded_and_records_no_exception_body(self):
        self.enqueue()
        for attempt in range(6):
            job = self.jobs._take()
            self.jobs._failure(job, OSError('private source and credential must never appear'))
            self.clock += 31
        result = self.jobs.status('principal', self.request)
        self.assertEqual('FAILED', result['state'])
        self.assertEqual('INDEX_CONNECTION_UNAVAILABLE', result['error_code'])
        self.assertNotIn('private source', json.dumps(result))
        self.assertIsNone(self.jobs._take())

    def test_missing_encryption_key_does_not_create_a_replacement_over_saved_jobs(self):
        self.enqueue()
        key = self.database.with_suffix('.engineering.key')
        key.unlink()
        with self.assertRaisesRegex(ValueError, 'key is missing'):
            self.storage.tx(lambda db: EngineeringCipher.encrypt(db, 'other-record', 'value'))
        self.assertFalse(key.exists())
        # Remove unreachable encrypted rows so teardown can complete without
        # pretending that the original key or job content was recovered.
        self.storage.tx(lambda db: db.execute('DELETE FROM code_graph_index_jobs'))

    def test_explicit_retry_creates_new_generation_and_no_reset_replay(self):
        self.enqueue()
        self.step()
        self.step()
        retry = self.enqueue(request_id='retry-1', retry_failed=True)['index_job']
        self.assertEqual(2, retry['generation'])
        job = self.jobs._take()
        with patch.object(self.service, 'execute', side_effect=[self.execute('principal', dict(self.request, action='status'), expected_scope='principal-7-feature'), OSError('connection lost')]):
            with self.assertRaises(OSError):
                self.jobs._step(job)
        retained = self.jobs._transaction(lambda db: self.jobs._read(db, job['id']))
        self.assertFalse(retained['retry_pending'])
        self.assertTrue(retained['retry_outcome_unknown'])
        self.jobs._failure(job, OSError('connection lost'))
        self.clock += 31
        unknown = self.step()
        self.assertEqual('FAILED', unknown['state'])
        self.assertEqual('INDEX_RETRY_OUTCOME_UNKNOWN', unknown['error_code'])
        self.assertFalse(unknown['processing_complete'])
        self.assertEqual(2, self.batches)

    def test_existing_worker_closes_without_initializing_disabled_or_unavailable_storage(self):
        self.enqueue()
        for flag in ('GATEWAY_DURABLE_JOBS', 'durable_job_initialized'):
            with self.subTest(flag=flag), patch.object(self.storage.runtime, flag, False), \
                    patch.object(self.storage, 'tx', side_effect=AssertionError('Disabled storage must not be opened')):
                self.jobs.close()
                self.assertTrue(self.jobs.stop.is_set())

    def test_maintenance_drain_blocks_new_admission_and_worker_claims(self):
        runtime = self.storage.runtime
        for target, flag in ((runtime, 'gateway_draining'), (runtime.gateway_service, 'draining')):
            with self.subTest(flag=flag), patch.object(target, flag, True), \
                    patch.object(self.service, 'authorize', side_effect=AssertionError('must not read after drain')):
                with self.assertRaises(TimeoutError):
                    self.enqueue()
        self.enqueue()
        with runtime.gateway_service.lock:
            runtime.gateway_service.draining = True
        self.assertIsNone(self.jobs._take())
        self.assertEqual('QUEUED', self.jobs.status('principal', self.request)['state'])
        self.assertEqual({'activeIndexBatches': 0}, self.jobs.snapshot())
        runtime.gateway_service.draining = False
        self.assertIsNotNone(self.jobs._take())

    def test_drain_during_authorization_rejects_before_persisting_admission(self):
        def authorize(*args):
            result = self.authorize(*args)
            with self.storage.runtime.gateway_service.lock:
                self.storage.runtime.gateway_service.draining = True
            return result
        with patch.object(self.service, 'authorize', side_effect=authorize):
            with self.assertRaises(TimeoutError):
                self.enqueue()
        count = self.jobs._transaction(lambda db: db.execute('SELECT COUNT(*) FROM code_graph_index_jobs').fetchone()[0])
        self.assertEqual(0, count)
        self.jobs.start.assert_not_called()

    def test_cancelled_row_keeps_physical_batch_visible_until_close_settles(self):
        accepted = self.enqueue()['index_job']
        entered, release, closed = threading.Event(), threading.Event(), threading.Event()
        def slow_step(_job):
            entered.set()
            if not release.wait(3):
                raise AssertionError('test batch was not released')
        def close():
            self.jobs.close()
            closed.set()
        with patch.object(self.jobs, '_step', side_effect=slow_step):
            self.jobs.thread = threading.Thread(target=self.jobs._run, name='test-index-worker', daemon=True)
            self.jobs.thread.start()
            closer = None
            try:
                self.assertTrue(entered.wait(2))
                self.enqueue(job_action='cancel', job_id=accepted['id'], job_generation=accepted['generation'], request_id='cancel-active')
                self.assertEqual('CANCELLED', self.jobs.status('principal', self.request)['state'])
                self.assertEqual({'activeIndexBatches': 1}, self.jobs.snapshot())
                closer = threading.Thread(target=close, daemon=True)
                closer.start()
                self.assertFalse(closed.wait(.05))
                self.assertEqual({'activeIndexBatches': 1}, self.jobs.snapshot())
            finally:
                release.set()
                self.jobs.thread.join(2)
                if closer is not None:
                    closer.join(2)
            self.assertTrue(closed.is_set())
            self.assertFalse(self.jobs.thread.is_alive())
            self.assertEqual({'activeIndexBatches': 0}, self.jobs.snapshot())


class GraphIndexIntegrationTests(unittest.TestCase):
    def setUp(self):
        from test_gateway_v14_code_graph import GraphServiceTests
        self.fixture = GraphServiceTests()
        self.fixture.setUp()
        self.service = self.fixture.service
        self.jobs = self.service.index_jobs
        self.started = patch.object(self.jobs, 'start')
        self.started.start()

    def tearDown(self):
        self.started.stop()
        self.jobs.close()
        self.fixture.tearDown()

    def enqueue(self):
        return self.fixture.call('index', background=True, request_id='integration-start')

    def finish(self):
        for _ in range(4):
            claimed = self.jobs._take()
            if claimed is None:
                break
            self.jobs._step(claimed)
        return self.fixture.call('status')

    def test_real_service_continues_multiple_source_batches_and_publishes_final_coverage(self):
        self.fixture.files = {f'module_{i}.py': f'def symbol_{i}(): return {i}\n' for i in range(30)}
        acknowledged = self.enqueue()
        self.assertEqual('INDEX_JOB_ACCEPTED', acknowledged['status'])
        self.assertEqual(0, self.fixture.blob_reads)
        self.assertEqual('QUEUED', self.fixture.call('status')['index_job']['state'])
        completed = self.finish()
        self.assertFalse(completed['has_more'])
        self.assertEqual('COMPLETED', completed['index_job']['state'])
        self.assertEqual(2, completed['index_job']['batches'])
        self.assertEqual(30, completed['index_job']['completed_files'])
        self.assertTrue(completed['index_job']['processing_complete'])
        self.assertTrue(completed['index_job']['coverage_complete'])
        self.assertEqual('FOUND', self.fixture.call('query', query='symbol_29')['status'])
        self.assertEqual(30, self.fixture.blob_reads)

    def test_real_source_failure_and_unsupported_file_remain_visible_after_processing(self):
        self.fixture.files = {'broken.py': 'def broken(', 'huge.py': '#' * (2 * 1024 * 1024 + 1)}
        self.enqueue()
        completed = self.finish()
        self.assertEqual('COMPLETED', completed['index_job']['state'])
        self.assertTrue(completed['index_job']['processing_complete'])
        self.assertFalse(completed['index_job']['coverage_complete'])
        self.assertEqual(1, completed['index_job']['failed_files'])
        self.assertEqual(1, completed['index_job']['unsupported_files'])
        self.assertEqual(1, self.fixture.blob_reads)

    def test_fresh_request_rebuilds_an_evicted_snapshot_after_historical_completion(self):
        self.enqueue()
        completed = self.finish()
        reads = self.fixture.blob_reads
        self.fixture.storage.tx(lambda db: db.execute('DELETE FROM code_graph_snapshots'))
        self.assertEqual('NOT_INDEXED', self.fixture.call('status')['status'])
        accepted = self.fixture.call('index', background=True, request_id='rebuild-evicted-snapshot')
        self.assertGreater(accepted['index_job']['generation'], completed['index_job']['generation'])
        self.assertEqual('COMPLETED', self.finish()['index_job']['state'])
        self.assertGreater(self.fixture.blob_reads, reads)
        self.assertEqual('FOUND', self.fixture.call('query', query='private_function')['status'])

    def test_cancel_during_source_read_prevents_snapshot_publish_and_restores_thread_hook(self):
        accepted = self.enqueue()['index_job']
        claimed = self.jobs._take()
        original_api = self.fixture.api
        previous = lambda: False
        self.service._cancellation.check = previous
        def cancel_during_read(path):
            if '/git/blobs/' in path:
                self.jobs.handle('principal', dict(self.fixture.request, background=True, job_action='cancel',
                    job_id=accepted['id'], job_generation=accepted['generation'], request_id='cancel-during-read'))
            return original_api(path)
        with patch.object(self.service.backend, 'api', side_effect=cancel_during_read):
            with self.assertRaises(InterruptedError):
                self.jobs._step(claimed)
        self.assertIs(previous, self.service._cancellation.check)
        status = self.fixture.call('status')
        self.assertEqual('NOT_INDEXED', status['status'])
        self.assertEqual('CANCELLED', status['index_job']['state'])

    def test_lost_retry_reply_reconciles_observed_processing_instead_of_repeating_reset(self):
        self.fixture.files = {'broken.py': 'def broken('}
        self.enqueue()
        self.finish()
        self.fixture.call('index', background=True, retry_failed=True, request_id='retry-broken-source')
        claimed = self.jobs._take()
        execute = self.service.execute
        def lose_index_reply(principal, request, **kwargs):
            result = execute(principal, request, **kwargs)
            if request['action'] == 'index':
                raise OSError('reply lost after source checkpoint')
            return result
        with patch.object(self.service, 'execute', side_effect=lose_index_reply):
            with self.assertRaises(OSError):
                self.jobs._step(claimed)
        self.jobs._finish(claimed, state='QUEUED', next_at=0)
        finished = self.finish()
        self.assertEqual('COMPLETED', finished['index_job']['state'])
        self.assertFalse(finished['index_job']['coverage_complete'])
        self.assertEqual(1, finished['index_job']['failed_files'])
        self.assertEqual(2, self.fixture.blob_reads)

    def test_public_map_job_admission_rechecks_live_tools_scope_and_device_revocation(self):
        from v14.config import Config
        from v14.identity import principal_can_graph
        devices = SimpleNamespace(list_devices=lambda: [('paired', 'phone', False), ('revoked', 'old', True)])
        config = Config(legacy_bearer=True)
        self.assertTrue(principal_can_graph(config, 'paired', devices))
        self.assertFalse(principal_can_graph(config, 'revoked', devices))
        self.assertFalse(principal_can_graph(config, 'unowned', devices))
        config = Config(mode='trusted_proxy', trusted_users={'owner@example.com': {'principalId': 'owner', 'scopes': ['tools']}})
        self.assertTrue(principal_can_graph(config, 'tailnet:owner', devices))
        config.trusted_users['owner@example.com']['scopes'] = ['chat']
        self.assertFalse(principal_can_graph(config, 'tailnet:owner', devices))
        config = Config(mode='open_network', remote_engineering=False)
        self.assertTrue(principal_can_graph(config, 'network:shared', devices))
        self.assertFalse(principal_can_graph(config, 'local:single-user', devices))


if __name__ == '__main__':
    unittest.main()
