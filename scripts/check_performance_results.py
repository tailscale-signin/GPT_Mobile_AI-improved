#!/usr/bin/env python3
"""Compare measured startup/frame latency on the same device model and Android build."""
import argparse
import json
import math
from pathlib import Path

LATENCY_METRICS = {"timeToInitialDisplayMs", "timeToFullDisplayMs", "frameDurationCpuMs", "frameOverrunMs"}


def metrics(document):
    output = {}
    for benchmark in document.get("benchmarks", []):
        for category in ("metrics", "sampledMetrics"):
            for name, data in benchmark.get(category, {}).items():
                if name not in LATENCY_METRICS:
                    continue
                for statistic in ("median", "P50", "P90", "P95", "P99"):
                    value = data.get(statistic)
                    if isinstance(value, (int, float)) and math.isfinite(value):
                        output[f"{benchmark['name']}:{name}:{statistic}"] = value
    return output


def identity(document):
    build = document.get("context", {}).get("build", {})
    value = (build.get("device"), build.get("model"), build.get("fingerprint"), build.get("version", {}).get("sdk"))
    if not all(value):
        raise ValueError("Device model, build fingerprint and SDK are required in both result files.")
    return value


def check(baseline, current, tolerance):
    if identity(baseline) != identity(current):
        raise ValueError("Baseline and candidate must use the same device model and Android build.")
    before, after = metrics(baseline), metrics(current)
    if not before or before.keys() != after.keys():
        raise ValueError("Benchmark suites or measured latency metrics do not match.")
    # Absolute tolerance prevents a near-zero/negative frame-overrun baseline from reversing the comparison.
    return [f"{key}: {after[key]:.2f} ms exceeds {before[key]:.2f} ms + tolerance" for key in before if after[key] > before[key] + max(abs(before[key]) * tolerance, 0.1)]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("current", type=Path)
    parser.add_argument("--tolerance", type=float, default=0.15)
    args = parser.parse_args()
    if not 0 <= args.tolerance <= 1:
        parser.error("Tolerance must be between 0 and 1.")
    failures = check(json.loads(args.baseline.read_text()), json.loads(args.current.read_text()), args.tolerance)
    if failures:
        raise SystemExit("\n".join(failures))
    print("Measured latency gate passed. Confirm the same physical device and workload configuration were used.")
