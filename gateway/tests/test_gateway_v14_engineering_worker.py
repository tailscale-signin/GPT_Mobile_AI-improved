"""Isolated real-worker recovery tests; original assertions and deadlines retained."""
import threading
import unittest
import test_gateway_v14_engineering as fixtures


class DurableEngineeringWorkerTests(unittest.TestCase):
    setUp = fixtures.DurableEngineeringTests.setUp
    tearDown = fixtures.DurableEngineeringTests.tearDown

    def test_worker_recovers_failed_checkpoint_without_a_client_or_repeated_commit(self):
        self.assert_autonomous_checkpoint_recovery()

    def test_worker_retries_failed_lease_recovery_before_dispatching_again(self):
        self.assert_autonomous_checkpoint_recovery(recovery_failures=2)

    def assert_autonomous_checkpoint_recovery(self, recovery_failures=0):
        job = self.store.create('owner', self.request)
        complete = self.store.complete
        recover = self.store.recover
        failed = False
        recovery_attempts = 0
        failed_recovery_calls = []
        ready = threading.Event()
        def save(current, lease, phase, output, delay=0):
            nonlocal failed
            if current['state'] == 'COMMIT' and not failed:
                failed = True
                raise OSError('simulated interrupted journal write')
            persisted = complete(current, lease, phase, output, delay)
            if persisted and phase == 'MERGE_READY':
                ready.set()
            return persisted
        def recover_leases():
            nonlocal recovery_attempts
            if failed:
                recovery_attempts += 1
                if recovery_attempts <= recovery_failures:
                    failed_recovery_calls.append(tuple(self.backend.calls))
                    raise OSError('simulated journal unavailable during lease recovery')
            return recover()
        self.store.complete = save
        self.store.recover = recover_leases
        self.backend.recovered = {'head_sha': 'a' * 40}
        self.service.start()
        try:
            # Let the real worker recover without client wakeups or contending
            # SQLite reads. Signal only after the ready checkpoint is durable;
            # allow its normal stage waits and slower Windows filesystem I/O.
            reached_ready = ready.wait(45)
        finally:
            self.service.stop.set()
            self.service.wake.set()
            self.service.thread.join(10)
            self.assertFalse(self.service.thread.is_alive(), 'Engineering worker did not stop after the recovery test')
        current = self.store.get(job['id'])
        diagnostics = (f"state={current['state']}, revision={current['revision']}, injected_failure={failed}, "
                       f"recovery_attempts={recovery_attempts}, calls={self.backend.calls}")
        self.assertTrue(reached_ready, 'Autonomous checkpoint recovery timed out: ' + diagnostics)
        self.assertTrue(failed, 'The interrupted checkpoint was not exercised: ' + diagnostics)
        self.assertEqual('MERGE_READY', current['state'], diagnostics)
        self.assertEqual(1, self.backend.calls.count('COMMIT'), diagnostics)
        self.assertEqual(1, self.backend.calls.count('reconcile:COMMIT'), diagnostics)
        self.assertEqual(recovery_failures + 1, recovery_attempts, diagnostics)
        if recovery_failures:
            self.assertEqual([failed_recovery_calls[0]] * recovery_failures, failed_recovery_calls, diagnostics)
            self.assertNotIn('reconcile:COMMIT', failed_recovery_calls[0], diagnostics)
