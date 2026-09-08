#!/usr/bin/env python3
"""Detect malformed XML and duplicate Android resources in each values directory."""

from __future__ import annotations

import collections
import pathlib
import sys
import xml.etree.ElementTree as ET

RESOURCE_ROOT = pathlib.Path("app/src/main/res")
IGNORED_TAGS = {"eat-comment", "public", "skip"}


def location(element: ET.Element, path: pathlib.Path) -> str:
    line = getattr(element, "sourceline", None)
    return f"{path}:{line}" if line else str(path)


def main() -> int:
    errors: list[str] = []
    definitions: dict[tuple[pathlib.Path, str, str], list[str]] = collections.defaultdict(list)
    files = sorted(RESOURCE_ROOT.glob("values*/*.xml"))

    if not files:
        print(f"::error::No Android values XML files found below {RESOURCE_ROOT}")
        return 1

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            message = f"Malformed XML in {path}: {error}"
            errors.append(message)
            print(f"::error file={path}::{message}")
            continue

        if root.tag != "resources":
            message = f"Expected <resources> as root element in {path}"
            errors.append(message)
            print(f"::error file={path}::{message}")
            continue

        for element in root:
            name = element.get("name")
            if not name or element.tag in IGNORED_TAGS:
                continue
            resource_type = element.get("type") if element.tag == "item" else element.tag
            if not resource_type:
                continue
            # Android permits the same resource in different qualifiers, but not
            # multiple definitions in one configuration directory.
            key = (path.parent, resource_type, name)
            definitions[key].append(location(element, path))

    for (directory, resource_type, name), locations in sorted(definitions.items(), key=lambda item: str(item[0])):
        if len(locations) < 2:
            continue
        message = (
            f"Duplicate {resource_type}/{name} in {directory}: "
            + ", ".join(locations)
        )
        errors.append(message)
        first_path = locations[0].split(":", 1)[0]
        print(f"::error file={first_path}::{message}")

    if errors:
        print(f"Resource preflight failed with {len(errors)} error(s).", file=sys.stderr)
        return 1

    print(f"Resource preflight passed ({len(files)} XML files checked).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
