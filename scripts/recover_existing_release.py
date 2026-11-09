#!/usr/bin/env python3
"""Recover one pinned release from its retained signed bytes, without rebuilding."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import time
import zipfile

from release_evidence import artifact_inventory, certificate_sha, exact_check_evidence, file_digest, verify_uploaded_release
from verify_published_release import resolve_tag_sha


def require(condition, message):
    if not condition:
        raise ValueError(message)


def write_json(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w") as output:
        json.dump(document, output, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


def verify_file(path, expected):
    require(path.is_file() and not path.is_symlink(), "Expected a regular retained file")
    require(path.stat().st_size == expected["size"], "File size differs: " + path.name)
    require(file_digest(path) == expected["sha256"], "File digest differs: " + path.name)


def extract_exact(archive, destination, expected_files):
    """Reject extra files, traversal, links, encryption, and expansion beyond pins."""
    expected = {item["name"]: item for item in expected_files}
    require(len(expected) == len(expected_files), "Duplicate archive file pins")
    require(not destination.exists(), "Extraction destination already exists")
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        require(len(entries) == len(expected) and {entry.filename for entry in entries} == set(expected),
                "Archive inventory differs from the retained files")
        for entry in entries:
            mode = entry.external_attr >> 16
            require(entry.filename == Path(entry.filename).name and "\\" not in entry.filename,
                    "Unsafe archive path")
            require(not entry.is_dir() and not entry.flag_bits & 1
                    and stat.S_IFMT(mode) in (0, stat.S_IFREG), "Unsupported archive entry")
            require(entry.file_size == expected[entry.filename]["size"], "Archive expansion differs from pins")
        destination.mkdir(parents=True)
        for entry in entries:
            output = destination / entry.filename
            with source.open(entry) as reader, output.open("xb") as writer:
                shutil.copyfileobj(reader, writer, length=1024 * 1024)
            verify_file(output, expected[entry.filename])


class GitHub:
    def __init__(self, repository):
        self.root = "repos/" + repository

    def json(self, path):
        require(path.startswith(self.root + "/"), "Unexpected API repository")
        result = subprocess.run(["gh", "api", "--method", "GET", path],
                                capture_output=True, text=True, check=True, timeout=90)
        return json.loads(result.stdout)

    def download(self, path, destination, expected):
        require(path.startswith(self.root + "/"), "Unexpected download repository")
        with destination.open("xb") as output:
            subprocess.run(["gh", "api", "--method", "GET", path,
                            "--header", "Accept: application/octet-stream"],
                           stdout=output, stderr=subprocess.PIPE, check=True, timeout=900)
        verify_file(destination, expected)

    def publish_once(self, release_id):
        # This is the only remote write in recovery. There is no retry here.
        subprocess.run(["gh", "api", "--method", "PATCH",
                        f"{self.root}/releases/{release_id}", "--input", "-"],
                       input='{"draft":false}', capture_output=True, text=True,
                       check=True, timeout=90)


class Recovery:
    def __init__(self, pins, api, source, work):
        self.pins, self.api, self.source, self.work = pins, api, source, work
        self.root = "repos/" + pins["repository"]
        self.evidence = work / "evidence"
        self.retained = work / "signed"
        self.provenance = None
        self.original_checks = None

    def source_identity(self):
        def git(*args):
            return subprocess.check_output(["git", "-C", str(self.source), *args], text=True).strip()
        require(git("rev-parse", "HEAD") == self.pins["source_sha"], "Source checkout SHA differs")
        require(git("rev-parse", "HEAD^{tree}") == self.pins["source_tree"], "Source checkout tree differs")
        require(not git("status", "--porcelain", "--untracked-files=no"), "Source checkout has tracked edits")

    def current_main(self):
        ref = self.api.json(self.root + "/git/ref/heads/main")
        require(ref.get("ref") == "refs/heads/main" and ref.get("object", {}).get("type") == "commit"
                and ref["object"].get("sha") == self.pins["source_sha"], "Main moved from the verified source")

    def original_metadata(self):
        p = self.pins
        run = self.api.json(f"{self.root}/actions/runs/{p['source_run_id']}")
        for field, expected in {"id": p["source_run_id"], "run_attempt": p["source_run_attempt"],
                                "workflow_id": p["source_workflow_id"], "path": p["source_workflow_path"],
                                "head_sha": p["source_sha"], "head_branch": "main", "event": "push",
                                "status": "completed", "conclusion": "failure"}.items():
            require(run.get(field) == expected, "Original run differs: " + field)
        for key in ("repository", "head_repository"):
            require(run.get(key, {}).get("id") == p["repository_id"]
                    and run[key].get("full_name") == p["repository"], "Original run repository differs")
        document = self.api.json(f"{self.root}/actions/runs/{p['source_run_id']}/attempts/{p['source_run_attempt']}/jobs?per_page=100")
        jobs = document.get("jobs", [])
        require(document.get("total_count") == len(jobs) == len(p["jobs"]), "Original job inventory is incomplete")
        require(len({job.get("id") for job in jobs}) == len(jobs), "Duplicate original jobs")
        by_id = {job["id"]: job for job in jobs}
        for expected in p["jobs"]:
            job = by_id.get(expected["id"], {})
            require(job.get("name") == expected["name"] and job.get("conclusion") == expected["conclusion"]
                    and job.get("status") == "completed" and job.get("run_id") == p["source_run_id"]
                    and job.get("run_attempt") == p["source_run_attempt"] and job.get("head_sha") == p["source_sha"],
                    "Original validation/signing job differs")
            require(all(step.get("status") == "completed" for step in job.get("steps", [])), "Original step is unfinished")
            steps = [{"name": step.get("name"), "conclusion": step.get("conclusion")} for step in job.get("steps", [])]
            require(steps == expected["steps"], "Original step outcomes differ from the pinned publication-only failure")
        metadata = []
        for expected in p["artifacts"]:
            artifact = self.api.json(f"{self.root}/actions/artifacts/{expected['id']}")
            require(artifact.get("id") == expected["id"] and artifact.get("name") == expected["name"]
                    and artifact.get("size_in_bytes") == expected["size"] and artifact.get("expired") is False
                    and artifact.get("digest") == "sha256:" + expected["sha256"], "Retained artifact metadata differs")
            binding = artifact.get("workflow_run", {})
            require(binding.get("id") == p["source_run_id"] and binding.get("head_sha") == p["source_sha"]
                    and binding.get("head_branch") == "main" and binding.get("repository_id") == p["repository_id"]
                    and binding.get("head_repository_id") == p["repository_id"], "Artifact source binding differs")
            metadata.append({"id": artifact["id"], "name": artifact["name"], "digest": artifact["digest"], "workflow_run": binding})
        write_json(self.evidence / "original-build.json", {"sourceSha": p["source_sha"], "runId": run["id"],
                                                          "attempt": run["run_attempt"], "jobs": p["jobs"], "artifacts": metadata})

    def release(self):
        p = self.pins
        release = self.api.json(f"{self.root}/releases/{p['release_id']}")
        require(release.get("id") == p["release_id"] and release.get("tag_name") == p["tag"]
                and release.get("name") == p["tag"] and release.get("target_commitish") == p["source_sha"]
                and release.get("prerelease") is False and type(release.get("draft")) is bool,
                "Pinned release identity changed")
        require(hashlib.sha256(release.get("body", "").encode()).hexdigest() == p["release_body_sha256"], "Release notes changed")
        require((release["draft"] and release.get("published_at") is None)
                or (not release["draft"] and bool(release.get("published_at"))), "Publication state is inconsistent")
        assets = self.api.json(f"{self.root}/releases/{p['release_id']}/assets?per_page=100")
        for rows in (release.get("assets"), assets):
            require(isinstance(rows, list) and len(rows) == len(p["assets"]), "Release asset inventory changed")
            require(len({row.get("name") for row in rows}) == len(rows), "Duplicate remote asset names")
            normalized = sorted((row.get("id"), row.get("name"), row.get("size"), row.get("digest"), row.get("state")) for row in rows)
            expected = sorted((row["id"], row["name"], row["size"], "sha256:" + row["sha256"], "uploaded") for row in p["assets"])
            require(normalized == expected, "A pinned asset was replaced, changed, or omitted")
        return release

    def retain_artifacts(self):
        required = sum(item["size"] for item in self.pins["artifacts"]) + 4 * sum(item["size"] for item in self.pins["assets"]) + 512 * 1024 * 1024
        require(shutil.disk_usage(self.work).free > required, "Insufficient space for bounded recovery verification")
        for expected in self.pins["artifacts"]:
            archive = self.work / (expected["kind"] + ".zip")
            self.api.download(f"{self.root}/actions/artifacts/{expected['id']}/zip", archive, expected)
            extract_exact(archive, self.work / expected["kind"], expected["files"])
        self.original_checks = json.loads((self.work / "checks/release-checks.json").read_text())
        original_rows = exact_check_evidence(self.original_checks, self.pins["source_sha"])
        self.provenance = json.loads((self.retained / "provenance.json").read_text())
        p, provenance = self.pins, self.provenance
        expected = {"schemaVersion": 2, "commit": p["source_sha"], "sourceTree": p["source_tree"],
                    "tag": p["tag"], "versionName": p["version_name"], "versionCode": p["version_code"],
                    "workflowRun": str(p["source_run_id"]), "workflowAttempt": str(p["source_run_attempt"]),
                    "signingCertificateSha256": p["signing_certificate_sha256"], "exactCommitChecks": original_rows,
                    "installationQualification": "NOT_MEASURED"}
        require(all(provenance.get(key) == value for key, value in expected.items()), "Retained provenance differs from the verified build")
        packages = [self.retained / item["name"] for item in p["assets"] if item["name"].endswith((".apk", ".aab"))]
        require(provenance.get("assets") == artifact_inventory(packages), "Retained package manifest differs")

    def required_checks(self, suffix):
        output = self.evidence / ("required-checks-" + suffix + ".json")
        subprocess.run([sys.executable, str(self.source / "scripts/await_release_checks.py"),
                        "--sha", self.pins["source_sha"], "--repository", self.pins["repository"],
                        "--timeout", "600", "--output", str(output)], cwd=self.source, check=True, timeout=660)
        document = json.loads(output.read_text())
        exact_check_evidence(document, self.pins["source_sha"])
        require(set(document["requiredWorkflows"]) == set(self.original_checks["requiredWorkflows"]),
                "Required workflow scope differs from retained evidence")

    def signed_identity(self):
        p = self.pins
        tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
        bundletool = Path(os.environ["BUNDLETOOL_JAR"])
        require(file_digest(bundletool) == p["bundletool"]["sha256"], "Bundletool digest differs")
        apks = sorted(self.retained.glob("*.apk"))
        bundle = self.retained / "app-release.aab"
        for apk in apks:
            output = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
            fingerprints = re.findall(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", output)
            require(re.search(r"Number of signers:\s*1\b", output) is not None and fingerprints
                    and {certificate_sha(value) for value in fingerprints} == {p["signing_certificate_sha256"]}, "APK signer differs")
        verified = subprocess.check_output(["jarsigner", "-verify", str(bundle)], text=True)
        require("jar verified." in verified.lower(), "Bundle signature is not verified")
        output = subprocess.check_output(["keytool", "-printcert", "-jarfile", str(bundle)], text=True)
        fingerprints = re.findall(r"^\s*SHA-?256:\s*([0-9A-Fa-f:]+)", output, re.MULTILINE)
        require(fingerprints and {certificate_sha(value) for value in fingerprints} == {p["signing_certificate_sha256"]}, "Bundle signer differs")
        # The identity generator writes provenance. Use disposable hard links and
        # original run metadata; the retained provenance is never overwritten.
        identity = self.work / "identity"
        identity.mkdir()
        for path in [*apks, bundle]:
            os.link(path, identity / path.name)
        checks_path = self.source / "build/reports/release-checks.json"
        checks_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.work / "checks/release-checks.json", checks_path)
        environment = {**os.environ, "GITHUB_REF_NAME": "main", "GITHUB_RUN_ID": str(p["source_run_id"]),
                       "GITHUB_RUN_ATTEMPT": str(p["source_run_attempt"]), "RELEASE_TAG": p["tag"],
                       "EXPECTED_CERT_SHA256": p["signing_certificate_sha256"]}
        subprocess.run([sys.executable, str(self.source / "scripts/verify_release_identity.py"), str(identity), p["source_sha"]],
                       cwd=self.source, env=environment, check=True, timeout=300)
        require((identity / "provenance.json").read_bytes() == (self.retained / "provenance.json").read_bytes(),
                "Independent package/version verification differs from retained provenance")
        commands = [("check_local_runtime_apk.py", [str(path) for path in apks]),
                    ("check_no_telemetry.py", [str(path) for path in apks]),
                    ("check_apk_size.py", [str(self.retained), "--build-type", "release", "--report", str(self.evidence / "apk-size.json")]),
                    ("check_mediapipe_jni.py", [str(bundle)]), ("check_objectbox_jni.py", [str(bundle)]),
                    ("check_play_bundle.py", [str(bundle)])]
        for script, arguments in commands:
            subprocess.run([sys.executable, str(self.source / "scripts" / script), *arguments], cwd=self.source, check=True, timeout=300)
        write_json(self.evidence / "signed-identity.json", {"sourceSha": p["source_sha"], "sourceTree": p["source_tree"],
                                                          "signingCertificateSha256": p["signing_certificate_sha256"], "status": "PASSED"})

    def verify_remote(self, release, folder, *, published):
        tag_sha = resolve_tag_sha(self.root, self.pins["tag"], self.pins["source_sha"], release, published=published)
        if published:
            tagged = self.api.json(self.root + "/releases/tags/" + self.pins["tag"])
            require(tagged.get("id") == self.pins["release_id"] and tagged.get("draft") is False
                    and tagged.get("published_at"), "Public tag resolves to a different release")
        destination = self.work / folder
        destination.mkdir()
        for expected in self.pins["assets"]:
            self.api.download(f"{self.root}/releases/assets/{expected['id']}", destination / expected["name"], expected)
            require(file_digest(destination / expected["name"]) == file_digest(self.retained / expected["name"]),
                    "Downloaded bytes differ from the retained artifact")
        for name in ("provenance.json", "SHA256SUMS.txt"):
            require((destination / name).read_bytes() == (self.retained / name).read_bytes(), "Uploaded manifest differs")
        inventory = artifact_inventory([destination / item["name"] for item in self.pins["assets"]])
        receipt = verify_uploaded_release(release, self.provenance, tag_sha, inventory, published=published)
        receipt.update(controllerSha=os.environ.get("GITHUB_SHA"), controllerRun=os.environ.get("GITHUB_RUN_ID"),
                       originalRun=self.pins["source_run_id"], originalAttempt=self.pins["source_run_attempt"],
                       allUploadedFiles=inventory, pinnedAssetIds={item["name"]: item["id"] for item in self.pins["assets"]})
        return receipt

    def run(self):
        self.work.mkdir(parents=True, exist_ok=False)
        self.evidence.mkdir()
        self.source_identity()
        self.current_main()
        self.original_metadata()
        self.release()
        self.retain_artifacts()
        self.required_checks("before-download")
        self.signed_identity()
        release = self.release()
        if not release["draft"]:
            receipt = self.verify_remote(release, "already-published", published=True)
            write_json(self.evidence / "release-publication.json", receipt)
            return receipt
        receipt = self.verify_remote(release, "uploaded", published=False)
        require(receipt["status"] == "UPLOAD_VERIFIED", "Upload verification did not complete")
        write_json(self.evidence / "release-upload.json", receipt)
        # Reconcile mutable state again after every download and immediately
        # before the sole write. An already published release is read-only.
        self.required_checks("before-publish")
        self.source_identity()
        self.current_main()
        release = self.release()
        if not release["draft"]:
            receipt = self.verify_remote(release, "published-concurrently", published=True)
            write_json(self.evidence / "release-publication.json", receipt)
            return receipt
        tag_sha = resolve_tag_sha(self.root, self.pins["tag"], self.pins["source_sha"], release, published=False)
        require(tag_sha in (None, self.pins["source_sha"]), "Draft tag moved before publication")
        intent = {"releaseId": self.pins["release_id"], "sourceSha": self.pins["source_sha"],
                  "controllerSha": os.environ.get("GITHUB_SHA"), "controllerRun": os.environ.get("GITHUB_RUN_ID"),
                  "operation": "PATCH draft=false", "attempts": 1}
        write_json(self.evidence / "publication-intent.json", intent)
        try:
            self.api.publish_once(self.pins["release_id"])
            intent["acknowledged"] = True
        except Exception as error:
            intent.update(acknowledged=False, errorType=type(error).__name__)
        write_json(self.evidence / "publication-intent.json", intent)
        for attempt in range(6):
            observed = self.release()
            if not observed["draft"]:
                receipt = self.verify_remote(observed, "published", published=True)
                write_json(self.evidence / "release-publication.json", receipt)
                return receipt
            if attempt < 5:
                time.sleep(5)
        raise RuntimeError("Same release remains a draft after reconciliation; the publication write was not repeated")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--pins", type=Path, default=Path(__file__).with_name("release-recovery-v0.9.66.7.json"))
    args = parser.parse_args()
    pins = json.loads(args.pins.read_text())
    require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
            and os.environ.get("GITHUB_REF") == "refs/heads/" + pins["recovery_branch"]
            and os.environ.get("GITHUB_REPOSITORY") == pins["repository"]
            and os.environ.get("GITHUB_RUN_ATTEMPT") == "1", "Recovery is restricted to its exact first-attempt branch dispatch")
    controller = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    require(controller == os.environ.get("GITHUB_SHA") and re.fullmatch(r"[0-9a-f]{40}", controller), "Controller checkout differs")
    receipt = Recovery(pins, GitHub(pins["repository"]), args.source.resolve(), args.work.resolve()).run()
    print(receipt["status"] + " for " + receipt["sourceSha"])


if __name__ == "__main__":
    main()
