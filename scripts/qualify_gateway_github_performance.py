#!/usr/bin/env python3
"""Evaluate measured navigation/frame targets without converting gaps to passes."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def qualify(document, source_sha):
    if not re.fullmatch(r"[a-f0-9]{40}", source_sha) or document.get("sourceSha") != source_sha:
        raise ValueError("Performance evidence must identify the exact tested source")
    identity = document.get("conditions", {})
    required = {"apkSha256", "deviceSerialHash", "deviceModel", "androidBuild", "refreshRateHz", "thermalState", "network", "debugCapture", "workloadRevision", "buildType"}
    if not required.issubset(identity) or identity.get("buildType") not in {"release", "benchmark"}:
        raise ValueError("Release build and reproducible physical workload conditions are required")
    for key in ("apkSha256", "deviceSerialHash"):
        if not re.fullmatch(r"[a-f0-9]{64}", str(identity[key])):
            raise ValueError("Physical package/device evidence is incomplete")
    refresh = identity["refreshRateHz"]
    if type(refresh) not in (int, float) or not math.isfinite(refresh) or not 1 <= refresh <= 1000:
        raise ValueError("An observed display refresh rate is required")
    result = {"schemaVersion": 1, "sourceSha": source_sha, "conditions": identity,
              "status": "NOT_QUALIFIED", "missing": [], "failed": [], "measurements": {}}
    navigation = document.get("navigation", [])
    if not isinstance(navigation, list) or len(navigation) > 10000:
        raise ValueError("Navigation sample bound exceeded")
    navigation_ids = set()
    for row in navigation:
        key = (row.get("source"), row.get("bootId"), row.get("sequence"))
        if any(value is None for value in key) or key in navigation_ids:
            raise ValueError("Missing or duplicate navigation trial identity")
        navigation_ids.add(key)
    for field, limit in (("firstUsefulFrameMs", 300), ("composerReadyMs", 500)):
        samples = []
        for row in navigation:
            # A screen-composition span cannot satisfy a navigation-input target.
            if row.get("cached") is True and row.get("origin") == "NAVIGATION_ACTION" and row.get("clock") == "SOURCE_MONOTONIC":
                value = row.get(field)
                if type(value) in (int, float) and math.isfinite(value) and value >= 0:
                    samples.append(value)
        if len(samples) < 30:
            result["missing"].append(field + ": at least 30 measured cached navigation trials required")
        else:
            observed = percentile(samples, .95)
            result["measurements"][field] = {"samples": len(samples), "p50": percentile(samples, .5), "p95": observed, "p99": percentile(samples, .99), "targetP95": limit}
            if observed > limit:
                result["failed"].append(field + " P95 target exceeded")
    windows = document.get("frameWindows", [])
    if not isinstance(windows, list) or len(windows) > 100000:
        raise ValueError("Frame sample bound exceeded")
    totals = dict(frames=0, jankFrames=0, droppedSamples=0, stallsOver100Ms=0, stallsOver500Ms=0, fallbackDeadlines=0)
    seen = set()
    for row in windows:
        identity_key = (row.get("source"), row.get("bootId"), row.get("sequence"))
        if any(value is None for value in identity_key) or identity_key in seen:
            raise ValueError("Missing or duplicate frame window identity")
        seen.add(identity_key)
        for key in totals:
            value = row.get(key)
            if type(value) is not int or value < 0:
                raise ValueError("Frame counters must be measured nonnegative integers")
            totals[key] += value
        if row["jankFrames"] > row["frames"] or not 0 <= row["stallsOver500Ms"] <= row["stallsOver100Ms"] <= row["frames"]:
            raise ValueError("Frame window denominator is inconsistent")
    result["measurements"]["frames"] = totals
    if totals["frames"] < 1000:
        result["missing"].append("At least 1000 measured workload frames required")
    if totals["droppedSamples"] or totals["fallbackDeadlines"]:
        result["missing"].append("Dropped samples or unavailable actual frame deadlines prevent complete jank qualification")
    if totals["frames"]:
        rate = totals["jankFrames"] / totals["frames"]
        result["measurements"]["frames"]["jankRate"] = rate
        if rate >= .01:
            result["failed"].append("Deadline-relative jank must be below 1%")
    if totals["stallsOver500Ms"]:
        result["failed"].append("Frames above 500 ms require trace attribution and remediation")
    result["status"] = "FAILED" if result["failed"] else "NOT_QUALIFIED" if result["missing"] else "PASSED_OBSERVED_WORKLOAD"
    result["limits"] = ["This result applies to the recorded workload and device only.", "No stall cause, GPU execution or inference speedup is inferred."]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.evidence.stat().st_size > 32 * 1024 * 1024:
        parser.error("Performance evidence exceeds its bound")
    result = qualify(json.loads(args.evidence.read_text()), args.source_sha)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(result["status"])
    if result["status"] != "PASSED_OBSERVED_WORKLOAD":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
