#!/usr/bin/env python3
"""Select a built APK by AGP metadata and native ABI, never its filename."""

import argparse
import json
import re
import zipfile
from pathlib import Path


def select_apk(directory, application_id, abi=None):
    directory = Path(directory).resolve()
    metadata = directory / "output-metadata.json"
    if not metadata.is_file():
        raise ValueError(f"Missing APK build metadata: {metadata}")
    data = json.loads(metadata.read_text())
    if data.get("applicationId") != application_id:
        raise ValueError("APK metadata belongs to an unexpected application")
    candidates = []
    for element in data.get("elements", []):
        filename = element.get("outputFile")
        if not isinstance(filename, str):
            raise ValueError("APK metadata has no output filename")
        path = (directory / filename).resolve()
        if path.parent != directory or path.suffix != ".apk" or not path.is_file():
            raise ValueError(f"APK metadata references a missing or out-of-directory artifact: {filename}")
        filters = element.get("filters", [])
        abi_filters = [item.get("value") for item in filters if item.get("filterType") == "ABI"]
        if abi and abi_filters and abi_filters != [abi]:
            continue
        with zipfile.ZipFile(path) as apk:
            native_abis = {match[1] for name in apk.namelist() if (match := re.fullmatch(r"lib/([^/]+)/[^/]+\.so", name))}
        if abi and abi not in native_abis:
            continue
        candidates.append((0 if abi and abi_filters == [abi] else 1, path))
    if not candidates:
        raise ValueError(f"No compatible APK for {application_id}, ABI {abi or 'any'} in {metadata}")
    priority = min(item[0] for item in candidates)
    selected = {path for rank, path in candidates if rank == priority}
    if len(selected) != 1:
        raise ValueError("Ambiguous APK artifacts; clean the build and regenerate its metadata")
    return selected.pop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--application-id", required=True)
    parser.add_argument("--abi")
    args = parser.parse_args()
    try:
        print(select_apk(args.directory, args.application_id, args.abi))
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"APK selection failed: {error}\n")


if __name__ == "__main__":
    main()
