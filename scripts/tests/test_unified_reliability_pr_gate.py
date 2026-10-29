"""Regression tests for draft-to-ready v2 delivery admission."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import check_unified_reliability_pr as gate  # noqa: E402


class ReliabilityV2PrGateTest(unittest.TestCase):
    def test_v2_delivery_requires_full_completion_even_if_draft(self):
        for ref in (
            "feat/unified-reliability-v2-2026-10-13",
            "release/unified-reliability-v2-2026-10-13",
        ):
            with self.subTest(ref=ref), patch.object(gate, "validate", return_value=(48, 110)) as validator:
                self.assertEqual((48, 110), gate.check_pr(ref))
                validator.assert_called_once_with(require_done=True)

    def test_other_refs_must_preserve_scope_without_claiming_v2_done(self):
        for ref in (
            "main",
            "refs/heads/gh-readonly-queue/main/pr-759",
            "fix/reliability-post-merge-audit-2026-10-13",
            "fix/reliability-v2-completion-ci-gate-2026-10-13",
            "feat/research-engine",
            "release/v0.9.50.0",
        ):
            with self.subTest(ref=ref), patch.object(gate, "validate", return_value=(48, 110)) as validator:
                self.assertEqual((48, 110), gate.check_pr(ref))
                validator.assert_called_once_with(require_done=False)

    def test_missing_pr_identity_fails_closed(self):
        for ref in ("", "  ", None):
            with self.subTest(ref=ref), patch.object(gate, "validate") as validator:
                with self.assertRaisesRegex(ValueError, "PR head ref is required"):
                    gate.check_pr(ref)
                validator.assert_not_called()

    def test_review_status_changes_trigger_new_ci_workflow(self):
        source = (ROOT / ".github/workflows/pr-validation.yml").read_text()
        pr_trigger = source.split("  pull_request:\n", 1)[1].split("\nconcurrency:", 1)[0]
        for event in ("opened", "synchronize", "reopened", "ready_for_review", "converted_to_draft", "edited"):
            with self.subTest(event=event):
                self.assertIn(event, pr_trigger)

    def test_completion_gate_runs_independently_of_draft_and_main_tests(self):
        source = (ROOT / ".github/workflows/pr-validation.yml").read_text()
        gate_job = source.split("\n  reliability_v2_completion:\n", 1)[1].split("\n  validate:\n", 1)[0]
        self.assertIn("name: Unified Reliability v2 completion gate", gate_job)
        self.assertIn("scripts/check_unified_reliability_pr.py", gate_job)
        self.assertIn("HEAD_REF: ${{ github.event.pull_request.head.ref || github.event.merge_group.head_ref || github.ref_name }}", gate_job)
        self.assertNotIn("pull_request.draft ==", gate_job)
        self.assertNotIn("if: github.event.pull_request.draft", gate_job)

    def test_signed_release_keeps_its_strict_completion_gate(self):
        source = (ROOT / ".github/workflows/release-build.yml").read_text()
        self.assertIn("python3 scripts/check_unified_reliability_v2.py --require-done", source)


if __name__ == "__main__":
    unittest.main()
