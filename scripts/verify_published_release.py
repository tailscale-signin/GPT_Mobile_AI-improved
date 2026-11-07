#!/usr/bin/env python3
"""Read-only reconciliation of a release tag and every required uploaded asset."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from release_evidence import artifact_inventory, immutable_sha, verify_uploaded_release


MAX_RELEASE_PAGES = 100


def gh_json(*arguments):
    return json.loads(subprocess.check_output(["gh", *arguments], text=True, stderr=subprocess.PIPE, timeout=90))


def is_not_found(error):
    return isinstance(error.stderr, str) and re.search(r"\(HTTP 404\)\s*$", error.stderr) is not None


def release_identity(release, tag, source, expected_id=None):
    if (not isinstance(release, dict) or type(release.get("id")) is not int or release["id"] <= 0
            or (expected_id is not None and release["id"] != expected_id)
            or release.get("tag_name") != tag or type(release.get("draft")) is not bool):
        raise ValueError("Release identity does not match the verified tag")
    if release["draft"] and release.get("target_commitish") != source:
        raise ValueError("Draft release does not target the verified immutable source commit")
    return release["id"]


def resolve_release(root, tag, source, *, release_id=None, published=False):
    """Resolve drafts through the authorized collection; tag lookup is published-only."""
    if release_id is not None:
        if type(release_id) is not int or release_id <= 0:
            raise ValueError("Expected a positive release ID")
        release = gh_json("api", f"{root}/releases/{release_id}")
    else:
        try:
            release = gh_json("api", root + "/releases/tags/" + tag)
        except subprocess.CalledProcessError as error:
            if published or not is_not_found(error):
                raise
            # The tag endpoint cannot find a draft, including after a lost
            # creation acknowledgement. Read every bounded page to rule out
            # duplicate tag identities before selecting a candidate.
            matches = []
            for page in range(1, MAX_RELEASE_PAGES + 1):
                rows = gh_json("api", f"{root}/releases?per_page=100&page={page}")
                if not isinstance(rows, list) or len(rows) > 100 or any(not isinstance(row, dict) for row in rows):
                    raise ValueError("Invalid release collection page")
                matches.extend(row for row in rows if row.get("tag_name") == tag)
                if len(matches) > 1:
                    raise ValueError("Multiple release identities use the verified tag")
                if len(rows) < 100:
                    break
            else:
                raise ValueError("Release lookup is incomplete; reconcile using an explicit release ID")
            if not matches:
                raise ValueError("No accessible release matches the verified tag")
            release_id = release_identity(matches[0], tag, source)
            release = gh_json("api", f"{root}/releases/{release_id}")
    identity = release_identity(release, tag, source, release_id)
    if published:
        if release["draft"] or not release.get("published_at"):
            raise ValueError("The release is not published")
        if release_id is not None:
            tagged = gh_json("api", root + "/releases/tags/" + tag)
            release_identity(tagged, tag, source, identity)
            if tagged["draft"] or not tagged.get("published_at"):
                raise ValueError("The release tag is not published")
    return release


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--release-id", type=int, help="Pin a previously verified release identity")
    parser.add_argument("--published", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        parser.error("Expected an owner/repository identity")
    provenance_path = args.artifacts / "provenance.json"
    provenance = json.loads(provenance_path.read_text())
    source = immutable_sha(provenance.get("commit", ""))
    tag = provenance["tag"]
    if not re.fullmatch(r"v[0-9A-Za-z_.+-]{1,100}", tag):
        raise ValueError("Unexpected release tag")
    root = "repos/" + args.repository
    release = resolve_release(root, tag, source, release_id=args.release_id, published=args.published)
    # GitHub's commit endpoint peels lightweight and annotated tags.
    try:
        commit = gh_json("api", root + "/commits/" + tag)
        sha = immutable_sha(commit.get("sha", ""))
    except subprocess.CalledProcessError as error:
        # A newly created draft may not have materialized its tag. Only an
        # explicit 404 and its immutable target permit upload reconciliation.
        if (not is_not_found(error) or args.published
                or release.get("draft") is not True or release.get("target_commitish") != source):
            raise
        sha = None
    names = [item["name"] for item in provenance["assets"]] + ["provenance.json", "SHA256SUMS.txt"]
    if any(Path(name).name != name or not re.fullmatch(r"[A-Za-z0-9_.+-]+", name) for name in names):
        raise ValueError("Unsafe asset filename")
    if len(names) != len(set(names)):
        raise ValueError("Duplicate required asset")
    remote = {row.get("name"): row for row in release.get("assets", [])}
    if len(remote) != len(release.get("assets", [])):
        raise ValueError("Duplicate release asset names")
    asset_ids = set()
    for name in names:
        asset = remote.get(name, {})
        size = asset.get("size")
        asset_id = asset.get("id")
        local = args.artifacts / name
        if type(size) is not int or not local.is_file() or size != local.stat().st_size or size <= 0:
            raise ValueError("Missing or mismatched asset size: " + name)
        if type(asset_id) is not int or asset_id <= 0 or asset_id in asset_ids or asset.get("state") != "uploaded":
            raise ValueError("Missing or ambiguous uploaded asset identity: " + name)
        asset_ids.add(asset_id)
    required_space = sum(remote[name]["size"] for name in names)
    if shutil.disk_usage(tempfile.gettempdir()).free < required_space + 64 * 1024 * 1024:
        raise ValueError("Insufficient space to verify release asset bytes")
    with tempfile.TemporaryDirectory(prefix="release-verification-") as folder:
        downloaded = Path(folder)
        for name in names:
            # Stream the exact asset's bytes; do not resolve the mutable tag
            # again or retain whole APK/AAB files in process memory.
            with (downloaded / name).open("wb") as output:
                subprocess.run(["gh", "api", f"{root}/releases/assets/{remote[name]['id']}",
                                "--header", "Accept: application/octet-stream"],
                               stdout=output, check=True, timeout=600)
        for name in ("provenance.json", "SHA256SUMS.txt"):
            if (downloaded / name).read_bytes() != (args.artifacts / name).read_bytes():
                raise ValueError("Published provenance or checksum manifest does not match the verified build")
        assets = artifact_inventory([downloaded / name for name in names[:-2]])
        result = verify_uploaded_release(release, provenance, sha, assets, published=args.published)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(result["status"] + " for " + provenance["commit"])


if __name__ == "__main__":
    main()
