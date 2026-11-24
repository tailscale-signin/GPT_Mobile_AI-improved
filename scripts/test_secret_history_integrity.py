#!/usr/bin/env python3
"""Run the complete history gate with an actual pinned scanner and Git objects.

The intact and restored fixtures must reach the scanner and its existing pin
controls. A missing ordinary historical blob must fail before either can claim
success. No child output or fixture contents are included in the report.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from test_gitleaks_model_pin import (
    CALIBRATION_PATH,
    COMPOSE_EVIDENCE_PATH,
    EVIDENCE_PATH,
    MOCKK_EVIDENCE_PATH,
    MODEL_PACK_PATH,
    NATIVE_PATH,
    VERSION,
    WAVEFORM_EVIDENCE_PATH,
    isolated_environment,
    quiet,
)

ROOT = Path(__file__).resolve().parents[1]
INPUTS = (
    ".gitleaks.toml",
    ".gitleaksignore",
    "scripts/scan_secret_history.sh",
    "scripts/scan_secrets.py",
    "scripts/test_gitleaks_model_pin.py",
    NATIVE_PATH,
    EVIDENCE_PATH,
    CALIBRATION_PATH,
    MOCKK_EVIDENCE_PATH,
    COMPOSE_EVIDENCE_PATH,
    WAVEFORM_EVIDENCE_PATH,
    MODEL_PACK_PATH,
    "gradle/verification-metadata.xml",
)


def wrapper_case(directory, environment, name, missing_object=None):
    result = subprocess.run(
        ["bash", "scripts/scan_secret_history.sh"], cwd=directory,
        env=environment, capture_output=True, text=True, timeout=60)
    controls = None
    for line in result.stdout.splitlines():
        if line.startswith("{"):
            candidate = json.loads(line)
            if isinstance(candidate, dict) and "scannerVersion" in candidate and "cases" in candidate:
                controls = candidate
    controls_passed = bool(
        controls and controls.get("scannerVersion") == VERSION
        and controls.get("passed") is True and controls.get("cases")
        and all(case.get("passed") is True for case in controls["cases"]))
    git_fatal = "fatal:" in result.stderr
    scanner_completed = "no leaks found" in result.stderr and " ERR " not in result.stderr
    if missing_object is None:
        passed = result.returncode == 0 and controls_passed and scanner_completed and not git_fatal
    else:
        passed = (result.returncode == 128 and git_fatal and missing_object in result.stderr
                  and controls is None and not scanner_completed)
    return {"case": name, "exitCode": result.returncode, "gitFatalObserved": git_fatal,
            "pinControls": len(controls["cases"]) if controls else 0,
            "pinControlsPassed": controls_passed, "scannerCompleted": scanner_completed,
            "passed": passed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scanner", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    scanner = args.scanner.resolve()
    summary = {"scannerVersion": VERSION, "cases": [], "passed": False}
    environment = isolated_environment()
    try:
        if quiet([str(scanner), "version"], ROOT, environment, timeout=5) != VERSION:
            raise ValueError("scanner_version")
        summary["scannerSha256"] = hashlib.sha256(scanner.read_bytes()).hexdigest()
        inputs = {path: (ROOT / path).read_bytes() for path in INPUTS}
        summary["sourceInputsSha256"] = {
            path: hashlib.sha256(raw).hexdigest() for path, raw in inputs.items()}
        with tempfile.TemporaryDirectory(prefix="secret-history-integrity-") as temporary:
            parent = Path(temporary)
            directory = parent / "repository"
            directory.mkdir()
            for path, raw in inputs.items():
                target = directory / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(raw)
            binary_directory = parent / "bin"
            binary_directory.mkdir()
            (binary_directory / "gitleaks").symlink_to(scanner)
            environment["PATH"] = str(binary_directory) + os.pathsep + environment.get("PATH", "")
            quiet(["git", "init", "--quiet", "--template="], directory, environment)
            historical = directory / "history-inert.txt"
            historical.write_text("Ordinary historical content for the missing-object control.\n")
            quiet(["git", "add", "--all"], directory, environment)
            quiet(["git", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Historical content"],
                  directory, environment)
            old_blob = quiet(["git", "rev-parse", "HEAD:history-inert.txt"], directory, environment)
            historical.write_text("Ordinary current content; the visible worktree remains complete.\n")
            quiet(["git", "add", "history-inert.txt"], directory, environment)
            quiet(["git", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Replace historical content"],
                  directory, environment)
            summary["cases"].append(wrapper_case(directory, environment, "intact_history"))
            object_path = directory / ".git" / "objects" / old_blob[:2] / old_blob[2:]
            saved_object = object_path.read_bytes()
            object_path.unlink()
            try:
                path_preflight = subprocess.run(
                    ["python3", "scripts/scan_secrets.py", "--history-paths"], cwd=directory,
                    env=environment, capture_output=True, timeout=10)
                case = wrapper_case(directory, environment, "missing_historical_blob", old_blob)
                case["filenamePreflightExitCode"] = path_preflight.returncode
                case["passed"] = case["passed"] and path_preflight.returncode == 0
                summary["cases"].append(case)
            finally:
                object_path.write_bytes(saved_object)
            summary["cases"].append(wrapper_case(directory, environment, "restored_historical_blob"))
        summary["passed"] = all(case["passed"] for case in summary["cases"])
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired):
        summary["error"] = "history_integrity_fixture_failed"
    if args.report:
        args.report.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
