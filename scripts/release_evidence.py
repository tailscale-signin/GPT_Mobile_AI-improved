"""Revision-bound release evidence; upload success never proves installation."""
from __future__ import annotations

import hashlib
from pathlib import Path
import re


def immutable_sha(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Expected a full immutable source SHA")
    return value


def certificate_sha(value: str) -> str:
    normalized = re.sub(r"[:\s]", "", value).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise ValueError("A verified signing certificate SHA-256 is required")
    return normalized


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact_inventory(paths: list[Path]) -> list[dict]:
    result = []
    names = set()
    for path in sorted(paths):
        if path.name in names or not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise ValueError("Release artifacts must be distinct nonempty regular files")
        names.add(path.name)
        result.append({"name": path.name, "bytes": path.stat().st_size, "sha256": file_digest(path)})
    if not result:
        raise ValueError("Release artifact evidence is empty")
    return result


def exact_check_evidence(document: dict, sha: str) -> list[dict]:
    immutable_sha(sha)
    if document.get("sourceSha") != sha or document.get("status") != "PASSED":
        raise ValueError("Required checks have not passed for the release commit")
    required = document.get("requiredWorkflows")
    rows = document.get("workflows")
    if not isinstance(required, list) or not required or not isinstance(rows, list):
        raise ValueError("Exact-commit check evidence is incomplete")
    if len(set(required)) != len(required) or len(rows) != len(required):
        raise ValueError("Required check evidence is ambiguous")
    by_name = {row.get("name"): row for row in rows}
    for name in required:
        row = by_name.get(name, {})
        if (row.get("headSha") != sha or row.get("conclusion") != "success"
                or row.get("status") != "completed" or type(row.get("runId")) is not int
                or row["runId"] <= 0 or type(row.get("attempt")) is not int
                or row["attempt"] < 1 or not str(row.get("url", "")).startswith("https://")):
            raise ValueError("A required check is missing, stale or unsuccessful")
    return rows


def verify_uploaded_release(release: dict, provenance: dict, tag_sha: str | None, uploaded: list[dict], *, published: bool) -> dict:
    """Verify downloaded asset bytes against a locally verified build manifest."""
    source = immutable_sha(provenance.get("commit", ""))
    tag_pending = tag_sha is None and not published and release.get("draft") is True and release.get("target_commitish") == source
    if (not tag_pending and immutable_sha(tag_sha) != source) or release.get("tag_name") != provenance.get("tag"):
        raise ValueError("Release tag does not identify the verified source commit")
    if type(release.get("id")) is not int or release["id"] <= 0:
        raise ValueError("Release identity is missing")
    if published and (release.get("draft") is not False or not release.get("published_at")):
        raise ValueError("The release is not published")
    certificate_sha(provenance.get("signingCertificateSha256", ""))
    expected = provenance.get("assets")
    if not isinstance(expected, list) or not expected:
        raise ValueError("Verified build asset manifest is missing")
    actual = {row.get("name"): row for row in uploaded}
    remote = {row.get("name"): row for row in release.get("assets", [])}
    if len(actual) != len(uploaded) or len(remote) != len(release.get("assets", [])):
        raise ValueError("Duplicate release asset identities")
    for item in expected:
        name = item.get("name")
        observed, inventory = actual.get(name, {}), remote.get(name, {})
        if (observed.get("sha256") != item.get("sha256") or observed.get("bytes") != item.get("bytes")
                or inventory.get("size") != item.get("bytes") or inventory.get("state") != "uploaded"):
            raise ValueError("Missing, stale or mismatched release asset: " + str(name))
        remote_digest = inventory.get("digest")
        if remote_digest and remote_digest != "sha256:" + item["sha256"]:
            raise ValueError("Remote asset digest contradicts downloaded bytes")
    if not any(row["name"].endswith(".apk") for row in expected) or not any(row["name"].endswith(".aab") for row in expected):
        raise ValueError("APK and bundle evidence are both required")
    return {"schemaVersion": 1, "sourceSha": source, "tag": provenance["tag"], "releaseId": release["id"],
            "status": "PUBLISHED_VERIFIED" if published else "UPLOAD_VERIFIED", "url": release.get("html_url"),
            "tagState": "PENDING_PUBLICATION" if tag_pending else "VERIFIED",
            "assets": expected, "applicationId": provenance.get("applicationId"),
            "versionName": provenance.get("versionName"), "versionCode": provenance.get("versionCode"),
            "signingCertificateSha256": provenance["signingCertificateSha256"],
            "installationQualification": "NOT_MEASURED"}
