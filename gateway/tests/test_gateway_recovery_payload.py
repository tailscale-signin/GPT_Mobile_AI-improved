"""Recovery checkpoints must outlive model-context clipping and reconnects."""
import ast
import json
import logging
import time
import unittest
from pathlib import Path


class GatewayRecoveryPayloadTests(unittest.TestCase):
    def setUp(self):
        self.tree = ast.parse((Path(__file__).resolve().parents[1] / "gateway_v13.py").read_text())
        names = {"emit_progress", "make_progress_sse", "sanitize_progress_value", "sanitized_progress_arguments"}
        module = ast.Module(body=[node for node in self.tree.body if isinstance(node, ast.FunctionDef) and node.name in names], type_ignores=[])
        self.scope = {
            "json": json, "time": time, "logger": logging.getLogger(__name__),
            "VISIBLE_PROGRESS_ENABLED": True, "STRUCTURED_GATEWAY_PROGRESS": True,
            "V13_LEGACY_PROGRESS_TEXT": False, "GATEWAY_PROGRESS_PROTOCOL": "v13",
            "PROGRESS_INCLUDE_SANITIZED_TOOL_ARGS": True,
            "enrich_progress_ui": lambda event: event,
            "v12_enrich_progress": lambda event: event,
        }
        exec(compile(module, "gateway-recovery", "exec"), self.scope)

    def test_full_research_reaches_client_without_becoming_chat_text(self):
        research = "Evidence 😀 https://example.org/source\n" * 10000
        events = []
        self.scope["emit_progress"](events.append, "tool_result", "Short summary", event="tool_completed", tool_result=research)
        stream = self.scope["make_progress_sse"]("model", "id", 1, events[0])
        chunk = json.loads(stream.removeprefix("data: ").strip())
        self.assertEqual(chunk["gateway_progress"]["tool_result"], research)
        self.assertEqual(chunk["choices"][0]["delta"], {})

    def test_complete_arguments_and_large_collections_are_kept_with_secrets_redacted(self):
        arguments = {"query": "large query" * 10000, "items": list(range(200)), "nested": {"authorization": "secret", "path": "research.txt"}}
        safe = self.scope["sanitized_progress_arguments"](arguments)
        self.assertEqual(safe["query"], arguments["query"])
        self.assertEqual(safe["items"], arguments["items"])
        self.assertEqual(safe["nested"], {"authorization": "<redacted>", "path": "research.txt"})

    def test_all_result_outcomes_emit_the_saved_unclipped_payload(self):
        outcomes = {}
        for node in ast.walk(self.tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "emit_progress":
                keywords = {keyword.arg: keyword.value for keyword in node.keywords}
                quality = keywords.get("result_quality")
                if isinstance(quality, ast.Constant) and quality.value in {"useful", "empty", "hard_failure", "repeated"}:
                    outcomes[quality.value] = keywords
        self.assertEqual(set(outcomes), {"useful", "empty", "hard_failure", "repeated"})
        for outcome in outcomes.values():
            self.assertEqual(outcome["tool_result"].id, "saved_tool_result")
            self.assertIn("tool_args", outcome)


if __name__ == "__main__":
    unittest.main()
