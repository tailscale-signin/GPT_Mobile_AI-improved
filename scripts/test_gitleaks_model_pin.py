#!/usr/bin/env python3
"""Exercise exact public declarations with the actual pinned history scanner.

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
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
VERSION = "8.30.1"
NATIVE_PATH = "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py"
EVIDENCE_PATH = "docs/repairs/evidence/speech-playback-reliability-2026-11-23/native-qnn.json"
CALIBRATION_PATH = "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/VoiceCpuCalibrationProtocol.kt"
MOCKK_EVIDENCE_PATH = "docs/repairs/evidence/speech-benchmark-1795470489503/combined-host-source-tests.json"
MOCKK_ARTIFACT = "mockk-agent-api-jvm-1.14.11.jar"
COMPOSE_EVIDENCE_PATH = "docs/repairs/privacy-lifecycle-disposal-idle-2026-11-24-task.json"
PRONUNCIATION_EVIDENCE_PATH = "docs/repairs/evidence/voice-pronunciation-vocabulary/independent-review-reconstructed.json"
HISTORY_OUTER_EVIDENCE_PATH = "docs/repairs/evidence/secret-history-finding-evidence/outer.stdout.log"
HISTORY_OUTER_FIRST_LINE_SHA256 = "db9b4401caf992c261ab1e9fc649b3cdf6533602fa81151fdfa83f6021c7dd45"
HISTORY_OUTER_FILE_SHA256 = "239d494e49bd476ad148e2be462a2a0a0e3a857372dcafe7904c02b6f68e42e6"
# These immutable public source digests are verified in the repair ledger.
# Keep path and digest separate so the control source is not a secret-like map.
PUBLIC_WRAPPER_DECLARATIONS = (
    ("wrapper_e855", "docs/repairs/diagnostic-93d824fd-secret-history-integrity-2026-11-24-task.json",
     "f19efdac6e205f9ecfb69e9c957c65d79b46e38d741eace05cc3c1200a3836ce"),
    ("wrapper_e406_before", "docs/repairs/secret-history-integrity-ci-2026-11-24-task.json",
     "f19efdac6e205f9ecfb69e9c957c65d79b46e38d741eace05cc3c1200a3836ce"),
    ("wrapper_e406_after", "docs/repairs/secret-history-integrity-ci-2026-11-24-task.json",
     "b3f807b708d932b6f0d2015afe69ee1ff5ec192a6086afbb9bc4f5e4f88b7f63"),
    ("wrapper_report_ledger", "docs/repairs/secret-history-finding-evidence-2026-11-24-task.json",
     "d3c62151d7cf40467999121e949ab5bdaf9195635b075b8d6b4d97b4f5744074"),
    ("wrapper_report_result", "docs/repairs/evidence/secret-history-finding-evidence/focused-result.json",
     "d3c62151d7cf40467999121e949ab5bdaf9195635b075b8d6b4d97b4f5744074"),
)
WAVEFORM_EVIDENCE_PATH = "docs/repairs/evidence/voice-cpu-gpu-npu-enhancements/single-cpu-waveform-validation.json"
ROUTING_FIXTURE_PATH = "scripts/tests/fixtures/github-routing-pr-2568.json"
MODEL_PACK_PATH = "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/KokoroModelPack.kt"
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


def calibration_order_line():
    lines = (ROOT / CALIBRATION_PATH).read_text(encoding="utf-8").splitlines()
    declarations = [line for line in lines if re.match(r"\s*const val ORDER_KEY\s*=", line)]
    if len(declarations) != 1 or not re.fullmatch(r'[ \t]*const val ORDER_KEY = "[a-z0-9-]+"[ \t]*', declarations[0]):
        raise ValueError("calibration_preference_contract")
    return declarations[0]


def mockk_dependency_line():
    document = json.loads((ROOT / MOCKK_EVIDENCE_PATH).read_text(encoding="utf-8"))
    if not isinstance(document, dict) or not isinstance(document.get("dependencySha256"), dict):
        raise ValueError("mockk_dependency_pin_contract")
    digest = document["dependencySha256"].get(MOCKK_ARTIFACT)
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("mockk_dependency_pin_contract")
    try:
        verification = ElementTree.parse(ROOT / "gradle/verification-metadata.xml")
    except ElementTree.ParseError:
        raise ValueError("mockk_dependency_pin_contract") from None
    pins = [pin.get("value")
            for component in verification.findall(".//{*}component")
            if (component.get("group"), component.get("name"), component.get("version")) ==
            ("io.mockk", "mockk-agent-api-jvm", "1.14.11")
            for artifact in component.findall("{*}artifact") if artifact.get("name") == MOCKK_ARTIFACT
            for pin in artifact.findall("{*}sha256")]
    if pins != [digest]:
        raise ValueError("mockk_dependency_pin_contract")
    return '    "' + MOCKK_ARTIFACT + '": "' + digest + '",'


def waveform_model_line(known_digest):
    document = json.loads((ROOT / WAVEFORM_EVIDENCE_PATH).read_text(encoding="utf-8"))
    if not isinstance(document, dict) or not isinstance(document.get("inputs"), dict):
        raise ValueError("waveform_model_pin_contract")
    pins = document["inputs"].get("sha256")
    if not isinstance(pins, dict) or pins.get("tokens.txt") != known_digest:
        raise ValueError("waveform_model_pin_contract")
    pack = (ROOT / MODEL_PACK_PATH).read_text(encoding="utf-8")
    pack_pins = re.findall(r'\bfile\(TOKENS,\s*\d[\d_]*,\s*"([0-9a-f]{64})"\s*\)', pack)
    if pack_pins != [known_digest]:
        raise ValueError("waveform_model_pin_contract")
    return '      "tokens.txt": "' + known_digest + '",'


def compose_dependency_line():
    document = json.loads((ROOT / COMPOSE_EVIDENCE_PATH).read_text(encoding="utf-8"))
    if not isinstance(document, dict) or not isinstance(document.get("sourceBasis"), dict):
        raise ValueError("compose_dependency_pin_contract")
    digest = document["sourceBasis"].get("apiArtifactSha256")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("compose_dependency_pin_contract")
    try:
        verification = ElementTree.parse(ROOT / "gradle/verification-metadata.xml")
    except ElementTree.ParseError:
        raise ValueError("compose_dependency_pin_contract") from None
    pins = [pin.get("value")
            for component in verification.findall(".//{*}component")
            if (component.get("group"), component.get("name"), component.get("version")) ==
            ("androidx.compose.ui", "ui-test-android", "1.12.1")
            for artifact in component.findall("{*}artifact") if artifact.get("name") == "ui-test.aar"
            for pin in artifact.findall("{*}sha256")]
    if pins != [digest]:
        raise ValueError("compose_dependency_pin_contract")
    return '    "apiArtifactSha256": "' + digest + '"'


def exact_declaration_cases(name, path, field, digest, alternate_digest):
    """Exercise every boundary of one exact path/field/value/whole-line pin."""
    exact = '  "' + field + '": "' + digest + '",'
    alternate = exact.replace(digest, alternate_digest)
    other_field = exact.replace('"' + field + '"', '"other_api_key"', 1)
    neighboring_key = '  "apiKey": "' + alternate_digest + '",'
    return [
        (name + "_exact", path, exact, 0),
        (name + "_different_value", path, alternate, 1),
        (name + "_different_path", path + ".other", exact, 1),
        (name + "_different_field", path, other_field, 1),
        (name + "_prefix_content", path, '"extra": true, ' + exact.lstrip(), 1),
        (name + "_trailing_content", path, exact + ' "extra": true,', 1),
        (name + "_secret_before", path, neighboring_key + "\n" + exact, 1),
        (name + "_secret_after", path, exact + "\n" + neighboring_key, 1, 3),
    ]


def immutable_outer_evidence_line():
    raw = (ROOT / HISTORY_OUTER_EVIDENCE_PATH).read_bytes()
    if hashlib.sha256(raw).hexdigest() != HISTORY_OUTER_FILE_SHA256:
        raise ValueError("historical_outer_evidence_contract")
    lines = raw.decode("utf-8").splitlines()
    if len(lines) != 3 or hashlib.sha256(lines[0].encode()).hexdigest() != HISTORY_OUTER_FIRST_LINE_SHA256:
        raise ValueError("historical_outer_evidence_contract")
    return lines[0]


def outer_evidence_cases(alternate_digest):
    # This is one frozen 2,287-byte historical receipt line, not a JSON-shaped
    # exemption. Any change to any value, field, prefix, suffix or path scans.
    exact = immutable_outer_evidence_line()
    original = PUBLIC_WRAPPER_DECLARATIONS[-1][2]
    if exact.count(original) != 1:
        raise ValueError("historical_outer_wrapper_contract")
    neighboring_key = '  "apiKey": "' + alternate_digest + '",'
    path = HISTORY_OUTER_EVIDENCE_PATH
    return [
        ("outer_exact", path, exact, 0),
        ("outer_different_value", path, exact.replace(original, alternate_digest), 3),
        ("outer_different_path", path + ".other", exact, 3),
        ("outer_different_field", path, exact.replace('"scripts/scan_secret_history.sh"', '"other_api_key"'), 3),
        ("outer_prefix_content", path, '"extra": true, ' + exact, 3),
        ("outer_trailing_content", path, exact + ' "extra": true,', 3),
        ("outer_secret_before", path, neighboring_key + "\n" + exact, 1),
        ("outer_secret_after", path, exact + "\n" + neighboring_key, 1, 3),
    ]


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


def scan_case(scanner, config, parent, environment, name, path, line, expected, expected_line=2):
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
                   and item.get("StartLine") == expected_line and item.get("Commit") == commit for item in findings)
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
        calibration_line = calibration_order_line()
        alternate_calibration = re.sub(r'"[^"]+"', '"' + alternate_digest + '"', calibration_line, count=1)
        mockk_line = mockk_dependency_line()
        alternate_mockk = f'    "{MOCKK_ARTIFACT}": "{alternate_digest}",'
        waveform_line = waveform_model_line(known_digest)
        alternate_waveform = waveform_line.replace(known_digest, alternate_digest)
        compose_line = compose_dependency_line()
        alternate_compose = '    "apiArtifactSha256": "' + alternate_digest + '"'
        nearby_api_key = '    "apiKey": "' + alternate_digest + '",'
        cases = [
            ("exact_evidence", EVIDENCE_PATH, exact_line, 0),
            ("existing_native_harness", NATIVE_PATH, exact_line, 0),
            ("different_checksum", EVIDENCE_PATH, '  "tokens.txt": "' + alternate_digest + '",', 1),
            ("different_path", EVIDENCE_PATH.replace("native-qnn.json", "other-native-qnn.json"), exact_line, 1),
            ("different_field", EVIDENCE_PATH, '  "tokens_other.txt": "' + known_digest + '",', 1),
            ("trailing_content", EVIDENCE_PATH, exact_line + ' "extra": true,', 1),
            ("calibration_exact_declaration", CALIBRATION_PATH, calibration_line, 0),
            ("calibration_historical_v3", CALIBRATION_PATH, re.sub(r'v[0-9]+"$', 'v3"', calibration_line), 0),
            ("calibration_unreviewed_version", CALIBRATION_PATH, re.sub(r'v[0-9]+"$', 'v99"', calibration_line), 1),
            ("calibration_different_value", CALIBRATION_PATH, alternate_calibration, 1),
            ("calibration_different_path", CALIBRATION_PATH.replace("VoiceCpuCalibrationProtocol.kt", "OtherCalibrationProtocol.kt"), calibration_line, 1),
            ("calibration_different_field", CALIBRATION_PATH, calibration_line.replace("ORDER_KEY", "OTHER_KEY"), 1),
            ("calibration_trailing_content", CALIBRATION_PATH, calibration_line + " // unrelated trailing content", 1),
            ("calibration_prefix_content", CALIBRATION_PATH, "private " + calibration_line.lstrip(), 1),
            ("calibration_secret_after", CALIBRATION_PATH, calibration_line + "\n" + alternate_calibration, 1, 3),
            ("calibration_secret_before", CALIBRATION_PATH, alternate_calibration + "\n" + calibration_line, 1),
            ("mockk_exact_declaration", MOCKK_EVIDENCE_PATH, mockk_line, 0),
            ("mockk_different_value", MOCKK_EVIDENCE_PATH, alternate_mockk, 1),
            ("mockk_different_path", MOCKK_EVIDENCE_PATH.replace("combined-host-source-tests.json", "other-host-source-tests.json"), mockk_line, 1),
            ("mockk_different_field", MOCKK_EVIDENCE_PATH, mockk_line.replace(MOCKK_ARTIFACT, "other-agent-api-jvm-1.14.11.jar"), 1),
            ("mockk_trailing_content", MOCKK_EVIDENCE_PATH, mockk_line + ' "extra": true,', 1),
            ("mockk_prefix_content", MOCKK_EVIDENCE_PATH, '"extra": true, ' + mockk_line.lstrip(), 1),
            ("mockk_secret_after", MOCKK_EVIDENCE_PATH, mockk_line + "\n" + alternate_mockk, 1, 3),
            ("mockk_secret_before", MOCKK_EVIDENCE_PATH, alternate_mockk + "\n" + mockk_line, 1),
            ("waveform_exact_declaration", WAVEFORM_EVIDENCE_PATH, waveform_line, 0),
            ("waveform_different_value", WAVEFORM_EVIDENCE_PATH, alternate_waveform, 1),
            ("waveform_different_path", WAVEFORM_EVIDENCE_PATH.replace("single-cpu-waveform-validation.json", "other-cpu-waveform-validation.json"), waveform_line, 1),
            ("waveform_different_field", WAVEFORM_EVIDENCE_PATH, waveform_line.replace("tokens.txt", "tokens_other.txt"), 1),
            ("waveform_trailing_content", WAVEFORM_EVIDENCE_PATH, waveform_line + ' "extra": true,', 1),
            ("waveform_prefix_content", WAVEFORM_EVIDENCE_PATH, '"extra": true, ' + waveform_line.lstrip(), 1),
            ("waveform_secret_after", WAVEFORM_EVIDENCE_PATH, waveform_line + "\n" + alternate_waveform, 1, 3),
            ("waveform_secret_before", WAVEFORM_EVIDENCE_PATH, alternate_waveform + "\n" + waveform_line, 1),
            ("compose_exact_declaration", COMPOSE_EVIDENCE_PATH, compose_line, 0),
            ("compose_different_value", COMPOSE_EVIDENCE_PATH, alternate_compose, 1),
            ("compose_different_path", COMPOSE_EVIDENCE_PATH.replace("-task.json", "-other.json"), compose_line, 1),
            ("compose_different_field", COMPOSE_EVIDENCE_PATH, compose_line.replace("apiArtifactSha256", "apiKey"), 1),
            ("compose_trailing_content", COMPOSE_EVIDENCE_PATH, compose_line + ', "extra": true,', 1),
            ("compose_prefix_content", COMPOSE_EVIDENCE_PATH, '"extra": true, ' + compose_line.lstrip(), 1),
            ("compose_secret_after", COMPOSE_EVIDENCE_PATH, compose_line + "\n" + nearby_api_key, 1, 3),
            ("compose_secret_before", COMPOSE_EVIDENCE_PATH, nearby_api_key + "\n" + compose_line, 1),
        ]
        for name, path, digest in PUBLIC_WRAPPER_DECLARATIONS:
            cases.extend(exact_declaration_cases(
                name, path, "scripts/scan_secret_history.sh", digest, alternate_digest))
        cases.extend(exact_declaration_cases(
            "pronunciation_tokens", PRONUNCIATION_EVIDENCE_PATH, "tokens_txt", known_digest, alternate_digest))
        trace = json.loads((ROOT / ROUTING_FIXTURE_PATH).read_text(encoding="utf-8"))
        routing_digests = sorted({row["key"] for run in trace["runs"] for row in run["requirements"]})
        if len(routing_digests) != 2 or not all(re.fullmatch(r"[a-f0-9]{64}", digest) for digest in routing_digests):
            raise ValueError("routing_requirement_identifiers")
        for index, digest in enumerate(routing_digests, 1):
            cases.extend(exact_declaration_cases(
                f"routing_requirement_{index}", ROUTING_FIXTURE_PATH, "key", digest, alternate_digest))
        cases.extend(outer_evidence_cases(alternate_digest))
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
