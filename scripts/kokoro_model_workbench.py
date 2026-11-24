#!/usr/bin/env python3
"""Prepare and diagnose fingerprinted Kokoro models without granting playback admission.

Install scripts/requirements-kokoro-model-workbench.txt in a dedicated environment.
All model writes use a new directory; the original model and CPU baseline are never
overwritten. A report is development evidence, not phone or listening qualification.
"""

import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import re
import tempfile
import zipfile


MAX_MODEL_BYTES = 2 * 1024**3
MAX_CAPTURE_BYTES = 128 * 1024**2
MAX_CASES = 256
MAX_TRACE_TENSORS = 64
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
QUANTIZED_OPS = {"QuantizeLinear", "DequantizeLinear", "DynamicQuantizeLinear",
                 "MatMulInteger", "ConvInteger", "QLinearMatMul", "QLinearConv"}


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked_file(path, expected, limit=MAX_MODEL_BYTES):
    path = Path(path).resolve()
    if not SHA256.fullmatch(expected) or not path.is_file():
        raise ValueError("A regular file and its explicit SHA-256 are required")
    if not 0 < path.stat().st_size <= limit or digest(path) != expected:
        raise ValueError("File size or SHA-256 does not match the supplied identity")
    return path


def local_file(parent, name):
    relative = Path(name)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Manifest paths must remain relative to their directory")
    parent = Path(parent).resolve()
    result = (parent / relative).resolve()
    if result == parent or parent not in result.parents:
        raise ValueError("Manifest path escapes its directory")
    return result


def read_json(path):
    path = Path(path)
    if path.stat().st_size > 2 * 1024**2:
        raise ValueError("JSON manifest is too large")
    value = json.loads(path.read_text())
    if not isinstance(value, dict) or value.get("schemaVersion") != 1:
        raise ValueError("Unsupported manifest schema")
    return value


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def new_artifact(output, generate):
    output = Path(output).resolve()
    if output.exists():
        raise ValueError("Output already exists; choose a new artifact directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".kokoro-", dir=output.parent) as temporary:
        temporary = Path(temporary)
        manifest = generate(temporary)
        manifest.update(schemaVersion=1, physicalDeviceQualified=False,
                        listeningQualified=False, playbackAdmission="NOT_GRANTED")
        write_json(temporary / "manifest.json", manifest)
        # The directory is published only after every output and manifest is complete.
        temporary.rename(output)
    return output / "manifest.json"


def load_model(path, expected):
    import onnx
    model = onnx.load(str(checked_file(path, expected)), load_external_data=False)
    # An external initializer is another input artifact; this workflow deliberately
    # accepts self-contained models so an untracked sidecar cannot change a result.
    if any(t.data_location == onnx.TensorProto.EXTERNAL for t in all_tensors(model.graph)):
        raise ValueError("External initializers must first be bundled into a fingerprinted model")
    onnx.checker.check_model(model)
    return model


def all_nodes(graph):
    import onnx
    for node in graph.node:
        yield node
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.GRAPH:
                yield from all_nodes(attribute.g)
            elif attribute.type == onnx.AttributeProto.GRAPHS:
                for child in attribute.graphs:
                    yield from all_nodes(child)


def all_tensors(graph):
    import onnx
    yield from graph.initializer
    for sparse in graph.sparse_initializer:
        yield sparse.values
        yield sparse.indices
    for node in graph.node:
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.TENSOR:
                yield attribute.t
            elif attribute.type == onnx.AttributeProto.TENSORS:
                yield from attribute.tensors
            elif attribute.type == onnx.AttributeProto.GRAPH:
                yield from all_tensors(attribute.g)
            elif attribute.type == onnx.AttributeProto.GRAPHS:
                for child in attribute.graphs:
                    yield from all_tensors(child)


def tensor_description(value):
    tensor = value.type.tensor_type
    return {"name": value.name, "elementType": tensor.elem_type,
            "shape": [d.dim_value if d.HasField("dim_value") else d.dim_param or None
                      for d in tensor.shape.dim]}


def inspect_model(model, source_hash):
    import onnx
    return {"schemaVersion": 1, "modelSha256": source_hash,
            "onnxVersion": onnx.__version__,
            "inputs": [tensor_description(v) for v in model.graph.input],
            "outputs": [tensor_description(v) for v in model.graph.output],
            "operators": dict(sorted(collections.Counter(n.op_type for n in all_nodes(model.graph)).items())),
            "initializerTypes": dict(sorted(collections.Counter(str(t.data_type) for t in model.graph.initializer).items())),
            "metadata": {v.key: v.value for v in model.metadata_props},
            "backendCoverage": "REQUIRES_COMPILATION_AND_EXECUTION",
            "physicalDeviceQualified": False}


def assert_original_float32(model):
    import onnx
    if any(n.op_type in QUANTIZED_OPS for n in all_nodes(model.graph)):
        raise ValueError("Use original FP32 weights; dequantizing an INT8 model cannot recover them")
    floats = [t for t in all_tensors(model.graph) if t.data_type in
              {onnx.TensorProto.FLOAT, onnx.TensorProto.FLOAT16, onnx.TensorProto.DOUBLE,
               onnx.TensorProto.BFLOAT16}]
    if not floats or any(t.data_type != onnx.TensorProto.FLOAT for t in floats):
        raise ValueError("Original floating-point initializers must all be FP32")
    if any(t.data_type in {onnx.TensorProto.INT8, onnx.TensorProto.UINT8}
           for t in all_tensors(model.graph)):
        raise ValueError("Quantized weight initializers are not original FP32 weights")


def specialize_tokens(model, count):
    if not 3 <= count <= 511:
        raise ValueError("Kokoro token count must be between 3 and 511")
    tokens = next((v for v in model.graph.input if v.name == "tokens"), None)
    if tokens is None or len(tokens.type.tensor_type.shape.dim) != 2:
        raise ValueError("Expected Kokoro tokens input with rank two")
    tokens.type.tensor_type.shape.dim[0].dim_value = 1
    tokens.type.tensor_type.shape.dim[1].dim_value = count
    # Specialization changes an input shape. It does not pad, mask, truncate,
    # bucket or claim that data-dependent output shapes have become static.


def read_npz(path):
    import numpy as np
    with zipfile.ZipFile(path) as archive:
        if sum(v.file_size for v in archive.infolist()) > MAX_CAPTURE_BYTES:
            raise ValueError("Uncompressed tensor capture exceeds the memory limit")
    with np.load(path, allow_pickle=False) as archive:
        result = {name: archive[name] for name in archive.files}
    if sum(value.nbytes for value in result.values()) > MAX_CAPTURE_BYTES:
        raise ValueError("Tensor capture exceeds the memory limit")
    if any(value.dtype.hasobject for value in result.values()):
        raise ValueError("Object tensors are not permitted")
    return result


def calibration_cases(path, token_count):
    import numpy as np
    path = Path(path)
    manifest = read_json(path)
    cases = manifest.get("cases", [])
    if manifest.get("purpose") != "calibration" or not 1 <= len(cases) <= MAX_CASES:
        raise ValueError("Provide a bounded, explicit calibration corpus")
    result = []
    ids = set()
    for case in cases:
        identifier = case.get("id", "")
        if not IDENTIFIER.fullmatch(identifier) or identifier in ids:
            raise ValueError("Calibration case identifiers must be unique")
        ids.add(identifier)
        source = checked_file(local_file(path.parent, case["file"]), case["sha256"], MAX_CAPTURE_BYTES)
        feed = read_npz(source)
        if set(feed) != {"tokens", "style", "speed"}:
            raise ValueError("Calibration cases require tokens, style and speed")
        if feed["tokens"].dtype != np.int64 or feed["tokens"].shape != (1, token_count):
            raise ValueError("Calibration tokens must match the exact prepared shape")
        if feed["style"].dtype != np.float32 or feed["style"].shape != (1, 256):
            raise ValueError("Calibration style must be float32 [1,256]")
        if feed["speed"].dtype != np.float32 or feed["speed"].shape != (1,) or not .5 <= float(feed["speed"][0]) <= 2:
            raise ValueError("Calibration speed must be one supported float32 value")
        if any(not np.isfinite(value).all() for value in feed.values()):
            raise ValueError("Calibration tensors must be finite")
        result.append(feed)
    return result


def prepare(args):
    import onnx
    model = load_model(args.model, args.model_sha256)
    assert_original_float32(model)
    specialize_tokens(model, args.token_count)
    if args.backend == "gpu" and args.precision != "fp32":
        raise ValueError("GPU preparation currently provides the original FP32 model only")
    if args.backend == "htp" and args.precision not in {"float", "a16w8", "a8w8"}:
        raise ValueError("HTP supports the float/FP16-math or calibrated QDQ variants")

    def generate(directory):
        output = directory / "model.onnx"
        onnx.save(model, output)
        calibration = None
        if args.precision in {"a16w8", "a8w8"}:
            import onnxruntime as ort
            from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
            from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model
            if not args.calibration:
                raise ValueError("Quantization requires a real, fingerprinted calibration corpus")
            feeds = calibration_cases(args.calibration, args.token_count)

            class Reader(CalibrationDataReader):
                def __init__(self):
                    self.rewind()

                def get_next(self):
                    return next(self.iterator, None)

                def rewind(self):
                    self.iterator = iter(feeds)

            preprocessed = directory / "preprocessed.onnx"
            # ORT 1.28's QNN preprocessing helper tries dict.update() on protobuf
            # metadata entries. Keep metadata in the immutable source object and
            # restore it after quantization instead of letting that helper drop it.
            quantization_source = onnx.ModelProto()
            quantization_source.CopyFrom(model)
            del quantization_source.metadata_props[:]
            onnx.save(quantization_source, output)
            changed = qnn_preprocess_model(str(output), str(preprocessed))
            quant_input = preprocessed if changed else output
            overrides = json.loads(Path(args.overrides).read_text()) if args.overrides else None
            config = get_qnn_qdq_config(str(quant_input), Reader(),
                                       activation_type=QuantType.QUInt16 if args.precision == "a16w8" else QuantType.QUInt8,
                                       weight_type=QuantType.QUInt8,
                                       init_overrides=overrides)
            quantized = directory / "quantized.onnx"
            quantize(str(quant_input), str(quantized), config)
            quantized.replace(output)
            if preprocessed.exists():
                preprocessed.unlink()
            calibration = {"manifestSha256": digest(args.calibration), "cases": len(feeds),
                           "onnxruntimeVersion": ort.__version__,
                           "overridesSha256": digest(args.overrides) if args.overrides else None}
        generated = onnx.load(str(output))
        del generated.metadata_props[:]
        generated.metadata_props.extend(model.metadata_props)
        onnx.checker.check_model(generated)
        onnx.save(generated, output)
        return {"kind": "kokoro-model-variant", "sourceModelSha256": args.model_sha256,
                "modelFile": output.name, "modelSha256": digest(output),
                "backend": args.backend, "precision": args.precision,
                "htpFloatMath": "fp16-on-supported-soc" if args.backend == "htp" and args.precision == "float" else None,
                "gpuPrecisionControl": "model-tensor-types; no unsupported EP option",
                "tokenCount": args.token_count, "paddingApplied": False,
                "calibration": calibration, "inspection": inspect_model(generated, digest(output))}
    return new_artifact(args.output_dir, generate)


def trace(args):
    import onnx
    model = load_model(args.model, args.model_sha256)
    wanted = list(dict.fromkeys(args.tensor))
    if not 1 <= len(wanted) <= MAX_TRACE_TENSORS:
        raise ValueError("Select between one and 64 named intermediate tensors")
    # Seed each stochastic node independently in this diagnostic copy. This is
    # explicitly recorded because instrumentation can also change partitioning.
    for index, node in enumerate(all_nodes(model.graph)):
        if node.op_type in {"RandomNormal", "RandomUniform", "RandomNormalLike", "RandomUniformLike", "Multinomial"}:
            keep = [v for v in node.attribute if v.name != "seed"]
            del node.attribute[:]
            node.attribute.extend(keep)
            node.attribute.append(onnx.helper.make_attribute("seed", float(args.seed + index)))
    model = onnx.shape_inference.infer_shapes(model, strict_mode=False, data_prop=True)
    types = {v.name: v for v in [*model.graph.input, *model.graph.value_info, *model.graph.output]}
    order = [name for node in model.graph.node for name in node.output if name in wanted]
    if set(order) != set(wanted) or any(name not in types or not types[name].type.HasField("tensor_type") for name in wanted):
        raise ValueError("Every selected intermediate requires an inferred tensor type in the top-level graph")
    existing = {v.name for v in model.graph.output}
    model.graph.output.extend(types[name] for name in order if name not in existing)

    def generate(directory):
        output = directory / "model.onnx"
        onnx.checker.check_model(model)
        onnx.save(model, output)
        return {"kind": "kokoro-diagnostic-model", "sourceModelSha256": args.model_sha256,
                "modelFile": output.name, "modelSha256": digest(output),
                "traceOrder": order, "randomSeedBase": args.seed,
                "instrumentationMayChangePartitions": True}
    return new_artifact(args.output_dir, generate)


def capture(args):
    import numpy as np
    import onnxruntime as ort
    manifest_path = Path(args.model_manifest)
    manifest = read_json(manifest_path)
    model = checked_file(local_file(manifest_path.parent, manifest["modelFile"]), manifest["modelSha256"])
    case = checked_file(args.case, args.case_sha256, MAX_CAPTURE_BYTES)
    feeds = read_npz(case)

    def generate(directory):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.enable_profiling = True
        options.profile_file_prefix = str(directory / "profile")
        library = None
        registered = False
        session = None
        try:
            if args.backend != "cpu":
                if not args.qnn_plugin or not args.qnn_backend:
                    raise ValueError("QNN execution requires explicit plugin and backend library paths")
                library = Path(args.qnn_plugin).resolve()
                ort.register_execution_provider_library("QNNExecutionProvider", str(library))
                registered = True
                kind = {"qnn-gpu": ort.OrtHardwareDeviceType.GPU,
                        "qnn-htp": ort.OrtHardwareDeviceType.NPU,
                        "qnn-emulator": ort.OrtHardwareDeviceType.CPU}[args.backend]
                devices = [d for d in ort.get_ep_devices() if d.ep_name == "QNNExecutionProvider" and d.device.type == kind]
                if len(devices) != 1:
                    raise ValueError("Requested QNN device is not uniquely available")
                configuration = {"backend_path": str(Path(args.qnn_backend).resolve())}
                if args.backend == "qnn-emulator":
                    configuration.update(htp_arch="79", soc_model="69")
                options.add_provider_for_devices(devices, configuration)
            session = ort.InferenceSession(str(model), sess_options=options,
                                           providers=["CPUExecutionProvider"] if args.backend == "cpu" else None)
            session.disable_fallback()
            if set(feeds) != {v.name for v in session.get_inputs()}:
                raise ValueError("Captured inputs do not match the model")
            names = [v.name for v in session.get_outputs()]
            values = session.run(names, feeds)
            if sum(value.nbytes for value in values) > MAX_CAPTURE_BYTES:
                raise ValueError("Captured outputs exceed the memory limit; select fewer tensors")
            archive = directory / "outputs.npz"
            np.savez(archive, **dict(zip(names, values)))
            profile_path = Path(session.end_profiling())
            if profile_path.stat().st_size > MAX_CAPTURE_BYTES:
                raise ValueError("Profile exceeds the memory limit")
            events = json.loads(profile_path.read_text())
            providers = collections.Counter(e.get("args", {}).get("provider") for e in events
                                             if e.get("cat") == "Node" and e.get("name", "").endswith("_kernel_time")
                                             and e.get("dur", 0) > 0)
            if args.backend != "cpu" and providers["QNNExecutionProvider"] == 0:
                raise ValueError("QNN registration without observed QNN execution is not a successful capture")
            checked_file(model, manifest["modelSha256"])
            checked_file(case, args.case_sha256, MAX_CAPTURE_BYTES)
            return {"kind": "kokoro-tensor-capture", "modelSha256": manifest["modelSha256"],
                    "sourceModelSha256": manifest["sourceModelSha256"],
                    "inputSha256": args.case_sha256, "backend": args.backend,
                    "onnxruntimeVersion": ort.__version__, "outputsFile": archive.name,
                    "outputsSha256": digest(archive), "traceOrder": manifest.get("traceOrder", names),
                    "kernelEvents": dict(providers), "profileFile": profile_path.name,
                    "qnnPluginSha256": digest(library) if library else None,
                    "qnnBackendSha256": digest(args.qnn_backend) if library else None,
                    "scope": "host-emulator" if args.backend == "qnn-emulator" else "runtime-tensor-diagnostic"}
        finally:
            if session is not None:
                del session
            if registered:
                ort.unregister_execution_provider_library("QNNExecutionProvider")
    return new_artifact(args.output_dir, generate)


def compare_arrays(reference, candidate, order, atol, rtol):
    import numpy as np
    if not math.isfinite(atol) or not math.isfinite(rtol) or atol < 0 or rtol < 0:
        raise ValueError("Comparison tolerances must be finite and nonnegative")
    if not order or len(set(order)) != len(order):
        raise ValueError("Comparison requires a nonempty, unique tensor order")
    rows = []
    for name in order:
        if name not in reference or name not in candidate:
            raise ValueError("A selected tensor is missing from a capture")
        left, right = reference[name], candidate[name]
        row = {"tensor": name, "referenceShape": list(left.shape), "candidateShape": list(right.shape)}
        if left.shape != right.shape:
            row.update(matches=False, reason="SHAPE_MISMATCH")
        elif left.dtype != right.dtype:
            row.update(matches=False, reason="TYPE_MISMATCH")
        elif left.dtype.kind not in "biuf":
            row.update(matches=False, reason="UNSUPPORTED_TENSOR_TYPE")
        elif not np.isfinite(left).all() or not np.isfinite(right).all():
            row.update(matches=False, reason="NONFINITE")
        elif left.size == 0:
            row.update(matches=True, maximumAbsoluteError=0.0)
        elif np.issubdtype(left.dtype, np.integer) or left.dtype == np.bool_:
            row.update(matches=bool(np.array_equal(left, right)), reason="INTEGER_EXACT_COMPARISON")
        else:
            difference = np.abs(left.astype(np.float64) - right.astype(np.float64))
            row.update(matches=bool(np.all(difference <= atol + rtol * np.abs(left))),
                       maximumAbsoluteError=float(difference.max()),
                       rootMeanSquaredError=float(np.sqrt(np.mean(difference**2))))
        rows.append(row)
    return {"schemaVersion": 1, "status": "CONSISTENT" if all(v["matches"] for v in rows) else "MISMATCH",
            "firstObservedDivergence": next((v["tensor"] for v in rows if not v["matches"]), None),
            "absoluteTolerance": atol, "relativeTolerance": rtol, "tensors": rows,
            "interpretation": "Topological observation, not proof of the failing operator; instrumentation may change partitions.",
            "physicalDeviceQualified": False, "listeningQualified": False}


def compare(args):
    left_path, right_path = Path(args.reference), Path(args.candidate)
    left, right = read_json(left_path), read_json(right_path)
    for key in ("modelSha256", "sourceModelSha256", "inputSha256", "traceOrder"):
        if not left.get(key) or left[key] != right.get(key):
            raise ValueError("Capture source, inputs and trace order must match")
    if left.get("backend") != "cpu":
        raise ValueError("The reference capture must execute the CPU provider")
    arrays = []
    for path, manifest in ((left_path, left), (right_path, right)):
        archive = checked_file(local_file(path.parent, manifest["outputsFile"]), manifest["outputsSha256"], MAX_CAPTURE_BYTES)
        arrays.append(read_npz(archive))
    report = compare_arrays(*arrays, left["traceOrder"], args.atol, args.rtol)
    report.update(referenceManifestSha256=digest(left_path), candidateManifestSha256=digest(right_path))
    write_json(args.output, report)
    return args.output


def stages(args):
    import onnx
    model = load_model(args.model, args.model_sha256)
    specification = read_json(args.specification)
    stage_list = specification.get("stages", [])
    if specification.get("sourceModelSha256") != args.model_sha256 or not 1 <= len(stage_list) <= 8:
        raise ValueError("Stage specification must bind the original model and contain one to eight stages")
    ids = [v.get("id", "") for v in stage_list]
    if len(set(ids)) != len(ids) or any(not IDENTIFIER.fullmatch(v) for v in ids):
        raise ValueError("Stage identifiers must be safe and unique")

    def generate(directory):
        records = []
        for stage in stage_list:
            inputs, outputs = stage.get("inputs", []), stage.get("outputs", [])
            if not inputs or not outputs or not all(isinstance(v, str) for v in inputs + outputs):
                raise ValueError("Explicit stage tensor boundaries are required")
            output = directory / (stage["id"] + ".onnx")
            onnx.utils.extract_model(str(args.model), str(output), inputs, outputs, check_model=True)
            extracted = onnx.load(str(output))
            del extracted.metadata_props[:]
            extracted.metadata_props.extend(model.metadata_props)
            onnx.save(extracted, output)
            records.append({"id": stage["id"], "modelFile": output.name, "modelSha256": digest(output),
                            "inputs": inputs, "outputs": outputs})
        return {"kind": "kokoro-explicit-stages", "sourceModelSha256": args.model_sha256,
                "specificationSha256": digest(args.specification), "stages": records,
                "recompositionValidated": False, "runtimeLoader": "NOT_IMPLICITLY_CHANGED"}
    return new_artifact(args.output_dir, generate)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("inspect", "prepare", "trace", "stages"):
        sub = commands.add_parser(command)
        sub.add_argument("--model", required=True, type=Path)
        sub.add_argument("--model-sha256", required=True)
        if command == "inspect":
            sub.add_argument("--output", required=True, type=Path)
        else:
            sub.add_argument("--output-dir", required=True, type=Path)
        if command == "prepare":
            sub.add_argument("--backend", required=True, choices=("gpu", "htp"))
            sub.add_argument("--precision", required=True, choices=("fp32", "float", "a16w8", "a8w8"))
            sub.add_argument("--token-count", required=True, type=int)
            sub.add_argument("--calibration", type=Path)
            sub.add_argument("--overrides", type=Path)
        elif command == "trace":
            sub.add_argument("--tensor", action="append", required=True)
            sub.add_argument("--seed", type=int, default=7)
        elif command == "stages":
            sub.add_argument("--specification", required=True, type=Path)
    sub = commands.add_parser("capture")
    sub.add_argument("--model-manifest", required=True, type=Path)
    sub.add_argument("--case", required=True, type=Path)
    sub.add_argument("--case-sha256", required=True)
    sub.add_argument("--backend", required=True, choices=("cpu", "qnn-gpu", "qnn-htp", "qnn-emulator"))
    sub.add_argument("--qnn-plugin", type=Path)
    sub.add_argument("--qnn-backend", type=Path)
    sub.add_argument("--output-dir", required=True, type=Path)
    sub = commands.add_parser("compare")
    sub.add_argument("--reference", required=True, type=Path)
    sub.add_argument("--candidate", required=True, type=Path)
    sub.add_argument("--output", required=True, type=Path)
    sub.add_argument("--atol", type=float, default=1e-5)
    sub.add_argument("--rtol", type=float, default=1e-3)
    args = parser.parse_args(argv)
    if args.command == "inspect":
        write_json(args.output, inspect_model(load_model(args.model, args.model_sha256), args.model_sha256))
        result = args.output
    else:
        result = {"prepare": prepare, "trace": trace, "capture": capture, "compare": compare, "stages": stages}[args.command](args)
    print(result)


if __name__ == "__main__":
    main()
