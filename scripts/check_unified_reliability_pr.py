"""Fail-closed, branch-scoped Unified Reliability v2 pull-request admission.

The gate must run for draft *and* ready PRs. Otherwise a green draft check
could be reused after switching to ready without having checked completion.
"""
import argparse
import json
import os
from pathlib import Path

from check_unified_reliability_v2 import SCOPE, validate

V2_DELIVERY_PREFIXES = (
    "feat/unified-reliability-v2-",
    "release/unified-reliability-v2-",
)


def requires_completion(head_ref: str):
    if not head_ref or not head_ref.strip():
        raise ValueError("PR head ref is required for reliability admission")
    return head_ref.startswith(V2_DELIVERY_PREFIXES)


def check_pr(head_ref: str):
    return validate(require_done=requires_completion(head_ref))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--head-ref", required=True)
    args = parser.parse_args()
    strict = requires_completion(args.head_ref)
    mode = "STRICT_RELEASE_COMPLETION" if strict else "SCOPE_ONLY_NOT_ACCEPTANCE"
    # Print the mode BEFORE validation, including when strict mode fails closed.
    print(f"Unified Reliability v2 gate mode: {mode}", flush=True)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        case_records = json.loads((SCOPE / "acceptance-tests.json").read_text())
        task_records = json.loads((SCOPE / "tasks.json").read_text())
        passed = sum(case["outcome"] == "PASS" for case in case_records)
        done = sum(task["status"] == "DONE" for task in task_records)
        with Path(summary_path).open("a", encoding="utf-8") as summary:
            summary.write("### Unified Reliability v2 gate\n\n")
            summary.write(f"**Mode:** {mode}  \n")
            summary.write(f"**Recorded:** {passed}/110 cases PASS; {done}/48 tasks DONE.  \n")
            summary.write("Scope-only success does not qualify acceptance or a release.\n\n")
    if not strict:
        print("::warning::This checks ledger integrity only; v2 acceptance remains incomplete.")
    print("PR v2 gate:", check_pr(args.head_ref))
