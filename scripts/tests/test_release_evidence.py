"""Publication must retain actual source, signer and downloaded asset evidence."""
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import await_release_checks as checks
import release_evidence as evidence


SHA = "a" * 40


class ReleaseEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        (root / "app.apk").write_bytes(b"verified signed fixture apk")
        (root / "app.aab").write_bytes(b"verified signed fixture bundle")
        self.assets = evidence.artifact_inventory([root / "app.apk", root / "app.aab"])
        self.provenance = {"commit": SHA, "tag": "v1.2.3", "signingCertificateSha256": "b" * 64,
                           "applicationId": "example.app", "versionCode": 10, "versionName": "1.2.3", "assets": self.assets}
        self.release = {"id": 17, "tag_name": "v1.2.3", "draft": False, "published_at": "2026-11-06T10:00:00Z",
                        "assets": [{"name": item["name"], "state": "uploaded", "size": item["bytes"],
                                    "digest": "sha256:" + item["sha256"]} for item in self.assets]}

    def tearDown(self):
        self.directory.cleanup()

    def test_uploaded_bytes_and_tag_are_bound_without_claiming_installation(self):
        result = evidence.verify_uploaded_release(self.release, self.provenance, SHA, self.assets, published=True)
        self.assertEqual("PUBLISHED_VERIFIED", result["status"])
        self.assertEqual("NOT_MEASURED", result["installationQualification"])
        self.assertEqual(SHA, result["sourceSha"])

    def test_stale_commit_missing_asset_and_changed_bytes_cannot_publish(self):
        bad_asset = copy.deepcopy(self.assets)
        bad_asset[0]["sha256"] = "d" * 64
        for sha, assets in [("c" * 40, self.assets), (SHA, self.assets[1:]), (SHA, bad_asset)]:
            with self.subTest(sha=sha, assets=assets), self.assertRaises(ValueError):
                evidence.verify_uploaded_release(self.release, self.provenance, sha, assets, published=True)

    def test_missing_signer_and_remote_digest_contradiction_fail(self):
        for provenance, release in [({**self.provenance, "signingCertificateSha256": ""}, self.release),
                                    (self.provenance, {**self.release, "assets": [{**self.release["assets"][0], "digest": "sha256:" + "0" * 64}, self.release["assets"][1]]})]:
            with self.assertRaises(ValueError):
                evidence.verify_uploaded_release(release, provenance, SHA, self.assets, published=True)

    def test_draft_with_immutable_target_is_upload_only_until_tag_materializes(self):
        draft = {**self.release, "draft": True, "published_at": None, "target_commitish": SHA}
        result = evidence.verify_uploaded_release(draft, self.provenance, None, self.assets, published=False)
        self.assertEqual("UPLOAD_VERIFIED", result["status"])
        self.assertEqual("PENDING_PUBLICATION", result["tagState"])
        for candidate in [draft, {**draft, "target_commitish": "main"}]:
            with self.assertRaises(ValueError):
                evidence.verify_uploaded_release(candidate, self.provenance, None, self.assets, published=True)

    def test_checks_are_bound_to_required_workflow_attempt_and_exact_commit(self):
        run = {"name": "Build", "head_sha": SHA, "id": 7, "run_attempt": 2, "event": "push",
               "status": "completed", "conclusion": "success", "html_url": "https://github.com/o/r/actions/runs/7"}
        document = checks.evidence_document([run], SHA, ["Build"])
        self.assertEqual(2, evidence.exact_check_evidence(document, SHA)[0]["attempt"])
        for altered in [{**document, "sourceSha": "c" * 40}, checks.evidence_document([run], SHA, ["Build", "Lint"]),
                        checks.evidence_document([{**run, "conclusion": "skipped"}], SHA, ["Build"])]:
            with self.assertRaises(ValueError):
                evidence.exact_check_evidence(altered, SHA)


class WorkflowPaginationTest(unittest.TestCase):
    class Response(io.BytesIO):
        def __init__(self, document, link=""):
            super().__init__(json.dumps(document).encode())
            self.headers = {"Link": link}

    def test_saved_continuation_is_read_before_accepting_coverage(self):
        urls = []
        next_url = f"https://api.github.com/repos/o/r/actions/runs?head_sha={SHA}&per_page=100&page=2"
        pages = [self.Response({"total_count": 2, "workflow_runs": [{"id": 1}]}, f'<{next_url}>; rel="next"'),
                 self.Response({"total_count": 2, "workflow_runs": [{"id": 2}]})]
        def open_page(request, timeout):
            urls.append(request.full_url)
            return pages.pop(0)
        self.assertEqual([{"id": 1}, {"id": 2}], checks.fetch_runs("o/r", SHA, "fixture", opener=open_page))
        self.assertEqual(next_url, urls[1])

    def test_missing_or_untrusted_continuation_fails_closed(self):
        for link in ["", '<https://untrusted.invalid/page>; rel="next"']:
            with self.subTest(link=link), self.assertRaises(ValueError):
                checks.fetch_runs("o/r", SHA, "fixture", opener=lambda *a, **k: self.Response({"total_count": 2, "workflow_runs": [{"id": 1}]}, link))


if __name__ == "__main__":
    unittest.main()
