"""Fail-closed, branch-scoped Unified Reliability v2 pull-request admission.

The gate must run for draft *and* ready PRs. Otherwise a green draft check
could be reused after switching to ready without having checked completion.
"""
import argparse

from check_unified_reliability_v2 import validate

V2_DELIVERY_PREFIXES = (
    "feat/unified-reliability-v2-",
    "release/unified-reliability-v2-",
)


def check_pr(head_ref: str):
    if not head_ref or not head_ref.strip():
        raise ValueError("PR head ref is required for reliability admission")
    require_done = head_ref.startswith(V2_DELIVERY_PREFIXES)
    return validate(require_done=require_done)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--head-ref", required=True)
    args = parser.parse_args()
    print("PR v2 gate:", check_pr(args.head_ref))
