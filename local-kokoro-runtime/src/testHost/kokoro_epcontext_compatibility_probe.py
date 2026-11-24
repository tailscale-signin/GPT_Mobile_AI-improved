#!/usr/bin/env python3
"""Execute a tiny hybrid graph and reload embedded QNN context on a host emulator.

No phone, real Kokoro model, GPU arithmetic or perceptual qualification is made.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import tempfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
import onnxruntime as ort


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(args):
    libraries = args.libraries.resolve()
    publisher = json.loads((libraries / "publisher-manifest.json").read_text())
    for name in publisher["files"]:
        if Path(name).name != name:
            raise ValueError("Publisher manifest library names must be local basenames")
        if digest(libraries / name) != publisher["files"][name]["sha256"]:
            raise ValueError("Host library differs from its verified publisher manifest")
    if ort.__version__ != args.ort_version:
        raise ValueError("Runtime version differs from the requested compatibility baseline")
    plugin = libraries / "libonnxruntime_providers_qnn.so"
    ort.register_execution_provider_library("QNNExecutionProvider", str(plugin))
    session = None
    try:
        devices = [d for d in ort.get_ep_devices() if d.ep_name == "QNNExecutionProvider" and
                   d.device.type == ort.OrtHardwareDeviceType.CPU]
        if len(devices) != 1:
            raise ValueError("Exactly one QNN host emulation device is required")
        with tempfile.TemporaryDirectory(prefix="qnn-context-probe-") as temporary:
            root = Path(temporary)
            model_path, context_path = root / "original.onnx", root / "context.onnx"
            metadata = {"sample_rate": "24000", "style_dim": "510,1,256", "n_speakers": "54", "voice": "en-us"}
            graph = helper.make_graph([
                helper.make_node("Relu", ["x"], ["audio"], name="accelerated_relu"),
                helper.make_node("NonZero", ["cpu_marker"], ["indices"], name="retained_cpu_node")
            ], "hybrid-context-compatibility", [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 4])],
                [helper.make_tensor_value_info("audio", TensorProto.FLOAT, [1, 4]),
                 helper.make_tensor_value_info("indices", TensorProto.INT64, [1, "nonzero"])],
                [numpy_helper.from_array(np.array([0, 3, 0, 5], dtype=np.int64), "cpu_marker")])
            model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 18)], ir_version=9)
            helper.set_model_props(model, metadata)
            onnx.checker.check_model(model)
            onnx.save(model, model_path)
            feed = {"x": np.array([[-1, 2, -3, 4]], dtype=np.float32)}
            cpu = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
            reference = cpu.run(None, feed)
            del cpu

            def options(export):
                result = ort.SessionOptions()
                result.intra_op_num_threads = 1
                result.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
                result.enable_profiling = True
                result.profile_file_prefix = str(root / ("prepare-profile" if export else "reload-profile"))
                result.add_provider_for_devices(devices, {"backend_path": str(libraries / "libQnnHtp.so"),
                    "htp_performance_mode": "balanced", "htp_arch": "79", "soc_model": "69"})
                if export:
                    result.add_session_config_entry("ep.context_enable", "1")
                    result.add_session_config_entry("ep.context_file_path", str(context_path))
                    result.add_session_config_entry("ep.context_embed_mode", "1")
                return result

            events = []
            errors = []
            prepared_values = None
            for path, export in ((model_path, True), (context_path, False)):
                session = ort.InferenceSession(str(path), sess_options=options(export))
                session.disable_fallback()
                values = session.run(None, feed)
                for expected, actual in zip(reference, values, strict=True):
                    equal = np.allclose(expected, actual, atol=1e-6, rtol=1e-6) if expected.dtype.kind == "f" else np.array_equal(expected, actual)
                    if expected.dtype != actual.dtype or expected.shape != actual.shape or not equal:
                        raise ValueError(f"Compiled-context output differs from CPU: export={export}, "
                                         f"names={[v.name for v in session.get_outputs()]}, "
                                         f"expected={expected.tolist()}, actual={actual.tolist()}")
                errors.append([float(np.max(np.abs(expected.astype(np.float64) - actual)))
                               for expected, actual in zip(reference, values, strict=True)])
                if export:
                    prepared_values = [value.copy() for value in values]
                elif any(not np.array_equal(expected, actual) for expected, actual in zip(prepared_values, values, strict=True)):
                    raise ValueError("Reloaded context output differs from its prepared execution")
                if [v.name for v in session.get_inputs()] != ["x"] or [v.name for v in session.get_outputs()] != ["audio", "indices"]:
                    raise ValueError("Model input/output contract changed")
                profile = json.loads(Path(session.end_profiling()).read_text())
                observed = collections.Counter(e.get("args", {}).get("provider") for e in profile
                    if e.get("cat") == "Node" and e.get("name", "").endswith("_kernel_time"))
                if not observed["QNNExecutionProvider"] or not observed["CPUExecutionProvider"]:
                    raise ValueError("Both QNN and CPU execution must be observed")
                events.append(dict(observed))
                del session
                session = None
            saved = onnx.load(context_path)
            saved_metadata = {v.key: v.value for v in saved.metadata_props}
            if any(saved_metadata.get(k) != value for k, value in metadata.items()):
                raise ValueError("Original Kokoro-style metadata was not preserved")
            operations = [node.op_type for node in saved.graph.node]
            if "EPContext" not in operations or "NonZero" not in operations:
                raise ValueError("Saved graph must retain the compiled partition and CPU node")
            if "cpu_marker" not in {v.name for v in saved.graph.initializer}:
                raise ValueError("CPU initializer was lost during context export")
            result = {"schemaVersion": 1, "status": "PASSED_HOST_EMULATOR", "onnxruntimeVersion": ort.__version__,
                      "publisherManifestSha256": digest(libraries / "publisher-manifest.json"),
                      "sourceModelSha256": digest(model_path), "contextModelSha256": digest(context_path),
                      "contextBytes": context_path.stat().st_size, "metadataPreserved": True,
                      "cpuNodeAndInitializerPreserved": True, "inputOutputNamesAndOrderPreserved": True,
                      "cpuReferenceConsistent": True, "cpuReferenceAbsoluteTolerance": 1e-6,
                      "cpuReferenceRelativeTolerance": 1e-6, "maximumAbsoluteErrors": errors,
                      "preparedAndReloadedOutputsBitwiseEqual": True, "kernelEvents": events, "operations": operations,
                      "hostLibraries": publisher["files"], "physicalDeviceQualified": False,
                      "kokoroQualified": False, "listeningQualified": False}
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result, indent=2))
    finally:
        if session is not None:
            del session
        ort.unregister_execution_provider_library("QNNExecutionProvider")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--libraries", type=Path, required=True)
    parser.add_argument("--ort-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    run(parser.parse_args())
