#!/usr/bin/env python3
"""Verify signed APK package/version identity and record exact source provenance."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from release_identity import release_application_id, release_build_tools_version

artifacts = Path(sys.argv[1])
sha = sys.argv[2]
if not re.fullmatch(r"[a-f0-9]{40}", sha):
    raise SystemExit("Expected a full immutable commit SHA")
build = Path("app/build.gradle.kts").read_text()
expected_id = release_application_id(build)
expected_code = re.search(r'versionCode = (\d+)', build).group(1)
expected_name = re.search(r'versionName = "([^"]+)"', build).group(1)
expected_min_sdk = re.search(r'\bminSdk\s*=\s*(\d+)', build).group(1)
expected_target_sdk = re.search(r'\btargetSdk\s*=\s*(\d+)', build).group(1)
if int(expected_target_sdk) < 36:
    raise SystemExit("Google Play requires targetSdk 36 or higher as of August 31, 2026")
aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools" / release_build_tools_version(build) / "aapt2"
apks = list(artifacts.glob("*.apk"))
if not apks:
    raise SystemExit("No APKs to verify")
for apk in apks:
    output = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output)
    if not match or match.groups() != (expected_id, expected_code, expected_name):
        raise SystemExit(f"Unexpected package or version: {apk.name}")
bundletool = os.environ.get("BUNDLETOOL_JAR")
if not bundletool:
    raise SystemExit("BUNDLETOOL_JAR is required to verify bundle identity")
bundles = list(artifacts.glob("*.aab"))
if len(bundles) != 1:
    raise SystemExit("Expected exactly one signed bundle")
for xpath, expected in [
    ("/manifest/@package", expected_id),
    ("/manifest/@android:versionCode", expected_code),
    ("/manifest/@android:versionName", expected_name),
    ("/manifest/uses-sdk/@android:minSdkVersion", expected_min_sdk),
    ("/manifest/uses-sdk/@android:targetSdkVersion", expected_target_sdk),
]:
    actual = subprocess.check_output(["java", "-jar", bundletool, "dump", "manifest", f"--bundle={bundles[0]}", f"--xpath={xpath}"], text=True).strip()
    if actual != expected:
        raise SystemExit(f"Unexpected bundle identity at {xpath}")
provenance = dict(commit=sha, applicationId=expected_id, versionName=expected_name, versionCode=int(expected_code),
                  minSdk=int(expected_min_sdk), targetSdk=int(expected_target_sdk),
                  workflowRun=os.environ.get("GITHUB_RUN_ID"), validation="unit tests, lint, AAR and Play AAB compatibility required on this SHA")
(artifacts / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
