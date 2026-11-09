"""Behavioral guards for recovering the existing signed draft without replay."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import traceback
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import recover_existing_release as recovery


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, indent=2) + "\n").encode()


def zipped(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in files.items():
            archive.writestr(name, content)
    return output.getvalue()


class FakeGitHub:
    def __init__(self, pins, files, archives):
        self.pins, self.files, self.archives = pins, copy.deepcopy(files), archives
        self.patch_calls = []
        self.remote_downloads = 0
        self.lose_ack = False
        self.keep_draft = False
        self.move_main = False
        self.replace_asset = False
        self.corrupt_after_patch = False
        self.run_changes = {}
        self.artifact_changes = {}
        self.jobs = [{**job, "steps": [{**step, "status": "completed"} for step in job["steps"]],
                      "status": "completed", "run_id": pins["source_run_id"],
                      "run_attempt": pins["source_run_attempt"], "head_sha": pins["source_sha"]}
                     for job in copy.deepcopy(pins["jobs"])]
        self.release = {"id": pins["release_id"], "name": pins["tag"], "tag_name": pins["tag"],
                        "draft": True, "prerelease": False, "published_at": None,
                        "target_commitish": pins["source_sha"], "body": "Scoped release notes",
                        "html_url": "https://github.com/example/release"}

    def assets(self):
        rows = [{"id": item["id"], "name": item["name"], "size": item["size"],
                 "digest": "sha256:" + item["sha256"], "state": "uploaded"} for item in self.pins["assets"]]
        if self.replace_asset and self.remote_downloads >= 10:
            rows[0]["id"] += 1
        return rows

    def json(self, path):
        p = self.pins
        root = "repos/" + p["repository"]
        if path == root + "/git/ref/heads/main":
            sha = "0" * 40 if self.move_main and self.remote_downloads >= 10 else p["source_sha"]
            return {"ref": "refs/heads/main", "object": {"type": "commit", "sha": sha}}
        if path == f"{root}/actions/runs/{p['source_run_id']}":
            return {"id": p["source_run_id"], "run_attempt": p["source_run_attempt"],
                    "workflow_id": p["source_workflow_id"], "path": p["source_workflow_path"],
                    "head_sha": p["source_sha"], "head_branch": "main", "event": "push",
                    "status": "completed", "conclusion": "failure",
                    "repository": {"id": p["repository_id"], "full_name": p["repository"]},
                    "head_repository": {"id": p["repository_id"], "full_name": p["repository"]}, **self.run_changes}
        if path == f"{root}/actions/runs/{p['source_run_id']}/attempts/{p['source_run_attempt']}/jobs?per_page=100":
            return {"total_count": len(self.jobs), "jobs": self.jobs}
        for item in p["artifacts"]:
            if path == f"{root}/actions/artifacts/{item['id']}":
                return {"id": item["id"], "name": item["name"], "size_in_bytes": item["size"],
                        "digest": "sha256:" + item["sha256"], "expired": False,
                        "workflow_run": {"id": p["source_run_id"], "head_sha": p["source_sha"],
                                         "head_branch": "main", "repository_id": p["repository_id"],
                                         "head_repository_id": p["repository_id"]}, **self.artifact_changes}
        if path == f"{root}/releases/{p['release_id']}/assets?per_page=100":
            return self.assets()
        if path in (f"{root}/releases/{p['release_id']}", f"{root}/releases/tags/{p['tag']}"):
            return {**self.release, "assets": self.assets()}
        raise AssertionError("Unexpected read: " + path)

    def download(self, path, destination, expected):
        if "/actions/artifacts/" in path:
            data = self.archives[expected["kind"]]
        else:
            item = next(item for item in self.pins["assets"] if path.endswith("/" + str(item["id"])))
            self.remote_downloads += 1
            data = self.files[item["name"]]
        destination.write_bytes(data)
        recovery.verify_file(destination, expected)

    def publish_once(self, release_id):
        self.patch_calls.append(release_id)
        if not self.keep_draft:
            self.release.update(draft=False, published_at="2026-11-09T20:00:00Z")
        if self.corrupt_after_patch:
            self.files["app-release.aab"] = b"changed after publication"
        if self.lose_ack:
            raise subprocess.TimeoutExpired("gh", 90)


class ReleaseRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.pins = json.loads((Path(__file__).resolve().parents[1] / "release-recovery-v0.9.66.7.json").read_text())
        self.pins["release_body_sha256"] = digest(b"Scoped release notes")
        files = {item["name"]: ("retained:" + item["name"]).encode() for item in self.pins["assets"]}
        workflows = ["PR Validation", "Build Android APK", "Post-merge correctness", "CodeQL Advanced", "v0.9.50 Android acceptance"]
        rows = [{"name": name, "headSha": self.pins["source_sha"], "runId": 100 + index,
                 "attempt": 1, "status": "completed", "conclusion": "success", "url": "https://github.com/run"}
                for index, name in enumerate(workflows)]
        self.checks = {"schemaVersion": 1, "sourceSha": self.pins["source_sha"], "requiredWorkflows": workflows,
                       "status": "PASSED", "workflows": rows}
        packages = [{"name": name, "bytes": len(content), "sha256": digest(content)}
                    for name, content in sorted(files.items()) if name.endswith((".apk", ".aab"))]
        self.provenance = {"schemaVersion": 2, "commit": self.pins["source_sha"], "sourceTree": self.pins["source_tree"],
                           "tag": self.pins["tag"], "versionName": self.pins["version_name"], "versionCode": self.pins["version_code"],
                           "workflowRun": str(self.pins["source_run_id"]), "workflowAttempt": str(self.pins["source_run_attempt"]),
                           "signingCertificateSha256": self.pins["signing_certificate_sha256"], "exactCommitChecks": rows,
                           "installationQualification": "NOT_MEASURED", "applicationId": "example.app", "assets": packages}
        files["provenance.json"] = encoded(self.provenance)
        files["SHA256SUMS.txt"] = "".join(digest(files[name]) + "  " + name + "\n"
                                            for name in sorted(files) if name.endswith((".apk", ".aab")) or name == "provenance.json").encode()
        for item in self.pins["assets"]:
            item.update(size=len(files[item["name"]]), sha256=digest(files[item["name"]]))
        self.archives = {"signed": zipped(files), "checks": zipped({"release-checks.json": encoded(self.checks)})}
        for item in self.pins["artifacts"]:
            item.update(size=len(self.archives[item["kind"]]), sha256=digest(self.archives[item["kind"]]))
            item["files"] = ([{key: asset[key] for key in ("name", "size", "sha256")} for asset in self.pins["assets"]]
                             if item["kind"] == "signed" else [{"name": "release-checks.json", "size": len(encoded(self.checks)),
                                                                  "sha256": digest(encoded(self.checks))}])
        self.api = FakeGitHub(self.pins, files, self.archives)
        self.controller = recovery.Recovery(self.pins, self.api, self.root / "source", self.root / "work")
        self.identity = mock.Mock()
        self.required = mock.Mock()

    def execute(self, tag_side_effect=None):
        tag = tag_side_effect or (lambda root, tag, source, release, published=False: source if published else None)
        with mock.patch.object(recovery.Recovery, "source_identity"), \
                mock.patch.object(recovery.Recovery, "signed_identity", lambda instance: self.identity()), \
                mock.patch.object(recovery.Recovery, "required_checks", lambda instance, suffix: self.required(suffix)), \
                mock.patch.object(recovery, "resolve_tag_sha", side_effect=tag), \
                mock.patch.object(recovery.time, "sleep"):
            return self.controller.run()

    def assert_blocked(self, expected=ValueError):
        with self.assertRaises(expected):
            self.execute()
        self.assertEqual([], self.api.patch_calls)

    def test_exact_retained_bytes_publish_once_and_produce_both_receipts(self):
        result = self.execute()
        self.assertEqual("PUBLISHED_VERIFIED", result["status"])
        self.assertEqual([self.pins["release_id"]], self.api.patch_calls)
        self.assertEqual(20, self.api.remote_downloads)
        self.assertEqual(1, self.identity.call_count)
        self.assertEqual([mock.call("before-download"), mock.call("before-publish")], self.required.call_args_list)
        receipt = json.loads((self.controller.evidence / "release-upload.json").read_text())
        self.assertEqual("UPLOAD_VERIFIED", receipt["status"])
        self.assertEqual(10, len(receipt["allUploadedFiles"]))
        self.assertEqual(encoded(self.provenance), (self.controller.retained / "provenance.json").read_bytes())

    def test_lost_patch_acknowledgement_reads_same_release_without_replay(self):
        self.api.lose_ack = True
        result = self.execute()
        self.assertEqual("PUBLISHED_VERIFIED", result["status"])
        self.assertEqual([self.pins["release_id"]], self.api.patch_calls)
        self.assertFalse(json.loads((self.controller.evidence / "publication-intent.json").read_text())["acknowledged"])

    def test_already_published_release_only_verifies_existing_bytes(self):
        self.api.release.update(draft=False, published_at="2026-11-09T20:00:00Z")
        self.assertEqual("PUBLISHED_VERIFIED", self.execute()["status"])
        self.assertEqual([], self.api.patch_calls)
        self.assertEqual(10, self.api.remote_downloads)

    def test_wrong_original_source_blocks_before_download_or_patch(self):
        self.api.run_changes["head_sha"] = "0" * 40
        self.assert_blocked()
        self.assertEqual(0, self.api.remote_downloads)

    def test_rerun_original_attempt_cannot_substitute_for_pinned_build(self):
        self.api.run_changes["run_attempt"] = 2
        self.assert_blocked()

    def test_failed_signing_step_cannot_be_recovered_as_publication_only(self):
        signing = next(step for job in self.api.jobs for step in job["steps"] if step["name"] == "Sign and verify APKs and bundle")
        signing["conclusion"] = "failure"
        self.assert_blocked()

    def test_missing_original_validation_job_is_not_success(self):
        self.api.jobs = [job for job in self.api.jobs if job["name"] != "Validate exact release commit"]
        self.assert_blocked()

    def test_expired_or_changed_artifact_cannot_be_used(self):
        self.api.artifact_changes["expired"] = True
        self.assert_blocked()

    def test_archive_hash_mismatch_stops_before_publication(self):
        self.archives["signed"] += b"changed"
        self.assert_blocked()

    def test_unexpected_archive_path_is_rejected_even_with_matching_archive_digest(self):
        self.archives["signed"] = zipped({"../escape": b"bad"})
        signed = self.pins["artifacts"][0]
        signed.update(size=len(self.archives["signed"]), sha256=digest(self.archives["signed"]))
        self.assert_blocked()
        self.assertFalse((self.root / "escape").exists())

    def test_remote_byte_mismatch_stops_before_patch(self):
        self.api.files["app-release.aab"] = b"different bytes"
        self.assert_blocked()

    def test_asset_replacement_during_download_is_rechecked_before_patch(self):
        self.api.replace_asset = True
        self.assert_blocked()
        self.assertEqual(10, self.api.remote_downloads)

    def test_main_move_during_download_is_rechecked_before_patch(self):
        self.api.move_main = True
        self.assert_blocked()
        self.assertEqual(10, self.api.remote_downloads)

    def test_signature_or_package_identity_failure_stops_before_patch(self):
        self.identity.side_effect = ValueError("signature mismatch")
        self.assert_blocked()

    def test_new_required_check_failure_stops_after_upload_verification(self):
        self.required.side_effect = [None, ValueError("new check failure")]
        self.assert_blocked()
        self.assertEqual(10, self.api.remote_downloads)
        self.assertTrue((self.controller.evidence / "release-upload.json").is_file())

    def test_tag_moving_to_another_commit_stops_before_patch(self):
        with self.assertRaises(ValueError):
            self.execute(tag_side_effect=[None, "0" * 40])
        self.assertEqual([], self.api.patch_calls)

    def test_uncertain_write_that_stays_draft_is_never_repeated(self):
        self.api.lose_ack = True
        self.api.keep_draft = True
        with self.assertRaises(RuntimeError):
            self.execute()
        self.assertEqual([self.pins["release_id"]], self.api.patch_calls)
        self.assertFalse((self.controller.evidence / "release-publication.json").exists())

    def test_changed_post_publication_bytes_do_not_produce_a_success_receipt(self):
        self.api.corrupt_after_patch = True
        with self.assertRaises(ValueError):
            self.execute()
        self.assertEqual([self.pins["release_id"]], self.api.patch_calls)
        self.assertFalse((self.controller.evidence / "release-publication.json").exists())

    def test_wrong_dispatch_ref_is_rejected_before_recovery(self):
        with mock.patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                                          "GITHUB_REPOSITORY": self.pins["repository"], "GITHUB_RUN_ATTEMPT": "1"}), \
                mock.patch.object(sys, "argv", ["recover", "--source", str(self.root), "--work", str(self.root / "unused")]), \
                mock.patch.object(recovery.Recovery, "run") as run:
            with self.assertRaises(ValueError):
                recovery.main()
        run.assert_not_called()


class GitHubDownloadProtocolTest(unittest.TestCase):
    """Exercise the real download adapter through a media-type enforcing CLI."""

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.body = self.root / "response.bin"
        self.body.write_bytes(b"PK\x03\x04\x00\xffexact signed archive bytes\x00")
        self.requests = self.root / "requests.jsonl"
        executable = self.root / "gh"
        executable.write_text('''#!/usr/bin/env python3
import json, os
from pathlib import Path
import sys
args = sys.argv[1:]
with Path(os.environ["RECOVERY_TEST_REQUESTS"]).open("a") as log:
    log.write(json.dumps(args) + "\\n")
assert len(args) == 6 and args[:3] == ["api", "--method", "GET"] and args[4] == "--header"
path, header = args[3], args[5]
expected = "Accept: application/vnd.github+json" if "/actions/artifacts/" in path else "Accept: application/octet-stream"
mode = os.environ.get("RECOVERY_TEST_MODE", "success")
if header != expected or mode == "http_failure":
    print("redirect https://storage.invalid/archive?token=PRIVATE_SIGNATURE", file=sys.stderr)
    print("Authorization: Bearer PRIVATE_CREDENTIAL", file=sys.stderr)
    print("gh: Unsupported media type (HTTP 415)", file=sys.stderr)
    raise SystemExit(1)
if mode == "transport_failure":
    print("failed https://storage.invalid/archive?token=PRIVATE_SIGNATURE", file=sys.stderr)
    raise SystemExit(1)
sys.stdout.buffer.write(Path(os.environ["RECOVERY_TEST_BODY"]).read_bytes())
''')
        executable.chmod(0o755)
        self.environment = mock.patch.dict(os.environ, {
            "PATH": str(self.root) + os.pathsep + os.environ.get("PATH", ""),
            "RECOVERY_TEST_REQUESTS": str(self.requests), "RECOVERY_TEST_BODY": str(self.body),
            "RECOVERY_TEST_MODE": "success",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.api = recovery.GitHub("owner/repository")
        self.expected = {"size": self.body.stat().st_size, "sha256": digest(self.body.read_bytes())}

    def call(self, path):
        destination = self.root / "download.bin"
        self.api.download(path, destination, self.expected)
        self.assertEqual(self.body.read_bytes(), destination.read_bytes())
        return json.loads(self.requests.read_text().strip())

    def test_actions_archive_uses_json_media_type_and_preserves_binary_bytes(self):
        request = self.call("repos/owner/repository/actions/artifacts/123/zip")
        self.assertEqual("Accept: application/vnd.github+json", request[-1])

    def test_release_asset_requests_octet_stream_and_preserves_binary_bytes(self):
        request = self.call("repos/owner/repository/releases/assets/456")
        self.assertEqual("Accept: application/octet-stream", request[-1])

    def test_successful_response_still_requires_exact_archive_hash(self):
        self.expected["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "File digest differs"):
            self.call("repos/owner/repository/actions/artifacts/123/zip")
        self.assertEqual(1, len(self.requests.read_text().splitlines()))

    def test_successful_response_still_requires_exact_asset_size(self):
        self.expected["size"] += 1
        with self.assertRaisesRegex(ValueError, "File size differs"):
            self.call("repos/owner/repository/releases/assets/456")

    def test_http_failure_exposes_only_status_without_redirect_or_credentials(self):
        with mock.patch.dict(os.environ, {"RECOVERY_TEST_MODE": "http_failure"}):
            with self.assertRaises(RuntimeError) as caught:
                self.call("repos/owner/repository/actions/artifacts/123/zip")
        self.assertEqual("GitHub download failed (HTTP 415)", str(caught.exception))
        rendered = "".join(traceback.format_exception(caught.exception))
        for secret in ("PRIVATE_SIGNATURE", "PRIVATE_CREDENTIAL", "storage.invalid", "Authorization:"):
            self.assertNotIn(secret, rendered)
        self.assertTrue(caught.exception.__suppress_context__)
        self.assertEqual(1, len(self.requests.read_text().splitlines()))

    def test_non_http_failure_does_not_print_untrusted_error_text(self):
        with mock.patch.dict(os.environ, {"RECOVERY_TEST_MODE": "transport_failure"}):
            with self.assertRaisesRegex(RuntimeError, "HTTP unavailable") as caught:
                self.call("repos/owner/repository/releases/assets/456")
        self.assertNotIn("PRIVATE_SIGNATURE", "".join(traceback.format_exception(caught.exception)))

    def test_timeout_omits_captured_redirect_information(self):
        error = subprocess.TimeoutExpired("gh", 900, stderr=b"https://storage.invalid/?token=PRIVATE_SIGNATURE")
        with mock.patch.object(recovery.subprocess, "run", side_effect=error):
            with self.assertRaisesRegex(RuntimeError, "HTTP unavailable") as caught:
                self.call("repos/owner/repository/actions/artifacts/123/zip")
        self.assertNotIn("PRIVATE_SIGNATURE", "".join(traceback.format_exception(caught.exception)))

    def test_unexpected_endpoints_are_rejected_before_invoking_cli(self):
        for path in ("repos/other/repository/actions/artifacts/123/zip",
                     "repos/owner/repository/actions/artifacts/123",
                     "repos/owner/repository/releases/assets/456?redirect=other"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.api.download(path, self.root / "unused", self.expected)
        self.assertFalse(self.requests.exists())
        self.assertFalse((self.root / "unused").exists())


if __name__ == "__main__":
    unittest.main()
