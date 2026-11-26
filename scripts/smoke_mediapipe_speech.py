#!/usr/bin/env python3
"""Real MediaPipe 1.2.3 checkpoint smoke on Linux x86_64 / CPython 3.12.

Install scripts/requirements-voice-mediapipe-smoke.txt in a dedicated venv using
pip --require-hashes --only-binary=:all:. Ubuntu 24.04 also needs libgles2,
libegl1, libgl1, libglib2.0-0t64 and libportaudio2. The published runtime requires ordinary
/proc and /sys CPU topology; this script reports missing topology as blocked.

This exercises the official SDK and actual pinned model files, never synthetic
models or replacement native bindings. Its timings describe this Linux run;
they are not Android latency, accelerator placement or speech-quality evidence.
"""

from __future__ import annotations

import argparse
import collections
from contextlib import closing
import datetime as dt
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request


RUNTIME_VERSION = "1.2.3"
RUNTIME_WHEEL_SHA256 = "1fcda646cc7b96f73453acc416ce31d0fba287d0f1fc095865272a7ea6986497"
NATIVE_LIBRARY_SHA256 = "7ce8b23159d3fd4e8b727e35afbfb35f3d45d6bde1ec11cc01c316b3bf469f63"
SAMPLE_RATE = 24_000
MAX_FRAMES = SAMPLE_RATE * 30
FIXTURE = "Café is ready. The quick brown fox jumps over the lazy dog."
INCREMENTAL_PARTS = ("Café is ready. ", "The quick brown fox jumps over the lazy dog.")
PRONUNCIATION_CORPUS = "Dr. Chen read the report yesterday. The lead pipe weighs 3.5 kilograms. The U.S. team met J. R. Tolkien. Café is ready."
CANCEL_FIXTURE = "This next sentence checks cancellation before the entire request finishes. " * 12

# Keep these immutable publisher identities in sync with MediaPipeSpeechCatalog.kt.
MODELS = {
    "kokoro": {
        "repository": "litert-community/Kokoro-82M",
        "revision": "bf1c2405642aa310bd210205c22d3ae1abc253fb",
        "file_name": "kokoro_int8_fp16.litertlm",
        "bytes": 294_783_298,
        "sha256": "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1",
        "voice": "af_heart",
        "required_voices": ["af_heart", "am_adam", "bf_emma"],
    },
    "kokoro-gpu": {
        "repository": "litert-community/Kokoro-82M",
        "revision": "bf1c2405642aa310bd210205c22d3ae1abc253fb",
        "file_name": "kokoro_int8_gpu_fp16.litertlm",
        "bytes": 294_750_530,
        "sha256": "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
        "voice": "af_heart",
        "required_voices": ["af_heart", "am_adam", "bf_emma"],
    },
    "phonon": {
        "repository": "litert-community/phonon-31m-en",
        "revision": "ee374c33f0f6f8ed1d9f761e21cdb97df6accfe8",
        "file_name": "phonon_int8.litertlm",
        "bytes": 87_205_888,
        "sha256": "b33a9a0d65acec36762db38a2bff6d899f4aab34b196bb56d89fab5665eabebe",
        "voice": "freya",
        "required_voices": ["freya"],
    },
    "pocket": {
        "repository": "litert-community/pocket-tts",
        "revision": "00c866f2397788f514ab6bbcb931be22afc3b58f",
        "file_name": "pocket_tts_en_int8.litertlm",
        "bytes": 162_766_856,
        "sha256": "465e0083cd61188772aed76160b07af0dcf59057497d7659f43f3f2b13a174a1",
        "voice": "alba",
        "required_voices": ["alba"],
    },
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    staged = path.with_suffix(path.suffix + ".part")
    staged.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    staged.replace(path)


def verify_catalog(model: dict) -> None:
    catalog = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/MediaPipeSpeechCatalog.kt"
    source = catalog.read_text(encoding="utf-8")
    for field in ("repository", "revision", "file_name", "sha256", "voice"):
        require(f'"{model[field]}"' in source, f"Smoke/catalog identity mismatch: {field}")
    require(str(model["bytes"]) in source.replace("_", ""), "Smoke/catalog byte length mismatch")


def provision_model(model: dict, directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / model["file_name"]
    if destination.exists():
        require(destination.is_file() and not destination.is_symlink(), "Model must be a regular file")
        require(destination.stat().st_size == model["bytes"] and sha256(destination) == model["sha256"], "Existing model failed size/SHA-256 verification; use a clean model directory")
        return destination
    require(shutil.disk_usage(directory).free >= model["bytes"] + 64 * 1024 * 1024, "Not enough space for the pinned model")
    url = f'https://huggingface.co/{model["repository"]}/resolve/{model["revision"]}/{model["file_name"]}'
    request = urllib.request.Request(url, headers={"User-Agent": "GPT-Mobile-MediaPipe-checkpoint-smoke/1"})
    started = time.monotonic()
    staged: Path | None = None
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            require(urllib.parse.urlparse(response.url).scheme == "https", "Model redirect must use HTTPS")
            length = response.headers.get("Content-Length")
            require(length is None or int(length) == model["bytes"], "Publisher length differs from the pinned model")
            digest = hashlib.sha256()
            downloaded = 0
            with tempfile.NamedTemporaryFile(dir=directory, prefix=model["file_name"] + ".", suffix=".part", delete=False) as output:
                staged = Path(output.name)
                while block := response.read(1024 * 1024):
                    downloaded += len(block)
                    require(downloaded <= model["bytes"], "Model download exceeds its pinned size")
                    require(time.monotonic() - started < 300, "Model download exceeded five minutes")
                    digest.update(block)
                    output.write(block)
                output.flush()
                os.fsync(output.fileno())
            require(downloaded == model["bytes"] and digest.hexdigest() == model["sha256"], "Downloaded model failed size/SHA-256 verification")
        staged.replace(destination)
        return destination
    finally:
        if staged is not None:
            staged.unlink(missing_ok=True)


def prerequisites() -> dict:
    require(platform.system() == "Linux" and platform.machine() in ("x86_64", "AMD64"), "Pinned host wheel requires Linux x86_64")
    require(sys.version_info[:2] == (3, 12), "Pinned host requirements require CPython 3.12")
    topology = {}
    for name in ("possible", "present", "online"):
        path = Path("/sys/devices/system/cpu") / name
        require(path.is_file() and os.access(path, os.R_OK), f"Published runtime prerequisite missing: {path}; run on an ordinary Linux host")
        topology[name] = path.read_text(encoding="ascii").strip()
    require(Path("/proc/cpuinfo").is_file(), "Published runtime requires /proc/cpuinfo")
    require(importlib.metadata.version("mediapipe") == RUNTIME_VERSION, "Install the pinned MediaPipe requirements first")
    distribution = importlib.metadata.distribution("mediapipe")
    native = Path(distribution.locate_file("mediapipe/tasks/c/libmediapipe.so"))
    require(sha256(native) == NATIVE_LIBRARY_SHA256, "Installed native library differs from the verified official wheel")
    return {"platform": platform.platform(), "python": platform.python_version(), "cpu_topology": topology, "native_sha256": NATIVE_LIBRARY_SHA256}


def input_chunks(task, text: str, *, incremental: bool, evidence: dict):
    """Drive the real SDK input protocol; the second delta waits for actual PCM."""
    evidence.update(text_delta_count=0, finish_input_calls=0, first_pcm_before_finish_input=False)
    if incremental:
        require(text == "".join(INCREMENTAL_PARTS), "Incremental fixture parts differ from the full input")
    with task.open_stream(initial_text="" if incremental else text, finish_input=not incremental) as stream:
        if incremental:
            stream.push_text(INCREMENTAL_PARTS[0])
            evidence["text_delta_count"] += 1
        for chunk in stream:
            yield chunk
            if incremental and chunk.samples.size and not evidence["finish_input_calls"]:
                require(not chunk.is_final, "Incremental stream ended before the remaining input was supplied")
                evidence["first_pcm_before_finish_input"] = True
                stream.push_text(INCREMENTAL_PARTS[1])
                evidence["text_delta_count"] += 1
                stream.finish_input()
                evidence["finish_input_calls"] += 1
    if incremental:
        require(evidence["first_pcm_before_finish_input"] and evidence["text_delta_count"] == 2 and evidence["finish_input_calls"] == 1, "Incremental input never completed after its first PCM")


def consume(task, text: str, np, *, incremental: bool = False, include_word_cues: bool = False) -> dict:
    start = time.monotonic()
    first_pcm = None
    frames = 0
    chunks = 0
    pcm_chunks = 0
    nonzero = 0
    energy = 0.0
    clipped = 0
    peak = 0.0
    final_seen = False
    digest = hashlib.sha256()
    words = {}
    input_evidence = {}
    boundaries = {0}
    position = 0
    for character in text:
        position += len(character.encode("utf-8"))
        boundaries.add(position)
    with closing(input_chunks(task, text, incremental=incremental, evidence=input_evidence)) as chunks_from_sdk:
        for chunk in chunks_from_sdk:
            require(not final_seen, "SDK emitted a chunk after its terminal chunk")
            require(chunk.sample_rate_hz == SAMPLE_RATE, "Unexpected sample rate")
            samples = chunk.samples
            require(samples.ndim == 1 and np.isfinite(samples).all(), "Non-finite or non-mono PCM")
            frames += int(samples.size)
            chunks += 1
            require(frames <= MAX_FRAMES and chunks <= 4096, "Smoke stream exceeded its bounds")
            if samples.size:
                pcm_chunks += 1
                if first_pcm is None:
                    first_pcm = (time.monotonic() - start) * 1000
                nonzero += int(np.count_nonzero(np.abs(samples) >= 1e-5))
                energy += float(np.dot(samples.astype(np.float64), samples.astype(np.float64)))
                clipped += int(np.count_nonzero(np.abs(samples) >= 1.0))
                peak = max(peak, float(np.max(np.abs(samples))))
                digest.update(samples.astype("<f4", copy=False).tobytes())
            for word in chunk.word_timestamps:
                require(word.start_char in boundaries and word.end_char in boundaries and word.end_char > word.start_char, "SDK timestamp is not a valid full-input UTF-8 span")
                require(math.isfinite(word.start_sec) and math.isfinite(word.end_sec) and 0 <= word.start_sec <= word.end_sec <= 30, "Invalid estimated word time")
                words.setdefault((word.start_char, word.end_char), (word.start_sec, word.end_sec))
            require(len(words) <= 2048, "Word timestamp limit exceeded")
            final_seen = chunk.is_final
    require(frames >= SAMPLE_RATE // 5 and nonzero > 0 and first_pcm is not None, "No audible PCM reached end-of-stream")
    require(pcm_chunks >= 2, "The two-sentence fixture did not produce incremental PCM chunks")
    if incremental:
        require((0, len("Café".encode("utf-8"))) in words, "Incremental UTF-8 first-word cue is missing")
        final_word_end = len(text.rstrip(".!? \n").encode("utf-8"))
        require(any(begin < final_word_end <= end for begin, end in words), "Incremental stream lost its final input word")
    # Python's released wrapper rounds seconds to four decimal places (up to 2.4 frames).
    require(all(end * SAMPLE_RATE <= frames + 3 for _, end in words.values()), "Estimated word time exceeds the complete waveform")
    ordered = [value for _, value in sorted(words.items())]
    overlaps = sum(start < previous_end for (_, previous_end), (start, _) in zip(ordered, ordered[1:]))
    ties = sum(start == end for start, end in ordered)
    return {
        "frames": frames, "sample_rate_hz": SAMPLE_RATE, "pcm_chunks": pcm_chunks,
        "total_chunks": chunks, "end_of_stream": True, "final_flag_seen": bool(final_seen),
        "non_silent_frames": nonzero, "rms": math.sqrt(energy / frames), "peak": peak,
        "pcm_sha256": digest.hexdigest(), "first_pcm_ms": first_pcm,
        "stream_wall_ms": (time.monotonic() - start) * 1000,
        "clipped_frames": clipped,
        **({"word_cues": [{"utf8_start": begin, "utf8_end": end, "start_s": start, "end_s": stop}
                         for (begin, end), (start, stop) in sorted(words.items())]} if include_word_cues else {}),
        "estimated_word_spans": len(words), "estimated_overlaps": overlaps,
        "zero_duration_estimates": ties,
        "input_mode": "incremental" if incremental else "single_input",
        "input_utf8_bytes": len(text.encode("utf-8")),
        "input_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "full_input_cues_observed": bool(incremental),
        **input_evidence,
    }


def cancellation_probe(task, after_first_chunk: bool, *, incremental: bool = False) -> dict:
    started = time.monotonic()
    with task.open_stream(initial_text="" if incremental else CANCEL_FIXTURE, finish_input=not incremental) as stream:
        if incremental:
            stream.push_text(INCREMENTAL_PARTS[0])
        iterator = iter(stream)
        if after_first_chunk:
            first = next(iterator)
            require(first.samples.size > 0, "Cancellation fixture produced no first PCM chunk")
        cancelled_at = time.monotonic()
        stream.cancel()
        try:
            next(iterator)
        except StopIteration:
            outcome = "end_of_stream"
        except RuntimeError as failure:
            # kMpCancelled == 1 in the released C status enum. Other errors do not pass.
            require("C API error (1):" in str(failure), f"Unexpected cancellation failure: {failure}")
            outcome = "native_cancelled"
        else:
            raise RuntimeError("Native stream returned more PCM after cancellation")
    return {"point": "after_first_chunk" if after_first_chunk else "before_first_read", "input_mode": "incremental" if incremental else "single_input", "input_open_at_cancel": incremental, "finish_input_calls": 0, "outcome": outcome, "cancel_and_close_ms": (time.monotonic() - cancelled_at) * 1000, "total_ms": (time.monotonic() - started) * 1000}


def compare_corpus(reference: dict, candidate: dict) -> dict:
    require(reference["input_sha256"] == candidate["input_sha256"] and
            reference["sample_rate_hz"] == candidate["sample_rate_hz"], "Corpus identities differ")
    cpu_spans = {(cue["utf8_start"], cue["utf8_end"]) for cue in reference["word_cues"]}
    gpu_spans = {(cue["utf8_start"], cue["utf8_end"]) for cue in candidate["word_cues"]}
    require(cpu_spans and gpu_spans, "No corpus word cues available for comparison")
    duration_ratio = candidate["frames"] / reference["frames"]
    rms_ratio = candidate["rms"] / reference["rms"]
    clipped_increase = candidate["clipped_frames"] / candidate["frames"] - reference["clipped_frames"] / reference["frames"]
    return {"duration_ratio": duration_ratio, "rms_ratio": rms_ratio, "clipped_fraction_increase": clipped_increase,
            "same_source_word_spans": cpu_spans == gpu_spans,
            "consistent": .8 <= duration_ratio <= 1.25 and .5 <= rms_ratio <= 2.0 and clipped_increase <= .01 and cpu_spans == gpu_spans}


def gpu_unavailable(message: str) -> bool:
    return any(fragment in message.lower() for fragment in (
        "gpu accelerator could not be loaded and registered", "gpu accelerator is not registered",
        "gpu delegate is not supported", "gpu support is not enabled", "no gpu accelerator",
        "no registered accelerator for the requested hardware", "no opencl platform", "cl_platform_not_found_khr",
    ))


def read_worker_output(output, tail, evidence):
    """Count PoCL kernel events created and completed during the measured speech stream only."""
    measuring = False
    pending = set()
    for line in iter(lambda: output.readline(8192), b""):
        tail.append(line)
        if line.rstrip() == b"KOKORO_CORPUS_BEGIN":
            measuring = True
        elif line.rstrip() == b"KOKORO_CORPUS_END":
            measuring = False
            pending.clear()
        elif measuring:
            created = re.search(rb"Created event (\d+) \([^)]*\) Command ndrange_kernel\b", line)
            completed = re.search(rb"Command complete, event (\d+)\b", line)
            if created and len(pending) < 65536:
                pending.add(created[1])
            elif completed and completed[1] in pending:
                pending.remove(completed[1])
                evidence["pocl_completed_corpus_kernels"] += 1
        if not evidence["runtime_reported_gpu_unavailable"] and gpu_unavailable(line.decode("utf-8", errors="replace")):
            evidence["runtime_reported_gpu_unavailable"] = True


def worker(args, model: dict, model_file: Path, base: dict) -> int:
    import numpy as np
    from mediapipe.tasks.python.audio.text_to_speech import TextToSpeech, TextToSpeechOptions
    from mediapipe.tasks.python.core.base_options import BaseOptions

    host = prerequisites()
    require(shutil.disk_usage(model_file.parent).free >= model["bytes"] * 4, "Not enough space for bounded runtime unpacking/cache")
    cache = model_file.parent / (args.engine + "-runtime-cache")
    cache.mkdir(exist_ok=True)
    started = time.monotonic()
    delegate = BaseOptions.Delegate.GPU if args.engine == "kokoro-gpu" else BaseOptions.Delegate.CPU
    options = TextToSpeechOptions(base_options=BaseOptions(model_asset_path=str(model_file), delegate=delegate),
                                  default_voice_id=model["voice"], speaking_rate=1.0, target_bucket=0, cache_dir=str(cache))
    try:
        task = TextToSpeech.create_from_options(options)
    except (RuntimeError, ValueError) as exc:
        if delegate != BaseOptions.Delegate.GPU:
            raise
        write_json(args.output, base | {"status": "gpu_initialization_failed", "error": str(exc)[:4096]})
        return 77
    with task:
        opened_ms = (time.monotonic() - started) * 1000
        voices = {voice.voice_id for voice in task.list_voices()}
        require(set(model["required_voices"]) <= voices, "Pinned model does not expose the app's selectable voices")
        corpus_result = {}
        if args.engine in ("kokoro", "kokoro-gpu"):
            print("KOKORO_CORPUS_BEGIN", flush=True)
            corpus = consume(task, PRONUNCIATION_CORPUS, np, include_word_cues=True)
            print("KOKORO_CORPUS_END", flush=True)
            require(corpus["word_cues"], "The pronunciation corpus produced no source word cues")
            corpus_result = {"pronunciation_input": PRONUNCIATION_CORPUS, "pronunciation_corpus": corpus}
            if args.cpu_reference:
                reference = json.loads(args.cpu_reference.read_text())
                require(reference["status"] == "passed" and reference["engine"] == "kokoro" and
                        reference["model"] == MODELS["kokoro"] and reference["host"]["native_sha256"] == host["native_sha256"] and
                        reference["requested_delegate"] == "CPU" and reference["speaking_rate"] == 1.0, "Invalid Kokoro CPU reference")
                corpus_result["cpu_reference"] = compare_corpus(reference["pronunciation_corpus"], corpus)
        initial = consume(task, FIXTURE, np)
        before = cancellation_probe(task, False)
        after = cancellation_probe(task, True)
        recovered = consume(task, FIXTURE, np)
        incremental = consume(task, FIXTURE, np, incremental=True)
        incremental_before = cancellation_probe(task, False, incremental=True)
        incremental_after = cancellation_probe(task, True, incremental=True)
        incremental_recovered = consume(task, FIXTURE, np, incremental=True)
    write_json(args.output, base | {"status": "passed", "host": host, **corpus_result, "requested_delegate": delegate.name, "speaking_rate": 1.0, "prepare_ms": opened_ms, "available_voice_ids": sorted(voices), "stream": initial, "cancellation": [before, after], "stream_after_cancellation": recovered, "incremental_stream": incremental, "incremental_cancellation": [incremental_before, incremental_after], "incremental_stream_after_cancellation": incremental_recovered})
    return 0


def run_worker(args, model_file: Path, base: dict) -> int:
    command = [sys.executable, str(Path(__file__).resolve()), "--engine", args.engine, "--output", str(args.output), "--model-dir", str(model_file.parent), "--timeout", str(args.timeout), "--worker"]
    if args.cpu_reference:
        command.extend(["--cpu-reference", str(args.cpu_reference)])
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    tail = collections.deque(maxlen=128)
    evidence = {"pocl_completed_corpus_kernels": 0, "runtime_reported_gpu_unavailable": False}
    assert process.stdout is not None
    reader = threading.Thread(target=read_worker_output, args=(process.stdout, tail, evidence), daemon=True)
    reader.start()
    timed_out = False
    try:
        code = process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        os.killpg(process.pid, signal.SIGKILL)
        code = process.wait(timeout=10)
    reader.join(timeout=5)
    log = b"".join(tail).decode("utf-8", errors="replace")
    args.output.with_suffix(".log").write_text(log, encoding="utf-8")
    if args.engine == "kokoro-gpu" and code == 77 and not timed_out and args.output.is_file():
        result = json.loads(args.output.read_text())
        if result.get("status") == "gpu_initialization_failed" and (evidence["runtime_reported_gpu_unavailable"] or gpu_unavailable(result["error"])):
            write_json(args.output, result | {"status": "gpu_unavailable", "gpu_dispatch": "none", "driver_evidence": evidence, "diagnostic_tail": log[-8192:]})
            print("GPU delegate unavailable on this host. See the recorded initialization error and native diagnostics.")
            return 77
    if code != 0 or timed_out:
        write_json(args.output, base | {"status": "failed", "native_process_exit": code, "timed_out": timed_out, "diagnostic_tail": log[-8192:]})
        return 1
    result = json.loads(args.output.read_text(encoding="utf-8"))
    require(result.get("status") == "passed", "Worker exited without successful real-checkpoint evidence")
    if args.engine == "kokoro-gpu":
        # The SDK exposes no per-op delegate counters. A successful PCM stream cannot identify its device.
        result["gpu_dispatch"] = "unreported_by_task"
        if evidence["pocl_completed_corpus_kernels"]:
            result["gpu_dispatch"] = "pocl_opencl_kernels_executed"
        elif evidence["runtime_reported_gpu_unavailable"]:
            result["gpu_dispatch"] = "unavailable_with_successful_pcm"
        result["driver_evidence"] = evidence
        result["diagnostic_tail"] = log[-8192:]
        if result.get("cpu_reference", {}).get("consistent") is False:
            result["status"] = "cpu_reference_mismatch"
            write_json(args.output, result)
            return 1
        write_json(args.output, result)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True, choices=MODELS)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model-dir", type=Path)
    parser.add_argument("--cpu-reference", type=Path, help="Compare the GPU checkpoint with the verified standard Kokoro run on this host")
    parser.add_argument("--download-only", action="store_true", help="Verify/provision the pinned model; does not run or pass inference")
    parser.add_argument("--timeout", type=int, default=300, help="Bound native worker wall time in seconds (30..900)")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # A native process exit must never inherit a prior invocation's successful receipt.
    args.output.unlink(missing_ok=True)
    if args.cpu_reference and args.engine != "kokoro-gpu":
        parser.error("--cpu-reference requires --engine kokoro-gpu")
    model = MODELS[args.engine]
    base = {"schema": "mediapipe-speech-checkpoint-smoke-v1", "engine": args.engine, "runtime_version": RUNTIME_VERSION, "runtime_wheel_sha256": RUNTIME_WHEEL_SHA256, "model": model, "fixture_sha256": hashlib.sha256(FIXTURE.encode("utf-8")).hexdigest(), "created_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "scope": "linux_host_runtime_checkpoint_smoke", "requested_delegate": "GPU" if args.engine == "kokoro-gpu" else "CPU", "speaking_rate": 1.0}
    try:
        require(30 <= args.timeout <= 900, "Native timeout must be between 30 and 900 seconds")
        verify_catalog(model)
        if not args.download_only:
            host = prerequisites()
            base["host"] = host
        directory = (args.model_dir or args.output.parent / "models").resolve()
        model_file = provision_model(model, directory)
        if args.download_only:
            write_json(args.output, base | {"status": "model_verified", "inference_executed": False})
            return 0
        if args.worker:
            return worker(args, model, model_file, base)
        return run_worker(args, model_file, base)
    except Exception as failure:
        write_json(args.output, base | {"status": "failed" if args.worker else "blocked", "error_type": type(failure).__name__, "error": str(failure)[:4096]})
        print(f"MediaPipe speech smoke could not complete: {failure}", file=sys.stderr)
        return 1 if args.worker else 2


if __name__ == "__main__":
    raise SystemExit(main())
