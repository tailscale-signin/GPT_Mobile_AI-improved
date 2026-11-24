"""Losslessly recompress unsigned distribution APKs and restore Android alignment."""
import argparse
import copy
import hashlib
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


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


def optimize(path, zipalign):
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
            with ZipFile(compressed, "w") as archive:
                for entry in entries:
                    if entry.filename in selected:
                        copy_member(source, archive, entry)
            subprocess.run(["advzip", "--recompress", "--shrink-normal", str(compressed)], check=True)
            with ZipFile(compressed, "a") as archive:
                for entry in entries:
                    if entry.filename not in selected:
                        copy_member(source, archive, entry)
            subprocess.run([zipalign, "-P", "16", "-f", "4", str(compressed), str(aligned)], check=True)
            subprocess.run([zipalign, "-c", "-P", "16", "4", str(aligned)], check=True)
            with ZipFile(aligned) as result:
                verify_contents(source, result)
        aligned.replace(path)
        print(f"{path.name}: {original_size:,} -> {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zipalign", required=True)
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    for apk in args.apks:
        optimize(apk, args.zipalign)
