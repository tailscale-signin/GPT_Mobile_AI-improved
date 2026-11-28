#!/usr/bin/env python3
"""Exercise exact public declarations with the actual pinned history scanner.

Invoked after scanner selection by scan_secret_history.sh. Temporary Git fixtures
contain generated test values; only classifications and counts leave this check.
"""
import argparse
import ast
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from xml.etree import ElementTree
import zipfile

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
POCKET_CATALOG_PATH = "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/PocketCompiledSpeechCatalog.kt"
POCKET_ALIGNMENT_EVIDENCE_PATH = "docs/audits/pocket-caption-alignment.json"
# Values remain in their original declarations and the fixed scanner policy.
# The repair receipt independently verifies their public source/artifact bytes.
POCKET_PUBLIC_DECLARATIONS = (
    ("pocket_public_revision", POCKET_CATALOG_PATH, "TOKENIZER_REVISION", "kotlin"),
    ("pocket_source_digest", POCKET_ALIGNMENT_EVIDENCE_PATH,
     "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/PocketCompiledTokenizer.kt", "json"),
    ("pocket_test_digest", POCKET_ALIGNMENT_EVIDENCE_PATH,
     "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/voice/PocketCompiledTokenizerTest.kt", "json"),
    ("pocket_runtime_digest", POCKET_ALIGNMENT_EVIDENCE_PATH,
     "/workspace/scratch/d6788699ebc6/pocket-research/litert-api-2.4.1.jar", "json"),
    ("pocket_residency_digest", "docs/repairs/pocket-diagnostics-ci-review-2026-11-28.json",
     "modelKey", "json"),
    ("pocket_smoke_digest", "docs/repairs/pocket-caption-validation-2026-11-28.json",
     "scripts/run_mediapipe_android_smoke.sh", "json"),
)
POCKET_PROVENANCE_PATH = "docs/repairs/pocket-history-declaration-provenance-2026-11-28.json"
POCKET_TOKENIZER_PATH = "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/PocketCompiledTokenizer.kt"
POCKET_TOKENIZER_TEST_PATH = "app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/voice/PocketCompiledTokenizerTest.kt"
POCKET_DIAGNOSTICS_PATH = "docs/repairs/pocket-diagnostics-ci-review-2026-11-28.json"
POCKET_OUTPUT_PATH = "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/KokoroVoiceOutput.kt"
POCKET_VALIDATION_PATH = "docs/repairs/pocket-caption-validation-2026-11-28.json"
POCKET_SMOKE_PATH = "scripts/run_mediapipe_android_smoke.sh"
POCKET_RUNTIME_PATH = "docs/audits/pocket-compiled-runtime.json"
POCKET_LICENSE_PATH = "docs/licenses/pocket-compiled.md"
POCKET_FIXTURE_PATH = "app/src/test/resources/pocket-compiled/tokenizer.model"
VERIFICATION_PATH = "gradle/verification-metadata.xml"
POCKET_HISTORICAL_PATHS = (
    POCKET_CATALOG_PATH, POCKET_ALIGNMENT_EVIDENCE_PATH, POCKET_TOKENIZER_PATH,
    POCKET_TOKENIZER_TEST_PATH, POCKET_DIAGNOSTICS_PATH, POCKET_OUTPUT_PATH,
    POCKET_VALIDATION_PATH, POCKET_SMOKE_PATH, POCKET_RUNTIME_PATH,
    POCKET_LICENSE_PATH, POCKET_FIXTURE_PATH, VERIFICATION_PATH,
)
POCKET_CLASSPATH_LABEL = "/workspace/scratch/d6788699ebc6/pocket-research/litert-api-2.4.1.jar"


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


def pocket_historical_inputs():
    """Read exact public source blobs, independent of subsequent worktree edits."""
    proof = json.loads((ROOT / POCKET_PROVENANCE_PATH).read_text(encoding="utf-8"))
    if proof["schemaVersion"] != 1 or proof["sourceCommit"] != "fc0f9c62dfb9e86b36c2647d39cb5040c12b1748":
        raise ValueError("pocket_provenance_contract")
    rows = proof["historicalInputs"]
    if len(rows) != len(POCKET_HISTORICAL_PATHS) or {row["path"] for row in rows} != set(POCKET_HISTORICAL_PATHS):
        raise ValueError("pocket_provenance_paths")
    environment = isolated_environment()
    inputs = {}
    for row in rows:
        blob = row["gitBlob"]
        if not isinstance(blob, str) or not re.fullmatch(r"[0-9a-f]{40}", blob):
            raise ValueError("pocket_provenance_object")
        if quiet(["git", "cat-file", "-t", blob], ROOT, environment) != "blob":
            raise ValueError("pocket_provenance_object_type")
        result = subprocess.run(["git", "cat-file", "blob", blob], cwd=ROOT,
                                env=environment, capture_output=True, timeout=10)
        if result.returncode:
            raise ValueError("pocket_provenance_object_unavailable")
        raw = result.stdout
        identity = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
        if identity != blob:
            raise ValueError("pocket_provenance_object_identity")
        inputs[row["path"]] = raw
    return inputs


def pocket_history_fixture_inputs():
    # The synthetic integrity repository recreates the same immutable blob IDs
    # by committing these bytes. Keep its current Gradle metadata for the older
    # controls and retain the historical copy at one fixed fixture-only path.
    inputs = pocket_historical_inputs()
    inputs["history-proof/pocket-verification-metadata.xml"] = inputs.pop(VERIFICATION_PATH)
    return inputs


def verified_pocket_historical_inputs(litert_api_aar=None):
    proof = json.loads((ROOT / POCKET_PROVENANCE_PATH).read_text(encoding="utf-8"))
    inputs = pocket_historical_inputs()
    caption = json.loads(inputs[POCKET_ALIGNMENT_EVIDENCE_PATH])
    validation = json.loads(inputs[POCKET_VALIDATION_PATH])
    diagnostics = json.loads(inputs[POCKET_DIAGNOSTICS_PATH])
    catalog = inputs[POCKET_CATALOG_PATH].decode("utf-8")
    license_text = inputs[POCKET_LICENSE_PATH].decode("utf-8")
    tokenizer = proof["tokenizer"]
    revision = tokenizer["revision"]
    exact = '    const val TOKENIZER_REVISION = "' + revision + '"'
    if (not re.fullmatch(r"[0-9a-f]{40}", revision) or catalog.splitlines().count(exact) != 1
            or tokenizer["publisherCommit"] !=
            "https://huggingface.co/kyutai/pocket-tts-without-voice-cloning/commit/" + revision
            or "/tree/" + revision not in license_text
            or len(inputs[POCKET_FIXTURE_PATH]) != tokenizer["bytes"]
            or hashlib.sha256(inputs[POCKET_FIXTURE_PATH]).hexdigest() != tokenizer["sha256"]
            or tokenizer["sha256"] not in catalog or tokenizer["sha256"] not in license_text):
        raise ValueError("pocket_public_revision_contract")
    source_rows = (
        (POCKET_TOKENIZER_PATH,
         caption["hostValidation"]["sourceSha256"][POCKET_TOKENIZER_PATH]),
        (POCKET_TOKENIZER_TEST_PATH,
         caption["hostValidation"]["sourceSha256"][POCKET_TOKENIZER_TEST_PATH]),
        (POCKET_SMOKE_PATH,
         validation["direct_tested_source_sha256"][POCKET_SMOKE_PATH]),
    )
    for source_path, digest in source_rows:
        if hashlib.sha256(inputs[source_path]).hexdigest() != digest:
            raise ValueError("pocket_historical_source_contract")
    blocks = re.findall(r"(?ms)^    internal data class ModelKey\(.*?^    }\n",
                        inputs[POCKET_OUTPUT_PATH].decode("utf-8"))
    if len(blocks) != 1:
        raise ValueError("pocket_model_extract_shape")
    extract = ("package dev.chungjungsoo.gptmobile.data.voice\n"
               "internal class KokoroVoiceOutput {\n" + blocks[0] + "}\n").encode("utf-8")
    digest = diagnostics["testedExtractSha256"]["modelKey"]
    if (hashlib.sha256(extract).hexdigest() != digest or proof["modelKeyExtract"]["sha256"] != digest
            or len(extract) != proof["modelKeyExtract"]["bytes"]):
        raise ValueError("pocket_model_extract_contract")
    artifact = proof["litertApi"]
    runtime = json.loads(inputs[POCKET_RUNTIME_PATH])
    publishers = [row for row in runtime["publisherArtifacts"]
                  if (row["artifact"], row["version"]) == ("litert-api", "2.4.1")]
    verification = ElementTree.fromstring(inputs[VERIFICATION_PATH])
    approved = [pin.get("value") for component in verification.findall(".//{*}component")
                if (component.get("group"), component.get("name"), component.get("version")) ==
                ("com.google.ai.edge.litert", "litert-api", "2.4.1")
                for item in component.findall("{*}artifact") if item.get("name") == "litert-api-2.4.1.aar"
                for pin in item.findall("{*}sha256")]
    digest = caption["hostValidation"]["classpathArtifactsSha256"][POCKET_CLASSPATH_LABEL]
    if (len(publishers) != 1 or artifact["coordinate"] != "com.google.ai.edge.litert:litert-api:2.4.1"
            or approved != [artifact["container"]["sha256"]]
            or publishers[0]["aar"]["sha256"] != artifact["container"]["sha256"]
            or publishers[0]["aar"]["bytes"] != artifact["container"]["bytes"]
            or publishers[0]["publisher"] + ".aar" != artifact["publisher"]
            or artifact["member"]["name"] != "classes.jar" or artifact["member"]["sha256"] != digest):
        raise ValueError("pocket_dependency_provenance_contract")
    if litert_api_aar is not None:
        raw = litert_api_aar.read_bytes()
        if (len(raw) != artifact["container"]["bytes"]
                or hashlib.sha256(raw).hexdigest() != artifact["container"]["sha256"]):
            raise ValueError("pocket_dependency_archive_contract")
        with zipfile.ZipFile(litert_api_aar) as archive:
            member = archive.read("classes.jar")
        if len(member) != artifact["member"]["bytes"] or hashlib.sha256(member).hexdigest() != digest:
            raise ValueError("pocket_dependency_member_contract")
    return inputs


def pocket_declaration_cases(alternate_digest, litert_api_aar=None):
    inputs = verified_pocket_historical_inputs(litert_api_aar)
    cases = []
    for name, path, field, syntax in POCKET_PUBLIC_DECLARATIONS:
        if syntax == "kotlin":
            pattern = r'[ \t]*const val ' + re.escape(field) + r' = "([0-9a-f]{40})"[ \t]*'
        else:
            pattern = r'[ \t]*"' + re.escape(field) + r'": "([0-9a-f]{64})",[ \t]*'
        declarations = [(line, match) for line in inputs[path].decode("utf-8").splitlines()
                        if (match := re.fullmatch(pattern, line))]
        if len(declarations) != 1:
            raise ValueError("pocket_public_declaration_contract")
        exact, match = declarations[0]
        digest = match.group(1)
        alternate = exact.replace(digest, alternate_digest[:len(digest)], 1)
        neighboring_key = '  "apiKey": "' + alternate_digest + '",'
        cases.extend([
            (name + "_exact", path, exact, 0),
            (name + "_different_value", path, alternate, 1),
            (name + "_different_path", path + ".other", exact, 1),
            (name + "_different_field", path, exact.replace(field, "other_api_key", 1), 1),
            (name + "_prefix_content", path, '"extra": true, ' + exact.lstrip(), 1),
            (name + "_trailing_content", path, exact + ' "extra": true,', 1),
            (name + "_secret_before", path, neighboring_key + "\n" + exact, 1),
            (name + "_secret_after", path, exact + "\n" + neighboring_key, 1, 3),
        ])
    return cases


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
    parser.add_argument("--litert-api-aar", type=Path,
                        help="Optionally reproduce the approved AAR-to-classes.jar byte proof")
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
        cases.extend(pocket_declaration_cases(alternate_digest, args.litert_api_aar))
        summary["pocketDependencyArchiveBytesVerified"] = args.litert_api_aar is not None
        if len({case[0] for case in cases}) != len(cases):
            raise ValueError("duplicate_scanner_case")
        with tempfile.TemporaryDirectory(prefix="gitleaks-model-pin-") as temporary:
            parent = Path(temporary)
            # Each case owns a separate repository/report. Bound concurrency to
            # keep the expanded controls inside the existing integrity deadline;
            # map retains input order and every case's original assertions.
            with ThreadPoolExecutor(max_workers=4) as pool:
                summary["cases"] = list(pool.map(
                    lambda case: scan_case(scanner, config, parent, environment, *case), cases))
        summary["passed"] = all(case["passed"] for case in summary["cases"])
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired,
            ElementTree.ParseError, zipfile.BadZipFile):
        # Child output and exception text can contain the fixture values.
        summary["error"] = "scanner_fixture_validation_failed"
    if args.report:
        args.report.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
