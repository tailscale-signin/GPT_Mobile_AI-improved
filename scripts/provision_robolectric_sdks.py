#!/usr/bin/env python3
"""Provision verified Robolectric runtimes before tests encounter network throttling."""
import argparse
from datetime import timezone
from email.utils import parsedate_to_datetime
import hashlib
import http.client
import os
from pathlib import Path
import random
import tempfile
import time
import urllib.error
import urllib.request

# SHA-512 values verified against Maven Central's publisher sidecars, 2026-11-02.
SDKS = {
    "14-robolectric-10818077-i7": {
        "jar": "7cdc0b1306e7865f1acd25a363073149654caee5b8e278c5ae37ff4724185724eff39f0b0111800b521e640bb28698d9d2e655134a801569a0f07470e7b5f455",
        "pom": "fb97a3028bb1eb4c4b3d1ab9c23c8b6b245842139f76194afc16afe002a212c2f93baff400a83cb6f35e525d0753e5b13aeda172fbdf0f628b25d21aa4a065d6"
    },
    "15-robolectric-13954326-i7": {
        "jar": "5fc64ffccf4788154162686b3966ddd63abea8326542c465e2a7f0129fc92770d0e1e8ddcfabd7f1500e491ec02e3b4104b9e51b61fccd9cf08006c9dc02987c",
        "pom": "e16eb42ba12b823ede906bec317f73688c58d05ebc7f6f4c39ff555c08926515aac59dbddddd888b87c85a022d756d6aa1520cc0276e2adee2caf946ad79e659"
    }
}
BASE = "https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented"
TRANSIENT_STATUS = {408, 429, 500, 502, 503, 504}
TRANSIENT_ATTEMPTS = 3
THROTTLED_ATTEMPTS = 6
MAX_RETRY_BACKOFF_SECONDS = 60


def verified(path, digest):
    if not path.is_file():
        return False
    checksum = hashlib.sha512()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            checksum.update(chunk)
    return checksum.hexdigest() == digest


def retry_after_seconds(error, wall_clock=time.time):
    if not isinstance(error, urllib.error.HTTPError) or error.headers is None:
        return None
    value = error.headers.get("Retry-After", "").strip()
    if not value:
        return None
    if value.isascii() and value.isdigit():
        try:
            return int(value)
        except ValueError:
            return None
    try:
        retry_at = parsedate_to_datetime(value)
        if retry_at.tzinfo is None:
            retry_at = retry_at.replace(tzinfo=timezone.utc)
        return max(0, retry_at.timestamp() - wall_clock())
    except (OverflowError, TypeError, ValueError):
        return None


def download(target, url, digest, deadline, opener=urllib.request.urlopen, wait=time.sleep, clock=time.monotonic,
             jitter=random.uniform, wall_clock=time.time):
    if verified(target, digest):
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    attempts_allowed = TRANSIENT_ATTEMPTS
    for attempt in range(THROTTLED_ATTEMPTS):
        temporary = None
        try:
            remaining = deadline - clock()
            if remaining <= 0:
                raise TimeoutError("Robolectric provisioning deadline expired")
            with opener(url, timeout=min(20, remaining)) as source, tempfile.NamedTemporaryFile(dir=target.parent, suffix=".part", delete=False) as output:
                temporary = Path(output.name)
                total = 0
                while chunk := source.read(65536):
                    if clock() >= deadline:
                        raise TimeoutError("Robolectric provisioning deadline expired")
                    total += len(chunk)
                    if total > 512 * 1024 * 1024:
                        raise ValueError("Robolectric artifact exceeds the download bound")
                    output.write(chunk)
            if not verified(temporary, digest):
                raise ValueError("Robolectric artifact SHA-512 does not match its publisher-verified pin")
            os.replace(temporary, target)
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.IncompleteRead) as error:
            if isinstance(error, urllib.error.HTTPError) and error.code not in TRANSIENT_STATUS:
                raise
            throttled = isinstance(error, urllib.error.HTTPError) and error.code == 429
            if throttled:
                # Shared CI egress can remain throttled beyond the former 15-second
                # retry window. Keep the overall deadline and permit bounded recovery.
                attempts_allowed = THROTTLED_ATTEMPTS
            remaining = deadline - clock()
            if attempt + 1 >= attempts_allowed or remaining <= 0:
                raise
            backoff = min((15 if throttled else 5) * (2 ** attempt), MAX_RETRY_BACKOFF_SECONDS)
            pause = max(backoff, retry_after_seconds(error, wall_clock) or 0)
            if pause < remaining:
                pause += jitter(0, min(5, backoff / 5))
            detail = f"HTTP {error.code}" if isinstance(error, urllib.error.HTTPError) else type(error).__name__
            if pause >= remaining:
                print(f"Robolectric {detail}: next retry cannot fit within the provisioning deadline", flush=True)
                raise
            print(f"Transient Robolectric download failure ({detail}); retry {attempt + 2}/{attempts_allowed} after {pause:g}s", flush=True)
            wait(pause)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def provision(repository, sdks=SDKS, opener=urllib.request.urlopen, wait=time.sleep, clock=time.monotonic):
    deadline = clock() + 480
    for version, artifacts in sdks.items():
        for extension, digest in artifacts.items():
            filename = f"android-all-instrumented-{version}.{extension}"
            target = Path(repository) / "org/robolectric/android-all-instrumented" / version / filename
            download(target, f"{BASE}/{version}/{filename}", digest, deadline, opener, wait, clock)
            target.with_suffix(target.suffix + ".sha512").write_text(digest + "\n")
            print(f"Verified {filename}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.home() / ".m2/repository")
    args = parser.parse_args()
    provision(args.repository)
