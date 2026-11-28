"""Failure injection for maintained branches and immutable index delta transfer."""
import base64
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.code_graph.index_jobs import GraphIndexJobs
from v14.code_graph.maintenance import GraphMaintenance
from v14.code_graph.sync import generation
from v14.github_api_governor import GitHubApiRateWait
from test_gateway_v14_code_graph import GraphServiceTests


class RepositoryIndexSyncTests(unittest.TestCase):
    api = GraphServiceTests.api
    call = GraphServiceTests.call

    def setUp(self):
        GraphServiceTests.setUp(self)
        self.clock = 1000.0
        self.service._maintenance = GraphMaintenance(self.service, now=lambda: self.clock)
        self.jobs = self.service.index_jobs
        self.jobs.now = lambda: self.clock
        self.patcher = patch.object(GraphIndexJobs, 'start')
        self.patcher.start()

    def tearDown(self):
        self.patcher.stop()
        self.jobs.close()
        GraphServiceTests.tearDown(self)

    def namespace(self):
        return self.service.authorize('principal', self.request)[0]

    def prepare(self):
        return self.call('index')

    def test_branch_advance_indexes_only_changed_blobs_without_rebinding_old_snapshot(self):
        self.prepare()
        original_reads = self.blob_reads
        original = self.head
        self.head = 'b' * 40
        self.files['b.py'] += '\ndef another(): return 2\n'
        self.assertTrue(self.service.maintenance.tick())
        queued = self.jobs._take()
        self.assertEqual(self.head, queued['request']['commit_sha'])
        self.jobs._step(queued)
        self.assertEqual(original_reads + 1, self.blob_reads)
        self.assertEqual(original, self.service.store.load(self.namespace(), original)['sha'])
        self.assertEqual(self.head, self.service.store.load(self.namespace(), self.head)['sha'])

    def test_unchanged_poll_reuses_snapshot_and_respects_active_interval(self):
        self.prepare()
        reads = self.blob_reads
        self.assertTrue(self.service.maintenance.tick())
        self.assertIsNone(self.jobs._take())
        self.clock += 89
        self.assertFalse(self.service.maintenance.tick())
        self.clock += 1
        self.assertTrue(self.service.maintenance.tick())
        self.assertEqual(reads, self.blob_reads)
        self.assertEqual('UP_TO_DATE', self.service.maintenance.status(self.namespace())['state'])

    def test_restart_restores_encrypted_watch_and_keeps_inactive_polling_bounded(self):
        self.prepare()
        self.assertNotIn(b'owner/repo', self.database.read_bytes())
        self.clock += 901
        restored = GraphMaintenance(self.service, now=lambda: self.clock)
        self.assertTrue(restored.tick())
        self.assertEqual(self.clock + 1800, restored.status(self.namespace())['next_at'])

    def test_account_switch_cannot_reuse_prior_account_watch(self):
        self.prepare()
        namespace = self.namespace()
        self.actor = 9
        self.service.maintenance.tick()
        self.assertEqual('ACCESS_REVOKED', self.service.maintenance.status(namespace)['state'])
        self.assertFalse(self.service.maintenance.status(namespace)['enabled'])
        self.assertIsNone(self.jobs._take())

    def test_revoked_principal_never_reads_or_queues_source(self):
        self.prepare()
        namespace = self.namespace()
        self.service.authorized = lambda principal: False
        with patch.object(self.service, 'authorize', side_effect=AssertionError('No remote read after revocation')):
            self.assertTrue(self.service.maintenance.tick())
        self.assertEqual('ACCESS_REVOKED', self.service.maintenance.status(namespace)['state'])

    def test_rate_limit_wait_survives_restart_and_interactive_observation(self):
        self.prepare()
        namespace = self.namespace()
        with patch.object(self.service, 'authorize', side_effect=GitHubApiRateWait(3600)):
            self.service.maintenance.tick()
        self.call('status')
        restored = GraphMaintenance(self.service, now=lambda: self.clock)
        self.assertEqual(self.clock + 3600, restored.status(namespace)['next_at'])
        self.clock += 90
        self.assertFalse(restored.tick())

    def test_explicit_pause_is_not_undone_by_status_or_maintenance(self):
        self.prepare()
        self.call('status', auto_refresh=False)
        self.call('status')
        self.clock += 3600
        self.assertFalse(self.service.maintenance.tick())
        self.assertEqual('PAUSED', self.service.maintenance.status(self.namespace())['state'])
        self.call('status', auto_refresh=True)
        self.assertTrue(self.service.maintenance.tick())

    def test_cancelled_index_is_not_restarted_by_branch_maintenance(self):
        self.prepare()
        self.head = 'c' * 40
        self.service.maintenance.tick()
        request = dict(self.request, commit_sha=self.head)
        job = self.jobs.status('principal', request)
        self.jobs.handle('principal', dict(request, job_action='cancel', job_id=job['id'],
                                          job_generation=job['generation'], request_id='cancel-current'))
        self.clock += 90
        self.service.maintenance.tick()
        self.assertEqual('CANCELLED', self.jobs.status('principal', request)['state'])
        self.assertIsNone(self.jobs._take())

    def test_completed_job_does_not_hide_an_evicted_snapshot(self):
        self.call('status')
        self.service.maintenance.tick()
        self.jobs._step(self.jobs._take())
        old = self.jobs.status('principal', self.request)
        self.assertEqual('COMPLETED', old['state'])
        self.storage.tx(lambda db: db.execute('DELETE FROM code_graph_snapshots'))
        self.clock += 90
        self.service.maintenance.tick()
        current = self.jobs.status('principal', self.request)
        self.assertEqual('QUEUED', current['state'])
        self.assertEqual(old['generation'] + 1, current['generation'])
        self.jobs._step(self.jobs._take())
        self.assertIsNotNone(self.service.store.load(self.namespace(), self.head))

    def test_full_transfer_has_verifiable_compressed_records_and_manifest(self):
        self.prepare()
        result = self.call('sync')
        rows = {}
        for row in result['records']:
            payload = zlib.decompress(base64.b64decode(row['payload']))
            self.assertEqual(row['digest'], hashlib.sha256(payload).hexdigest())
            self.assertEqual(row['path'], json.loads(payload)['path'])
            rows[row['path']] = row
        self.assertEqual(result['generation'], generation(rows))
        self.assertEqual(2, result['record_count'])
        self.assertIsNone(result['next_offset'])
        self.assertFalse(result['delta'])

    def test_unchanged_delta_transfers_no_records(self):
        self.prepare()
        first = self.call('sync')
        result = self.call('sync', base_sha=self.head, base_generation=first['generation'])
        self.assertEqual([], result['records'])
        self.assertTrue(result['delta'])
        self.assertEqual(first['generation'], result['generation'])

    def test_incremental_delta_contains_only_changed_added_and_removed_paths(self):
        self.prepare()
        first = self.call('sync')
        self.files['c.py'] = 'def new_file(): return 3\n'
        self.files.pop('b.py')
        self.head = 'b' * 40
        self.request['commit_sha'] = self.head
        self.prepare()
        result = self.call('sync', base_sha='a' * 40, base_generation=first['generation'])
        self.assertEqual({'b.py', 'c.py'}, {row['path'] for row in result['records']})
        self.assertTrue(next(row for row in result['records'] if row['path'] == 'b.py')['deleted'])
        self.assertTrue(result['delta'])

    def test_wrong_base_falls_back_to_full_transfer_without_overwriting_other_scope(self):
        self.prepare()
        result = self.call('sync', base_sha=self.head, base_generation='0' * 64)
        self.assertFalse(result['delta'])
        other = self.service.execute('other-principal', dict(self.request, action='sync'))
        self.assertEqual('NOT_INDEXED', other['status'])
        self.assertEqual(2, len(self.service.store.load(self.namespace(), self.head)['fragments']))

    def test_generation_offset_and_transfer_binding_reject_stale_or_forged_resume(self):
        self.prepare()
        for extra in ({'sync_generation': '0' * 64}, {'sync_offset': 999}, {'transfer_id': 'foreign'}, {'sync_offset': True}):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                self.call('sync', **extra)

    def test_many_paths_resume_at_bounded_page_offset(self):
        self.files = {f'{index:03}.py': f'def f{index}(): return {index}\n' for index in range(140)}
        self.prepare()
        first = self.call('sync')
        self.assertEqual(100, len(first['records']))
        second = self.call('sync', sync_generation=first['generation'], transfer_id=first['transfer_id'], sync_offset=first['next_offset'])
        self.assertEqual(40, len(second['records']))
        self.assertIsNone(second['next_offset'])
        self.assertTrue({row['path'] for row in first['records']}.isdisjoint(row['path'] for row in second['records']))


if __name__ == '__main__':
    unittest.main()
