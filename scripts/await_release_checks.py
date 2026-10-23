#!/usr/bin/env python3
"""Wait for successful required push checks on the exact release commit; never accept skipped checks."""
import argparse
import json
import os
import time
import urllib.request
from pathlib import Path

REQUIRED_WORKFLOWS = ('Build Android APK', 'Post-merge correctness', 'CodeQL Advanced', 'v0.9.50 Android acceptance')


def required_workflows(manifest):
    # Full releases may omit a scoped manifest, but never the exact-commit checks.
    configured = manifest.get('requiredWorkflows', [])
    if not isinstance(configured, list) or not all(isinstance(name, str) and name.strip() for name in configured):
        raise ValueError('Invalid required workflow names')
    return list(dict.fromkeys([*REQUIRED_WORKFLOWS, *configured]))


def conclusions(runs, sha, required):
    latest = {}
    for run in runs:
        if run.get("head_sha") != sha or run.get("event") not in {"push", "workflow_dispatch"} or run.get("name") not in required:
            continue
        name = run["name"]
        if name not in latest or (run.get("id", 0), run.get("run_attempt", 1)) > (latest[name].get("id", 0), latest[name].get("run_attempt", 1)):
            latest[name] = run
    failures = [name for name, run in latest.items() if run.get("status") == "completed" and run.get("conclusion") != "success"]
    pending = [name for name in required if name not in latest or latest[name].get("status") != "completed"]
    return failures, pending


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sha", required=True)
    p.add_argument("--repository", required=True)
    p.add_argument("--timeout", type=int, default=3600)
    args = p.parse_args()
    scope = Path("release-scope.json")
    required = required_workflows(json.loads(scope.read_text()) if scope.exists() else {})
    end = time.monotonic() + args.timeout
    while time.monotonic() < end:
        request = urllib.request.Request(f"https://api.github.com/repos/{args.repository}/actions/runs?head_sha={args.sha}&per_page=100", headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            runs = json.load(response)["workflow_runs"]
        failed, pending = conclusions(runs, args.sha, required)
        if failed:
            raise SystemExit("Required exact-commit checks failed: " + ", ".join(failed))
        if not pending:
            print("All required checks passed on " + args.sha)
            return
        print("Waiting for: " + ", ".join(pending), flush=True)
        time.sleep(30)
    raise SystemExit("Timed out waiting for exact-commit checks")


if __name__ == "__main__":
    main()
