#!/usr/bin/env python3
"""Validate an explicit scoped release; missing/full manifests retain full-v2 qualification."""
import argparse
import json
import re
import subprocess
from pathlib import Path

DISCLOSURE = "Unified Reliability v2 is not complete"


def validate(root, manifest, changed):
    build = (root / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName = "([^" ]+)"', build).group(1)
    code = int(re.search(r"versionCode = (\d+)", build).group(1))
    if manifest.get("version") != version or manifest.get("versionCode") != code:
        raise ValueError("Release scope must match both the version name and code")
    mode = manifest.get("qualification")
    if mode not in {"scoped", "full"}:
        raise ValueError("Unknown release qualification")
    if mode == "full":
        return mode
    if not re.fullmatch(r"[a-f0-9]{40}", manifest.get("baseCommit", "")):
        raise ValueError("Scope requires an exact base commit")
    for field in ("changes", "limitations", "requiredWorkflows", "allowedPaths"):
        values = manifest.get(field)
        if not isinstance(values, list) or not values or not all(isinstance(v, str) and v.strip() for v in values):
            raise ValueError(f"Scope requires nonempty {field}")
    allowed = manifest["allowedPaths"]
    if any(p in {"", "/", "*", "**"} or p.startswith("/") or ".." in p.split("/") for p in allowed):
        raise ValueError("Scope paths must be bounded repository paths")
    outside = [p for p in changed if not any(p == a or (a.endswith("/") and p.startswith(a)) for a in allowed)]
    if outside:
        raise ValueError("Changes outside release scope: " + ", ".join(outside))
    notes = (root / "RELEASE_NOTES.md").read_text()
    if DISCLOSURE not in notes or version not in notes:
        raise ValueError("Scoped release notes must identify this version and disclose incomplete v2 qualification")
    if any(limitation not in notes for limitation in manifest["limitations"]):
        raise ValueError("Release notes must disclose every scoped limitation")
    return mode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit")
    args = parser.parse_args()
    root = Path.cwd()
    if args.commit and subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() != args.commit:
        raise SystemExit("Checkout does not match the release candidate")
    path = root / "release-scope.json"
    if not path.exists():
        print("full")
        return
    manifest = json.loads(path.read_text())
    changed = []
    if manifest.get("qualification") == "scoped":
        base = manifest.get("baseCommit", "")
        subprocess.run(["git", "merge-base", "--is-ancestor", base, "HEAD"], check=True)
        changed = subprocess.check_output(["git", "diff", "--name-only", base, "HEAD"], text=True).splitlines()
    print(validate(root, manifest, changed))


if __name__ == "__main__":
    main()
