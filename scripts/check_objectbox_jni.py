#!/usr/bin/env python3
"""Reject APKs/AABs missing ObjectBox's native scored-query result constructors."""
import argparse
from pathlib import Path
from zipfile import ZipFile
from check_mediapipe_jni import archive_definitions


# ObjectBox 5.4.2 JNI loads these names and constructs wrappers with NewObject.
# Checking actual class_data prevents dangling references from passing the gate.
REQUIRED_CONSTRUCTORS = {
    "Lio/objectbox/query/ObjectWithScore;": "(Ljava/lang/Object;D)V",
    "Lio/objectbox/query/IdWithScore;": "(JD)V",
}


def check_bindings(archive, label):
    definitions, dex_count = archive_definitions(archive, label, REQUIRED_CONSTRUCTORS)
    missing = []
    for owner, descriptor in REQUIRED_CONSTRUCTORS.items():
        methods = definitions.get(owner, {}).get("methods", {})
        signature = ("<init>", descriptor)
        if signature not in methods or methods[signature] & 0x8:
            missing.append(owner + "-><init>" + descriptor)
    if missing:
        raise ValueError(f"{label}: missing/renamed ObjectBox JNI definitions:\n  " + "\n  ".join(missing))
    print(f"{label}: ObjectBox scored-query JNI definitions verified across {dex_count} DEX files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", nargs="+", type=Path)
    for path in parser.parse_args().archives:
        with ZipFile(path) as archive:
            check_bindings(archive, path.name)


if __name__ == "__main__":
    main()
