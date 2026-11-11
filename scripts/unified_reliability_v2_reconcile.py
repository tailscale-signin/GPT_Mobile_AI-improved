"""Read-only, exact-case Unified Reliability v2 CI reconciliation.

Passing component tests are CANDIDATE evidence, not formal case PASS results.
Never modifies tasks.json, acceptance-tests.json, or external GitHub issues.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile, is_zipfile

from check_unified_reliability_v2 import validate

ROOT = Path(__file__).resolve().parents[1]
SCOPE = ROOT / "docs/implementation/unified-reliability-v2"
CANDIDATES = SCOPE / "component-fixtures.json"
SHA = re.compile(r"^[0-9a-f]{40}$")
MAX_XML_BYTES = 6_000_000


def _junit_tests(directory):
    """Index exact (class, method) pairs and XML digests; no suite inference."""
    if directory is None:
        return {}
    if not directory.exists():
        raise ValueError(f"Missing JUnit source: {directory}")
    tests = {}

    def add_xml(name, data):
        if len(data) > MAX_XML_BYTES:
            raise ValueError(f"JUnit XML exceeds safe limit: {name}")
        digest = hashlib.sha256(data).hexdigest()
        for case in ET.fromstring(data).iter("testcase"):
            key = (case.get("classname", ""), case.get("name", ""))
            if not all(key):
                continue
            outcome = ("FAIL" if case.find("failure") is not None or
                       case.find("error") is not None else
                       "SKIP" if case.find("skipped") is not None else "PASS")
            tests.setdefault(key, []).append({
                "outcome": outcome, "xml_sha256": digest, "junit_file": name,
            })

    if directory.is_dir():
        for path in sorted(directory.rglob("TEST-*.xml")):
            add_xml(str(path.relative_to(directory)), path.read_bytes())
    elif directory.is_file() and is_zipfile(directory):
        # Inspect GitHub Actions ZIP in memory; never extract untrusted paths.
        with ZipFile(directory) as archive:
            for entry in archive.infolist():
                if not Path(entry.filename).name.startswith("TEST-") or not entry.filename.endswith(".xml"):
                    continue
                if entry.file_size > MAX_XML_BYTES:
                    raise ValueError(f"JUnit XML exceeds safe limit: {entry.filename}")
                add_xml(entry.filename, archive.read(entry))
    else:
        raise ValueError(f"JUnit source must be a directory or ZIP archive: {directory}")
    return tests


def reconcile(scope=SCOPE, candidates=CANDIDATES, junit_dir=None,
              source_sha="UNKNOWN", run_url=None):
    """Return evidence inventory without promoting any stored outcome."""
    if source_sha != "UNKNOWN" and not SHA.fullmatch(source_sha):
        raise ValueError("Source SHA must be a real 40-character lowercase hex digest")
    tasks = json.loads((scope / "tasks.json").read_text(encoding="utf-8"))
    cases = json.loads((scope / "acceptance-tests.json").read_text(encoding="utf-8"))
    mapping = json.loads(candidates.read_text(encoding="utf-8"))
    expected_cases = {f"{prefix}{number:02}" for prefix, count in
                      (("LA-QA", 36), ("LC-T", 18), ("CM-T", 26), ("IX", 30))
                      for number in range(1, count + 1)}
    expected_tasks = {f"V{number:02}" for number in range(1, 49)}
    if (len(cases) != 110 or {c["id"] for c in cases} != expected_cases or
            len(tasks) != 48 or {t["id"] for t in tasks} != expected_tasks):
        raise ValueError("The original 48-task / 110-case acceptance scope changed")
    if mapping.get("schema") != "unified-reliability-v2-component-fixtures.v1":
        raise ValueError("Unknown component fixture mapping schema")
    items = mapping.get("candidates", [])
    if len({item["case_id"] for item in items}) != len(items):
        raise ValueError("Duplicate candidate mapping")
    if any(item["case_id"] not in expected_cases for item in items):
        raise ValueError("Candidate names an unknown acceptance case")
    known = _junit_tests(junit_dir)
    candidate_by_id = {}
    for item in items:
        if not item.get("remaining_assertions") or not item.get("tests"):
            raise ValueError("Candidate must describe both tests and unverified assertions")
        observed = []
        for fixture in item["tests"]:
            key = (fixture["class_name"], fixture["method"])
            occurrences = known.get(key, [])
            states = {row["outcome"] for row in occurrences}
            outcome = ("NOT_OBSERVED" if not occurrences else
                       "PASS" if states == {"PASS"} else
                       "FAIL" if "FAIL" in states else "SKIP")
            observed.append({"class_name": key[0], "method": key[1],
                             "result": outcome, "junit_evidence": occurrences})
        candidate_by_id[item["case_id"]] = {
            "component_status": ("ALL_NAMED_TESTS_PASS" if
                                 all(row["result"] == "PASS" for row in observed) else
                                 "NOT_FULLY_OBSERVED"),
            "scope": "PARTIAL_COMPONENT_ONLY",
            "named_tests": observed,
            "remaining_assertions": item["remaining_assertions"],
        }

    task_references = {}
    for task in tasks:
        for case_id in task["acceptance_ids"]:
            task_references.setdefault(case_id, []).append(task["id"])
    rows = []
    for case in cases:
        rows.append({
            "case_id": case["id"], "recorded_outcome": case["outcome"],
            "fixture": case["fixture"], "assertion": case["assertion"],
            "ledger_commit": case.get("commit"),
            "ledger_evidence": case.get("evidence", []),
            "implementation_tasks": task_references.get(case["id"], []),
            "component_candidate": candidate_by_id.get(case["id"]),
            "promotion_authorized": False,
        })
    statuses = Counter(case["outcome"] for case in cases)
    task_statuses = Counter(task["status"] for task in tasks)
    observed_candidates = sum(
        row["component_candidate"]["component_status"] == "ALL_NAMED_TESTS_PASS"
        for row in rows if row["component_candidate"] is not None
        and row["recorded_outcome"] == "NOT_RUN"
    )
    return {
        "schema": "unified-reliability-v2-reconciliation.v1",
        "source_sha": source_sha, "run_url": run_url,
        "junit_evidence_scope": "CURRENT_RUN_NAMED_JVM_TESTS" if junit_dir else "NONE",
        "summary": {
            "tasks": len(tasks), "task_statuses": dict(sorted(task_statuses.items())),
            "cases": len(cases), "case_outcomes": dict(sorted(statuses.items())),
            "not_run_with_partial_component_proof": observed_candidates,
            "new_case_pass_claims": 0, "new_task_done_claims": 0,
        },
        "limitations": [
            "A passing named JUnit method is not a formal acceptance case PASS.",
            "CI/emulator runs do not qualify physical hardware or live model behavior.",
            "The ledger is immutable; promotion requires independently reviewed case-wide evidence.",
        ],
        "cases": rows,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit-dir", type=Path)
    parser.add_argument("--source-sha", default="UNKNOWN")
    parser.add_argument("--run-url")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fail-on-candidate-regression", action="store_true")
    args = parser.parse_args(argv)
    # Validate historical receipts and ledger invariants before publishing a report.
    validate()
    report = reconcile(junit_dir=args.junit_dir, source_sha=args.source_sha,
                       run_url=args.run_url)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    tmp = args.output.with_name(args.output.name + ".tmp")
    tmp.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(args.output)
    summary = report["summary"]
    print("Unified Reliability v2: ", summary["task_statuses"],
          summary["case_outcomes"], "partial-component-only cases:",
          summary["not_run_with_partial_component_proof"])
    print("Formal promotions: 0. Receipt review and complete case qualification still required.")
    if args.fail_on_candidate_regression and args.junit_dir is not None:
        missing = [row["case_id"] for row in report["cases"]
                   if row["component_candidate"] is not None and
                   row["component_candidate"]["component_status"] != "ALL_NAMED_TESTS_PASS"]
        if missing:
            print("Missing/failing named candidate regressions:", missing, file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
