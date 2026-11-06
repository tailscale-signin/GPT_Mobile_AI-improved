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


def gh_json(*arguments):
    return json.loads(subprocess.check_output(["gh", *arguments], text=True, stderr=subprocess.PIPE, timeout=90))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--published", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        parser.error("Expected an owner/repository identity")
    provenance_path = args.artifacts / "provenance.json"
    provenance = json.loads(provenance_path.read_text())
    tag = provenance["tag"]
    if not re.fullmatch(r"v[0-9A-Za-z_.+-]{1,100}", tag):
        raise ValueError("Unexpected release tag")
    root = "repos/" + args.repository
    release = gh_json("api", root + "/releases/tags/" + tag)
    # GitHub's commit endpoint peels lightweight and annotated tags.
    try:
        commit = gh_json("api", root + "/commits/" + tag)
        sha = immutable_sha(commit.get("sha", ""))
    except subprocess.CalledProcessError as error:
        # A newly created draft may not have materialized its tag. Only an
        # explicit 404 and its immutable target permit upload reconciliation.
        if ("HTTP 404" not in (error.stderr or "") or args.published
                or release.get("draft") is not True or release.get("target_commitish") != provenance["commit"]):
            raise
        sha = None
    names = [item["name"] for item in provenance["assets"]] + ["provenance.json", "SHA256SUMS.txt"]
    if any(Path(name).name != name or not re.fullmatch(r"[A-Za-z0-9_.+-]+", name) for name in names):
        raise ValueError("Unsafe asset filename")
    if len(names) != len(set(names)):
        raise ValueError("Duplicate required asset")
    remote = {row.get("name"): row for row in release.get("assets", [])}
    for name in names:
        size = remote.get(name, {}).get("size")
        local = args.artifacts / name
        if type(size) is not int or not local.is_file() or size != local.stat().st_size or size <= 0:
            raise ValueError("Missing or mismatched asset size: " + name)
    required_space = sum(remote[name]["size"] for name in names)
    if shutil.disk_usage(tempfile.gettempdir()).free < required_space + 64 * 1024 * 1024:
        raise ValueError("Insufficient space to verify release asset bytes")
    with tempfile.TemporaryDirectory(prefix="release-verification-") as folder:
        for name in names:
            subprocess.run(["gh", "release", "download", tag, "--repo", args.repository,
                            "--pattern", name, "--dir", folder], check=True, timeout=600)
        downloaded = Path(folder)
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
