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
from collect_app_audit_qualification import (
    MAX_REPORT_BYTES, current_junit_receipt, junit_observations, observe_junit_reports,
    parse_xml, timestamp,
)

ROOT = Path(__file__).resolve().parents[1]
SCOPE = ROOT / "docs/implementation/unified-reliability-v2"
CANDIDATES = SCOPE / "component-fixtures.json"
SHA = re.compile(r"^[0-9a-f]{40}$")
ARTIFACT_SHA = re.compile(r"^[0-9a-f]{64}$")
RUN_URL = re.compile(r"https://github\.com/([\w.-]+/[\w.-]+)/actions/runs/([1-9]\d*)/attempts/([1-9]\d*)")
MAX_REPORTS = 4096
MAX_ARCHIVE_XML_BYTES = 256 * 1024 * 1024


def _artifact_junit_observations(source, since, until, artifact_sha256):
    if not artifact_sha256 or not ARTIFACT_SHA.fullmatch(artifact_sha256):
        raise ValueError("An imported JUnit archive requires the GitHub artifact SHA-256")
    # Hash and inspect the same open artifact without extracting any paths.
    with source.open("rb") as artifact:
        if hashlib.file_digest(artifact, "sha256").hexdigest() != artifact_sha256:
            raise ValueError("JUnit artifact SHA-256 mismatch")
        artifact.seek(0)
        with ZipFile(artifact) as archive:
            reports = [entry for entry in archive.infolist() if not entry.is_dir()
                       and Path(entry.filename).match("TEST-*.xml")]
            if (len(reports) > MAX_REPORTS or sum(entry.file_size for entry in reports) > MAX_ARCHIVE_XML_BYTES
                    or any(entry.file_size > MAX_REPORT_BYTES for entry in reports)):
                raise ValueError("JUnit archive exceeds collection bounds")

            def inputs():
                for entry in reports:
                    yield Path(entry.filename).name, lambda: parse_xml(archive.read(entry))

            return observe_junit_reports(inputs(), since, until)


def _junit_tests(source, qualification, source_sha, run_url, artifact_sha256):
    """Bind parsed test cases to the collector's CI attempt and exact report bytes."""
    run = RUN_URL.fullmatch(run_url or "")
    if not run or source_sha == "UNKNOWN" or not isinstance(qualification, dict):
        raise ValueError("JUnit evidence requires a tested SHA, exact run attempt URL and qualification receipt")
    repository, run_id, attempt = run.groups()
    environment = qualification.get("execution_environment", {})
    if (qualification.get("schema_version") != 1
            or qualification.get("kind") != "APP_AUDIT_SOURCE_HARNESS_OBSERVATIONS"
            or qualification.get("checkout", {}).get("commit") != source_sha
            or (environment.get("github_repository"), environment.get("github_run_id"),
                environment.get("github_run_attempt")) != (repository, run_id, attempt)):
        raise ValueError("JUnit qualification receipt does not match the tested SHA and workflow attempt")
    since = timestamp(qualification.get("execution_window_start") or "")
    until = timestamp(qualification.get("collected_at") or "")
    sources = source if isinstance(source, (list, tuple)) else [source]
    if not sources:
        raise ValueError("JUnit source must be a directory or ZIP archive")
    if all(directory.is_dir() for directory in sources):
        paths = []
        for directory in sources:
            for path in directory.rglob("TEST-*.xml"):
                paths.append(path)
                if len(paths) > MAX_REPORTS:
                    raise ValueError("JUnit report count exceeds the collection bound")
        classes, errors = junit_observations(paths, since, until)
    elif len(sources) == 1 and is_zipfile(sources[0]):
        classes, errors = _artifact_junit_observations(sources[0], since, until, artifact_sha256)
    else:
        raise ValueError("JUnit sources must be directories or one ZIP archive supplied alone")
    receipt = current_junit_receipt(classes, errors)
    if not receipt or receipt != qualification.get("junit_receipt") or qualification.get("report_errors") != []:
        raise ValueError("JUnit reports are stale, duplicate, invalid, or differ from the qualification digest")
    tests = {}
    for cases in classes.values():
        for case in cases:
            outcome = {"FAILURE": "FAIL", "ERROR": "FAIL", "SKIPPED": "SKIP"}.get(case["outcome"], "PASS")
            tests[(case["class_name"], case["name"])] = [{
                "outcome": outcome, "xml_sha256": case["report_sha256"], "junit_file": case["report_name"],
            }]
    return tests, dict(receipt, workflow_run_id=run_id, workflow_run_attempt=attempt,
                       artifact_sha256=artifact_sha256, execution_window_start=since.isoformat(),
                       execution_window_end=until.isoformat())


def reconcile(scope=SCOPE, candidates=CANDIDATES, junit_dir=None,
              source_sha="UNKNOWN", run_url=None, qualification=None, artifact_sha256=None):
    """Return evidence inventory without promoting any stored outcome."""
    if source_sha != "UNKNOWN" and (not SHA.fullmatch(source_sha) or source_sha == "0" * 40):
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
    known, receipt = (_junit_tests(junit_dir, qualification, source_sha, run_url, artifact_sha256)
                      if junit_dir is not None else ({}, None))
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
        "junit_evidence_scope": "CURRENT_RUN_NAMED_JVM_TESTS" if receipt else "NONE",
        "junit_receipt": receipt,
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
    parser.add_argument("--junit-dir", type=Path, action="append",
                        help="Repeat for independent current-run JVM report directories, or supply one ZIP archive.")
    parser.add_argument("--source-sha", default="UNKNOWN")
    parser.add_argument("--run-url")
    parser.add_argument("--qualification", type=Path)
    parser.add_argument("--artifact-sha256", help="Expected GitHub artifact digest (hex, without sha256:)")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fail-on-candidate-regression", action="store_true")
    args = parser.parse_args(argv)
    # Validate historical receipts and ledger invariants before publishing a report.
    validate()
    try:
        qualification = json.loads(args.qualification.read_text()) if args.qualification else None
        report = reconcile(junit_dir=args.junit_dir, source_sha=args.source_sha, run_url=args.run_url,
                           qualification=qualification, artifact_sha256=args.artifact_sha256)
    except (OSError, TypeError, ValueError, ET.ParseError) as error:
        parser.error(str(error))
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
