#!/usr/bin/env python3
"""Exact-model graph regression using QNN's x86 HTP emulator, never device qualification.

Requires onnxruntime 1.28.0, onnxruntime-qnn 2.7.0 and numpy on the host. Build the
adapter against the app's 1.28.2 headers and place it beside the QNN plugin.
The Android/native JNI gate separately exercises the pinned 1.28.2 runtime.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import tempfile

import numpy as np


MODEL_SHA256 = "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e"


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def options(count):
    import onnxruntime as ort
    result = ort.SessionOptions()
    result.log_severity_level = 3
    result.intra_op_num_threads = 2
    result.add_free_dimension_override_by_name("sequence_length", count)
    return result


def validate(outputs, count):
    audio, durations = outputs
    assert audio.dtype == np.float32 and 0 < audio.size <= 30 * 24000
    assert np.isfinite(audio).all() and np.any(audio != 0)
    assert durations.dtype == np.int64 and durations.shape == (count,)
    assert (durations > 0).all() and int(durations.sum()) * 600 == audio.size


def compare_audio(reference, candidate):
    """Numerical CPU agreement on fixed inputs; never a perceptual-quality claim."""
    first, first_durations = reference
    second, second_durations = candidate
    if first_durations.shape != second_durations.shape:
        return {"status": "MISMATCH", "reason": "TOKEN_COUNT_MISMATCH"}
    duration_ratio = second.size / first.size
    timing_distance = float(np.abs(second_durations.astype(np.float64) - first_durations).sum() / first_durations.sum())
    first_rms = float(np.sqrt(np.mean(first.astype(np.float64) ** 2)))
    second_rms = float(np.sqrt(np.mean(second.astype(np.float64) ** 2)))
    first_peak = float(np.abs(first).max())
    second_peak = float(np.abs(second).max())
    rms_ratio = second_rms / first_rms
    first_clipping = float(np.mean(np.abs(first) >= 1.0))
    second_clipping = float(np.mean(np.abs(second) >= 1.0))
    activity_difference = abs(float(np.mean(np.abs(first) >= 1e-5)) - float(np.mean(np.abs(second) >= 1e-5)))
    reason = None
    if not .9 <= duration_ratio <= 1.1:
        reason = "DURATION_MISMATCH"
    elif timing_distance > .15:
        reason = "TOKEN_TIMING_MISMATCH"
    elif not .5 <= rms_ratio <= 2.0 or second_peak > max(first_peak * 2.0, first_peak + .1):
        reason = "SIGNAL_LEVEL_MISMATCH"
    elif second_clipping > first_clipping + .001:
        reason = "CLIPPING_MISMATCH"
    elif activity_difference > .15:
        reason = "SIGNAL_ACTIVITY_MISMATCH"
    return {"status": "CONSISTENT" if reason is None else "MISMATCH", "reason": reason,
            "duration_ratio": duration_ratio, "token_timing_distance": timing_distance,
            "rms_ratio": rms_ratio, "peak_ratio": second_peak / first_peak,
            "clipped_fraction_increase": second_clipping - first_clipping,
            "activity_fraction_difference": activity_difference}


def main():
    import onnxruntime as ort
    import onnxruntime_qnn as qnn
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--original", action="store_true", help="Reproduce the unmodified provider; expected to fail qualification")
    parser.add_argument("--token-counts", type=int, nargs="+", default=[17, 31, 83, 25, 43, 71, 84, 27, 93])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    assert ort.__version__ == "1.28.0" and qnn.__version__ == "2.7.0", "Use the recorded host versions"
    model = args.pack / "model.int8.onnx"
    assert digest(model) == MODEL_SHA256, "Exact app model required"
    library = Path(qnn.get_library_path()) if args.original else args.adapter.resolve()
    ort.register_execution_provider_library(qnn.get_ep_name(), str(library))
    # CPU here is the official host emulator. Never treat it as a phone NPU.
    devices = [d for d in ort.get_ep_devices() if d.ep_name == qnn.get_ep_name() and d.device.type == ort.OrtHardwareDeviceType.CPU]
    assert len(devices) == 1, "This regression is for the x86 QNN host emulator"
    voices = np.fromfile(args.pack / "voices.bin", dtype=np.float32).reshape(-1, 510, 256)
    report = {"scope": "x86_htp_emulator_graph_regression", "physical_device_qualified": False,
              "onnxruntime": ort.__version__, "qnn_plugin": qnn.__version__, "qairt": "2.51.0",
              "model_sha256": MODEL_SHA256, "provider_library_sha256": digest(library), "cases": []}
    try:
        for count in args.token_counts:
            assert 3 <= count <= 511
            # Deterministic valid phoneme-token IDs; this is a tensor/graph
            # regression, while KokoroNativeIntegration covers the real frontend.
            middle = np.resize(np.array([47, 43, 54, 54, 57], dtype=np.int64), count - 2)
            feeds = {"tokens": np.concatenate(([0], middle, [0])).reshape(1, count).astype(np.int64),
                     "style": voices[0, count - 2].reshape(1, 256), "speed": np.array([1.0], dtype=np.float32)}
            cpu = ort.InferenceSession(str(model), sess_options=options(count), providers=["CPUExecutionProvider"])
            reference = cpu.run(None, feeds)
            validate(reference, count)
            del cpu
            with tempfile.TemporaryDirectory(prefix="kokoro-qnn-regression-") as profiles:
                opts = options(count)
                opts.enable_profiling = True
                opts.profile_file_prefix = str(Path(profiles) / "execution")
                opts.add_provider_for_devices(devices, {"backend_path": qnn.get_qnn_htp_path(), "htp_arch": "79", "soc_model": "69"})
                session = ort.InferenceSession(str(model), sess_options=opts)
                session.disable_fallback()
                result = session.run(None, feeds)
                validate(result, count)
                events = json.loads(Path(session.end_profiling()).read_text())
                kernels = collections.Counter(e.get("args", {}).get("provider") for e in events
                                              if e.get("cat") == "Node" and e.get("name", "").endswith("_kernel_time") and e.get("dur", 0) > 0)
                case = {"tokens": count, "samples": int(result[0].size), "positive_durations": int(result[1].size),
                        "finite_nonzero_audio": True, "same_inference_timing_valid": True,
                        "cpu_duration_array_equal": bool(np.array_equal(reference[1], result[1])),
                        "cpu_sample_count_equal": reference[0].size == result[0].size,
                        "cpu_samples": int(reference[0].size),
                        "maximum_token_duration_delta": int(np.max(np.abs(reference[1] - result[1]))),
                        "total_duration_delta": int(result[1].sum() - reference[1].sum()),
                        "qnn_kernels": kernels["QNNExecutionProvider"], "cpu_kernels": kernels["CPUExecutionProvider"],
                        "qnn_audio_rms": float(np.sqrt(np.mean(result[0] ** 2))), "cpu_audio_rms": float(np.sqrt(np.mean(reference[0] ** 2))),
                        "cpu_reference": compare_audio(reference, result)}
                report["cases"].append(case)
                print(json.dumps(case), flush=True)
                # Small duration-rounding differences remain within the app's
                # numerical reference limits. Structurally valid but collapsed
                # timing (as observed on GPU) cannot pass this graph gate.
                assert case["qnn_kernels"] > 0, "Provider registration or CPU-only output cannot qualify"
                assert case["cpu_reference"]["status"] == "CONSISTENT", "Fixed-input CPU reference mismatch"
                del session
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
