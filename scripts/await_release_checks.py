#!/usr/bin/env python3
"""Wait for successful required push checks on the exact release commit; never accept skipped checks."""
import argparse
import json
import os
import time
import urllib.request
from urllib.parse import urlsplit, parse_qs
from pathlib import Path
import re

REQUIRED_WORKFLOWS = ('PR Validation', 'Build Android APK', 'Post-merge correctness', 'CodeQL Advanced', 'v0.9.50 Android acceptance')


def required_workflows(manifest):
    # Full releases may omit a scoped manifest, but never the exact-commit checks.
    configured = manifest.get('requiredWorkflows', [])
    if not isinstance(configured, list) or not all(isinstance(name, str) and name.strip() for name in configured):
        raise ValueError('Invalid required workflow names')
    return list(dict.fromkeys([*REQUIRED_WORKFLOWS, *configured]))


def latest_runs(runs, sha, required):
    latest = {}
    for run in runs:
        if run.get("head_sha") != sha or run.get("event") not in {"push", "workflow_dispatch"} or run.get("name") not in required:
            continue
        name = run["name"]
        if name not in latest or (run.get("id", 0), run.get("run_attempt", 1)) > (latest[name].get("id", 0), latest[name].get("run_attempt", 1)):
            latest[name] = run
    return latest


def conclusions(runs, sha, required):
    latest = latest_runs(runs, sha, required)
    failures = [name for name, run in latest.items() if run.get("status") == "completed" and run.get("conclusion") != "success"]
    pending = [name for name in required if name not in latest or latest[name].get("status") != "completed"]
    return failures, pending


def fetch_runs(repository, sha, token, *, opener=urllib.request.urlopen):
    """Finish every server-issued page; incomplete inventory never satisfies CI."""
    root = f"/repos/{repository}/actions/runs"
    url = f"https://api.github.com{root}?head_sha={sha}&per_page=100"
    seen, runs = set(), []
    while url:
        parts = urlsplit(url)
        query = parse_qs(parts.query)
        if (parts.scheme != "https" or parts.netloc != "api.github.com" or parts.path != root
                or query.get("head_sha") != [sha] or url in seen or len(seen) >= 10):
            raise ValueError("Untrusted or incomplete workflow continuation")
        seen.add(url)
        request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token,
                                                      "Accept": "application/vnd.github+json"})
        with opener(request, timeout=30) as response:
            raw = response.read(8 * 1024 * 1024 + 1)
            if len(raw) > 8 * 1024 * 1024:
                raise ValueError("Workflow page exceeds the response bound")
            document = json.loads(raw)
            page = document.get("workflow_runs")
            if not isinstance(page, list) or len(page) > 100:
                raise ValueError("Invalid workflow page")
            runs.extend(page)
            matches = re.findall(r'<([^>]+)>;\s*rel="next"', response.headers.get("Link", ""))
            if len(matches) > 1:
                raise ValueError("Ambiguous workflow pagination")
            url = matches[0] if matches else None
            if not url and type(document.get("total_count")) is int and document["total_count"] > len(runs):
                raise ValueError("Workflow inventory ended before its reported total")
    return runs


def evidence_document(runs, sha, required):
    latest = latest_runs(runs, sha, required)
    failed, pending = conclusions(runs, sha, required)
    return {"schemaVersion": 1, "sourceSha": sha, "requiredWorkflows": required,
            "status": "FAILED" if failed else "PENDING" if pending else "PASSED",
            "failed": failed, "pending": pending,
            "workflows": [{"name": name, "headSha": run.get("head_sha"), "runId": run.get("id"),
                           "attempt": run.get("run_attempt", 1), "status": run.get("status"),
                           "conclusion": run.get("conclusion"), "url": run.get("html_url")}
                          for name, run in latest.items()]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sha", required=True)
    p.add_argument("--repository", required=True)
    p.add_argument("--timeout", type=int, default=3600)
    p.add_argument("--output", type=Path, default=Path("build/reports/release-checks.json"))
    args = p.parse_args()
    scope = Path("release-scope.json")
    required = required_workflows(json.loads(scope.read_text()) if scope.exists() else {})
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        p.error("A repository identity and full immutable source SHA are required")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    end = time.monotonic() + args.timeout
    while time.monotonic() < end:
        runs = fetch_runs(args.repository, args.sha, os.environ["GH_TOKEN"])
        args.output.write_text(json.dumps(evidence_document(runs, args.sha, required), indent=2) + "\n")
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
