#!/usr/bin/env python3
"""Reject APKs containing telemetry upload backends or the embedding SDK's remote logger."""
import argparse
from pathlib import Path
from zipfile import ZipFile


FORBIDDEN = (
    b"com/google/android/datatransport/",
    b"com/google/firebase/analytics/",
    b"com/google/android/gms/measurement/",
    b"TasksStatsProtoLogger",
    b"RemoteLoggingClient",
)


def check_apk(path):
    with ZipFile(path) as apk:
        dex_files = [name for name in apk.namelist() if name.startswith("classes") and name.endswith(".dex")]
        assert dex_files, f"{path}: no application DEX files"
        for name in dex_files:
            data = apk.read(name)
            for marker in FORBIDDEN:
                assert marker not in data, f"{path}: telemetry component remains in {name}: {marker.decode()}"
    print(f"{Path(path).name}: telemetry upload components absent")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", type=Path)
    for path in parser.parse_args().apks:
        check_apk(path)


if __name__ == "__main__":
    main()
