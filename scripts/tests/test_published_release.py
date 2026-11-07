"""Reconcile a real draft identity before publishing its verified assets."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_evidence
import verify_published_release as verifier


SHA = "a" * 40
ROOT = "repos/owner/repo"
TAG = "v1.2.3"


def not_found():
    return subprocess.CalledProcessError(1, ["gh", "api"], stderr="gh: Not Found (HTTP 404)")


class PublishedReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()
        (self.artifacts / "app.apk").write_bytes(b"verified signed fixture apk")
        (self.artifacts / "app.aab").write_bytes(b"verified signed fixture bundle")
        self.assets = release_evidence.artifact_inventory([self.artifacts / "app.apk", self.artifacts / "app.aab"])
        provenance = {"commit": SHA, "tag": TAG, "signingCertificateSha256": "b" * 64, "assets": self.assets}
        (self.artifacts / "provenance.json").write_text(json.dumps(provenance))
        (self.artifacts / "SHA256SUMS.txt").write_text("fixture checksums\n")
        names = [item["name"] for item in self.assets] + ["provenance.json", "SHA256SUMS.txt"]
        self.contents = {index: (self.artifacts / name).read_bytes() for index, name in enumerate(names, 101)}
        self.release = {
            "id": 17, "tag_name": TAG, "draft": True, "published_at": None, "target_commitish": SHA,
            "assets": [{"id": index, "name": name, "state": "uploaded", "size": len(self.contents[index])}
                       for index, name in enumerate(names, 101)]
        }
        self.endpoints = {
            ROOT + "/releases/tags/" + TAG: not_found(),
            ROOT + "/releases?per_page=100&page=1": [self.release],
            ROOT + "/releases/17": self.release,
            ROOT + "/commits/" + TAG: not_found()
        }
        self.requests = []
        self.downloads = []
        self.output = self.root / "receipt.json"

    def gh_json(self, *arguments):
        self.assertEqual("api", arguments[0])
        self.assertEqual(2, len(arguments))
        endpoint = arguments[1]
        self.requests.append(endpoint)
        response = self.endpoints[endpoint]
        if isinstance(response, Exception):
            raise response
        return copy.deepcopy(response)

    def download(self, command, *, check, timeout, stdout):
        self.assertEqual(["gh", "api"], command[:2])
        self.assertEqual(["--header", "Accept: application/octet-stream"], command[3:])
        self.assertTrue(check)
        self.assertEqual(600, timeout)
        self.assertTrue(command[2].startswith(ROOT + "/releases/assets/"))
        asset_id = int(command[2].rsplit("/", 1)[1])
        self.downloads.append(asset_id)
        stdout.write(self.contents[asset_id])

    def verify(self, *options):
        arguments = ["verify_published_release.py", "--repository", "owner/repo",
                     "--artifacts", str(self.artifacts), "--output", str(self.output), *options]
        with patch.object(sys, "argv", arguments), patch.object(verifier, "gh_json", self.gh_json), \
                patch.object(verifier.subprocess, "run", self.download), contextlib.redirect_stdout(io.StringIO()):
            verifier.main()
        return json.loads(self.output.read_text())

    def test_draft_upload_resolves_unpublished_tag_by_paginated_identity(self):
        self.endpoints[ROOT + "/releases?per_page=100&page=1"] = [
            {"id": index + 200, "tag_name": "v0." + str(index)} for index in range(100)
        ]
        self.endpoints[ROOT + "/releases?per_page=100&page=2"] = [self.release]
        receipt = self.verify()
        self.assertEqual("UPLOAD_VERIFIED", receipt["status"])
        self.assertEqual("PENDING_PUBLICATION", receipt["tagState"])
        self.assertEqual(17, receipt["releaseId"])
        self.assertEqual(SHA, receipt["sourceSha"])
        self.assertEqual(list(self.contents), self.downloads)
        self.assertIn(ROOT + "/releases?per_page=100&page=2", self.requests)
        self.assertIn(ROOT + "/releases/17", self.requests)

    def make_published(self):
        self.release.update(draft=False, published_at="2026-11-07T00:00:00Z")
        self.endpoints[ROOT + "/releases/tags/" + TAG] = self.release
        self.endpoints[ROOT + "/commits/" + TAG] = {"sha": SHA}

    def test_published_receipt_requires_the_same_id_and_actual_tag_commit(self):
        self.make_published()
        receipt = self.verify("--release-id", "17", "--published")
        self.assertEqual("PUBLISHED_VERIFIED", receipt["status"])
        self.assertEqual("VERIFIED", receipt["tagState"])
        self.assertEqual("NOT_MEASURED", receipt["installationQualification"])
        self.assertEqual([ROOT + "/releases/17", ROOT + "/releases/tags/" + TAG,
                          ROOT + "/commits/" + TAG], self.requests)

    def test_published_tag_lookup_does_not_substitute_a_draft(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.verify("--published")
        self.assertEqual([ROOT + "/releases/tags/" + TAG], self.requests)
        self.assertEqual([], self.downloads)
        self.assertFalse(self.output.exists())

    def test_only_an_explicit_404_permits_draft_collection_lookup(self):
        for status in (401, 403, 429, 500):
            with self.subTest(status=status):
                self.requests.clear()
                self.endpoints[ROOT + "/releases/tags/" + TAG] = subprocess.CalledProcessError(
                    1, ["gh", "api"], stderr=f"gh: request failed (HTTP {status})")
                with self.assertRaises(subprocess.CalledProcessError):
                    self.verify()
                self.assertEqual([ROOT + "/releases/tags/" + TAG], self.requests)
        self.assertEqual([], self.downloads)
        self.assertFalse(self.output.exists())

    def test_ambiguous_draft_ids_on_later_pages_fail_before_download(self):
        self.endpoints[ROOT + "/releases?per_page=100&page=1"] = [self.release] + [
            {"id": index + 200, "tag_name": "v0." + str(index)} for index in range(99)
        ]
        self.endpoints[ROOT + "/releases?per_page=100&page=2"] = [{**self.release, "id": 18}]
        with self.assertRaisesRegex(ValueError, "Multiple release identities"):
            self.verify()
        self.assertEqual([], self.downloads)
        self.assertFalse(self.output.exists())

    def test_incomplete_or_malformed_collection_cannot_authorize_publication(self):
        for page in ({"message": "unavailable"}, [None], [{"id": 17, "tag_name": TAG}] * 101):
            with self.subTest(page_type=type(page).__name__):
                self.endpoints[ROOT + "/releases?per_page=100&page=1"] = page
                with self.assertRaisesRegex(ValueError, "Invalid release collection"):
                    self.verify()
        self.endpoints[ROOT + "/releases?per_page=100&page=1"] = [self.release] + [
            {"id": index + 200, "tag_name": "v0." + str(index)} for index in range(99)
        ]
        with patch.object(verifier, "MAX_RELEASE_PAGES", 1), self.assertRaisesRegex(ValueError, "incomplete"):
            self.verify()
        self.assertEqual([], self.downloads)
        self.assertFalse(self.output.exists())

    def test_draft_target_must_be_the_exact_immutable_source(self):
        for target in ("main", "c" * 40):
            with self.subTest(target=target):
                self.release["target_commitish"] = target
                with self.assertRaisesRegex(ValueError, "immutable source"):
                    self.verify()
        self.assertEqual([], self.downloads)
        self.assertFalse(self.output.exists())

    def test_stale_or_invalid_pinned_identity_is_not_replaced_by_a_tag_match(self):
        for change in ({"id": 18}, {"id": True}, {"tag_name": "v2.0.0"}, {"target_commitish": "c" * 40}):
            with self.subTest(change=change):
                self.endpoints[ROOT + "/releases/17"] = {**self.release, **change}
                with self.assertRaises(ValueError):
                    self.verify("--release-id", "17")
        for release_id in ("0", "-1"):
            with self.subTest(release_id=release_id), self.assertRaisesRegex(ValueError, "positive release ID"):
                self.verify("--release-id", release_id)
        self.assertEqual([], self.downloads)
        self.assertFalse(self.output.exists())

    def test_published_tag_cannot_point_to_a_different_release_id(self):
        self.make_published()
        self.endpoints[ROOT + "/releases/tags/" + TAG] = {**self.release, "id": 18}
        with self.assertRaisesRegex(ValueError, "Release identity"):
            self.verify("--release-id", "17", "--published")
        self.assertEqual([], self.downloads)
        self.assertFalse(self.output.exists())

    def test_retargeted_draft_cannot_be_accepted_from_old_collection_data(self):
        self.endpoints[ROOT + "/releases/17"] = {**self.release, "target_commitish": "c" * 40}
        with self.assertRaisesRegex(ValueError, "immutable source"):
            self.verify()
        self.assertIn(ROOT + "/releases/17", self.requests)
        self.assertEqual([], self.downloads)

    def test_missing_and_ambiguous_asset_ids_are_rejected_before_download(self):
        for change in ({"id": None}, {"id": True}, {"id": 0}, {"id": 102}, {"state": "new"}):
            with self.subTest(change=change):
                candidate = copy.deepcopy(self.release)
                candidate["assets"][0].update(change)
                self.endpoints[ROOT + "/releases/17"] = candidate
                with self.assertRaisesRegex(ValueError, "asset identity"):
                    self.verify()
        self.assertEqual([], self.downloads)
        self.assertFalse(self.output.exists())

    def test_downloaded_asset_and_manifest_bytes_still_control_the_receipt(self):
        for asset_id in self.contents:
            with self.subTest(asset_id=asset_id):
                original = self.contents[asset_id]
                self.contents[asset_id] = b"x" * len(original)
                with self.assertRaises(ValueError):
                    self.verify()
                self.contents[asset_id] = original
                self.assertFalse(self.output.exists())


FAKE_GH = '''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

root = Path(os.environ["RELEASE_FIXTURE"])
state_path = root / "state.json"
state = json.loads(state_path.read_text())
args = sys.argv[1:]
state["commands"].append(args)
release = state["release"]
prefix = "repos/owner/repo"

def save():
    state_path.write_text(json.dumps(state))

def missing():
    save()
    print("gh: Not Found (HTTP 404)", file=sys.stderr)
    sys.exit(1)

if args[:2] == ["release", "create"]:
    assert args[2] == release["tag_name"] and "--draft" in args
    assert args[args.index("--target") + 1] == "a" * 40
    state["creates"] += 1
    save()
    if state.get("lost_create"):
        sys.exit(1)
    print("https://github.com/owner/repo/releases/tag/untagged-fixture")
elif args[:3] == ["api", "--method", "PATCH"]:
    assert args[3] == prefix + "/releases/17"
    assert args[4:] == ["--field", "draft=false", "--silent"]
    state["publications"] += 1
    release.update(draft=False, published_at="2026-11-07T00:00:00Z")
    save()
    if state.get("lost_publish"):
        sys.exit(1)
elif args[0] == "api":
    endpoint = args[1]
    save()
    if endpoint == prefix + "/releases/tags/v1.2.3":
        if release["draft"]:
            missing()
        print(json.dumps(release))
    elif endpoint == prefix + "/releases?per_page=100&page=1":
        print(json.dumps([release]))
    elif endpoint == prefix + "/releases/17":
        print(json.dumps(release))
    elif endpoint == prefix + "/commits/v1.2.3":
        if release["draft"]:
            missing()
        print(json.dumps({"sha": "a" * 40}))
    elif endpoint.startswith(prefix + "/releases/assets/"):
        assert args[2:] == ["--header", "Accept: application/octet-stream"]
        asset_id = int(endpoint.rsplit("/", 1)[1])
        name = next(item["name"] for item in release["assets"] if item["id"] == asset_id)
        sys.stdout.buffer.write((root / "remote" / name).read_bytes())
    else:
        raise AssertionError(args)
else:
    raise AssertionError(args)
'''


class ReleasePublicationWorkflowTest(unittest.TestCase):
    def run_publication(self, *, lost_create=False, lost_publish=False, wrong_target=False, corrupt_bytes=False):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            artifacts = root / "release-artifacts"
            artifacts.mkdir()
            (artifacts / "app.apk").write_bytes(b"signed apk")
            (artifacts / "app.aab").write_bytes(b"signed aab")
            assets = release_evidence.artifact_inventory([artifacts / "app.apk", artifacts / "app.aab"])
            (artifacts / "provenance.json").write_text(json.dumps({
                "commit": SHA, "tag": TAG, "signingCertificateSha256": "b" * 64, "assets": assets
            }))
            (artifacts / "SHA256SUMS.txt").write_text("fixture manifest\n")
            names = [item["name"] for item in assets] + ["provenance.json", "SHA256SUMS.txt"]
            release = {
                "id": 17, "tag_name": TAG, "draft": True, "published_at": None,
                "target_commitish": "c" * 40 if wrong_target else SHA,
                "assets": [{"id": index, "name": name, "state": "uploaded", "size": (artifacts / name).stat().st_size}
                           for index, name in enumerate(names, 101)]
            }
            shutil.copytree(artifacts, root / "remote")
            if corrupt_bytes:
                (root / "remote" / "app.apk").write_bytes(b"wrong data")
            (root / "state.json").write_text(json.dumps({
                "release": release, "creates": 0, "publications": 0, "commands": [],
                "lost_create": lost_create, "lost_publish": lost_publish
            }))
            scripts = root / "scripts"
            scripts.mkdir()
            source = Path(__file__).resolve().parents[1]
            for name in ("verify_published_release.py", "release_evidence.py"):
                shutil.copy2(source / name, scripts / name)
            binary = root / "bin"
            binary.mkdir()
            (binary / "gh").write_text(FAKE_GH)
            (binary / "gh").chmod(0o755)
            reports = root / "build/reports"
            reports.mkdir(parents=True)
            # An old receipt must not authorize PATCH after a new verification failure.
            (reports / "release-upload.json").write_text(json.dumps({"releaseId": 99, "sourceSha": "d" * 40}))
            workflow = (source.parent / ".github/workflows/release-build.yml").read_text()
            block = workflow.split("      - name: Publish new immutable release\n", 1)[1]
            block = block.split("        run: |\n", 1)[1].split("\n      - ", 1)[0]
            script = "\n".join(line[10:] for line in block.splitlines())
            environment = {**os.environ, "PATH": str(binary) + os.pathsep + os.environ.get("PATH", ""),
                           "RELEASE_FIXTURE": folder, "GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": SHA,
                           "TAG": TAG, "PRERELEASE": "false", "GH_TOKEN": "local-fixture-only"}
            result = subprocess.run(["bash", "-c", script], cwd=root, env=environment,
                                    capture_output=True, text=True, timeout=30)
            state = json.loads((root / "state.json").read_text())
            publication = reports / "release-publication.json"
            receipt = json.loads(publication.read_text()) if publication.exists() else None
            return result, state, receipt

    def test_lost_create_and_publish_acknowledgements_reconcile_without_replay(self):
        for lost_create, lost_publish in ((False, False), (True, False), (False, True), (True, True)):
            with self.subTest(lost_create=lost_create, lost_publish=lost_publish):
                result, state, receipt = self.run_publication(lost_create=lost_create, lost_publish=lost_publish)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(1, state["creates"])
                self.assertEqual(1, state["publications"])
                self.assertEqual("PUBLISHED_VERIFIED", receipt["status"])
                self.assertEqual(17, receipt["releaseId"])
                self.assertEqual(SHA, receipt["sourceSha"])

    def test_wrong_target_or_asset_bytes_stop_before_publication_despite_old_receipt(self):
        for case in ({"wrong_target": True}, {"corrupt_bytes": True}):
            with self.subTest(case=case):
                result, state, receipt = self.run_publication(**case)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, state["creates"])
                self.assertEqual(0, state["publications"])
                self.assertIsNone(receipt)


if __name__ == "__main__":
    unittest.main()
