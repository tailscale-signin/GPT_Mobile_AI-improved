#!/usr/bin/env python3
"""Provision a pinned model before Gradle; builds themselves never download weights."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SPEC = json.loads((ROOT / "gradle/model-artifacts.json").read_text())["memory"]


def verified(path, spec=SPEC):
    if not path.is_file() or path.stat().st_size != spec["bytes"]:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest() == spec["sha256"]


def provision(cache, local=None, offline=False, spec=SPEC, opener=urllib.request.urlopen, wait=time.sleep):
    target = Path(cache) / spec["sha256"] / spec["filename"]
    if local is not None and not verified(Path(local), spec):
        raise ValueError("The supplied memory model does not match the pinned size and SHA-256.")
    if verified(target, spec):
        return target
    if local is None and offline:
        raise ValueError("No verified model is cached. Provision online first or pass --file /path/to/the/pinned/model.")
    target.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(3):
        temporary = None
        try:
            deadline = time.monotonic() + 180
            source = Path(local).open("rb") if local else opener(spec["url"], timeout=120)
            with source, tempfile.NamedTemporaryFile(dir=target.parent, prefix="model-", suffix=".part", delete=False) as output:
                temporary = Path(output.name)
                total = 0
                while chunk := source.read(65536):
                    if time.monotonic() > deadline:
                        raise TimeoutError("Memory model provisioning exceeded the per-attempt deadline.")
                    total += len(chunk)
                    if total > spec["bytes"]:
                        raise ValueError("Downloaded memory model exceeds its pinned size.")
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if not verified(temporary, spec):
                raise ValueError("Memory model integrity check failed; nothing was installed.")
            os.replace(temporary, target)
            return target
        except (OSError, ValueError):
            if local is not None or attempt == 2:
                raise
            wait(2 ** attempt)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/gptmobile-models"
    parser.add_argument("--cache-dir", type=Path, default=default_cache)
    parser.add_argument("--file", type=Path)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        print(provision(args.cache_dir, args.file, args.offline))
    except (OSError, ValueError) as error:
        parser.exit(1, f"Memory model provisioning failed: {error}\n")


if __name__ == "__main__":
    main()
