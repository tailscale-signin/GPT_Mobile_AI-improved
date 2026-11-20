#!/usr/bin/env python3
"""Recover a reviewed publication from retained, immutable artifacts; never rebuild."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import zipfile

import await_release_checks as checks
from release_evidence import artifact_inventory, exact_check_evidence
import verify_published_release as publication

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = "Recover verified Android 144 and Gateway 18 publication"
PUBLISHER = "Publish Signed Release"
WINDOWS_WORKFLOW = "Gateway Windows installer"
REPAIR_PATHS = frozenset({
    ".github/workflows/recover-verified-android144-gateway18.yml",
    "scripts/recover_release_publication.py",
    "scripts/tests/test_release_recovery.py",
    "scripts/test_verify_published_release_draft.py",
    "release-recovery.json",
    "scripts/verify_published_release.py",
    "scripts/tests/test_published_release.py",
    "release-scope.json",
    "docs/architecture/repository-index.json",
})
NEW_PATHS = frozenset({
    ".github/workflows/recover-verified-android144-gateway18.yml",
    "scripts/recover_release_publication.py",
    "scripts/tests/test_release_recovery.py",
    "scripts/test_verify_published_release_draft.py",
    "release-recovery.json",
})
TRIGGER_PATHS = frozenset({
    ".github/workflows/recover-verified-android144-gateway18.yml",
    "scripts/recover_release_publication.py",
    "scripts/verify_published_release.py",
    "release-recovery.json",
})
ANDROID_NAMES = frozenset({
    "app-arm64-v8a-release.apk", "app-arm64-v8a-release.apk.idsig",
    "app-x86_64-release.apk", "app-x86_64-release.apk.idsig",
    "app-universal-release.apk", "app-universal-release.apk.idsig",
    "app-release.aab", "optional-payload-inventory.json", "provenance.json", "SHA256SUMS.txt",
})
REQUIRED = frozenset({
    "PR Validation", "Build Android APK", "Post-merge correctness", "CodeQL Advanced",
    "v0.9.50 Android acceptance", "Gateway stability", "Gateway Windows installer",
    "Development environments",
})
MAX_ARCHIVE_BYTES = 2 * 1024**3
MAX_UNPACKED_BYTES = 2 * 1024**3
MAX_MEMBER_BYTES = 1024**3
MAX_MEMBERS = 4096


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive(value):
    return type(value) is int and value > 0


def sha(value, length=40):
    return isinstance(value, str) and re.fullmatch("[0-9a-f]{" + str(length) + "}", value) is not None


def inventory_row(row, *, archive=False):
    fields = {"id", "name", "bytes", "sha256"}
    require(isinstance(row, dict) and set(row) == fields, "Invalid artifact or asset fields")
    require(positive(row["id"]) and positive(row["bytes"]) and row["bytes"] <= MAX_ARCHIVE_BYTES,
            "Invalid artifact or asset bounds")
    require(sha(row["sha256"], 64), "Invalid artifact or asset digest")
    require(isinstance(row["name"], str) and re.fullmatch(r"[A-Za-z0-9_.+-]{1,200}", row["name"]),
            "Unsafe artifact or asset name")


def validate_plan(plan):
    require(isinstance(plan, dict) and set(plan) == {"schemaVersion", "repository", "sourceSha", "android", "windows"},
            "Unknown recovery request fields")
    require(type(plan["schemaVersion"]) is int and plan["schemaVersion"] == 1, "Unsupported recovery request")
    require(isinstance(plan["repository"], str) and re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", plan["repository"]),
            "Invalid repository")
    require(sha(plan["sourceSha"]), "Immutable candidate source is required")
    android, windows = plan["android"], plan["windows"]
    require(isinstance(android, dict) and set(android) == {
        "version", "versionCode", "tag", "releaseId", "runId", "runAttempt",
        "validationJobId", "buildJobId", "artifact", "certificateSha256", "assets",
    }, "Invalid Android recovery fields")
    require(isinstance(windows, dict) and set(windows) == {
        "version", "bridgeVersion", "runId", "runAttempt", "artifact",
    }, "Invalid Windows recovery fields")
    for item in (android, windows):
        require(isinstance(item["version"], str) and re.fullmatch(r"[0-9]+(?:\.[0-9]+){2,3}", item["version"]),
                "Invalid release version")
        require(positive(item["runId"]) and positive(item["runAttempt"]), "Invalid retained run identity")
        inventory_row(item["artifact"], archive=True)
    require(android["tag"] == "v" + android["version"] and positive(android["versionCode"]),
            "Android version and tag disagree")
    require(all(positive(android[field]) for field in ("releaseId", "validationJobId", "buildJobId")),
            "Invalid original publication identity")
    require(sha(android["certificateSha256"], 64), "Original signing certificate is required")
    require(windows["version"] == "18.0.0" and windows["bridgeVersion"] == "17.0.2",
            "This recovery supports the reviewed v18 and v17 transition")
    assets = android["assets"]
    require(isinstance(assets, list) and len(assets) == len(ANDROID_NAMES), "The complete ten-asset inventory is required")
    for row in assets:
        inventory_row(row)
    require({row["name"] for row in assets} == ANDROID_NAMES and len({row["id"] for row in assets}) == len(assets),
            "Duplicate or missing Android asset identity")
    require(android["artifact"]["name"] == "signed-release-" + plan["sourceSha"]
            and windows["artifact"]["name"] == "Gateway-Windows-x64", "Unexpected retained artifact names")
    return plan


def git(*args, cwd=ROOT):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True, stderr=subprocess.PIPE, timeout=60).strip()


def api(plan, path):
    return publication.gh_json("api", "repos/" + plan["repository"] + "/" + path)


def save(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, indent=2) + "\n")


def intent(event, ref, changed):
    return event == "push" and ref == "refs/heads/main" and bool(set(changed) & TRIGGER_PATHS)


def validate_scope(original, recovery, changed, plan):
    require(set(changed) <= REPAIR_PATHS, "Recovery contains product or unreviewed changes")
    require(NEW_PATHS <= set(changed), "Recovery request and implementation must be reviewed together")
    for key in set(original) | set(recovery):
        if key not in {"changes", "allowedPaths"}:
            require(original.get(key) == recovery.get(key), "Recovery changed release qualification or publication intent")
    require(recovery.get("changes", [])[:len(original["changes"])] == original["changes"]
            and len(recovery["changes"]) == len(original["changes"]) + 1
            and isinstance(recovery["changes"][-1], str) and bool(recovery["changes"][-1].strip()),
            "Recovery must add exactly one truthful scope note")
    require(recovery["allowedPaths"][:len(original["allowedPaths"])] == original["allowedPaths"]
            and len(recovery["allowedPaths"]) == len(original["allowedPaths"]) + len(NEW_PATHS)
            and set(recovery["allowedPaths"][len(original["allowedPaths"]):]) == NEW_PATHS,
            "Recovery must preserve existing scope entries and append only its five explicit paths")
    require(REQUIRED <= set(checks.required_workflows(original))
            and REQUIRED <= set(checks.required_workflows(recovery)), "All eight independent workflows remain required")
    require(original["version"] == plan["android"]["version"]
            and original["versionCode"] == plan["android"]["versionCode"]
            and original["publicationRequest"] == {
                "android": plan["android"]["version"], "versionCode": plan["android"]["versionCode"],
                "gateway": plan["windows"]["version"],
            }, "Recovery request disagrees with original publication intent")


def validate_run(run, plan, item, name, path, conclusion):
    require(isinstance(run, dict) and run.get("id") == item["runId"]
            and run.get("run_attempt") == item["runAttempt"]
            and run.get("head_sha") == plan["sourceSha"] and run.get("head_branch") == "main"
            and run.get("event") == "push" and run.get("name") == name and run.get("path") == path
            and run.get("status") == "completed" and run.get("conclusion") == conclusion
            and run.get("repository", {}).get("full_name") == plan["repository"]
            and run.get("head_repository", {}).get("full_name") == plan["repository"],
            "Original workflow identity, attempt or outcome changed")


def validate_job(job, plan, item, expected_id, name, conclusion):
    require(isinstance(job, dict) and job.get("id") == expected_id and job.get("run_id") == item["runId"]
            and job.get("run_attempt") == item["runAttempt"] and job.get("head_sha") == plan["sourceSha"]
            and job.get("head_branch") == "main" and job.get("name") == name
            and job.get("status") == "completed" and job.get("conclusion") == conclusion,
            "Original job identity or outcome changed")


def validate_build_steps(steps):
    require(isinstance(steps, list), "Original build steps are missing")
    numbered = {row.get("number"): row for row in steps}
    require(len(numbered) == len(steps), "Original build steps are ambiguous")
    for number in range(1, 16):
        require(numbered.get(number, {}).get("status") == "completed"
                and numbered[number].get("conclusion") == "success",
                "An original pre-publication step did not succeed")
    expected = {
        8: "Require green main checks on this exact candidate",
        10: "Require qualified QNN dispatch provenance",
        11: "Shrink release code in a separate process",
        12: "Build release artifacts",
        13: "Sign and verify APKs and bundle",
        14: "Run actions/attest@59d89421af93a897026c735860bf21b6eb4f7b26",
        15: "Run actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
        16: "Publish new immutable release",
    }
    require(all(numbered.get(n, {}).get("name") == name for n, name in expected.items()),
            "Original build/sign/attest/upload sequence changed")
    failed = [row for row in steps if row.get("conclusion") not in {"success", "skipped"}]
    require(len(failed) == 1 and failed[0].get("number") == 16
            and failed[0].get("status") == "completed" and failed[0].get("conclusion") == "failure",
            "Only the known original publication failure is recoverable")


def validate_artifact(actual, expected, item, source):
    require(isinstance(actual, dict) and actual.get("id") == expected["id"]
            and actual.get("name") == expected["name"] and actual.get("size_in_bytes") == expected["bytes"]
            and actual.get("digest") == "sha256:" + expected["sha256"] and actual.get("expired") is False,
            "Retained artifact identity, bytes, digest or expiry changed")
    run = actual.get("workflow_run", {})
    require(run.get("id") == item["runId"] and run.get("head_sha") == source and run.get("head_branch") == "main"
            and positive(run.get("repository_id")) and run.get("repository_id") == run.get("head_repository_id"),
            "Retained artifact has another workflow source")


def prove_original(plan):
    android, windows = plan["android"], plan["windows"]
    original = api(plan, f"actions/runs/{android['runId']}")
    validate_run(original, plan, android, PUBLISHER, ".github/workflows/release-build.yml", "failure")
    validation = api(plan, f"actions/jobs/{android['validationJobId']}")
    build = api(plan, f"actions/jobs/{android['buildJobId']}")
    validate_job(validation, plan, android, android["validationJobId"], "Validate exact release commit", "success")
    validate_job(build, plan, android, android["buildJobId"], "Build, verify and publish", "failure")
    validate_build_steps(build.get("steps"))
    windows_run = api(plan, f"actions/runs/{windows['runId']}")
    validate_run(windows_run, plan, windows, WINDOWS_WORKFLOW,
                 ".github/workflows/gateway-windows-installer.yml", "success")
    for item in (android, windows):
        validate_artifact(api(plan, f"actions/artifacts/{item['artifact']['id']}"),
                          item["artifact"], item, plan["sourceSha"])
    return {
        "runId": android["runId"], "attempt": android["runAttempt"],
        "validationJobId": android["validationJobId"], "buildJobId": android["buildJobId"],
        "excludedOriginalPublisher": PUBLISHER,
        "reason": "Exact original validation and build/sign/attest/upload succeeded; only publication step 16 failed. "
                  "Recovery re-verifies every original byte and trusted attestation before any publication.",
        "windowsRunId": windows["runId"], "windowsAttempt": windows["runAttempt"],
    }


def latest_publisher_is_original(runs, plan):
    latest = checks.latest_runs(runs, plan["sourceSha"], [PUBLISHER], main_push=[PUBLISHER]).get(PUBLISHER, {})
    require(latest.get("id") == plan["android"]["runId"]
            and latest.get("run_attempt") == plan["android"]["runAttempt"]
            and latest.get("status") == "completed" and latest.get("conclusion") == "failure",
            "Original publisher exclusion no longer identifies the reviewed failed attempt")


def check_document(runs, source, scope, publisher, *, plan=None):
    if plan is not None:
        latest_publisher_is_original(runs, plan)
    observed = checks.observed_main_workflows(runs, source, publisher)
    required = list(dict.fromkeys([*checks.required_workflows(scope), *observed]))
    return checks.evidence_document(runs, source, required, main_push=required)


def wait_checks(plan, source, scope, publisher, output, timeout, *, original=False):
    end = time.monotonic() + timeout
    while True:
        runs = checks.fetch_runs(plan["repository"], source, os.environ["GH_TOKEN"])
        document = check_document(runs, source, scope, publisher, plan=plan if original else None)
        save(output, document)
        require(not document["failed"], "A required exact-source check failed: " + ", ".join(document["failed"]))
        if not document["pending"]:
            exact_check_evidence(document, source)
            if original:
                windows = next(row for row in document["workflows"] if row["name"] == WINDOWS_WORKFLOW)
                require(windows["runId"] == plan["windows"]["runId"]
                        and windows["attempt"] == plan["windows"]["runAttempt"],
                        "The retained Windows artifact is not from the qualifying check attempt")
            return document
        require(time.monotonic() < end, "Timed out waiting for complete exact-source checks")
        print("Waiting for " + source + ": " + ", ".join(document["pending"]), flush=True)
        time.sleep(min(30, max(0, end - time.monotonic())))


def stream_download(endpoint, destination, expected_bytes, expected_digest, *, timeout=1200):
    """Bound gh's trusted redirect transport by bytes and elapsed time."""
    require(positive(expected_bytes) and expected_bytes <= MAX_ARCHIVE_BYTES and sha(expected_digest, 64),
            "Invalid download bounds")
    deadline, size, digest = time.monotonic() + timeout, 0, hashlib.sha256()
    destination.parent.mkdir(parents=True, exist_ok=True)
    # The artifact endpoint returns an HTTPS 302 to object storage. gh api can
    # reject the binary redirect, so use curl's bounded streaming transport.
    # curl does not forward Authorization to a different host with --location.
    require(re.fullmatch(
        r"repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(?:actions/artifacts/[0-9]+/zip|releases/assets/[0-9]+)",
        endpoint) is not None, "Unapproved release download endpoint")
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen([
            "curl", "--fail", "--location", "--silent", "--show-error",
            "--proto", "=https", "--proto-redir", "=https",
            "--connect-timeout", "30", "--max-time", str(max(1, timeout)),
            "--header", ("Accept: application/octet-stream" if "/releases/assets/" in endpoint
                         else "Accept: application/vnd.github+json"),
            "--header", "Authorization: Bearer " + os.environ["GH_TOKEN"],
            "https://api.github.com/" + endpoint,
        ], stdout=subprocess.PIPE, stderr=errors)
        try:
            with selectors.DefaultSelector() as ready, destination.open("xb") as output:
                ready.register(process.stdout, selectors.EVENT_READ)
                while True:
                    remaining = deadline - time.monotonic()
                    require(remaining > 0, "Download exceeded its deadline")
                    if not ready.select(min(30, remaining)):
                        continue
                    block = os.read(process.stdout.fileno(), 1024 * 1024)
                    if not block:
                        break
                    size += len(block)
                    require(size <= expected_bytes, "Download exceeded its declared size")
                    digest.update(block)
                    output.write(block)
            status = process.wait(timeout=max(1, deadline - time.monotonic()))
            if status != 0:
                errors.seek(0)
                diagnostic = errors.read(2048).decode("utf-8", "replace").strip()
                diagnostic = re.sub(r"https?://[^\s]+", "[redacted URL]", diagnostic)
                raise ValueError(f"Release download transport failed (curl exit {status}): {diagnostic}")
            require(size == expected_bytes and digest.hexdigest() == expected_digest,
                    "Downloaded bytes disagree with the reviewed digest")
        except BaseException:
            destination.unlink(missing_ok=True)
            raise
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            if process.stdout:
                process.stdout.close()


def safe_extract(archive, destination, *, allowed_files=None):
    """Extract only bounded regular data files, never paths or executable links."""
    require(not destination.exists() or not any(destination.iterdir()), "Extraction destination must be empty")
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        require(0 < len(entries) <= MAX_MEMBERS, "ZIP member count exceeds the bound")
        seen, files, total = set(), [], 0
        for row in entries:
            name = row.filename
            parts = PurePosixPath(name).parts
            require(name and "\x00" not in name and "\\" not in name and not name.startswith("/")
                    and ":" not in name and all(part not in {"", ".", ".."} for part in name.rstrip("/").split("/")),
                    "Unsafe ZIP path")
            canonical = "/".join(parts).rstrip("/")
            require(canonical not in seen, "Duplicate ZIP member")
            seen.add(canonical)
            mode = stat.S_IFMT(row.external_attr >> 16)
            require(not row.flag_bits & 1 and mode in {0, stat.S_IFREG, stat.S_IFDIR},
                    "Encrypted or non-regular ZIP member")
            require((row.is_dir() and mode in {0, stat.S_IFDIR}) or (not row.is_dir() and mode in {0, stat.S_IFREG}),
                    "ZIP member type disagrees with its path")
            if row.is_dir():
                continue
            require(0 < row.file_size <= MAX_MEMBER_BYTES, "ZIP member size exceeds the bound")
            total += row.file_size
            require(total <= MAX_UNPACKED_BYTES, "ZIP expansion exceeds the bound")
            files.append(row)
        names = {row.filename for row in files}
        require(allowed_files is None or names == set(allowed_files), "ZIP does not contain the exact expected asset set")
        require(shutil.disk_usage(destination).free >= total + 64 * 1024**2, "Insufficient extraction space")
        for row in files:
            target = destination.joinpath(*PurePosixPath(row.filename).parts)
            require(not any(parent.is_symlink() for parent in target.parents), "Unsafe extraction parent")
            target.parent.mkdir(parents=True, exist_ok=True)
            written = 0
            with source.open(row) as incoming, target.open("xb") as outgoing:
                while block := incoming.read(1024 * 1024):
                    written += len(block)
                    require(written <= row.file_size, "ZIP member exceeded its declared size")
                    outgoing.write(block)
            require(written == row.file_size, "Truncated ZIP member")
        return names


def verify_local_assets(directory, expected):
    actual = artifact_inventory(list(directory.iterdir()))
    wanted = [{key: row[key] for key in ("name", "bytes", "sha256")} for row in expected]
    require(sorted(actual, key=lambda x: x["name"]) == sorted(wanted, key=lambda x: x["name"]),
            "Retained Android bytes disagree with the reviewed complete inventory")
    return actual


def verify_draft_inventory(release, plan, *, published=False):
    android = plan["android"]
    publication.release_identity(release, android["tag"], plan["sourceSha"], android["releaseId"])
    require(release.get("target_commitish") == plan["sourceSha"] and release.get("prerelease") is False,
            "Existing release target or channel changed")
    require(not published or release.get("draft") is False and bool(release.get("published_at")),
            "The exact release is not published")
    rows = release.get("assets")
    require(isinstance(rows, list) and len(rows) == len(android["assets"]), "Release asset set changed")
    by_name = {row.get("name"): row for row in rows}
    require(len(by_name) == len(rows), "Duplicate release asset names")
    for expected in android["assets"]:
        actual = by_name.get(expected["name"], {})
        require(actual.get("id") == expected["id"] and actual.get("size") == expected["bytes"]
                and actual.get("digest") == "sha256:" + expected["sha256"] and actual.get("state") == "uploaded",
                "Release asset identity or bytes changed: " + expected["name"])



def verify_gateway_assets(release, directory, version):
    names = [f"Gateway-{version}-windows-x64.zip", f"Gateway-{version}-windows-x64-setup.exe",
             "SHA256SUMS.txt", "smoke-results.json"]
    expected = artifact_inventory([directory / name for name in names])
    rows = release.get("assets")
    require(isinstance(rows, list) and len(rows) == len(names), "Published Gateway asset set changed")
    remote = {row.get("name"): row for row in rows}
    require(len(remote) == len(names) and len({row.get("id") for row in rows}) == len(names)
            and all(positive(row.get("id")) for row in rows), "Published Gateway asset identities changed")
    for item in expected:
        actual = remote.get(item["name"], {})
        require(actual.get("size") == item["bytes"] and actual.get("digest") == "sha256:" + item["sha256"]
                and actual.get("state") == "uploaded", "Published Gateway bytes changed: " + item["name"])
    return expected

def verify_provenance(provenance, plan, tree):
    android = plan["android"]
    expected = {
        "schemaVersion": 2, "commit": plan["sourceSha"], "sourceTree": tree, "tag": android["tag"],
        "versionName": android["version"], "versionCode": android["versionCode"],
        "workflowRun": str(android["runId"]), "workflowAttempt": str(android["runAttempt"]),
        "signingCertificateSha256": android["certificateSha256"], "installationQualification": "NOT_MEASURED",
    }
    require(all(provenance.get(key) == value for key, value in expected.items()),
            "Original provenance identity changed")
    core = [{key: row[key] for key in ("name", "bytes", "sha256")} for row in android["assets"]
            if row["name"].endswith((".apk", ".aab"))]
    require(sorted(provenance.get("assets", []), key=lambda row: row["name"]) == sorted(core, key=lambda row: row["name"]),
            "Original package provenance disagrees with the retained artifact")
    rows = provenance.get("exactCommitChecks")
    require(isinstance(rows, list), "Original exact-source evidence is missing")
    document = {"sourceSha": plan["sourceSha"], "status": "PASSED",
                "requiredWorkflows": [row.get("name") for row in rows], "workflows": rows}
    exact_check_evidence(document, plan["sourceSha"])
    require(REQUIRED <= set(document["requiredWorkflows"]), "Original provenance omits mandatory gates")


def promote_existing(verify, patch, *, before_write):
    """A lost write acknowledgement is reconciled once; the mutation is never replayed."""
    state = verify(False)
    if state["draft"]:
        before_write()
        try:
            patch()
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError):
            print("Publication acknowledgement unavailable; reconciling the same release ID.", flush=True)
    return verify(True)


class Recovery:
    def __init__(self, plan, output, timeout):
        self.plan, self.output, self.timeout = plan, output.resolve(), timeout
        self.code_sha = os.environ.get("GITHUB_SHA", "")
        require(os.environ.get("GITHUB_EVENT_NAME") == "push" and os.environ.get("GITHUB_REF") == "refs/heads/main"
                and os.environ.get("GITHUB_REPOSITORY") == plan["repository"]
                and os.environ.get("GITHUB_WORKFLOW") == WORKFLOW,
                "Recovery requires the reviewed main push workflow")
        require(sha(self.code_sha) and self.code_sha != plan["sourceSha"]
                and git("rev-parse", "HEAD") == self.code_sha, "Recovery code checkout changed")
        subprocess.run(["git", "merge-base", "--is-ancestor", plan["sourceSha"], self.code_sha],
                       cwd=ROOT, check=True, timeout=60)
        changed = git("diff", "--name-only", plan["sourceSha"], self.code_sha, "--").splitlines()
        self.original_scope = json.loads(git("show", plan["sourceSha"] + ":release-scope.json"))
        self.scope = json.loads((ROOT / "release-scope.json").read_text())
        validate_scope(self.original_scope, self.scope, changed, plan)
        self.tree = git("rev-parse", plan["sourceSha"] + "^{tree}")
        self.base_receipt = {
            "schemaVersion": 1, "repository": plan["repository"],
            "candidateSourceSha": plan["sourceSha"], "candidateSourceTree": self.tree,
            "recoveryCodeSha": self.code_sha, "recoveryRunId": os.environ["GITHUB_RUN_ID"],
            "recoveryRunAttempt": os.environ["GITHUB_RUN_ATTEMPT"],
            "originalSigningRunId": plan["android"]["runId"],
            "originalSigningRunAttempt": plan["android"]["runAttempt"],
            "installationQualification": "NOT_MEASURED",
        }

    def current_main(self):
        require(api(self.plan, "git/ref/heads/main").get("object", {}).get("sha") == self.code_sha,
                "Main advanced; recovery must be reviewed against the new main")

    def gates(self):
        self.current_main()
        proof = prove_original(self.plan)
        recovery = wait_checks(self.plan, self.code_sha, self.scope, WORKFLOW,
                               self.output / "recovery-code-checks.json", self.timeout)
        candidate = wait_checks(self.plan, self.plan["sourceSha"], self.original_scope, PUBLISHER,
                                self.output / "candidate-checks.json", self.timeout, original=True)
        self.current_main()
        save(self.output / "gates.json", {**self.base_receipt, "status": "GATES_PASSED",
                                         "originalPublicationFailure": proof,
                                         "candidateChecks": candidate, "recoveryCodeChecks": recovery})
        return proof

    def artifact(self, item, destination, *, android=False):
        expected = item["artifact"]
        validate_artifact(api(self.plan, f"actions/artifacts/{expected['id']}"),
                          expected, item, self.plan["sourceSha"])
        require(shutil.disk_usage(destination.parent).free >= expected["bytes"] + MAX_UNPACKED_BYTES,
                "Insufficient artifact verification space")
        archive = destination.parent / (str(expected["id"]) + ".zip")
        stream_download(f"repos/{self.plan['repository']}/actions/artifacts/{expected['id']}/zip",
                        archive, expected["bytes"], expected["sha256"])
        safe_extract(archive, destination, allowed_files=ANDROID_NAMES if android else None)
        archive.unlink()

    def verify_android(self, retained, *, published):
        android = self.plan["android"]
        root = "repos/" + self.plan["repository"]
        state = publication.resolve_release(root, android["tag"], self.plan["sourceSha"],
                                            release_id=android["releaseId"], published=published)
        verify_draft_inventory(state, self.plan, published=published)
        phase = "published" if published else "uploaded"
        with tempfile.TemporaryDirectory(prefix="recovery-release-bytes-") as folder:
            downloaded = Path(folder)
            for asset in android["assets"]:
                stream_download(f"{root}/releases/assets/{asset['id']}", downloaded / asset["name"],
                                asset["bytes"], asset["sha256"])
            actual = verify_local_assets(downloaded, android["assets"])
            attested = publication.verify_asset_attestations(
                downloaded, actual, self.plan["repository"], self.plan["sourceSha"],
                self.plan["repository"] + "/.github/workflows/release-build.yml")
            require(len(attested) == len(android["assets"]), "Incomplete ten-asset trusted provenance")
        # The canonical verifier additionally reconciles exact tag identity and original
        # package/checksum provenance. It must retain the corrected ref/404-only behavior.
        command = [sys.executable, str(ROOT / "scripts/verify_published_release.py"),
                   "--repository", self.plan["repository"], "--artifacts", str(retained),
                   "--release-id", str(android["releaseId"]),
                   "--signer-workflow", self.plan["repository"] + "/.github/workflows/release-build.yml",
                   "--output", str(self.output / (phase + "-canonical.json"))]
        if published:
            command.append("--published")
        subprocess.run(command, cwd=ROOT, check=True, timeout=2400)
        canonical = json.loads((self.output / (phase + "-canonical.json")).read_text())
        require(canonical.get("sourceSha") == self.plan["sourceSha"]
                and canonical.get("releaseId") == android["releaseId"]
                and canonical.get("status") == ("PUBLISHED_VERIFIED" if published else "UPLOAD_VERIFIED")
                and canonical.get("assetSourceVerified") is True,
                "Canonical publication reconciliation did not verify the original release")
        save(self.output / (phase + ".json"), {
            **self.base_receipt, "status": canonical["status"], "releaseId": android["releaseId"],
            "tag": android["tag"], "url": state.get("html_url"),
            "assets": android["assets"], "assetAttestations": attested,
            "assetVerificationScope": "ALL_TEN_PINNED_IDS_DOWNLOADED_BYTES_AND_ORIGINAL_TRUSTED_ATTESTATIONS",
            "canonical": canonical,
        })
        return state

    def android(self):
        self.gates()
        with tempfile.TemporaryDirectory(prefix="recovery-original-android-") as folder:
            retained = Path(folder) / "retained"
            self.artifact(self.plan["android"], retained, android=True)
            verify_local_assets(retained, self.plan["android"]["assets"])
            verify_provenance(json.loads((retained / "provenance.json").read_text()), self.plan, self.tree)

            def patch():
                subprocess.run(["gh", "api", "--method", "PATCH",
                                f"repos/{self.plan['repository']}/releases/{self.plan['android']['releaseId']}",
                                "-F", "draft=false", "-f", "make_latest=true", "--silent"],
                               check=True, timeout=90)

            promote_existing(lambda published: self.verify_android(retained, published=published),
                             patch, before_write=self.current_main)

    def gateway(self):
        self.gates()
        # A fresh complete Android reconciliation is required in this independently
        # locked job; an earlier job's output is not publication authority.
        with tempfile.TemporaryDirectory(prefix="recovery-gateway-") as folder:
            work = Path(folder)
            retained = work / "android"
            self.artifact(self.plan["android"], retained, android=True)
            verify_local_assets(retained, self.plan["android"]["assets"])
            verify_provenance(json.loads((retained / "provenance.json").read_text()), self.plan, self.tree)
            self.verify_android(retained, published=True)
            shutil.rmtree(retained)
            candidate = work / "candidate"
            subprocess.run(["git", "worktree", "add", "--detach", str(candidate), self.plan["sourceSha"]],
                           cwd=ROOT, check=True, timeout=120)
            try:
                destination = candidate / "dist/windows"
                destination.parent.mkdir(parents=True, exist_ok=True)
                self.artifact(self.plan["windows"], destination)
                windows = self.plan["windows"]
                for version, directory in ((windows["version"], destination),
                                           (windows["bridgeVersion"], destination / "v17-transition")):
                    for name in (f"Gateway-{version}-windows-x64.zip", f"Gateway-{version}-windows-x64-setup.exe",
                                 "SHA256SUMS.txt", "smoke-results.json"):
                        require((directory / name).is_file(), "Retained Windows publication payload is incomplete")
                self.current_main()
                # This child process publishes the original tested product source.
                # Recovery code/run identity remains distinct in the surrounding receipt.
                environment = {**os.environ, "GITHUB_SHA": self.plan["sourceSha"], "GITHUB_REF": "refs/heads/main"}
                subprocess.run(["bash", "scripts/publish_gateway_windows.sh"], cwd=candidate,
                               env=environment, check=True, timeout=6000)
                releases = []
                for version in (windows["version"], windows["bridgeVersion"]):
                    release = api(self.plan, "releases/tags/gateway-v" + version)
                    require(release.get("tag_name") == "gateway-v" + version
                            and release.get("target_commitish") == self.plan["sourceSha"]
                            and release.get("draft") is False and release.get("prerelease") is False
                            and bool(release.get("published_at")), "Gateway publication was not reconciled")
                    directory = destination if version == windows["version"] else destination / "v17-transition"
                    verify_gateway_assets(release, directory, version)
                    releases.append({"id": release["id"], "tag": release["tag_name"],
                                     "url": release.get("html_url"), "assets": [
                                         {key: row.get(key) for key in ("id", "name", "size", "digest")}
                                         for row in release["assets"]]})
                save(self.output / "gateway-published.json", {
                    **self.base_receipt, "status": "GATEWAY_AND_TRANSITION_PUBLISHED_VERIFIED",
                    "originalWindowsRunId": windows["runId"], "originalWindowsRunAttempt": windows["runAttempt"],
                    "artifact": windows["artifact"], "releases": releases,
                    "verificationScope": "ORIGINAL_SMOKE_AND_CHECKSUMS_EXACT_SOURCE_TAGS_PUBLIC_DOWNLOAD_BYTE_COMPARISON",
                })
            finally:
                subprocess.run(["git", "worktree", "remove", "--force", str(candidate)],
                               cwd=ROOT, check=False, timeout=120)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("intent", "gate", "android", "gateway"))
    parser.add_argument("--plan", type=Path, default=ROOT / "release-recovery.json")
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/release-recovery")
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    plan = validate_plan(json.loads(args.plan.read_text()))
    require(args.plan.resolve() == ROOT / "release-recovery.json", "Use the reviewed tracked recovery request")
    if args.phase == "intent":
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
        before = event.get("before", "")
        require(sha(before), "An immutable main push base is required")
        changed = git("diff", "--name-only", before, os.environ["GITHUB_SHA"], "--").splitlines()
        selected = intent(os.environ.get("GITHUB_EVENT_NAME"), os.environ.get("GITHUB_REF"), changed)
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            output.write("recover=" + str(selected).lower() + "\n")
        return
    require(0 < args.timeout <= 12600, "Invalid gate deadline")
    recovery = Recovery(plan, args.output, args.timeout)
    {"gate": recovery.gates, "android": recovery.android, "gateway": recovery.gateway}[args.phase]()


if __name__ == "__main__":
    main()
