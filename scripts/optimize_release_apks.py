"""Losslessly recompress unsigned distribution APKs and restore Android alignment."""
import argparse
import copy
import hashlib
from pathlib import Path
import shutil
import subprocess
import time
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


TOOL_TIMEOUT_SECONDS = 180
BATCH_TIMEOUT_SECONDS = 900
STAGING_RESERVE_BYTES = 128 * 1024 * 1024


def remaining(deadline):
    seconds = deadline - time.monotonic()
    if seconds <= 0:
        raise TimeoutError("APK optimization deadline expired")
    return seconds


def run_tool(command, deadline):
    return subprocess.run(command, check=True, timeout=min(TOOL_TIMEOUT_SECONDS, remaining(deadline)))


def admit_staging(path, original_size, entries):
    required = 2 * (original_size + sum(entry.file_size for entry in entries)) + STAGING_RESERVE_BYTES
    if shutil.disk_usage(path.parent).free < required:
        raise OSError("Insufficient staging space for lossless APK optimization")


def copy_member(source, destination, entry):
    with source.open(entry) as reader, destination.open(copy.copy(entry), "w") as writer:
        shutil.copyfileobj(reader, writer, 1024 * 1024)


def verify_contents(source, result):
    if sorted(source.namelist()) != sorted(result.namelist()):
        raise ValueError("APK entry inventory changed during compression")
    for original in source.infolist():
        entry = result.getinfo(original.filename)
        if entry.file_size != original.file_size or (original.compress_type == ZIP_STORED and entry.compress_type != ZIP_STORED):
            raise ValueError(f"APK size or required direct access changed for {entry.filename}")
        with source.open(original) as before, result.open(entry) as after:
            if hashlib.file_digest(before, "sha256").digest() != hashlib.file_digest(after, "sha256").digest():
                raise ValueError(f"APK content changed for {entry.filename}")


def optimize(path, zipalign, *, deadline=None):
    if deadline is None:
        deadline = time.monotonic() + BATCH_TIMEOUT_SECONDS
    remaining(deadline)
    if not path.name.endswith("-unsigned.apk"):
        raise ValueError(f"Expected an unsigned release APK: {path.name}")
    original_size = path.stat().st_size
    with TemporaryDirectory(dir=path.parent) as directory:
        compressed = Path(directory) / "members.zip"
        aligned = Path(directory) / "aligned.apk"
        with ZipFile(path) as source:
            entries = source.infolist()
            if len(entries) != len(set(source.namelist())):
                raise ValueError("Duplicate APK entries")
            with path.open("rb") as raw:
                raw.seek(max(0, source.start_dir - 16))
                has_signing_block = raw.read(16) == b"APK Sig Block 42"
            if has_signing_block or any(e.filename.upper().startswith("META-INF/") and e.filename.upper().endswith(".SF") for e in entries):
                raise ValueError("APK already signed")
            # Keep stored assets (including the mmap-backed embedding model),
            # resources and small members outside the recompression step.
            selected = {e.filename for e in entries if e.compress_type == ZIP_DEFLATED and e.compress_size >= 64 * 1024}
            if not selected:
                raise ValueError("No compressed distribution payloads found")
            admit_staging(path, original_size, entries)
            remaining(deadline)
            with ZipFile(compressed, "w") as archive:
                for entry in entries:
                    if entry.filename in selected:
                        copy_member(source, archive, entry)
            run_tool(["advzip", "--recompress", "--shrink-normal", str(compressed)], deadline)
            remaining(deadline)
            with ZipFile(compressed, "a") as archive:
                for entry in entries:
                    if entry.filename not in selected:
                        copy_member(source, archive, entry)
            run_tool([zipalign, "-P", "16", "-f", "4", str(compressed), str(aligned)], deadline)
            run_tool([zipalign, "-c", "-P", "16", "4", str(aligned)], deadline)
            with ZipFile(aligned) as result:
                verify_contents(source, result)
        if aligned.stat().st_size > original_size:
            raise ValueError("Optimized APK grew; original retained")
        remaining(deadline)
        aligned.replace(path)
        print(f"{path.name}: {original_size:,} -> {path.stat().st_size:,} bytes")


def optimize_all(paths, zipalign):
    deadline = time.monotonic() + BATCH_TIMEOUT_SECONDS
    for path in paths:
        optimize(path, zipalign, deadline=deadline)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zipalign", required=True)
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    optimize_all(args.apks, args.zipalign)
