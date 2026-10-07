"""General web questions must not receive repository catalogs."""
import ast
import re
import unittest
from pathlib import Path


class GeneralToolRoutingTests(unittest.TestCase):
    def setUp(self):
        tree = ast.parse((Path(__file__).resolve().parents[1] / "gateway_v13.py").read_text())
        names = {"apply_workflow_tool_profile", "is_repository_tool_name", "get_tool_name", "get_workflow_context_text", "get_latest_user_text", "get_effective_user_text", "task_text_without_saved_work", "has_repository_context", "classify_request_domain", "classify_workflow_profile"}
        module = ast.Module(body=[node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names], type_ignores=[])
        self.scope = {"re": re, "WORKFLOW_TOOL_PROFILES_ENABLED": True, "github_full_access_workflow": lambda profile: False,
                      "is_tool_inventory_request": lambda text: False, "repository_action_intent": lambda text: {"build": False, "write": False},
                      "extract_branch_hint": lambda text: None, "looks_like_personal_memory_question": lambda text: False}
        exec(compile(module, "gateway-routing", "exec"), self.scope)

    def test_general_profile_excludes_repository_tools_but_retains_web_and_memory(self):
        tools = [{"type": "function", "function": {"name": name}} for name in ["github-official_search_repositories", "github-official_actions_list", "web_search", "read_url", "angruvadal-memory_context_retrieve"]]
        selected = self.scope["apply_workflow_tool_profile"](tools, "general")
        self.assertEqual([tool["function"]["name"] for tool in selected], ["web_search", "read_url", "angruvadal-memory_context_retrieve"])
        self.scope["WORKFLOW_TOOL_PROFILES_ENABLED"] = False
        self.assertEqual(self.scope["apply_workflow_tool_profile"](tools, "general"), selected)

    def test_explicit_tool_inventory_keeps_repository_tools(self):
        tools = [{"type": "function", "function": {"name": "github-official_actions_list"}}]
        self.assertEqual(self.scope["apply_workflow_tool_profile"](tools, "tool_inventory"), tools)

    def test_history_report_and_historical_release_do_not_expose_github(self):
        for text in ["Analyze a report on France's history", "Review the timeline including the release of prisoners in 1789", "Explain a royal branch and a political commitment"]:
            with self.subTest(text=text):
                self.assertEqual(self.scope["classify_workflow_profile"](text), "general")
                self.assertEqual(self.scope["classify_request_domain"](text), "general")

    def test_repository_and_release_commands_keep_their_tools(self):
        for text in ["Analyze the GitHub repository", "Fix branch", "Commit and push"]:
            self.assertEqual(self.scope["classify_request_domain"](text), "repository")
        self.assertEqual(self.scope["classify_workflow_profile"]("Publish a release"), "release_action")

    def test_short_followup_retains_intent_without_contaminating_a_new_question(self):
        history = [{"role": "user", "content": "Fix the GitHub repository"}, {"role": "user", "content": "Continue"}]
        context = self.scope["get_workflow_context_text"](history)
        self.assertIn("GitHub repository", context)
        history.append({"role": "user", "content": "What is the weather in London?"})
        self.assertEqual(self.scope["get_workflow_context_text"](history), "What is the weather in London?")

    def test_recovered_repository_research_does_not_turn_a_web_question_into_a_repo_task(self):
        original = "What is the weather in London?"
        saved = "\n\nSaved work from an incomplete response follows.\n<saved_response_resource>\nTool github_search_repositories; old GitHub commit research\n</saved_response_resource>"
        messages = [{"role": "user", "content": original + saved}]
        self.assertEqual(self.scope["get_latest_user_text"](messages), original)
        self.assertEqual(self.scope["get_workflow_context_text"](messages), original)
        self.assertIn("old GitHub commit", messages[0]["content"])
