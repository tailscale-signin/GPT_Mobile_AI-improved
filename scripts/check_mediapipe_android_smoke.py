#!/usr/bin/env python3
"""Require the named Android JNI test and its matching real-checkpoint receipt."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import uuid

from check_native_memory_smoke import check_instrumentation
from smoke_mediapipe_speech import FIXTURE, MAX_FRAMES, MODELS, RUNTIME_VERSION, SAMPLE_RATE, verify_catalog


TEST_CLASS = "dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechNativeSmokeTest"
TEST_METHOD = "pinnedCheckpointStreamsAndCancelsThroughThePackagedAndroidJni"
RECEIPT_PREFIX = "INSTRUMENTATION_STATUS: mediapipe_smoke_evidence="
APK_NAMES = {"app-x86_64.apk", "test.apk"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate evidence field: {key}")
        result[key] = value
    return result


def parse_json(text):
    def invalid_constant(value):
        raise ValueError(f"Non-finite JSON value: {value}")

    value = json.loads(text, object_pairs_hook=unique_object, parse_constant=invalid_constant)
    require(isinstance(value, dict), "Evidence must be a JSON object")
    return value


def read_json(path):
    require(path.is_file() and not path.is_symlink(), f"Missing regular evidence file: {path}")
    require(path.stat().st_size <= 64 * 1024, f"Evidence exceeds its bound: {path}")
    return parse_json(path.read_text(encoding="utf-8"))


def digest_file(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify_apks(directory, source_revision):
    require(re.fullmatch(r"[0-9a-f]{40}", source_revision) is not None, "Expected an exact source commit")
    manifest = read_json(directory / "manifest.json")
    require(manifest.get("schema") == "native-smoke-apks-v1", "Unexpected APK manifest schema")
    require(manifest.get("source_revision") == source_revision, "APK source commit differs from this checkout")
    entries = manifest.get("apks")
    require(isinstance(entries, dict) and set(entries) == APK_NAMES, "Expected the single app/test APK pair")
    for name in sorted(APK_NAMES):
        item = entries[name]
        path = directory / name
        require(isinstance(item, dict), f"Invalid APK manifest entry: {name}")
        require(path.is_file() and not path.is_symlink(), f"Missing regular APK: {name}")
        require(type(item.get("bytes")) is int and item["bytes"] > 0 and path.stat().st_size == item["bytes"], f"APK size differs: {name}")
        require(isinstance(item.get("sha256"), str) and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) is not None, f"Invalid APK digest: {name}")
        require(digest_file(path) == item["sha256"], f"APK SHA-256 differs: {name}")
    return manifest


def verify_provisioning(path, engine):
    model = MODELS[engine]
    verify_catalog(model)
    receipt = read_json(path)
    require(receipt.get("schema") == "mediapipe-speech-checkpoint-smoke-v1", "Unexpected provisioning schema")
    require(receipt.get("engine") == engine and receipt.get("runtime_version") == RUNTIME_VERSION, "Provisioning engine/runtime mismatch")
    require(receipt.get("status") == "model_verified" and receipt.get("inference_executed") is False, "Provisioning must verify a model without claiming inference")
    require(receipt.get("model") == model, "Provisioning does not identify the exact pinned checkpoint")
    return receipt


def check_receipt(receipt, engine, run_id):
    model = MODELS[engine]
    expected = {
        "schema": "mediapipe-android-jni-smoke-v2",
        "scope": "android_jni_checkpoint_smoke",
        "status": "passed",
        "engine": engine.upper(),
        "runtime": "mediapipe-tasks-audio-" + RUNTIME_VERSION,
        "run_id": run_id,
        "model_sha256": model["sha256"],
        "model_bytes": model["bytes"],
        "requested_delegate": "CPU",
        "sample_rate_hz": SAMPLE_RATE,
        "utf8_estimated_cue_observed": True,
    }
    for key, value in expected.items():
        require(type(receipt.get(key)) is type(value) and receipt[key] == value, f"Android receipt mismatch: {key}")
    require("x86_64" in str(receipt.get("abis", "")).split(","), "Receipt does not identify the CI x86_64 ABI")
    for key in ("frames", "recovered_frames"):
        require(type(receipt.get(key)) is int and 0 < receipt[key] <= MAX_FRAMES, f"Missing bounded PCM evidence: {key}")
    require(type(receipt.get("emitted_chunks")) is int and 0 < receipt["emitted_chunks"] <= 4096, "Missing bounded stream chunk evidence")
    for key in ("prepare_ms", "first_pcm_ms"):
        require(type(receipt.get(key)) is int and 0 <= receipt[key] <= 300_000, f"Missing bounded native timing: {key}")
    for key in ("cancel_before_first_read", "cancel_after_first_chunk"):
        require(isinstance(receipt.get(key), str) and receipt[key] in {"native_cancelled", "end_of_stream"}, f"Native cancellation did not complete: {key}")
    if engine == "kokoro":
        playback = receipt.get("application_playback")
        require(isinstance(playback, dict), "Missing actual application streaming playback")
        for key, expected in {
            "voice_id": "af_heart", "finish_input_calls": 1, "presented_before_last_text": True,
            "pronunciation_reached_sdk": True, "source_caption_ranges_presented": True, "stream_closed": True,
        }.items():
            require(type(playback.get(key)) is type(expected) and playback[key] == expected, f"Application playback mismatch: {key}")
        require(type(playback.get("speaking_rate")) in (int, float) and playback["speaking_rate"] == 1.0, "Expected normal-rate Kokoro playback")
        written = playback.get("first_audio_track_write_ms")
        presented = playback.get("first_presented_audio_ms")
        require(type(written) is int and type(presented) is int and 0 <= written <= presented <= 60_000, "Missing ordered Android output timing")
        require(playback.get("clock_basis") in ("audio_timestamp", "playback_head"), "Missing AudioTrack presentation clock")
    for key in ("incremental_stream", "incremental_stream_after_cancellation"):
        check_incremental_stream(receipt.get(key), key)
    before = receipt.get("incremental_cancel_before_first_read")
    require(isinstance(before, str) and before in {"native_cancelled", "end_of_stream"}, "Open-input cancellation before first read did not complete")
    cancellation = receipt.get("incremental_cancel_while_waiting_for_text")
    require(isinstance(cancellation, dict), "Missing cancellation while production input waits")
    for key, value in {
        "outcome": "native_cancelled", "input_open_at_cancel": True, "finish_input_calls": 0,
        "first_pcm_observed": True, "input_owner_retired": True, "stream_closed": True,
    }.items():
        require(type(cancellation.get(key)) is type(value) and cancellation[key] == value, f"Open-input cancellation mismatch: {key}")
    require(type(cancellation.get("total_ms")) is int and 0 <= cancellation["total_ms"] <= 300_000, "Open-input cancellation exceeded its bound")


def check_incremental_stream(stream, label):
    require(isinstance(stream, dict), f"Missing actual incremental stream evidence: {label}")
    expected = {
        "input_mode": "incremental", "input_utf8_bytes": len(FIXTURE.encode("utf-8")),
        "input_sha256": hashlib.sha256(FIXTURE.encode("utf-8")).hexdigest(),
        "text_delta_count": 2, "finish_input_calls": 1,
        "first_pcm_before_finish_input": True, "full_input_cues_observed": True,
        "stream_closed": True,
    }
    for key, value in expected.items():
        require(type(stream.get(key)) is type(value) and stream[key] == value, f"Incremental evidence mismatch: {label}.{key}")
    require(type(stream.get("frames")) is int and 0 < stream["frames"] <= MAX_FRAMES, f"Missing bounded incremental PCM: {label}")
    require(type(stream.get("emitted_chunks")) is int and 0 < stream["emitted_chunks"] <= 4096, f"Missing bounded incremental chunks: {label}")
    require(type(stream.get("first_pcm_ms")) is int and 0 <= stream["first_pcm_ms"] <= 300_000, f"Missing bounded incremental timing: {label}")


def check_results(output, engine, run_id):
    require(str(uuid.UUID(run_id)) == run_id, "Expected this invocation's canonical UUID")
    require(output.is_file() and output.stat().st_size <= 16 * 1024 * 1024, "Missing or oversized instrumentation output")
    # No approved assumptions: a skipped, crashed, empty or misnamed test fails.
    check_instrumentation(output, TEST_CLASS, TEST_METHOD)
    lines = [line[len(RECEIPT_PREFIX):] for line in output.read_text(encoding="utf-8").splitlines() if line.startswith(RECEIPT_PREFIX)]
    require(len(lines) == 1 and len(lines[0].encode("utf-8")) <= 32 * 1024, "Expected one bounded native completion receipt")
    receipt = parse_json(lines[0])
    check_receipt(receipt, engine, run_id)
    return receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True, choices=MODELS)
    parser.add_argument("--instrumentation-output", type=Path, required=True)
    parser.add_argument("--provisioning-receipt", type=Path, required=True)
    parser.add_argument("--apk-dir", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--process-exit", type=int, required=True)
    parser.add_argument("--status-output", type=Path, required=True)
    args = parser.parse_args(argv)
    status = {
        "schema": "mediapipe-android-smoke-verification-v1",
        "status": "NOT_VERIFIED",
        "scope": "android_x86_64_cpu_jni_checkpoint_smoke",
        "engine": args.engine,
        "source_revision": args.source_revision,
        "run_id": args.run_id,
        "instrumentation_process_exit": args.process_exit,
    }

    def write_status():
        args.status_output.parent.mkdir(parents=True, exist_ok=True)
        args.status_output.write_text(json.dumps(status, indent=2, allow_nan=False) + "\n", encoding="utf-8")

    # Replace any prior success before opening potentially absent/failed inputs.
    write_status()
    try:
        require(args.process_exit == 0, f"Instrumentation process exited {args.process_exit}")
        apks = verify_apks(args.apk_dir, args.source_revision)
        verify_provisioning(args.provisioning_receipt, args.engine)
        receipt = check_results(args.instrumentation_output, args.engine, args.run_id)
        status.update(
            status="PASSED",
            test_class=TEST_CLASS,
            test_method=TEST_METHOD,
            apks=apks["apks"],
            model_sha256=MODELS[args.engine]["sha256"],
            instrumentation_sha256=digest_file(args.instrumentation_output),
            provisioning_sha256=digest_file(args.provisioning_receipt),
            native_receipt=receipt,
        )
        write_status()
        print(f"Android speech JNI: {args.engine} passed the named test, pinned model, single/incremental input, cancellation and recovery checks")
        return 0
    except (OSError, UnicodeError, ValueError, RuntimeError) as failure:
        status["reason"] = str(failure)
        write_status()
        print(f"Android speech JNI: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
