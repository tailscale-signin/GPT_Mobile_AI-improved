"""Execute the real fingerprint function without loading a server or model."""
import ast
import copy
import hashlib
import json
import unittest
from pathlib import Path
from contextvars import ContextVar

SOURCE = Path(__file__).resolve().parents[1] / "gateway_v12.py"
scope = {"copy": copy, "hashlib": hashlib, "json": json,
         "current_device": ContextVar("device", default="test-device")}
tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
exec(compile(ast.Module(body=[node for node in tree.body
                             if isinstance(node, ast.FunctionDef)
                             and node.name == "canonical_chat_request_fingerprint"],
                        type_ignores=[]), str(SOURCE), "exec"), scope)
fingerprint = scope["canonical_chat_request_fingerprint"]


class SingleflightAttemptTests(unittest.TestCase):
    def setUp(self):
        self.payload = {"model": "reasoner", "messages": [{"role": "user", "content": "Task"}],
                        "max_tokens": 768, "stream": True}

    def test_five_retries_have_distinct_inference_identity(self):
        keys = [fingerprint(self.payload, f"attempt-{i}") for i in range(6)]
        self.assertEqual(6, len(set(keys)))
        self.assertEqual(keys[0], fingerprint(self.payload, "attempt-0"))

    def test_cap_repair_cannot_replay_smaller_completion(self):
        self.assertNotEqual(fingerprint(self.payload),
                            fingerprint(dict(self.payload, max_tokens=4096)))

    def test_tool_isolation_and_reasoning_settings_are_not_equivalent(self):
        self.assertNotEqual(fingerprint(self.payload), fingerprint(dict(self.payload, reasoning_effort="none")))
        worker = dict(self.payload, _gateway_performance={"delegated_worker": True, "allow_gateway_local_tools": True})
        reviewer = dict(self.payload, _gateway_performance={"delegated_worker": True, "allow_gateway_local_tools": False})
        self.assertNotEqual(fingerprint(worker), fingerprint(reviewer))

    def test_transport_reconnect_still_coalesces_same_attempt(self):
        reconnected = dict(self.payload, stream=False, gateway_job_id="resumed", stream_options={"include_usage": True})
        self.assertEqual(fingerprint(self.payload, "attempt-0"), fingerprint(reconnected, "attempt-0"))
        self.assertIn("stream", self.payload)


if __name__ == "__main__":
    unittest.main()
