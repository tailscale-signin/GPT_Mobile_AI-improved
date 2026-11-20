"""Behavioral recovery contracts: immutable bytes, complete gates and no write replay."""
import copy
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import recover_release_publication as recovery


class ReleaseRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((ROOT / "release-recovery.json").read_text())
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def run_row(self, name, source=None, **fields):
        item = self.plan["android"] if name == recovery.PUBLISHER else self.plan["windows"]
        row = dict(name=name, id=item["runId"], run_attempt=item["runAttempt"],
                   head_sha=source or self.plan["sourceSha"], head_branch="main", event="push",
                   status="completed", conclusion="success", html_url="https://github.com/example/run")
        row.update(fields)
        return row

    def passing_runs(self, source=None):
        return [self.run_row(name, source=source) for name in sorted(recovery.REQUIRED)]

    def scope(self):
        return dict(version=self.plan["android"]["version"], versionCode=self.plan["android"]["versionCode"],
                    qualification="scoped", baseCommit="a" * 40, changes=["Earlier reviewed changes"],
                    limitations=["Physical installation is not measured"], requiredWorkflows=sorted(recovery.REQUIRED),
                    allowedPaths=["scripts/verify_published_release.py"],
                    publicationRequest=dict(android=self.plan["android"]["version"],
                                            versionCode=self.plan["android"]["versionCode"],
                                            gateway=self.plan["windows"]["version"]))

    def original_run(self, *, windows=False):
        item = self.plan["windows" if windows else "android"]
        return self.run_row(recovery.WINDOWS_WORKFLOW if windows else recovery.PUBLISHER,
                            path=".github/workflows/" + ("gateway-windows-installer.yml" if windows else "release-build.yml"),
                            conclusion="success" if windows else "failure",
                            repository={"full_name": self.plan["repository"]},
                            head_repository={"full_name": self.plan["repository"]})

    def artifact(self):
        item = self.plan["android"]
        expected = item["artifact"]
        return dict(id=expected["id"], name=expected["name"], size_in_bytes=expected["bytes"],
                    digest="sha256:" + expected["sha256"], expired=False,
                    workflow_run=dict(id=item["runId"], head_sha=self.plan["sourceSha"], head_branch="main",
                                      repository_id=1, head_repository_id=1))

    def release(self):
        return dict(id=self.plan["android"]["releaseId"], tag_name=self.plan["android"]["tag"],
                    draft=True, prerelease=False, published_at=None, target_commitish=self.plan["sourceSha"],
                    assets=[dict(id=item["id"], name=item["name"], size=item["bytes"],
                                 digest="sha256:" + item["sha256"], state="uploaded")
                            for item in self.plan["android"]["assets"]])

    def test_reviewed_request_is_strict_and_has_all_ten_distinct_assets(self):
        self.assertIs(self.plan, recovery.validate_plan(self.plan))
        cases = [
            lambda plan: plan.update(sourceSha="main"),
            lambda plan: plan.update(unreviewed="input"),
            lambda plan: plan["android"].update(runAttempt=True),
            lambda plan: plan["android"].update(certificateSha256=""),
            lambda plan: plan["android"]["assets"].pop(),
            lambda plan: plan["android"]["assets"][0].update(name="../app.apk"),
            lambda plan: plan["android"]["assets"][1].update(id=plan["android"]["assets"][0]["id"]),
            lambda plan: plan["android"]["artifact"].update(bytes=recovery.MAX_ARCHIVE_BYTES + 1),
            lambda plan: plan["windows"].update(bridgeVersion="17.0.1"),
        ]
        for mutate in cases:
            changed = copy.deepcopy(self.plan)
            mutate(changed)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                recovery.validate_plan(changed)

    def test_intent_requires_reviewed_recovery_change_on_main_push(self):
        self.assertTrue(recovery.intent("push", "refs/heads/main", ["release-recovery.json"]))
        self.assertTrue(recovery.intent("push", "refs/heads/main", ["scripts/verify_published_release.py"]))
        for event, ref, changed in [
            ("pull_request", "refs/pull/1/merge", ["release-recovery.json"]),
            ("workflow_dispatch", "refs/heads/main", ["release-recovery.json"]),
            ("push", "refs/heads/feature", ["release-recovery.json"]),
            ("push", "refs/heads/main", ["app/build.gradle.kts"]),
            ("push", "refs/heads/main", []),
        ]:
            with self.subTest(event=event, ref=ref, changed=changed):
                self.assertFalse(recovery.intent(event, ref, changed))

    def test_scope_preserves_product_version_qualification_and_all_eight_workflows(self):
        original = self.scope()
        candidate = copy.deepcopy(original)
        candidate["changes"].append("Recover retained publication without rebuilding")
        candidate["allowedPaths"] += sorted(recovery.NEW_PATHS)
        changed = sorted(recovery.REPAIR_PATHS)
        recovery.validate_scope(original, candidate, changed, self.plan)
        cases = [
            lambda scope: scope.update(versionCode=145),
            lambda scope: scope.update(baseCommit="b" * 40),
            lambda scope: scope.update(limitations=[]),
            lambda scope: scope.update(requiredWorkflows=["PR Validation"]),
            lambda scope: scope["publicationRequest"].update(gateway="19.0.0"),
            lambda scope: scope["allowedPaths"].append("gateway/**"),
            lambda scope: scope["changes"].append("Another unreviewed change"),
            lambda scope: scope["changes"].__setitem__(0, "Rewrite prior qualification"),
        ]
        for mutate in cases:
            bad = copy.deepcopy(candidate)
            mutate(bad)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                recovery.validate_scope(original, bad, changed, self.plan)
        with self.assertRaises(ValueError):
            recovery.validate_scope(original, candidate, changed + ["app/build.gradle.kts"], self.plan)
        with self.assertRaises(ValueError):
            recovery.validate_scope(original, candidate, ["release-recovery.json"], self.plan)

    def test_original_workflow_attempt_source_repository_and_failure_are_pinned(self):
        item = self.plan["android"]
        original = self.original_run()
        recovery.validate_run(original, self.plan, item, recovery.PUBLISHER,
                              ".github/workflows/release-build.yml", "failure")
        for fields in [
            {"run_attempt": item["runAttempt"] + 1}, {"head_sha": "d" * 40},
            {"event": "workflow_dispatch"}, {"head_branch": "feature"}, {"conclusion": "success"},
            {"path": ".github/workflows/untrusted.yml"}, {"head_repository": {"full_name": "other/repo"}},
        ]:
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                recovery.validate_run({**original, **fields}, self.plan, item, recovery.PUBLISHER,
                                      ".github/workflows/release-build.yml", "failure")

    def test_original_build_only_recovers_the_one_failed_publication_step(self):
        names = {
            8: "Require green main checks on this exact candidate",
            10: "Require qualified QNN dispatch provenance",
            11: "Shrink release code in a separate process",
            12: "Build release artifacts",
            13: "Sign and verify APKs and bundle",
            14: "Run actions/attest@59d89421af93a897026c735860bf21b6eb4f7b26",
            15: "Run actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
            16: "Publish new immutable release",
        }
        steps = [dict(number=n, name=names.get(n, "Setup"), status="completed",
                      conclusion="failure" if n == 16 else "success") for n in range(1, 17)]
        recovery.validate_build_steps(steps)
        for bad in [
            [{**row, "conclusion": "failure"} if row["number"] == 13 else row for row in steps],
            [{**row, "conclusion": "skipped"} if row["number"] == 14 else row for row in steps],
            [{**row, "name": "Untrusted signing"} if row["number"] == 13 else row for row in steps],
            steps + [dict(number=17, name="Cleanup", status="completed", conclusion="failure")],
            steps[1:], steps + [steps[0]],
        ]:
            with self.subTest(steps=bad), self.assertRaises(ValueError):
                recovery.validate_build_steps(bad)

    def test_original_publisher_exclusion_cannot_hide_another_attempt_or_failed_gate(self):
        original = self.run_row(recovery.PUBLISHER, conclusion="failure")
        runs = [*self.passing_runs(), original]
        report = recovery.check_document(runs, self.plan["sourceSha"], self.scope(),
                                         recovery.PUBLISHER, plan=self.plan)
        self.assertEqual("PASSED", report["status"])
        self.assertNotIn(recovery.PUBLISHER, report["requiredWorkflows"])
        other = self.run_row("Independent check", conclusion="failure")
        failed = recovery.check_document([*runs, other], self.plan["sourceSha"], self.scope(),
                                         recovery.PUBLISHER, plan=self.plan)
        self.assertEqual(["Independent check"], failed["failed"])
        for extra in [{**original, "run_attempt": original["run_attempt"] + 1},
                      {**original, "id": original["id"] + 1}]:
            with self.assertRaises(ValueError):
                recovery.check_document([*runs, extra], self.plan["sourceSha"], self.scope(),
                                        recovery.PUBLISHER, plan=self.plan)

    def test_recovery_gate_excludes_itself_but_waits_for_normal_publisher(self):
        source = "c" * 40
        own = self.run_row(recovery.WORKFLOW, source=source, status="in_progress", conclusion=None)
        publisher = self.run_row(recovery.PUBLISHER, source=source, status="in_progress", conclusion=None)
        report = recovery.check_document([*self.passing_runs(source), own, publisher], source,
                                         self.scope(), recovery.WORKFLOW)
        self.assertNotIn(recovery.WORKFLOW, report["requiredWorkflows"])
        self.assertEqual([recovery.PUBLISHER], report["pending"])
        success = {**publisher, "status": "completed", "conclusion": "success"}
        report = recovery.check_document([*self.passing_runs(source), own, success], source,
                                         self.scope(), recovery.WORKFLOW)
        self.assertEqual("PASSED", report["status"])

    def test_required_skips_fail_and_missing_or_foreign_source_never_passes(self):
        source = self.plan["sourceSha"]
        runs = self.passing_runs()
        for conclusion in ("skipped", "failure", "cancelled", "timed_out"):
            with self.subTest(conclusion=conclusion):
                rows = [{**runs[0], "conclusion": conclusion}, *runs[1:]]
                report = recovery.check_document(rows, source, self.scope(), recovery.WORKFLOW)
                self.assertEqual("FAILED", report["status"])
        for rows in (runs[1:], [{**runs[0], "head_sha": "e" * 40}, *runs[1:]],
                     [{**runs[0], "event": "pull_request"}, *runs[1:]],
                     [{**runs[0], "event": "workflow_dispatch"}, *runs[1:]],
                     [{**runs[0], "head_branch": "feature"}, *runs[1:]]):
            report = recovery.check_document(rows, source, self.scope(), recovery.WORKFLOW)
            self.assertEqual("PENDING", report["status"])

    def test_gate_rejects_windows_success_from_a_different_retained_attempt(self):
        runs = [*self.passing_runs(), self.run_row(recovery.PUBLISHER, conclusion="failure")]
        runs = [{**row, "run_attempt": row["run_attempt"] + 1}
                if row["name"] == recovery.WINDOWS_WORKFLOW else row for row in runs]
        with mock.patch.object(recovery.checks, "fetch_runs", return_value=runs), \
                mock.patch.dict(os.environ, {"GH_TOKEN": "fixture"}), self.assertRaises(ValueError):
            recovery.wait_checks(self.plan, self.plan["sourceSha"], self.scope(), recovery.PUBLISHER,
                                 self.root / "checks.json", 1, original=True)

    def test_artifact_rejects_replacement_expiry_foreign_source_and_archive_digest(self):
        actual, item = self.artifact(), self.plan["android"]
        recovery.validate_artifact(actual, item["artifact"], item, self.plan["sourceSha"])
        for fields in [
            {"id": actual["id"] + 1}, {"name": "other"}, {"expired": True},
            {"size_in_bytes": actual["size_in_bytes"] + 1}, {"digest": "sha256:" + "0" * 64},
            {"workflow_run": {**actual["workflow_run"], "head_sha": "d" * 40}},
            {"workflow_run": {**actual["workflow_run"], "id": item["runId"] + 1}},
            {"workflow_run": {**actual["workflow_run"], "head_repository_id": 2}},
        ]:
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                recovery.validate_artifact({**actual, **fields}, item["artifact"], item, self.plan["sourceSha"])

    def test_release_has_exact_existing_id_source_channel_and_ten_asset_ids_and_bytes(self):
        original = self.release()
        recovery.verify_draft_inventory(original, self.plan)
        cases = [
            lambda release: release.update(id=release["id"] + 1),
            lambda release: release.update(tag_name="v0.0.0"),
            lambda release: release.update(target_commitish="main"),
            lambda release: release.update(prerelease=True),
            lambda release: release["assets"].pop(),
            lambda release: release["assets"].append(dict(release["assets"][0], name="extra.txt", id=99)),
            lambda release: release["assets"][1].update(id=release["assets"][1]["id"] + 1),
            lambda release: release["assets"][1].update(digest="sha256:" + "e" * 64),
            lambda release: release["assets"][1].update(size=1),
            lambda release: release["assets"][1].update(state="new"),
        ]
        for mutate in cases:
            changed = copy.deepcopy(original)
            mutate(changed)
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                recovery.verify_draft_inventory(changed, self.plan)
        with self.assertRaises(ValueError):
            recovery.verify_draft_inventory(original, self.plan, published=True)
        recovery.verify_draft_inventory({**original, "draft": False, "published_at": "2026-11-20T12:00:00Z"},
                                        self.plan, published=True)

    def test_all_ten_local_files_including_auxiliary_metadata_are_byte_verified(self):
        assets = copy.deepcopy(self.plan["android"]["assets"])
        for asset in assets:
            data = ("fixture:" + asset["name"]).encode()
            (self.root / asset["name"]).write_bytes(data)
            asset.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        self.assertEqual(10, len(recovery.verify_local_assets(self.root, assets)))
        sidecar = self.root / "app-arm64-v8a-release.apk.idsig"
        original = sidecar.read_bytes()
        sidecar.write_bytes(b"x" * len(original))
        with self.assertRaises(ValueError):
            recovery.verify_local_assets(self.root, assets)
        sidecar.write_bytes(original)
        (self.root / "unexpected.json").write_bytes(b"extra")
        with self.assertRaises(ValueError):
            recovery.verify_local_assets(self.root, assets)

    def test_original_provenance_keeps_signing_attempt_tree_checks_and_qualification(self):
        android = self.plan["android"]
        checks = recovery.check_document(self.passing_runs(), self.plan["sourceSha"], self.scope(), recovery.PUBLISHER)
        provenance = dict(schemaVersion=2, commit=self.plan["sourceSha"], sourceTree="b" * 40,
                          tag=android["tag"], versionName=android["version"], versionCode=android["versionCode"],
                          workflowRun=str(android["runId"]), workflowAttempt=str(android["runAttempt"]),
                          signingCertificateSha256=android["certificateSha256"], installationQualification="NOT_MEASURED",
                          assets=[{key: row[key] for key in ("name", "bytes", "sha256")}
                                  for row in android["assets"] if row["name"].endswith((".apk", ".aab"))],
                          exactCommitChecks=checks["workflows"])
        recovery.verify_provenance(provenance, self.plan, "b" * 40)
        for fields in [
            {"commit": "c" * 40}, {"sourceTree": "c" * 40}, {"workflowRun": "1"},
            {"workflowAttempt": "2"}, {"versionCode": 145},
            {"signingCertificateSha256": "d" * 64}, {"installationQualification": "PASSED"},
            {"exactCommitChecks": checks["workflows"][:-1]}, {"assets": provenance["assets"][:-1]},
        ]:
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                recovery.verify_provenance({**provenance, **fields}, self.plan, "b" * 40)

    def zip_file(self, entries):
        archive = self.root / "input.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for name, data in entries:
                output.writestr(name, data)
        return archive

    def test_safe_zip_preserves_windows_evidence_but_android_requires_exact_set(self):
        entries = [("payload.zip", b"verified"), ("setup.png", b"screenshot"),
                   ("v17-transition/smoke-results.json", b"{}")]
        archive = self.zip_file(entries)
        names = recovery.safe_extract(archive, self.root / "windows")
        self.assertEqual({name for name, _ in entries}, names)
        self.assertEqual(b"{}", (self.root / "windows/v17-transition/smoke-results.json").read_bytes())
        with self.assertRaises(ValueError):
            recovery.safe_extract(archive, self.root / "android", allowed_files=recovery.ANDROID_NAMES)

    def test_safe_zip_rejects_traversal_links_duplicates_and_size_overflow(self):
        names = ["../escape", "/escape", "dir/../../escape", "C:/escape", "dir\\escape", "a//b", "a/./b"]
        for index, name in enumerate(names):
            archive = self.zip_file([(name, b"data")])
            with self.subTest(name=name), self.assertRaises(ValueError):
                recovery.safe_extract(archive, self.root / ("unsafe-" + str(index)))
        link = zipfile.ZipInfo("link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive = self.zip_file([(link, b"../outside")])
        with self.assertRaises(ValueError):
            recovery.safe_extract(archive, self.root / "links")
        with self.assertWarns(UserWarning):
            archive = self.zip_file([("same", b"first"), ("same", b"second")])
        with self.assertRaises(ValueError):
            recovery.safe_extract(archive, self.root / "duplicates")
        archive = self.zip_file([("large", b"four")])
        with mock.patch.object(recovery, "MAX_UNPACKED_BYTES", 3), self.assertRaises(ValueError):
            recovery.safe_extract(archive, self.root / "oversized")

    def fake_gh(self, source):
        executable = self.root / "gh"
        executable.write_text("#!/usr/bin/env python3\n" + source + "\n")
        executable.chmod(0o755)
        return mock.patch.dict(os.environ, {"PATH": str(self.root) + os.pathsep + os.environ["PATH"]})

    def test_stream_download_enforces_actual_bytes_digest_exit_and_deadline(self):
        data = b"retained archive bytes"
        digest = hashlib.sha256(data).hexdigest()
        destination = self.root / "download.zip"
        with self.fake_gh("import os\nos.write(1, " + repr(data) + ")"):
            recovery.stream_download("repos/example/repo/actions/artifacts/1/zip", destination, len(data), digest, timeout=5)
            self.assertEqual(data, destination.read_bytes())
            destination.unlink()
            for size, checksum in [(len(data) - 1, digest), (len(data) + 1, digest), (len(data), "0" * 64)]:
                with self.subTest(size=size, checksum=checksum), self.assertRaises(ValueError):
                    recovery.stream_download("repos/example/repo/actions/artifacts/1/zip", destination, size, checksum, timeout=5)
                self.assertFalse(destination.exists())
        with self.fake_gh("import sys\nsys.exit(1)"), self.assertRaises(ValueError):
            recovery.stream_download("repos/example/repo/actions/artifacts/1/zip", destination, len(data), digest, timeout=5)
        with self.fake_gh("import time\ntime.sleep(60)"), self.assertRaises(ValueError):
            recovery.stream_download("repos/example/repo/actions/artifacts/1/zip", destination, len(data), digest, timeout=0.05)

    def test_failed_prepublication_verification_or_main_guard_never_mutates(self):
        patch = mock.Mock()
        with self.assertRaises(ValueError):
            recovery.promote_existing(mock.Mock(side_effect=ValueError("changed bytes")), patch, before_write=mock.Mock())
        patch.assert_not_called()
        guard = mock.Mock(side_effect=ValueError("main advanced"))
        with self.assertRaises(ValueError):
            recovery.promote_existing(mock.Mock(return_value={"draft": True}), patch, before_write=guard)
        patch.assert_not_called()

    def test_lost_publication_ack_is_read_reconciled_once_without_replay(self):
        events = []
        def verify(published):
            events.append(("verify", published))
            return {"draft": not published, "id": self.plan["android"]["releaseId"]}
        def patch():
            events.append(("patch", self.plan["android"]["releaseId"]))
            raise subprocess.CalledProcessError(1, ["gh", "api"])
        result = recovery.promote_existing(verify, patch, before_write=lambda: events.append(("guard", True)))
        self.assertFalse(result["draft"])
        self.assertEqual([("verify", False), ("guard", True),
                          ("patch", self.plan["android"]["releaseId"]), ("verify", True)], events)

    def test_already_published_is_read_only_and_unresolved_write_does_not_retry(self):
        patch = mock.Mock()
        verify = mock.Mock(return_value={"draft": False})
        recovery.promote_existing(verify, patch, before_write=mock.Mock())
        patch.assert_not_called()
        self.assertEqual([mock.call(False), mock.call(True)], verify.call_args_list)
        verify = mock.Mock(side_effect=[{"draft": True}, ValueError("publication outcome unresolved")])
        with self.assertRaises(ValueError):
            recovery.promote_existing(verify, patch, before_write=mock.Mock())
        patch.assert_called_once_with()

    def test_android_phase_patches_only_the_reviewed_release_after_verification(self):
        subject = recovery.Recovery.__new__(recovery.Recovery)
        subject.plan, subject.tree = self.plan, "b" * 40
        subject.gates, subject.current_main = mock.Mock(), mock.Mock()
        subject.verify_android = mock.Mock(side_effect=[{"draft": True}, {"draft": False}])
        def retained(item, directory, **kwargs):
            directory.mkdir()
            (directory / "provenance.json").write_text("{}")
        subject.artifact = mock.Mock(side_effect=retained)
        with mock.patch.object(recovery, "verify_local_assets"), mock.patch.object(recovery, "verify_provenance"), \
                mock.patch.object(recovery.subprocess, "run") as command:
            subject.android()
        subject.gates.assert_called_once_with()
        subject.current_main.assert_called_once_with()
        self.assertEqual([False, True], [call.kwargs["published"] for call in subject.verify_android.call_args_list])
        command.assert_called_once()
        arguments = command.call_args.args[0]
        self.assertEqual(["gh", "api", "--method", "PATCH"], arguments[:4])
        self.assertEqual(f"repos/{self.plan['repository']}/releases/{self.plan['android']['releaseId']}", arguments[4])
        self.assertIn("draft=false", arguments)
        self.assertNotIn("POST", arguments)
        self.assertNotIn("upload", arguments)

    def test_workflow_does_not_hold_publisher_lock_while_waiting_for_main_checks(self):
        import check_ci_policy
        text = (ROOT / ".github/workflows/recover-verified-android144-gateway18.yml").read_text()
        self.assertEqual([], check_ci_policy.check_text(text, ".github/workflows/recover-verified-android144-gateway18.yml"))
        header, jobs = text.split("\njobs:", 1)
        gate = jobs.split("\n  gate:", 1)[1].split("\n  android:", 1)[0]
        android = jobs.split("\n  android:", 1)[1].split("\n  gateway:", 1)[0]
        gateway = jobs.split("\n  gateway:", 1)[1]
        self.assertIn("name: " + recovery.WORKFLOW, header)
        self.assertNotIn("group: signed-release", header)
        self.assertNotIn("concurrency:", gate)
        self.assertIn("timeout-minutes: 125", gate)
        self.assertIn("gate --timeout 6900", gate)
        self.assertIn("needs: gate", android)
        self.assertIn("group: signed-release", android)
        self.assertIn("needs: [gate, android]", gateway)
        self.assertIn("group: gateway-release-publication", gateway)
        self.assertNotIn("id-token: write", text)
        self.assertNotIn("setup-android", text)

    def test_scope_retains_existing_duplicate_entries_without_adding_new_duplicates(self):
        original = self.scope()
        original["allowedPaths"].append(original["allowedPaths"][0])
        candidate = copy.deepcopy(original)
        candidate["changes"].append("Recover the retained publication")
        candidate["allowedPaths"] += sorted(recovery.NEW_PATHS)
        recovery.validate_scope(original, candidate, sorted(recovery.REPAIR_PATHS), self.plan)
        self.assertEqual(original["allowedPaths"], candidate["allowedPaths"][:len(original["allowedPaths"])])
        for rows in (candidate["allowedPaths"] + [candidate["allowedPaths"][-1]],
                     candidate["allowedPaths"][:-1] + [candidate["allowedPaths"][-2]],
                     list(dict.fromkeys(candidate["allowedPaths"]))):
            with self.subTest(paths=rows), self.assertRaises(ValueError):
                recovery.validate_scope(original, {**candidate, "allowedPaths": rows},
                                        sorted(recovery.REPAIR_PATHS), self.plan)

    def test_gateway_uses_original_source_only_after_fresh_android_verification(self):
        subject = recovery.Recovery.__new__(recovery.Recovery)
        subject.plan, subject.tree = self.plan, "b" * 40
        subject.output = self.root / "receipts"
        subject.base_receipt = {"candidateSourceSha": self.plan["sourceSha"], "recoveryCodeSha": "f" * 40}
        subject.gates, subject.current_main = mock.Mock(), mock.Mock()
        events = []
        subject.verify_android = mock.Mock(side_effect=lambda *args, **kwargs: events.append(("android", kwargs["published"])))
        def retained(item, directory, **kwargs):
            directory.mkdir(parents=True)
            if item is self.plan["android"]:
                (directory / "provenance.json").write_text("{}")
            else:
                for version, folder in ((item["version"], directory),
                                        (item["bridgeVersion"], directory / "v17-transition")):
                    folder.mkdir(exist_ok=True)
                    for name in (f"Gateway-{version}-windows-x64.zip", f"Gateway-{version}-windows-x64-setup.exe",
                                 "SHA256SUMS.txt", "smoke-results.json"):
                        (folder / name).write_bytes(b"verified fixture")
        subject.artifact = mock.Mock(side_effect=retained)
        def execute(arguments, **kwargs):
            events.append(("command", arguments[0]))
            if arguments[0] == "bash":
                self.assertEqual(["bash", "scripts/publish_gateway_windows.sh"], arguments)
                self.assertEqual(self.plan["sourceSha"], kwargs["env"]["GITHUB_SHA"])
                self.assertEqual("refs/heads/main", kwargs["env"]["GITHUB_REF"])
                self.assertEqual("candidate", kwargs["cwd"].name)
        def published(plan, path):
            tag = path.rsplit("/", 1)[1]
            version = tag[len("gateway-v"):]
            names = [f"Gateway-{version}-windows-x64.zip", f"Gateway-{version}-windows-x64-setup.exe",
                     "SHA256SUMS.txt", "smoke-results.json"]
            assets = [dict(id=index + 1, name=name, state="uploaded", size=len(b"verified fixture"),
                           digest="sha256:" + hashlib.sha256(b"verified fixture").hexdigest())
                      for index, name in enumerate(names)]
            return dict(id=10, tag_name=tag, target_commitish=plan["sourceSha"],
                        draft=False, prerelease=False, published_at="2026-11-20T12:00:00Z",
                        html_url="https://github.com/example/release/" + tag, assets=assets)
        with mock.patch.object(recovery, "verify_local_assets"), mock.patch.object(recovery, "verify_provenance"), \
                mock.patch.object(recovery.subprocess, "run", side_effect=execute), \
                mock.patch.object(recovery, "api", side_effect=published), \
                mock.patch.dict(os.environ, {"GITHUB_SHA": "f" * 40}):
            subject.gateway()
        self.assertEqual(("android", True), events[0])
        self.assertIn(("command", "bash"), events)
        subject.current_main.assert_called_once_with()
        receipt = json.loads((subject.output / "gateway-published.json").read_text())
        self.assertEqual(self.plan["sourceSha"], receipt["candidateSourceSha"])
        self.assertEqual("f" * 40, receipt["recoveryCodeSha"])
        self.assertEqual(["gateway-v18.0.0", "gateway-v17.0.2"], [row["tag"] for row in receipt["releases"]])

    def test_final_gateway_read_rejects_concurrent_asset_changes_after_script_success(self):
        version = self.plan["windows"]["version"]
        names = [f"Gateway-{version}-windows-x64.zip", f"Gateway-{version}-windows-x64-setup.exe",
                 "SHA256SUMS.txt", "smoke-results.json"]
        assets = []
        for index, name in enumerate(names):
            data = ("original retained Windows file:" + name).encode()
            (self.root / name).write_bytes(data)
            assets.append(dict(id=index + 1, name=name, state="uploaded", size=len(data),
                               digest="sha256:" + hashlib.sha256(data).hexdigest()))
        self.assertEqual(4, len(recovery.verify_gateway_assets({"assets": assets}, self.root, version)))
        for changed in [
            [], assets[:-1], assets + [dict(assets[0], id=5, name="extra")],
            [{**assets[0], "digest": "sha256:" + "0" * 64}, *assets[1:]],
            [{**assets[0], "size": 1}, *assets[1:]],
            [{**assets[0], "state": "new"}, *assets[1:]],
            [{**assets[0], "id": assets[1]["id"]}, *assets[1:]],
        ]:
            with self.subTest(assets=changed), self.assertRaises(ValueError):
                recovery.verify_gateway_assets({"assets": changed}, self.root, version)


if __name__ == "__main__":
    unittest.main()
