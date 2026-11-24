#!/usr/bin/env python3
"""Run the unchanged history scan and retain only bounded finding identities.

The full JSON report exists only in a private temporary directory. Match text,
secret values, commit messages and author metadata never enter this diagnostic.
Scanner failure remains failure; absent or invalid evidence also fails closed.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile

MAX_REPORT_BYTES = 16 * 1024 * 1024
MAX_FINDINGS = 10_000
MAX_IDENTITY_BYTES = 2 * 1024 * 1024
HISTORY_OPTIONS = "--all --full-history --diff-merges=first-parent --root"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_report_field")
        result[key] = value
    return result


def reject_constant(_value):
    raise ValueError("non_json_number")


def identity_lines(identities):
    return ["SECRET_HISTORY_FINDING " + json.dumps(identity, sort_keys=True, ensure_ascii=True) + "\n"
            for identity in identities]


def finding_identities(path, scanner_exit):
    with path.open("rb") as stream:
        raw = stream.read(MAX_REPORT_BYTES + 1)
    if len(raw) > MAX_REPORT_BYTES:
        raise ValueError("report_byte_limit")
    findings = json.loads(raw, object_pairs_hook=unique_object, parse_constant=reject_constant)
    if not isinstance(findings, list) or len(findings) > MAX_FINDINGS:
        raise ValueError("report_shape_or_count")
    if scanner_exit not in (0, 1) or (scanner_exit == 0) != (len(findings) == 0):
        raise ValueError("report_exit_mismatch")
    identities = []
    for item in findings:
        if not isinstance(item, dict):
            raise ValueError("finding_shape")
        rule, commit, file, line = (item.get(key) for key in ("RuleID", "Commit", "File", "StartLine"))
        if not isinstance(rule, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,256}", rule):
            raise ValueError("finding_rule")
        if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise ValueError("finding_commit")
        if not isinstance(file, str) or not file or len(file.encode("utf-8")) > 4096 or "\x00" in file:
            raise ValueError("finding_path")
        if type(line) is not int or not 1 <= line <= 2_147_483_647:
            raise ValueError("finding_line")
        if item.get("Secret") != "REDACTED" or not isinstance(item.get("Match"), str) or "REDACTED" not in item["Match"]:
            raise ValueError("finding_redaction")
        identities.append({"ruleId": rule, "commit": commit, "path": file, "line": line})
    # Count the exact retained bytes, including prefixes, escaping and newlines.
    if sum(len(line.encode("ascii")) for line in identity_lines(identities)) > MAX_IDENTITY_BYTES:
        raise ValueError("identity_byte_limit")
    return identities


def run_history(scanner):
    scanner_exit = None
    try:
        with tempfile.TemporaryDirectory(prefix="secret-history-report-") as temporary:
            report = Path(temporary) / "findings.json"
            result = subprocess.run([
                str(scanner), "git", "--redact=100", "--no-banner", "--no-color",
                "--report-format=json", "--report-path", str(report),
                "--log-opts=" + HISTORY_OPTIONS, ".",
            ], check=False)
            scanner_exit = result.returncode
            identities = finding_identities(report, scanner_exit)
        # Validate the complete bounded report before publishing any identity.
        for line in identity_lines(identities):
            print(line, end="")
        print("SECRET_HISTORY_REPORT " + json.dumps({
            "schemaVersion": 1, "scannerExitCode": scanner_exit,
            "findingCount": len(identities), "identityReportComplete": True,
            "retainedFields": ["ruleId", "commit", "path", "line"],
        }, sort_keys=True))
        return scanner_exit
    except (OSError, ValueError, TypeError, UnicodeError, RecursionError):
        # Do not expose exception text or partially parsed scanner content.
        print("SECRET_HISTORY_REPORT " + json.dumps({
            "schemaVersion": 1, "scannerExitCode": scanner_exit,
            "identityReportComplete": False, "error": "history_finding_evidence_invalid",
        }, sort_keys=True))
        if scanner_exit is not None and scanner_exit not in (0, 1):
            return scanner_exit if scanner_exit > 0 else 128 - scanner_exit
        return 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scanner", required=True, type=Path)
    args = parser.parse_args()
    return run_history(args.scanner.resolve())


if __name__ == "__main__":
    raise SystemExit(main())
