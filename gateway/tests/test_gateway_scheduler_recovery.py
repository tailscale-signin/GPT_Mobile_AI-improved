"""Presentation/receipt failures cannot retain a model slot before model dispatch."""
import threading
import unittest
from types import SimpleNamespace

from v14.inference_scheduler import bound_invocation, install_scheduler


class SchedulerRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.runtime = SimpleNamespace(
            _acquire_llama_model_gate=lambda *args, **kwargs: None,
            LLAMA_SLOT_COUNT=1,
            GATEWAY_QUALIFIED_LOCAL_SLOTS=1,
            LLAMA_MODEL_QUEUE_TIMEOUT_SECONDS=1,
            job_registry_lock=threading.RLock(),
            job_registry={}
        )
        self.scheduler = install_scheduler(self.runtime)

    def assert_next_request_can_use_slot(self):
        self.assertEqual(0, self.scheduler.snapshot()['active'])
        self.assertEqual(0, self.scheduler.snapshot()['queued'])
        self.runtime._acquire_llama_model_gate('interactive')
        self.assertEqual(1, self.scheduler.snapshot()['active'])
        self.runtime._release_llama_model_gate(None)
        self.assertEqual(0, self.scheduler.snapshot()['active'])

    def test_admitted_progress_failure_releases_slot_before_caller_receives_lease(self):
        original = ConnectionError('Synthetic progress consumer disconnected')
        events = []

        def progress(event):
            events.append(event['event'])
            if event['event'] == 'model_queue_admitted':
                raise original

        with self.assertRaises(ConnectionError) as failure:
            self.runtime._acquire_llama_model_gate('interactive', progress_callback=progress)
        self.assertIs(original, failure.exception)
        self.assertEqual(['model_queue_admitted'], events)
        self.assert_next_request_can_use_slot()

    def test_admission_receipt_failure_releases_slot_and_preserves_original_failure(self):
        original = OSError('Synthetic receipt store unavailable')

        def receipt(*args):
            raise original

        token = bound_invocation.set(SimpleNamespace(validate_queue=lambda: None, queue_receipt=receipt))
        try:
            with self.assertRaises(OSError) as failure:
                self.runtime._acquire_llama_model_gate('interactive')
            self.assertIs(original, failure.exception)
        finally:
            bound_invocation.reset(token)
        self.assert_next_request_can_use_slot()

    def test_queued_progress_failure_removes_only_its_own_waiter(self):
        first = self.scheduler.acquire('interactive')
        original = RuntimeError('Synthetic waiting progress failure')

        def progress(event):
            self.assertEqual('model_queue_wait', event['event'])
            raise original

        try:
            with self.assertRaises(RuntimeError) as failure:
                self.runtime._acquire_llama_model_gate('interactive', progress_callback=progress)
            self.assertIs(original, failure.exception)
            self.assertEqual(1, self.scheduler.snapshot()['active'])
            self.assertEqual(0, self.scheduler.snapshot()['queued'])
        finally:
            self.scheduler.release(first)
        self.assert_next_request_can_use_slot()


if __name__ == '__main__':
    unittest.main()
