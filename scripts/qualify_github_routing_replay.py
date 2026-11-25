#!/usr/bin/env python3
"""Measure two read-only routing policies on actual Git objects for the PR's focused-schema inspection task."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import re
import subprocess
from time import perf_counter_ns

from github_routing_replay import canonical, SCHEMA, SHA, validate

SOURCE = "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/GitHubActionSchema.kt"
# The task verifies two public field declarations in a shared 100-line range.
# Each criterion has its own exact grader, independent of the route that supplied the range.
FIELDS = [("branch_dependencies", "page"), ("detail", "release_id")]


def capture(repo, revision):
    if not SHA.fullmatch(revision):
        raise ValueError("Use the full source commit")
    identity = subprocess.check_output(["git", "rev-parse", "--verify", revision + "^{commit}"], cwd=repo, text=True).strip()
    if identity != revision:
        raise ValueError("Cannot resolve the frozen revision")
    task = sha256(canonical(["focused-schema-fields-v1", SOURCE, 1, 100, FIELDS])).hexdigest()
    runs = []
    for policy in ("read-each-range", "reuse-pinned-range"):
        cache = None
        calls, requirements = [], []
        start = perf_counter_ns()
        for action, field in FIELDS:
            before = perf_counter_ns()
            if cache is None or policy == "read-each-range":
                source = subprocess.check_output(["git", "cat-file", "blob", revision + ":" + SOURCE], cwd=repo)
                cache = b"\n".join(source.splitlines()[:100])
                route = "lookup"
            else:
                route = "cached-range"
            duration = (perf_counter_ns() - before) // 1_000_000
            matches = re.findall(r'^\s*"' + re.escape(action) + r'" -> "([^"]+)"$', cache.decode(), re.MULTILINE)
            verified = len(matches) == 1 and field in matches[0].split()
            calls.append(dict(key=1, route=route, elapsedMs=duration, result=sha256(cache).hexdigest()))
            requirements.append(dict(key=sha256(canonical([task, action, field])).hexdigest(),
                                     witnesses=[len(calls) - 1] if verified else []))
        runs.append(dict(policy=policy, calls=calls, requirements=requirements, tokens=0,
                         completionVerified=all(row["witnesses"] for row in requirements),
                         elapsedMs=(perf_counter_ns() - start) // 1_000_000))
    trace = dict(schema=SCHEMA, revision=revision, task=task, runs=runs)
    validate(trace)
    return trace


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revision")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    print(json.dumps(capture(args.repo, args.revision), indent=2))
