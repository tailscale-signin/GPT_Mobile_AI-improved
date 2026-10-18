#!/usr/bin/env python3
"""Report compressed APK contributions and enforce explicit per-ABI download budgets."""
import argparse
import json
import zipfile
from pathlib import Path

BUDGETS = {"arm64-v8a": 180 * 1024 * 1024, "x86_64": 180 * 1024 * 1024, "universal": 230 * 1024 * 1024}
# Debug builds retain unshrunk DEX, coverage instrumentation and native debug code.
# Baseline: main 20cb01de, run 39109834697: 300.38 / 232.80 / 363.07 MiB.
# Keep roughly 7% headroom without relaxing the production download limits above.
DEBUG_BUDGETS = {"arm64-v8a": 320 * 1024 * 1024, "x86_64": 250 * 1024 * 1024, "universal": 390 * 1024 * 1024}
BUDGET_PROFILES = {"release": BUDGETS, "debug": DEBUG_BUDGETS}


def inspect(path, build_type="release"):
    budgets = BUDGET_PROFILES[build_type]
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        abis = sorted({entry.filename.split("/")[1] for entry in entries if entry.filename.startswith("lib/") and entry.filename.count("/") >= 2})
        edition = abis[0] if len(abis) == 1 and abis[0] in budgets else "universal"
        size = path.stat().st_size
        return {"file": path.name, "abis": abis, "build_type": build_type, "budget_class": edition, "bytes": size, "budget_bytes": budgets[edition],
                "passed": size <= budgets[edition], "largest_entries": [{"path": entry.filename, "compressed_bytes": entry.compress_size, "expanded_bytes": entry.file_size} for entry in sorted(entries, key=lambda entry: entry.compress_size, reverse=True)[:20]]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--build-type", choices=sorted(BUDGET_PROFILES), default="release",
                        help="Explicit artifact variant; defaults to the stricter release limits.")
    args = parser.parse_args()
    paths = sorted({apk for path in args.paths for apk in (path.rglob("*.apk") if path.is_dir() else [path])})
    if not paths:
        parser.error("No APK files found; the size gate cannot pass without artifacts.")
    records = [inspect(path, args.build_type) for path in paths]
    report = json.dumps({"schema": 1, "artifacts": records}, indent=2)
    if args.report:
        args.report.write_text(report + "\n")
    print(report)
    return 0 if all(record["passed"] for record in records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
