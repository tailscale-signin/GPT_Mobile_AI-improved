"""One bounded monotonic deadline owns gateway queue and execution time."""
import sys
import threading
import types
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.deadlines import Deadline, DeadlineExceeded, request_deadline
from v14.errors import FAILURE_CODES, GatewayFailure


class DeadlineTests(unittest.TestCase):
    def test_deadline_uses_monotonic_remaining_time(self):
        now = [100.0]
        deadline = Deadline.after(2, clock=lambda: now[0])
        now[0] = 101.25
        self.assertAlmostEqual(deadline.remaining(), .75)
        now[0] = 102.0
        self.assertTrue(deadline.expired())
        with self.assertRaises(DeadlineExceeded):
            deadline.require_time()

    def test_request_deadline_is_bounded_and_validated(self):
        deadline = request_deadline({"gateway": {"schema": "gptmobile.gateway-run.v1",
                                                  "budget": {"totalDeadlineMs": 5000}}})
        self.assertGreater(deadline.remaining(), 4.9)
        with self.assertRaises(ValueError):
            request_deadline({"gateway": {"budget": {"totalDeadlineMs": 900001}}})
        with self.assertRaises(ValueError):
            request_deadline({"gateway": {"budget": {"totalDeadlineMs": True}}})
        with self.assertRaises(ValueError):
            request_deadline({"gateway": {"profileId": "client-controlled"}})
        self.assertGreater(request_deadline({}).remaining(), 899)

    def test_deadline_watchdog_cancels_active_processing(self):
        from types import SimpleNamespace

        # ServiceState itself only needs FastAPI's response helper at import
        # time. Keep the unit test usable in the lightweight gateway test env.
        try:
            import fastapi.responses  # noqa: F401
        except ModuleNotFoundError:
            fastapi = types.ModuleType("fastapi")
            responses = types.ModuleType("fastapi.responses")
            responses.JSONResponse = object
            fastapi.responses = responses
            sys.modules["fastapi"] = fastapi
            sys.modules["fastapi.responses"] = responses
        from v14.service import ServiceState

        cancelled, hard_cancelled = threading.Event(), threading.Event()
        runtime = SimpleNamespace(logger=SimpleNamespace(warning=lambda *_: None))
        def process(payload, cancel_event, progress_callback, hard_cancel_event):
            self.assertNotIn("gateway", payload)
            self.assertTrue(cancel_event.wait(1))
            self.assertTrue(hard_cancel_event.is_set())
            return 408, {"error": {"code": "deadline_exceeded"}}
        runtime.process_chat_payload = process
        service = ServiceState(max_workers=1)
        service.install(runtime)
        status, body = runtime.process_chat_payload(
            {"gateway": {"schema": "gptmobile.gateway-run.v1",
                          "budget": {"totalDeadlineMs": 100}}}, cancelled, None, hard_cancelled)
        self.assertEqual(status, 408)
        self.assertEqual(body["error"]["code"], "deadline_exceeded")
        self.assertEqual(service.snapshot()["activeWorkers"], 0)


class TypedFailureTests(unittest.TestCase):
    def test_error_taxonomy_is_stable_and_sanitized(self):
        self.assertTrue({"permission_denied", "quota_exceeded", "protocol_error",
                         "persistence_failed", "external_outcome_unknown"} <= FAILURE_CODES)
        failure = GatewayFailure("quota_exceeded", "Provider quota is exhausted", 429, True,
                                 {"retryAfterSeconds": 30})
        self.assertEqual(failure.payload("trace-1"), {
            "error": {"code": "quota_exceeded", "message": "Provider quota is exhausted",
                      "retryable": True, "details": {"retryAfterSeconds": 30}},
            "traceId": "trace-1"})
        with self.assertRaises(ValueError):
            GatewayFailure("raw_provider_error", "Do not leak provider detail")


if __name__ == "__main__":
    unittest.main()
