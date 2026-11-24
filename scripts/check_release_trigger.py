#!/usr/bin/env python3
"""Distinguish a version-changing push from an ordinary build configuration edit."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys


def version_identity(source: str) -> tuple[str, int]:
    names = re.findall(
        r'^[ \t]*versionName\s*=\s*"([^"\r\n]+)"[ \t]*(?://[^\r\n]*)?$',
        source,
        re.MULTILINE,
    )
    codes = re.findall(
        r"^[ \t]*versionCode\s*=\s*(\d+)[ \t]*(?://[^\r\n]*)?$",
        source,
        re.MULTILINE,
    )
    if len(names) != 1 or len(codes) != 1:
        raise ValueError("Expected exactly one literal versionName and versionCode")
    return names[0], int(codes[0])


def release_requested(event: str, before: str) -> bool:
    # Manual dispatch still reaches every existing release validation check.
    if event == "workflow_dispatch":
        return True
    if event != "push":
        raise ValueError(f"Unsupported release event: {event}")
    if not re.fullmatch(r"[0-9a-f]{40}", before):
        raise ValueError("Push event must supply a valid before commit")
    path = "app/build.gradle.kts"
    current = version_identity(Path(path).read_text(encoding="utf-8"))
    if before == "0" * 40:
        return True
    previous = subprocess.run(
        ["git", "show", f"{before}:{path}"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return current != version_identity(previous)


def coordinated_release(event: str, before: str) -> bool:
    """Only a newly reviewed version pair on main can request both publications."""
    path = "release-scope.json"
    if event != "push" or os.environ.get("GITHUB_REF") != "refs/heads/main" or not Path(path).is_file():
        return False
    manifest = json.loads(Path(path).read_text(encoding="utf-8"))
    request = manifest.get("publicationRequest")
    if request is None:
        return False
    if before != "0" * 40:
        files = subprocess.check_output(["git", "ls-tree", "--name-only", before, "--", path], text=True)
        if files.strip():
            previous = json.loads(subprocess.check_output(["git", "show", f"{before}:{path}"], text=True))
            if previous.get("publicationRequest") == request:
                return False
    android, code = version_identity(Path("app/build.gradle.kts").read_text(encoding="utf-8"))
    source = Path("gateway/v14/__init__.py").read_text(encoding="utf-8")
    version = re.search(r'^VERSION = "((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"$', source, re.MULTILINE)
    if not version or (manifest.get("version"), manifest.get("versionCode")) != (android, code) or request != {
        "android": android, "versionCode": code, "gateway": version[1]
    }:
        raise ValueError("Publication request must match the Android and Gateway versions in this checkout")
    return True


def main() -> None:
    if len(sys.argv) == 3 and sys.argv[1] == "--unsigned-pr":
        base = sys.argv[2]
        if not re.fullmatch(r"[0-9a-f]{40}", base):
            raise ValueError("A pull request requires an exact base revision")
        changed = release_requested("push", base)
        if not changed:
            # A version can stay fixed while its APK contents or size rules change.
            # Include deleted and renamed inputs via a path-limited Git diff.
            packaging = (
                ":(glob)**/build.gradle*", ":(glob)**/src/**/AndroidManifest.xml",
                ":(glob)**/src/**/cpp/**", ":(glob)**/src/**/jniLibs/**",
                ":(glob)**/src/**/assets/**", ":(glob)**/*-rules.pro",
                "gradle/", "gradle.properties", "settings.gradle.kts",
                "scripts/check_apk_size.py", "scripts/android_pipeline.py",
                ".github/workflows/unsigned-release-build.yml", ".github/workflows/release-build.yml",
            )
            changed = bool(subprocess.check_output(["git", "diff", "--name-only", "-z", base, "--", *packaging]))
        path = "release-scope.json"
        if not changed and Path(path).is_file():
            request = json.loads(Path(path).read_text(encoding="utf-8")).get("publicationRequest")
            if request is not None:
                prior = subprocess.check_output(["git", "ls-tree", "--name-only", base, "--", path], text=True)
                previous = json.loads(subprocess.check_output(["git", "show", f"{base}:{path}"], text=True)) if prior.strip() else {}
                changed = previous.get("publicationRequest") != request
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            output.write(f"build={str(changed).lower()}\n")
        return
    publish = release_requested(
        os.environ["GITHUB_EVENT_NAME"],
        os.environ.get("PUSH_BEFORE_SHA", ""),
    )
    together = coordinated_release(os.environ["GITHUB_EVENT_NAME"], os.environ.get("PUSH_BEFORE_SHA", ""))
    publish = publish or together
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        output.write(f"publish={str(publish).lower()}\n")
        if together:
            output.write("publish_gateway=true\n")
    if publish:
        print("Release requested; all release validation gates remain mandatory.")
    else:
        print("Version unchanged; this build configuration update does not publish a release.")


if __name__ == "__main__":
    main()
