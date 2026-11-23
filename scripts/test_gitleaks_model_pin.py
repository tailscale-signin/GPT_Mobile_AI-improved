#!/usr/bin/env python3
"""Exercise the model-checksum exception with the actual pinned history scanner.

Invoked after scanner selection by scan_secret_history.sh. Temporary Git fixtures
contain generated test values; only classifications and counts leave this check.
"""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "8.30.1"
NATIVE_PATH = "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py"
EVIDENCE_PATH = "docs/repairs/evidence/speech-playback-reliability-2026-11-23/native-qnn.json"
HISTORY_OPTIONS = "--all --full-history --diff-merges=first-parent --root"


def model_digest():
    tree = ast.parse((ROOT / NATIVE_PATH).read_text(encoding="utf-8"))
    pins = [ast.literal_eval(node.value) for node in ast.walk(tree)
            if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == "model_pins" for target in node.targets)]
    if len(pins) != 1:
        raise ValueError("model_pin_contract")
    value = pins[0]["tokens.txt"]
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("model_pin_contract")
    return value


def isolated_environment():
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("GIT_", "GITLEAKS_"))}
    environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                       GIT_AUTHOR_NAME="Scanner fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                       GIT_COMMITTER_NAME="Scanner fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid")
    return environment


def quiet(command, directory, environment, timeout=10):
    result = subprocess.run(command, cwd=directory, env=environment,
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("fixture_command_failed")
    return result.stdout.strip()


def scan_case(scanner, config, parent, environment, name, path, line, expected):
    directory = parent / name
    directory.mkdir()
    quiet(["git", "init", "--quiet", "--template="], directory, environment)
    target = directory / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("{\n" + line + '\n  "kind": "synthetic scanner fixture"\n}\n', encoding="utf-8")
    quiet(["git", "add", "--", path], directory, environment)
    quiet(["git", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Scanner fixture"], directory, environment)
    commit = quiet(["git", "rev-parse", "HEAD"], directory, environment)
    report = parent / (name + ".json")
    result = subprocess.run(
        [str(scanner), "git", "--config", str(config), "--redact=100", "--no-banner", "--no-color",
         "--report-format=json", "--report-path", str(report), "--log-opts=" + HISTORY_OPTIONS, "."],
        cwd=directory, env=environment, capture_output=True, text=True, timeout=30)
    findings = json.loads(report.read_text(encoding="utf-8"))
    if not isinstance(findings, list):
        raise ValueError("scanner_report_shape")
    observed = len(findings)
    redacted = all(item.get("Secret") == "REDACTED" and "REDACTED" in item.get("Match", "")
                   for item in findings)
    identity = all(item.get("RuleID") == "generic-api-key" and item.get("File") == path
                   and item.get("StartLine") == 2 and item.get("Commit") == commit for item in findings)
    passed = observed == expected and result.returncode == (1 if expected else 0) and redacted and identity
    return {"case": name, "expectedFindings": expected, "observedFindings": observed,
            "exitCode": result.returncode, "redacted": redacted,
            "exactFindingIdentity": identity, "passed": passed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scanner", required=True, type=Path)
    parser.add_argument("--config", type=Path, default=ROOT / ".gitleaks.toml")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    scanner, config = args.scanner.resolve(), args.config.resolve()
    summary = {"scannerVersion": VERSION, "cases": [], "passed": False}
    environment = isolated_environment()
    try:
        if quiet([str(scanner), "version"], ROOT, environment, timeout=5) != VERSION:
            raise ValueError("scanner_version")
        summary["configSha256"] = hashlib.sha256(config.read_bytes()).hexdigest()
        known_digest = model_digest()
        alternate_digest = hashlib.sha256(b"unapproved model-file regression fixture").hexdigest()
        exact_line = '  "tokens.txt": "' + known_digest + '",'
        cases = [
            ("exact_evidence", EVIDENCE_PATH, exact_line, 0),
            ("existing_native_harness", NATIVE_PATH, exact_line, 0),
            ("different_checksum", EVIDENCE_PATH, '  "tokens.txt": "' + alternate_digest + '",', 1),
            ("different_path", EVIDENCE_PATH.replace("native-qnn.json", "other-native-qnn.json"), exact_line, 1),
            ("different_field", EVIDENCE_PATH, '  "tokens_other.txt": "' + known_digest + '",', 1),
            ("trailing_content", EVIDENCE_PATH, exact_line + ' "extra": true,', 1),
        ]
        with tempfile.TemporaryDirectory(prefix="gitleaks-model-pin-") as temporary:
            parent = Path(temporary)
            for case in cases:
                summary["cases"].append(scan_case(scanner, config, parent, environment, *case))
        summary["passed"] = all(case["passed"] for case in summary["cases"])
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired):
        # Child output and exception text can contain the fixture values.
        summary["error"] = "scanner_fixture_validation_failed"
    if args.report:
        args.report.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
