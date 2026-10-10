"""Focused durable action-ledger tests; intentionally independent of FastAPI."""
import sqlite3
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from contextvars import ContextVar
from unittest.mock import Mock

from v14.actions import (
    ActionConflict,
    ActionLedger,
    ActionReplay,
    ExternalOutcomeUnknown,
    IdempotencyConflict,
    current_gateway_job_id,
)


class ActionLedgerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.runtime = SimpleNamespace(
            GATEWAY_DURABLE_JOBS=True,
            durable_job_initialized=True,
            durable_job_lock=threading.RLock(),
            _durable_connect=lambda: sqlite3.connect(Path(self.temp.name) / "journal.db", timeout=5),
        )
        self.ledger = ActionLedger(self.runtime)
        self.binding = {"server": "inventory", "tool": "lookup", "schemaDigest": "abc"}

    def tearDown(self):
        self.temp.cleanup()

    def test_committed_result_is_replayed(self):
        self.assertIsNone(self.ledger.begin("owner", "job", "call", self.binding, {"sku": "A"}))
        result = {"content": [{"type": "text", "text": "found"}]}
        self.assertEqual(self.ledger.finish("owner", "job", "call", result), result)
        replay = self.ledger.begin("owner", "job", "call", self.binding, {"sku": "A"})
        self.assertIsInstance(replay, ActionReplay)
        self.assertEqual(replay.result, result)

    def test_rebinding_or_changed_arguments_conflict(self):
        self.ledger.begin("owner", "job", "call", self.binding, {"sku": "A"})
        self.ledger.finish("owner", "job", "call", {"ok": True})
        with self.assertRaises(ActionConflict):
            self.ledger.begin("owner", "job", "call", self.binding, {"sku": "B"})
        with self.assertRaises(ActionConflict):
            self.ledger.begin("owner", "job", "call", {**self.binding, "server": "other"}, {"sku": "A"})

    def test_uncertain_dispatch_state_blocks_replay(self):
        self.ledger.begin("owner", "job", "call", self.binding, {"sku": "A"})
        self.ledger.uncertain("owner", "job", "call")
        with self.assertRaises(ExternalOutcomeUnknown):
            self.ledger.begin("owner", "job", "call", self.binding, {"sku": "A"})

    def test_result_commit_failure_after_external_success_stays_unknown(self):
        base_connect = self.runtime._durable_connect
        calls = 0
        def fail_result_write():
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("simulated disk failure after external success")
            return base_connect()

        self.runtime._durable_connect = fail_result_write
        self.ledger.begin("owner", "job", "call", self.binding, {"sku": "A"})
        with self.assertRaises(ExternalOutcomeUnknown):
            self.ledger.finish("owner", "job", "call", {"ok": True})
        self.runtime._durable_connect = base_connect
        with self.assertRaises(ExternalOutcomeUnknown):
            self.ledger.begin("owner", "job", "call", self.binding, {"sku": "A"})

    def test_concurrent_admission_reserves_one_action(self):
        barrier = threading.Barrier(8)
        outcomes = []
        lock = threading.Lock()

        def reserve():
            barrier.wait()
            try:
                result = self.ledger.begin("owner", "job", "call", self.binding, {"sku": "A"})
                outcome = "reserved" if result is None else "replayed"
            except ExternalOutcomeUnknown:
                outcome = "blocked"
            with lock:
                outcomes.append(outcome)

        threads = [threading.Thread(target=reserve) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(outcomes.count("reserved"), 1)
        self.assertEqual(outcomes.count("blocked"), 7)

    def test_request_idempotency_is_principal_scoped_and_content_bound(self):
        first = self.ledger.admit_request("owner", "POST /chat", "idem-1", {"messages": ["hello"]}, "job-1")
        replay = self.ledger.admit_request("owner", "POST /chat", "idem-1", {"messages": ["hello"]}, "job-2")
        isolated = self.ledger.admit_request("other", "POST /chat", "idem-1", {"messages": ["hello"]}, "job-3")
        self.assertEqual(first, ("job-1", False))
        self.assertEqual(replay, ("job-1", True))
        self.assertEqual(isolated, ("job-3", False))
        with self.assertRaises(IdempotencyConflict):
            self.ledger.admit_request("owner", "POST /chat", "idem-1", {"messages": ["changed"]}, "job-4")

    def test_concurrent_identical_request_keys_resolve_one_run(self):
        barrier = threading.Barrier(8)
        rows = []
        lock = threading.Lock()
        def admit(index):
            barrier.wait()
            result = self.ledger.admit_request("owner", "POST /chat", "idem-concurrent", {"x": 1}, f"job-{index}")
            with lock:
                rows.append(result)
        threads = [threading.Thread(target=admit, args=(index,)) for index in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(len({job_id for job_id, _ in rows}), 1)
        self.assertEqual(sum(not replay for _, replay in rows), 1)

    def test_expired_request_key_is_not_silently_reused(self):
        from unittest.mock import patch
        with patch("v14.actions.time.time", return_value=1000.0):
            self.ledger.admit_request("owner", "POST /chat", "idem-exp", {"x": 1}, "job-1", ttl_seconds=1)
        with patch("v14.actions.time.time", return_value=1002.0):
            from v14.actions import IdempotencyExpired
            with self.assertRaises(IdempotencyExpired):
                self.ledger.admit_request("owner", "POST /chat", "idem-exp", {"x": 1}, "job-2", ttl_seconds=1)

    def test_exact_dispatch_replays_known_result_and_blocks_unknown_write(self):
        try:
            from gateway_security import current_device
        except ModuleNotFoundError as exc:
            if exc.name != "fastapi":
                raise
            import types
            security = types.ModuleType("gateway_security")
            security.current_device = ContextVar("test_gateway_device", default=None)
            sys.modules["gateway_security"] = security
            current_device = security.current_device

        from v14.tools import install_exact_routing

        definition = {"function": {"name": "inventory_lookup", "parameters": {
            "type": "object", "properties": {"sku": {"type": "string"}}, "required": ["sku"]
        }}}
        runtime = SimpleNamespace(
            get_all_mcp_tools=lambda force_refresh=False: [definition],
            load_mcp_config=lambda: {"inventory": {}},
            mcp_tool_metadata={"inventory_lookup": {"server": "inventory", "tool": "lookup"}},
            mcp_cache_lock=threading.RLock(), mcp_tools_cache=[definition],
            mcp_call=Mock(return_value={"ok": True}),
            GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True,
            durable_job_lock=threading.RLock(),
            _durable_connect=lambda: sqlite3.connect(Path(self.temp.name) / "dispatch.db", timeout=5),
        )
        install_exact_routing(runtime)
        token = current_device.set("owner")
        job_token = current_gateway_job_id.set("job-1")
        try:
            first = runtime._execute_mcp_tool_single("inventory_lookup", {"sku": "A"}, call_id="stable")
            second = runtime._execute_mcp_tool_single("inventory_lookup", {"sku": "A"}, call_id="stable")
            third = runtime._execute_mcp_tool_single("inventory_lookup", {"sku": "A"}, call_id="fresh-provider-id")
            self.assertEqual(first, second)
            self.assertEqual(first, third)
            runtime.mcp_call.assert_called_once()

            runtime.mcp_call.reset_mock(side_effect=True, return_value=True)
            runtime.mcp_call.side_effect = TimeoutError("response lost")
            with self.assertRaises(TimeoutError):
                runtime._execute_mcp_tool_single("inventory_lookup", {"sku": "B"}, call_id="uncertain")
            with self.assertRaises(ExternalOutcomeUnknown):
                runtime._execute_mcp_tool_single("inventory_lookup", {"sku": "B"}, call_id="fresh-provider-id")
            with self.assertRaises(ExternalOutcomeUnknown):
                runtime._execute_mcp_tool_single("inventory_lookup", {"sku": "C"}, call_id="different-action")
            runtime.mcp_call.assert_called_once()
        finally:
            current_gateway_job_id.reset(job_token)
            current_device.reset(token)


if __name__ == "__main__":
    unittest.main()
