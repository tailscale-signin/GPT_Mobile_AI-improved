#!/usr/bin/env python3
"""Recover the already-signed PR #806 release after a draft-tag lookup failure.

This procedure does not rebuild or replace assets, change signing secrets, or
infer success from a workflow dispatch. It fails closed on mismatched evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

from await_release_checks import fetch_runs, latest_runs, required_workflows
from release_evidence import artifact_inventory, certificate_sha, exact_check_evidence, file_digest, verify_uploaded_release

REPOSITORY = "tailscale-signin/GPT_Mobile_AI-improved"
SOURCE = "223b7c9f1dbf1ecaa77b65936ca112817cfb6a9b"
TAG = "v0.9.66.6"
RELEASE_ID = 437239302
SOURCE_RUN = 42314516219
SOURCE_JOB = 128245629284
ORIGINAL_ARTIFACT = "signed-release-" + SOURCE
BASE = "repos/" + REPOSITORY
REQUIRED_ASSETS = frozenset({
    "app-arm64-v8a-release.apk",
    "app-arm64-v8a-release.apk.idsig",
    "app-universal-release.apk",
    "app-universal-release.apk.idsig",
    "app-x86_64-release.apk",
    "app-x86_64-release.apk.idsig",
    "app-release.aab",
    "optional-payload-inventory.json",
    "provenance.json",
    "SHA256SUMS.txt",
})


def command(*args, input_text=None, timeout=900):
    result = subprocess.run(args, text=True, input=input_text,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError("Command failed: " + str(args[0]) + " / " + str(args[1:3]) +
                           "; exit=" + str(result.returncode) + "; check the workflow log")
    return result.stdout


def api(path):
    if not path.startswith(BASE + "/"):
        raise ValueError("Untrusted API path")
    return json.loads(command("gh", "api", path, timeout=90))


def assert_equal(observed, expected, label):
    if observed != expected:
        raise ValueError("Unexpected " + label)


def release_state():
    # Releases/tags/<name> returns 404 for an unpublished draft with an
    # unmaterialized tag. The immutable release ID is the correct authority.
    document = api(BASE + "/releases/" + str(RELEASE_ID))
    assert_equal(document.get("id"), RELEASE_ID, "release identity")
    assert_equal(document.get("tag_name"), TAG, "release tag identity")
    assert_equal(document.get("target_commitish"), SOURCE, "release target commit")
    assert_equal(document.get("prerelease"), False, "release prerelease status")
    all_releases = api(BASE + "/releases?per_page=100")
    duplicates = [row for row in all_releases if row.get("tag_name") == TAG]
    assert_equal([row.get("id") for row in duplicates], [RELEASE_ID], "duplicate release identities")
    return document


def main_head():
    ref = api(BASE + "/git/ref/heads/main")
    assert_equal(ref["object"]["sha"], SOURCE, "live main SHA")
    actual = command("git", "rev-parse", SOURCE + "^{tree}").strip()
    assert re.fullmatch(r"[0-9a-f]{40}", actual)
    source_version = command("git", "show", SOURCE + ":app/build.gradle.kts")
    assert len(re.findall(r'versionName = "0\.9\.66\.6"', source_version)) == 1
    assert len(re.findall(r'versionCode = 140\b', source_version)) == 1
    return actual


def original_build_receipts():
    run = api(BASE + "/actions/runs/" + str(SOURCE_RUN))
    assert_equal((run.get("head_sha"), run.get("event"), run.get("status")),
                 (SOURCE, "push", "completed"), "original publication run")
    assert_equal(run.get("run_attempt"), 1, "original run attempt")
    jobs = api(BASE + "/actions/runs/" + str(SOURCE_RUN) + "/jobs?per_page=100")
    matches = [job for job in jobs.get("jobs", []) if job.get("id") == SOURCE_JOB]
    if len(matches) != 1:
        raise ValueError("Original signed-build job not found")
    job = matches[0]
    if job.get("conclusion") != "failure":
        raise ValueError("Source publication did not have the expected resumable failure")
    by_name = {step["name"]: step["conclusion"] for step in job.get("steps", [])}
    necessary = [
        "Require green main checks on this exact candidate",
        "Configure release signing",
        "Require qualified QNN dispatch provenance",
        "Shrink release code in a separate process",
        "Build release artifacts",
        "Sign and verify APKs and bundle",
    ]
    if any(by_name.get(name) != "success" for name in necessary):
        raise ValueError("Source signed build or required checks did not complete successfully")
    if by_name.get("Publish new immutable release") != "failure":
        raise ValueError("Expected interrupted publication step not found")
    if not any("actions/attest@" in name and result == "success" for name, result in by_name.items()):
        raise ValueError("Missing original signed attestation")
    if not any("actions/upload-artifact@" in name and result == "success"
               for name, result in by_name.items()):
        raise ValueError("Missing original signed artifact upload")
    artifacts = api(BASE + "/actions/runs/" + str(SOURCE_RUN) + "/artifacts?per_page=100")
    rows = [item for item in artifacts.get("artifacts", []) if item.get("name") == ORIGINAL_ARTIFACT]
    if len(rows) != 1 or rows[0].get("expired") or rows[0].get("size_in_bytes", 0) <= 0:
        raise ValueError("Original signed artifacts unavailable")


def green_gate():
    scope = json.loads(Path("release-scope.json").read_text())
    required = list(dict.fromkeys([*required_workflows(scope), "Gateway stability"]))
    runs = fetch_runs(REPOSITORY, SOURCE, os.environ["GH_TOKEN"])
    latest = latest_runs(runs, SOURCE, required)
    failed, pending = [], []
    for name in required:
        run = latest.get(name)
        if run is None or run.get("status") != "completed":
            pending.append(name)
        elif run.get("conclusion") != "success":
            failed.append(name)
    return {"required": required, "failed": failed, "pending": pending,
            "runs": [{"name": name, "id": latest[name]["id"],
                      "attempt": latest[name].get("run_attempt", 1),
                      "conclusion": latest[name]["conclusion"]}
                     for name in required if name in latest]}


def download_asset(asset, destination):
    aid = asset.get("id")
    size = asset.get("size")
    if type(aid) is not int or type(size) is not int or not (0 < size <= 500_000_000):
        raise ValueError("Unexpected release asset ID or byte count")
    url = "https://api.github.com/" + BASE + "/releases/assets/" + str(aid)
    command("curl", "--fail", "--silent", "--show-error", "--location",
            "--retry", "2", "--connect-timeout", "30", "--max-time", "900",
            "-H", "Accept: application/octet-stream",
            "-H", "Authorization: Bearer " + os.environ["GH_TOKEN"],
            "--output", str(destination), url, timeout=960)
    if not destination.is_file() or destination.stat().st_size != size:
        raise ValueError("Downloaded release asset bytes do not match remote size")
    advertised_digest = asset.get("digest")
    if advertised_digest and advertised_digest != "sha256:" + file_digest(destination):
        raise ValueError("Downloaded release asset digest mismatch")


def signer(path, *, bundle=False):
    if bundle:
        output = command("keytool", "-printcert", "-jarfile", str(path), timeout=120)
        expression = r"SHA-?256:\s*([0-9A-Fa-f: ]{64,100})"
    else:
        executable = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "37.0.0" / "apksigner"
        output = command(str(executable), "verify", "--print-certs", str(path), timeout=120)
        expression = r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)"
    match = re.search(expression, output, re.IGNORECASE)
    if not match:
        raise ValueError("Could not read installed APK/AAB signing certificate")
    return certificate_sha(match.group(1))


def verify_original_and_remote(release, source_tree, work):
    originals = work / "original"
    originals.mkdir()
    command("gh", "run", "download", str(SOURCE_RUN), "--repo", REPOSITORY,
            "--name", ORIGINAL_ARTIFACT, "--dir", str(originals), timeout=1200)
    source_files = {path.name: path for path in originals.iterdir() if path.is_file()}
    assert_equal(set(source_files), REQUIRED_ASSETS, "original signed artifact inventory")
    provenance = json.loads(source_files["provenance.json"].read_text())
    for field, expected in (("commit", SOURCE), ("tag", TAG), ("sourceTree", source_tree),
                            ("versionName", "0.9.66.6"), ("versionCode", 140),
                            ("workflowRun", str(SOURCE_RUN)),
                            ("workflowAttempt", "1"), ("installationQualification", "NOT_MEASURED")):
        assert_equal(provenance.get(field), expected, "signed provenance " + field)
    assert re.fullmatch(r"[a-f0-9]{64}", provenance["signingCertificateSha256"])
    checks = provenance["exactCommitChecks"]
    exact_check_evidence({"sourceSha": SOURCE, "status": "PASSED",
                          "requiredWorkflows": [r["name"] for r in checks],
                          "workflows": checks}, SOURCE)
    assert {r["name"] for r in checks}.issuperset(
        {"PR Validation", "Build Android APK", "Post-merge correctness", "CodeQL Advanced",
         "v0.9.50 Android acceptance"})

    manifest = source_files["SHA256SUMS.txt"].read_text()
    observed = {}
    for line in manifest.splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})  ([A-Za-z0-9_.+-]+)", line)
        if not match or match[2] in observed:
            raise ValueError("Invalid or duplicate original checksum line")
        observed[match[2]] = match[1]
    assert_equal(set(observed), {p for p in source_files if p.endswith((".apk", ".aab"))} |
                 {"provenance.json"}, "original checksum coverage")
    for name, digest in observed.items():
        assert_equal(file_digest(source_files[name]), digest, "original " + name + " checksum")

    by_name = {asset.get("name"): asset for asset in release.get("assets", [])}
    assert_equal(set(by_name), REQUIRED_ASSETS, "draft release asset identities")
    remote = work / "uploaded"
    remote.mkdir()
    for name in sorted(REQUIRED_ASSETS):
        download_asset(by_name[name], remote / name)
        assert_equal(file_digest(remote / name), file_digest(source_files[name]),
                     "original vs draft release asset " + name)

    previous = api(BASE + "/releases/tags/v0.9.66.5")
    if previous.get("draft") or not previous.get("published_at"):
        raise ValueError("Previous signed release is not published")
    previous_dir = work / "previous"
    previous_dir.mkdir()
    command("gh", "release", "download", "v0.9.66.5", "--repo", REPOSITORY,
            "--pattern", "app-arm64-v8a-release.apk", "--dir", str(previous_dir), timeout=900)
    previous_fingerprint = signer(previous_dir / "app-arm64-v8a-release.apk")
    expected = certificate_sha(provenance["signingCertificateSha256"])
    assert_equal(previous_fingerprint, expected, "signing certificate continuity")
    for name in sorted(n for n in REQUIRED_ASSETS if n.endswith(".apk")):
        assert_equal(signer(remote / name), expected, "release APK signer " + name)
    assert_equal(signer(remote / "app-release.aab", bundle=True), expected, "release bundle signer")
    command("jarsigner", "-verify", str(remote / "app-release.aab"), timeout=120)

    files = [remote / item["name"] for item in provenance["assets"]]
    evidence = verify_uploaded_release(release, provenance, None, artifact_inventory(files), published=False)
    return {"status": evidence["status"], "sourceSha": SOURCE,
            "releaseId": RELEASE_ID, "tag": TAG, "assetsCompared": len(REQUIRED_ASSETS),
            "signer": expected, "originalRunId": SOURCE_RUN,
            "originalSignedArtifact": ORIGINAL_ARTIFACT}, provenance, files


def publish_once(provenance, files):
    # No mutation until every complete main check and every original/draft
    # byte, signer and provenance assertion has passed.
    current = release_state()
    if current.get("draft") is False:
        observed = api(BASE + "/releases/tags/" + TAG)
        assert_equal(observed.get("id"), RELEASE_ID, "published release ID")
        tag_commit = api(BASE + "/commits/" + TAG)
        assert_equal(tag_commit.get("sha"), SOURCE, "published release tag commit")
        verify_uploaded_release(observed, provenance, SOURCE, artifact_inventory(files), published=True)
        return "ALREADY_PUBLISHED_VERIFIED"
    if current.get("draft") is not True:
        raise ValueError("Release state is neither verified draft nor published")
    if green_gate()["failed"] or green_gate()["pending"]:
        raise ValueError("Required exact-main verification is not entirely green")
    main_head()
    cmd = ["gh", "api", "--method", "PATCH", BASE + "/releases/" + str(RELEASE_ID),
           "--input", "-"]
    result = subprocess.run(cmd, input=json.dumps({"draft": False}), text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    # An uncertain HTTP response is NEVER replay authorization: reconcile only.
    refreshed = release_state()
    if refreshed.get("draft") is not False:
        raise ValueError("Publication remains draft after one attempt; do not replay")
    if result.returncode != 0:
        print("The publication response was uncertain; exact release readback established publication.")
    published = api(BASE + "/releases/tags/" + TAG)
    assert_equal(published.get("id"), RELEASE_ID, "published release identity")
    assert_equal(api(BASE + "/commits/" + TAG).get("sha"), SOURCE, "new tag target")
    post = verify_uploaded_release(published, provenance, SOURCE,
                                   artifact_inventory(files), published=True)
    assert_equal(post.get("status"), "PUBLISHED_VERIFIED", "publication evidence")
    return post["status"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true",
                        help="After every exact-commit and byte check succeeds, finalize the existing draft exactly once")
    args = parser.parse_args()
    if not os.environ.get("GH_TOKEN"):
        raise ValueError("GitHub Actions authorized access required")
    source_tree = main_head()
    original_build_receipts()
    draft = release_state()
    if draft.get("draft") is not True and not args.publish:
        raise ValueError("Expected an existing draft for independent verification")
    with tempfile.TemporaryDirectory(prefix="pr806-release-proof-") as directory:
        evidence, provenance, files = verify_original_and_remote(draft, source_tree, Path(directory))
        gate = green_gate()
        evidence["gate"] = gate
        if args.publish:
            if gate["failed"] or gate["pending"]:
                raise ValueError("Exact-main checks are not green: " + json.dumps(gate))
            evidence["publication"] = publish_once(provenance, files)
        else:
            evidence["publication"] = "NOT_ATTEMPTED_VERIFY_ONLY"
        print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
