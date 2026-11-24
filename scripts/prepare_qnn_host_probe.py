#!/usr/bin/env python3
"""Fetch the publisher-verified host libraries for the tiny QNN context regression."""
import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import zipfile

from build_qualcomm_dispatch_candidate import checked_download, digest

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "docs/audits/qnn-host-probe-publisher-2.8.json"


def extract_libraries(archive, destination, publisher):
    files = publisher["files"]
    if not 1 <= len(files) <= 32 or sum(value["bytes"] for value in files.values()) > 512 * 1024**2:
        raise ValueError("Host library inventory is outside its bounded scope")
    if any(PurePosixPath(name).name != name or name in ("", ".", "..") for name in files):
        raise ValueError("Host library names must be local basenames")
    with zipfile.ZipFile(archive) as package:
        for name, expected in files.items():
            matches = [entry for entry in package.infolist() if PurePosixPath(entry.filename).name == name]
            if len(matches) != 1:
                raise ValueError("Expected exactly one archive member for each host library")
            entry = matches[0]
            relative = PurePosixPath(entry.filename)
            if relative.is_absolute() or ".." in relative.parts or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Invalid host library archive entry")
            if entry.file_size != expected["bytes"]:
                raise ValueError("Host library size differs from the verified publisher artifact")
            target = destination / name
            with package.open(entry) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output, 64 * 1024)
            if digest(target) != expected["sha256"]:
                raise ValueError("Host library hash differs from the verified publisher artifact")


def prepare(manifest, output):
    publisher = json.loads(manifest.read_text())
    if publisher.get("schemaVersion") != 1 or publisher["publisherSha256"] != publisher["downloadedSha256"]:
        raise ValueError("Expected an audited publisher artifact identity")
    if output.exists():
        raise ValueError("Choose a new host library output directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".qnn-host-", dir=output.parent) as temporary:
        root = Path(temporary)
        archive = checked_download({"url": publisher["url"], "bytes": publisher["bytes"],
                                    "sha256": publisher["publisherSha256"]}, root / "publisher.whl")
        libraries = root / "libraries"
        libraries.mkdir()
        extract_libraries(archive, libraries, publisher)
        shutil.copyfile(manifest, libraries / "publisher-manifest.json")
        libraries.rename(output)
    print(f"Prepared {len(publisher['files'])} verified host files; device qualification is not granted.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publisher-manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.publisher_manifest.resolve(), args.output_dir.resolve())
