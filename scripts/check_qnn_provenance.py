#!/usr/bin/env python3
"""Reject LiteRT/QNN package metadata that disagrees with the checked-in binaries."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERSIONS = ROOT / "gradle/libs.versions.toml"
MANIFEST = ROOT / "docs/audits/local-runtime-native-libraries.json"
PROVENANCE = ROOT / "docs/audits/litert-qualcomm-dispatch-build.md"
DISPATCH = ROOT / "app/src/main/jniLibs/arm64-v8a/libLiteRtDispatch_Qualcomm.so"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--allow-unqualified",
        action="store_true",
        help="Report a LiteRT/dispatch revision mismatch without blocking source-level checks.",
    )
    args = parser.parse_args()

    toml = VERSIONS.read_text()
    versions_section = toml.split("[versions]", 1)[1].split("\n[", 1)[0]
    versions = dict(re.findall(r'^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"', versions_section, re.MULTILINE))
    manifest = json.loads(MANIFEST.read_text())
    app_litert = versions.get("litertlm")
    app_qairt = versions.get("qnn")
    problems = []
    unqualified = []

    if not app_litert or manifest.get("litertlmVersion") != app_litert:
        problems.append(f"LiteRT-LM mismatch: Gradle={app_litert!r}, manifest={manifest.get('litertlmVersion')!r}")
    if not app_qairt or manifest.get("qairtVersion") != app_qairt:
        problems.append(f"QAIRT mismatch: Gradle={app_qairt!r}, manifest={manifest.get('qairtVersion')!r}")

    provenance = PROVENANCE.read_text()
    if app_litert and f"litertlm-android:{app_litert}`" not in provenance:
        unqualified.append(f"Dispatch source provenance does not name LiteRT-LM {app_litert}")
    if app_qairt and f"QAIRT SDK | `{app_qairt}." not in provenance:
        problems.append(f"Dispatch source provenance does not name QAIRT {app_qairt}")

    record = manifest.get("libraries", {}).get("lib/arm64-v8a/libLiteRtDispatch_Qualcomm.so", {})
    if not DISPATCH.is_file():
        problems.append(f"Qualcomm dispatch library is missing: {DISPATCH}")
    else:
        digest = hashlib.sha256(DISPATCH.read_bytes()).hexdigest()
        if record.get("sha256", "").lower() != digest:
            problems.append(f"Dispatch SHA-256 mismatch: manifest={record.get('sha256')!r}, actual={digest}")
        if record.get("bytes") != DISPATCH.stat().st_size:
            problems.append("Dispatch size does not match the native library manifest")

    if unqualified and not args.allow_unqualified:
        problems.extend(unqualified)
    if problems:
        print("QNN provenance check failed:", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
        return 1
    if unqualified:
        print("QNN remains unqualified:", file=sys.stderr)
        for issue in unqualified:
            print(f"- {issue}", file=sys.stderr)
        print("Source checks passed; release provenance validation must use strict mode.")
    else:
        print(f"QNN provenance matches LiteRT-LM {app_litert} / QAIRT {app_qairt}; device execution remains unverified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
