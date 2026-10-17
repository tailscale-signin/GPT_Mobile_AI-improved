#!/usr/bin/env python3
"""Distinguish a version-changing push from an ordinary build configuration edit."""

import os
from pathlib import Path
import re
import subprocess


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


def main() -> None:
    publish = release_requested(
        os.environ["GITHUB_EVENT_NAME"],
        os.environ.get("PUSH_BEFORE_SHA", ""),
    )
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        output.write(f"publish={str(publish).lower()}\n")
    if publish:
        print("Release requested; all release validation gates remain mandatory.")
    else:
        print("Version unchanged; this build configuration update does not publish a release.")


if __name__ == "__main__":
    main()
