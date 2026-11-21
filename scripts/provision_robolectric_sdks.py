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

# Android API 31–36 runtimes used by this repository's Robolectric suites.
# Each JAR and POM is pinned to its SHA-512 sidecar from Maven Central.
SDKS = {
    "12-robolectric-7732740-i7": {
        "jar": "1fe2738ae44f06ec77ddfd61a04ab80d6d070a7692724d983855157065b13d31a2dc98363846d7f6178e287b0a445a71adf4f6661e8443b21b4682b433ca5399",
        "pom": "478406c439d99341d5b2a3712d0bef96eb7f3bf701c09985f42dca045202cab39ddca1cc82909f6c042cb75a82c30c2c0c72eebbbfc18da890d46ce94964a464"
    },
    "12.1-robolectric-8229987-i7": {
        "jar": "747d403cd1e80a898a9ffb917920ce094216312b3dabaa312410e100647102b4141d2fbd1efca32251308e713fd69dca72c0971f93f7d6f1247d19a13cbac4ef",
        "pom": "6593aa91a6d1cf671c8bc0217615150a56b865b288552d5eda6c6a36888ed14284531ad2827b8f8a02148556fd622a5394f964342243014a44dc19aeab0feca9"
    },
    "13-robolectric-9030017-i7": {
        "jar": "0ffcb15c867dcfd87e57b6eef601067de84d23db0e818f8ea8a6035745482a144e91eb65a7f532dd9614ce27eba67204b117b7185058c2aac074dcb26e62acc9",
        "pom": "c9df74b02f9ed13b1797ab0d4e3e8deae2a1d1d40cc4de8f22d318830de438c6bdccf1385b4f76e72585f523af3bfc474de485dd47cd9e3aa4673dc468833a0f"
    },
    "14-robolectric-10818077-i7": {
        "jar": "7cdc0b1306e7865f1acd25a363073149654caee5b8e278c5ae37ff4724185724eff39f0b0111800b521e640bb28698d9d2e655134a801569a0f07470e7b5f455",
        "pom": "fb97a3028bb1eb4c4b3d1ab9c23c8b6b245842139f76194afc16afe002a212c2f93baff400a83cb6f35e525d0753e5b13aeda172fbdf0f628b25d21aa4a065d6"
    },
    "15-robolectric-13954326-i7": {
        "jar": "5fc64ffccf4788154162686b3966ddd63abea8326542c465e2a7f0129fc92770d0e1e8ddcfabd7f1500e491ec02e3b4104b9e51b61fccd9cf08006c9dc02987c",
        "pom": "e16eb42ba12b823ede906bec317f73688c58d05ebc7f6f4c39ff555c08926515aac59dbddddd888b87c85a022d756d6aa1520cc0276e2adee2caf946ad79e659"
    },
    "16-robolectric-13921718-i7": {
        "jar": "0576318b89abb0a1c9a760a61bb68ccd2d509b33164b8265d6b1fb587a4b7629e1e805b6c7f8fbefab94fc03c8db8100b1d4856a32728803c242d2677aa7ec76",
        "pom": "1638998c677480fb21c074cc66ce286123f581b6c6a9c7b82fdc13f2b1f3ae806bcadb790c8fb73c2606e63bcef71ef9e26c75e874bf94a02caf028a098333d6"
    }
}
BASE = "https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented"
# A separately routed canonical Maven Central endpoint. Both hosts are trusted
# only as transports: every jar and POM still requires the pinned SHA-512.
MAVEN_CENTRAL_ALTERNATE_BASE = "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented"
# Google Cloud's documented Maven Central mirror provides an independent
# transport when both canonical Central hosts share an HTTP 429 throttle.
# Every jar/POM is still checked against the same publisher-pinned SHA-512.
GOOGLE_MAVEN_CENTRAL_BASE = "https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/android-all-instrumented"
PINNED_CENTRAL_ARTIFACTS = {
    f"{BASE}/{version}/android-all-instrumented-{version}.{extension}": digest
    for version, artifacts in SDKS.items() for extension, digest in artifacts.items()
}
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
    # Never forward custom or credential-bearing repository URLs to mirrors.
    # Only this source-owned Maven Central path can rotate among the two
    # canonical hosts and Google's separately routed public Central mirror.
    sources = (url,)
    if url.startswith(BASE + "/"):
        suffix = url[len(BASE):]
        sources = (
            url,
            MAVEN_CENTRAL_ALTERNATE_BASE + suffix,
            GOOGLE_MAVEN_CENTRAL_BASE + suffix,
        )
    for attempt in range(THROTTLED_ATTEMPTS):
        temporary = None
        try:
            remaining = deadline - clock()
            if remaining <= 0:
                raise TimeoutError("Robolectric provisioning deadline expired")
            with opener(sources[attempt % len(sources)], timeout=min(20, remaining)) as source, tempfile.NamedTemporaryFile(dir=target.parent, suffix=".part", delete=False) as output:
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
            selected = sources[attempt % len(sources)]
            # Central edges can miss an already published POM. A primary 404 may
            # rotate only for an exact repository-owned URL and its matching pin.
            missing_at_edge = (
                isinstance(error, urllib.error.HTTPError)
                and error.code == 404
                and len(sources) > 1
                and (selected != url or PINNED_CENTRAL_ARTIFACTS.get(url) == digest)
            )
            if isinstance(error, urllib.error.HTTPError) and error.code not in TRANSIENT_STATUS and not missing_at_edge:
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
