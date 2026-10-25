#!/usr/bin/env python3
"""Qualify QNN only from a fresh physical run with native, backend and cleanup proof."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from check_native_memory_smoke import check_instrumentation

TEST_CLASS = "dev.chungjungsoo.gptmobile.data.localruntime.QualcommNpuInstrumentedTest"
TEST_METHOD = "matchingSm8750ModelGeneratesOnNpu"
MAX_EVIDENCE_BYTES = 256 * 1024


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def number(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def nonempty(value):
    return isinstance(value, str) and bool(value.strip())


def physical_identity(path):
    require(path is not None, "QNN qualification requires captured physical-device properties")
    properties = dict(re.findall(r"^\[([^\]]+)\]: \[([^\]]*)\]$", path.read_text(), re.MULTILINE))
    names = ("ro.build.fingerprint", "ro.product.manufacturer", "ro.product.model", "ro.hardware", "ro.product.cpu.abi", "ro.soc.model")
    require(all(properties.get(name) for name in names), "Physical QNN device/build/SoC/ABI identity is incomplete")
    require(properties.get("ro.kernel.qemu") != "1" and properties.get("ro.boot.qemu") != "1" and properties["ro.hardware"] not in {"ranchu", "goldfish", "cutf_cvm", "cuttlefish"}, "An emulator cannot qualify QNN hardware")
    require(properties["ro.product.cpu.abi"] == "arm64-v8a", "QNN qualification requires the ARM64 device ABI")
    require(re.match(r"(?i)^SM8750(?=$|[-_ ])", properties["ro.soc.model"].strip()), "QNN qualification requires SM8750")
    return {name: properties[name] for name in names}


def cleanup_proof(cleanup, execution, minimum_time, *, release=False):
    require(isinstance(cleanup, dict), "Cleanup receipt is absent")
    require(cleanup.get("obligationsObserved") is True and cleanup.get("quarantined") is not True and cleanup.get("failureReason") is None, "Cleanup obligations are unknown, failed or quarantined")
    for field in ("runtimeRequestId", "workerGenerationId", "engineInstanceId"):
        require(nonempty(execution.get(field)) and cleanup.get(field) == execution[field], f"Cleanup {field} does not match the executed request")
    if execution.get("nativeOperationId") is not None:
        require(cleanup.get("nativeOperationId") == execution["nativeOperationId"], "Cleanup native operation does not match")
    require(cleanup.get("nativeClosureConfirmed") is True and cleanup.get("conversationClosed") is True, "Native closure and conversation close are not positively acknowledged")
    actions = cleanup.get("requiredActions", [])
    require(isinstance(actions, list) and all(isinstance(action, str) for action in actions), "Cleanup action identity is malformed")
    if "release_engine" in actions or cleanup.get("terminationRequested") is True:
        require(cleanup.get("workerExitConfirmed") is True or (cleanup.get("terminationRequested") is not True and cleanup.get("engineReleased") is True), "Requested engine release or worker termination is not positively complete")
    require(cleanup.get("engineHealthy") is True or cleanup.get("engineReleased") is True or cleanup.get("workerExitConfirmed") is True, "Cleanup does not establish safe engine reuse or release")
    if release:
        require(cleanup.get("engineReleased") is True, "Cold-start engine release was not acknowledged")
    begin, end = cleanup.get("startedAtElapsedMs"), cleanup.get("observedAtElapsedMs")
    require(number(begin) and number(end) and minimum_time <= begin <= end, "Cleanup timing does not follow the matching request")
    return end


def validate_evidence(data, model_sha256, source_revision, run_id, identity):
    require(isinstance(data, dict) and data.get("schemaVersion") == 1, "Unsupported QNN evidence schema")
    require(data.get("status") == "NOT_QUALIFIED", "The app must not publish qualification before the external evidence gate")
    require(data.get("runId") == run_id and data.get("sourceRevision") == source_revision, "Stale or mismatched run/source evidence")
    require(data.get("modelSha256") == model_sha256.lower(), "Evidence does not match the trusted model digest")
    require(data.get("runtimeScope") == "isolated_process" and data.get("coldStartScope") == "engine_recreation", "Unknown execution or cold-start scope")
    device = data.get("device", {})
    require(isinstance(device, dict), "Recorded device identity is malformed")
    for field, key in (("fingerprint", "ro.build.fingerprint"), ("manufacturer", "ro.product.manufacturer"), ("model", "ro.product.model"), ("abi", "ro.product.cpu.abi")):
        require(device.get(field) == identity[key], f"Recorded device {field} does not match captured properties")
    require(device.get("soc") == "SM8750", "Recorded SoC does not match this qualification")
    require(data.get("collectionComplete") is True, "QNN evidence collection did not finish: " + str(data.get("reason", "unknown")))
    issues = data.get("issues")
    require(isinstance(issues, list), "Evidence issues are missing")
    require(not issues, "QNN remains NOT_QUALIFIED: " + "; ".join(str(value) for value in issues))
    turns = data.get("turns")
    require(isinstance(turns, list) and len(turns) == 4 and all(isinstance(turn, dict) for turn in turns), "Exactly two requests on each of two recreated engines are required")
    require([(turn.get("coldStart"), turn.get("turn")) for turn in turns] == [(0, 0), (0, 1), (1, 0), (1, 1)], "Missing, duplicated or reordered qualification requests")
    started, observed = data.get("startedAtElapsedMs"), data.get("observedAtElapsedMs")
    require(number(started) and number(observed) and started <= observed, "Evidence observation window is invalid")
    requests, generations, engines, loads, workers, last = set(), set(), {}, {}, {}, {}
    previous_finished = started
    for turn in turns:
        cold = turn["coldStart"]
        begin, end = turn.get("startedAtElapsedMs"), turn.get("finishedAtElapsedMs")
        require(number(begin) and number(end) and previous_finished <= begin <= end <= observed, "Request window is stale or overlaps an earlier request")
        previous_finished = end
        require(turn.get("done") is True and turn.get("nonemptyOutput") is True and number(turn.get("visibleCharacters")) and 0 < turn["visibleCharacters"] <= 16384, "Request has no completed nonempty output")
        require(re.fullmatch(r"[a-f0-9]{64}", turn.get("outputSha256", "")), "Output digest is absent")
        require(turn.get("errors") == [], "A failed request cannot qualify the accelerator")
        execution = turn.get("execution")
        require(isinstance(execution, dict), "Execution receipt is absent")
        require(execution.get("nativeEntered") is True and execution.get("nativeReturned") is True and execution.get("terminalKind") in {"eos", "sdk_done"}, "Native entry and successful native terminal are unobserved")
        require(execution.get("commandTerminalAcknowledged") is True, "Worker command terminal was not acknowledged")
        require(execution.get("deadlineKind") is None and execution.get("cancellationOrigin") is None, "Cancelled or deadline-limited execution cannot qualify")
        require(number(execution.get("promptTokens")) and number(execution.get("sampledTokens")) and execution["sampledTokens"] > 0, "Native token counters are absent or have no generated tokens")
        for field in ("runtimeRequestId", "nativeGenerationId", "workerGenerationId", "engineInstanceId"):
            require(nonempty(execution.get(field)), f"Execution {field} is missing")
        require(execution["runtimeRequestId"] not in requests and execution["nativeGenerationId"] not in generations, "A prior request or generation receipt was reused")
        requests.add(execution["runtimeRequestId"])
        generations.add(execution["nativeGenerationId"])
        configuration, load = execution.get("configuration"), execution.get("engineLoad")
        require(isinstance(configuration, dict) and configuration.get("generationId") == execution["nativeGenerationId"] and configuration.get("engineInstanceId") == execution["engineInstanceId"], "Generation configuration does not match the executed generation")
        require(isinstance(load, dict), "Matching engine-load receipt is absent")
        actual = load.get("actualBackend")
        require(nonempty(actual), "ACTUAL_BACKEND_UNOBSERVED: the SDK does not report its actual native backend; requested NPU is insufficient")
        acknowledged = load.get("nativeAcknowledgedFields")
        require(actual.lower() == "npu" and isinstance(acknowledged, list) and any(field in acknowledged for field in ("backend", "actualBackend")), "Native NPU backend proof is missing; requested/applied settings do not qualify")
        require(nonempty(load.get("artifactDigest")) and load["artifactDigest"].lower() == model_sha256.lower(), "Executed engine artifact does not match the trusted model")
        require(load.get("engineInstanceId") == execution["engineInstanceId"] and load.get("workerGenerationId") == execution["workerGenerationId"], "Engine-load identity does not match the request")
        require(nonempty(load.get("receiptId")) and nonempty(load.get("runtimeRevision")), "Engine load/runtime identity is incomplete")
        for field in ("adapterInvokedAtElapsedMs", "nativeReturnedAtElapsedMs", "observedAtElapsedMs"):
            require(number(execution.get(field)) and begin <= execution[field] <= end, f"Execution {field} is outside this request")
        returned = execution["nativeReturnedAtElapsedMs"]
        require(execution["adapterInvokedAtElapsedMs"] <= returned, "Native terminal precedes request invocation")
        cleanup_end = cleanup_proof(turn.get("cleanup"), execution, returned)
        require(cleanup_end <= end, "Request was recorded before cleanup completed")
        if cold in engines:
            require(engines[cold] == execution["engineInstanceId"] and loads[cold] == load["receiptId"] and workers[cold] == execution["workerGenerationId"], "Resident engine/worker changed within a cold-start pair")
        engines[cold], loads[cold], workers[cold] = execution["engineInstanceId"], load["receiptId"], execution["workerGenerationId"]
        last[cold] = (execution, end)
    require(len(set(engines.values())) == 2 and len(set(loads.values())) == 2, "Second cold start reused the first engine/load receipt")
    releases = data.get("engineReleases")
    require(isinstance(releases, list) and len(releases) == 2 and all(isinstance(release, dict) for release in releases) and [release.get("coldStart") for release in releases] == [0, 1], "Both recreated engines need release receipts")
    for release in releases:
        cold = release["coldStart"]
        execution, end = last[cold]
        require(release.get("releaseError") is None, "Engine release threw an error")
        release_end = cleanup_proof(release.get("cleanup"), execution, end, release=True)
        next_begin = turns[2]["startedAtElapsedMs"] if cold == 0 else observed
        require(release_end <= next_begin, "Next engine or final report preceded acknowledged engine release")
    return {"engineColdStarts": 2, "turnsPerStart": 2, "requestIds": sorted(requests), "engineInstanceIds": list(engines.values())}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instrumentation_output", type=Path)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--device-properties", type=Path, required=True)
    parser.add_argument("--status-output", type=Path, required=True)
    parser.add_argument("--instrumentation-exit-code", type=int, default=0)
    parser.add_argument("--evidence-exit-code", type=int, default=0)
    args = parser.parse_args(argv)
    status = {"schemaVersion": 1, "status": "NOT_QUALIFIED", "reason": "No successful matching physical QNN evidence", "sourceRevision": args.source_revision, "runId": args.run_id, "requestedModelSha256": args.model_sha256, "qualifiedBackends": []}
    def save():
        args.status_output.parent.mkdir(parents=True, exist_ok=True)
        args.status_output.write_text(json.dumps(status, indent=2, sort_keys=True) + "\n")
    save()
    try:
        require(re.fullmatch(r"[a-fA-F0-9]{64}", args.model_sha256), "A trusted model SHA-256 is required")
        require(re.fullmatch(r"[a-f0-9]{40}", args.source_revision), "An exact source revision is required")
        require(re.fullmatch(r"[A-Za-z0-9_-]{1,128}", args.run_id), "A fresh bounded qualification run ID is required")
        require(args.evidence_exit_code == 0 and args.evidence.is_file(), "Fresh app-private QNN evidence could not be captured")
        require(args.evidence.stat().st_size <= MAX_EVIDENCE_BYTES, "QNN evidence exceeds its bounded size")
        data = json.loads(args.evidence.read_text())
        identity = physical_identity(args.device_properties)
        proof = validate_evidence(data, args.model_sha256, args.source_revision, args.run_id, identity)
        require(args.instrumentation_exit_code == 0, "Instrumentation command failed or timed out")
        check_instrumentation(args.instrumentation_output, TEST_CLASS, TEST_METHOD)
        status.update(status="QUALIFIED", reason="Four matching native NPU requests and both engine releases were observed and the named physical test passed.", qualifiedBackends=["npu"], deviceIdentity=identity, evidenceSha256=hashlib.sha256(args.evidence.read_bytes()).hexdigest(), instrumentationSha256=hashlib.sha256(args.instrumentation_output.read_bytes()).hexdigest(), **proof)
        save()
        return 0
    except (OSError, UnicodeError, ValueError, TypeError, KeyError) as error:
        status["reason"] = str(error)
        save()
        print(f"QNN remains NOT_QUALIFIED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
