"""A detached benchmark must still protect the installer maintenance boundary."""
import sys
import sqlite3
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.maintenance import prepare, pending_work
from v14.service import ServiceState


class BenchmarkMaintenanceTests(unittest.TestCase):
    def test_queued_and_running_benchmarks_block_upgrade_without_cancellation(self):
        for active, queued in ((1, 0), (0, 1), (1, 2)):
            with self.subTest(active=active, queued=queued):
                state = ServiceState()
                benchmarks = Mock()
                benchmarks.snapshot.return_value = {'activeBenchmarks': active, 'queuedBenchmarks': queued}
                runtime = SimpleNamespace(gateway_draining=False, gateway_benchmarks=benchmarks)
                with patch('v14.maintenance.pending_work', return_value=0):
                    self.assertFalse(prepare(runtime, state, None))
                self.assertFalse(state.draining)
                self.assertFalse(runtime.gateway_draining)
                benchmarks.begin_drain.assert_not_called()

    def test_finished_benchmarks_allow_existing_atomic_maintenance_fence(self):
        state = ServiceState()
        benchmarks = Mock()
        benchmarks.snapshot.return_value = {'activeBenchmarks': 0, 'queuedBenchmarks': 0}
        runtime = SimpleNamespace(gateway_draining=False, gateway_benchmarks=benchmarks)
        with patch('v14.maintenance.pending_work', return_value=0):
            self.assertTrue(prepare(runtime, state, None))
        self.assertTrue(state.draining)
        self.assertTrue(runtime.gateway_draining)
        benchmarks.begin_drain.assert_not_called()

    def test_detached_resumable_index_jobs_block_upgrade_until_terminal(self):
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE gateway_jobs(status TEXT)')
            db.execute('CREATE TABLE engineering_jobs(state TEXT)')
            engineering = SimpleNamespace(store=SimpleNamespace(tx=lambda action: action(db)))
            runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, gateway_draining=False)
            self.assertEqual(0, pending_work(runtime, engineering))
            db.execute('CREATE TABLE code_graph_index_jobs(state TEXT)')
            for status in ('QUEUED', 'RUNNING', 'INTERRUPTED', 'PAUSED'):
                with self.subTest(status=status):
                    db.execute('DELETE FROM code_graph_index_jobs')
                    db.execute('INSERT INTO code_graph_index_jobs VALUES(?)', (status,))
                    state = ServiceState()
                    self.assertFalse(prepare(runtime, state, engineering))
                    self.assertFalse(state.draining)
                    self.assertFalse(runtime.gateway_draining)
            for status in ('COMPLETED', 'CANCELLED', 'FAILED'):
                with self.subTest(status=status):
                    db.execute('DELETE FROM code_graph_index_jobs')
                    db.execute('INSERT INTO code_graph_index_jobs VALUES(?)', (status,))
                    self.assertEqual(0, pending_work(runtime, engineering))

    def test_cancelled_index_batch_must_settle_before_upgrade(self):
        for name in ('graph', 'public_graph'):
            with self.subTest(owner=name):
                worker = SimpleNamespace(snapshot=lambda: {'activeIndexBatches': 1})
                engineering = SimpleNamespace(**{name: SimpleNamespace(_index_jobs=worker)})
                runtime = SimpleNamespace(gateway_draining=False)
                state = ServiceState()
                with patch('v14.maintenance.pending_work', return_value=0):
                    self.assertFalse(prepare(runtime, state, engineering))
                    self.assertFalse(state.draining)
                    worker.snapshot = lambda: {'activeIndexBatches': 0}
                    self.assertTrue(prepare(runtime, state, engineering))


if __name__ == '__main__':
    unittest.main()
