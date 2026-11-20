#!/usr/bin/env python3
"""Regression: GitHub draft releases lack a resolvable tag before publication."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_published_release import resolve_tag_commit

SOURCE = "c823d7a9f7e1d3927000ec30b151c1b6122da503"
ROOT = "repos/tailscale-signin/GPT_Mobile_AI-improved"
TAG = "v0.9.66.10"
DRAFT = {"draft": True, "target_commitish": SOURCE}


def api_error(status):
    return subprocess.CalledProcessError(1, ["gh", "api"], stderr=f"gh: lookup failed (HTTP {status})")


class DraftReleaseTagVerificationTest(unittest.TestCase):
    def test_missing_draft_ref_requires_verified_exact_source(self):
        calls = []
        def fake(*arguments):
            calls.append(arguments)
            if arguments[-1] == ROOT + "/git/ref/tags/" + TAG:
                raise api_error(404)
            self.assertEqual(arguments, ("api", ROOT + "/commits/" + SOURCE))
            return {"sha": SOURCE}
        with patch("verify_published_release.gh_json", side_effect=fake):
            self.assertIsNone(resolve_tag_commit(ROOT, TAG, SOURCE, DRAFT, published=False))
        self.assertEqual(len(calls), 2)

    def test_existing_ref_is_resolved_and_peeled(self):
        def fake(*arguments):
            if "/git/ref/tags/" in arguments[-1]:
                return {"ref": "refs/tags/" + TAG, "object": {"type": "tag"}}
            self.assertEqual(arguments[-1], ROOT + "/commits/" + TAG)
            return {"sha": SOURCE}
        with patch("verify_published_release.gh_json", side_effect=fake):
            self.assertEqual(resolve_tag_commit(ROOT, TAG, SOURCE, DRAFT, published=False), SOURCE)

    def test_422_is_not_misread_as_absent_ref(self):
        with patch("verify_published_release.gh_json", side_effect=api_error(422)):
            with self.assertRaises(subprocess.CalledProcessError):
                resolve_tag_commit(ROOT, TAG, SOURCE, DRAFT, published=False)

    def test_published_release_needs_an_actual_tag(self):
        with patch("verify_published_release.gh_json", side_effect=api_error(404)):
            with self.assertRaises(subprocess.CalledProcessError):
                resolve_tag_commit(ROOT, TAG, SOURCE, {"draft": False}, published=True)

    def test_wrong_draft_target_is_never_reconciled(self):
        with patch("verify_published_release.gh_json", side_effect=api_error(404)):
            with self.assertRaises(subprocess.CalledProcessError):
                resolve_tag_commit(ROOT, TAG, SOURCE, {"draft": True, "target_commitish": "0" * 40}, published=False)

    def test_absent_ref_but_wrong_commit_is_rejected(self):
        def fake(*arguments):
            if "/git/ref/tags/" in arguments[-1]:
                raise api_error(404)
            return {"sha": "0" * 40}
        with patch("verify_published_release.gh_json", side_effect=fake):
            with self.assertRaisesRegex(ValueError, "source commit"):
                resolve_tag_commit(ROOT, TAG, SOURCE, DRAFT, published=False)

    def test_unexpected_ref_identity_is_rejected(self):
        with patch("verify_published_release.gh_json", return_value={"ref": "refs/tags/not-the-release"}):
            with self.assertRaisesRegex(ValueError, "ref identity"):
                resolve_tag_commit(ROOT, TAG, SOURCE, DRAFT, published=False)


if __name__ == "__main__":
    unittest.main()
