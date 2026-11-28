#!/usr/bin/env python3
"""Fetch only the audited QAIRT SDK headers; verify their complete pinned tree.

The SDK is a 3 GB ZIP. HTTP range reads avoid downloading unrelated toolchains.
Header authenticity is bound to the existing publisher-audited tree digest,
not to untrusted ZIP CRCs or a locally regenerated checksum.
"""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tempfile
import urllib.request
import urllib.parse
import zipfile


def headers_digest(root):
    digest = hashlib.sha256()
    for path in sorted((root / "include/QNN").rglob("*.h")):
        digest.update(path.relative_to(root).as_posix().encode() + b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def sdk_manifest(manifest):
    """Require the audited SDK shape; a host-library wheel is not an SDK pin."""
    record = json.loads(manifest.read_text())
    if (not isinstance(record, dict) or type(record.get("schemaVersion")) is not int
            or record["schemaVersion"] != 1
            or not isinstance(record.get("dispatchBuild"), dict)
            or not isinstance(record.get("qairtSdk"), dict)):
        raise ValueError("Expected an audited QAIRT SDK header manifest, not a host-library manifest")
    build, pin = record["dispatchBuild"], record["qairtSdk"]
    version, runtime = build.get("qairtSdkVersion"), build.get("qairtVersion")
    if (not isinstance(version, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+", version)
            or not isinstance(runtime, str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", runtime)
            or not version.startswith(runtime + ".")):
        raise ValueError("QAIRT SDK and runtime versions are missing or mismatched")
    for value in (build.get("qnnHeadersSha256"), pin.get("qnnHeadersSha256"), pin.get("sha256")):
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
            raise ValueError("QAIRT SDK and header identities require exact SHA-256 pins")
    if build["qnnHeadersSha256"] != pin["qnnHeadersSha256"]:
        raise ValueError("QAIRT SDK header identities differ")
    if type(pin.get("bytes")) is not int or pin["bytes"] <= 0:
        raise ValueError("QAIRT SDK archive requires an exact positive byte count")
    suffix = f"/{version}/v{version}.zip"
    for field in ("url", "resolvedUrl"):
        value = pin.get(field)
        if not isinstance(value, str):
            raise ValueError("QAIRT SDK publisher URLs do not match the pinned version")
        url = urllib.parse.urlsplit(value)
        if url.scheme != "https" or not url.netloc or not url.path.endswith(suffix) or url.query or url.fragment:
            raise ValueError("QAIRT SDK publisher URLs do not match the pinned version")
    source = pin.get("publisherChecksumSource")
    if not isinstance(source, str) or not re.fullmatch(r"onnxruntime/onnxruntime-qnn@[0-9a-f]{40}:qcom/packages\.yml", source):
        raise ValueError("QAIRT SDK checksum requires immutable publisher provenance")
    return record


class RemoteZip(io.RawIOBase):
    def __init__(self, url, size):
        self.url, self.size, self.position = url, size, 0
        self.cache = {}
        self.block_size = 1024 * 1024

    def seekable(self):
        return True

    def readable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        position = offset + (0 if whence == 0 else self.position if whence == 1 else self.size)
        if not 0 <= position <= self.size:
            raise ValueError("Invalid SDK ZIP seek")
        self.position = position
        return position

    def read(self, size=-1):
        if size < 0:
            size = self.size - self.position
        end = min(self.size, self.position + size)
        if end - self.position > 32 * 1024 * 1024:
            raise ValueError("Unbounded SDK ZIP read")
        result = bytearray()
        while self.position < end:
            index = self.position // self.block_size
            start = index * self.block_size
            stop = min(start + self.block_size, self.size) - 1
            if index not in self.cache:
                request = urllib.request.Request(self.url, headers={"Range": f"bytes={start}-{stop}"})
                with urllib.request.urlopen(request, timeout=60) as response:
                    if response.status != 206 or response.headers.get("Content-Range") != f"bytes {start}-{stop}/{self.size}":
                        raise ValueError("SDK server did not honor the exact bounded range")
                    data = response.read(self.block_size + 1)
                if len(data) != stop - start + 1:
                    raise ValueError("Incomplete SDK ZIP range")
                self.cache[index] = data
                if len(self.cache) > 32:
                    del self.cache[next(iter(self.cache))]
            count = min(end - self.position, stop - self.position + 1)
            result.extend(self.cache[index][self.position - start:self.position - start + count])
            self.position += count
        return bytes(result)


def prepare(manifest, output, offline=False):
    record = sdk_manifest(manifest)
    pin = record["qairtSdk"]
    if headers_digest(output) == pin["qnnHeadersSha256"]:
        return
    if offline:
        raise ValueError("Prepared ASR QNN headers are missing or differ from the audited pin")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="qnn-headers-", dir=output.parent) as temporary:
        staged = Path(temporary)
        prefix = f'qairt/{record["dispatchBuild"]["qairtSdkVersion"]}/'
        archive = RemoteZip(pin["resolvedUrl"], pin["bytes"])
        with zipfile.ZipFile(archive) as package:
            for entry in package.infolist():
                if entry.is_dir() or not entry.filename.startswith(prefix + "include/QNN/") or not entry.filename.endswith(".h"):
                    continue
                relative = PurePosixPath(entry.filename.removeprefix(prefix))
                if relative.is_absolute() or ".." in relative.parts or stat.S_ISLNK(entry.external_attr >> 16) or entry.file_size > 8 * 1024 * 1024:
                    raise ValueError("Invalid SDK header entry")
                target = staged.joinpath(*relative.parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(package.read(entry))
        if headers_digest(staged) != pin["qnnHeadersSha256"]:
            raise ValueError("QAIRT headers differ from the publisher-audited pin")
        if output.exists():
            shutil.rmtree(output)
        shutil.copytree(staged, output)
        # copytree preserves the private temporary root mode (0700). Only the
        # verified, published headers need to be readable by offline build users.
        output.chmod(0o755)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    prepare(args.manifest, args.output, args.offline)
