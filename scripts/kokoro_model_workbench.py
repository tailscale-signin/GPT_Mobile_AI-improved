#!/usr/bin/env python3
"""Prepare and diagnose fingerprinted Kokoro models without granting playback admission.

Install scripts/requirements-kokoro-model-workbench.txt in a dedicated environment.
All model writes use a new directory; the original model and CPU baseline are never
overwritten. A report is development evidence, not phone or listening qualification.
"""

import argparse
import ast
import collections
from contextlib import nullcontext
import hashlib
import heapq
import json
import math
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
import zipfile


MAX_MODEL_BYTES = 2 * 1024**3
MAX_CAPTURE_BYTES = 128 * 1024**2
MAX_JSON_BYTES = 2 * 1024**2
MAX_CASES = 256
MAX_TRACE_TENSORS = 64
MAX_NPY_HEADER_BYTES = 10_000
MAX_NPY_DIMENSIONS = 64
COPY_BYTES = 64 * 1024
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
NUMERIC_DTYPE = re.compile(r"[<>=|]([biufc])([1-9][0-9]?)\Z")
NUMERIC_ITEM_SIZES = {"b": {1}, "i": {1, 2, 4, 8}, "u": {1, 2, 4, 8},
                      "f": {2, 4, 8, 16}, "c": {8, 16, 32}}
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


def read_json_snapshot(path, *, manifest=True):
    # A size check followed by an unbounded read can admit a growing file, and
    # hashing its pathname later can describe different bytes than those parsed.
    with Path(path).open("rb") as stream:
        encoded = stream.read(MAX_JSON_BYTES + 1)
    if len(encoded) > MAX_JSON_BYTES:
        raise ValueError("JSON manifest is too large")
    value = json.loads(encoded.decode("utf-8"))
    if manifest and (not isinstance(value, dict) or value.get("schemaVersion") != 1):
        raise ValueError("Unsupported manifest schema")
    return value, hashlib.sha256(encoded).hexdigest()


def read_json(path):
    return read_json_snapshot(path)[0]


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def report_destination(path, inputs):
    destination = Path(path).resolve()
    for source in inputs:
        source = Path(source).resolve()
        if destination == source or (destination.exists() and destination.samefile(source)):
            raise ValueError("A report destination must not alias an input file")
    return destination


def write_report(path, value, inputs):
    destination = report_destination(path, inputs)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", prefix=".kokoro-report-",
                                         suffix=".json", dir=destination.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json.dumps(value, indent=2, allow_nan=False) + "\n")
        if report_destination(path, inputs) != destination:
            raise ValueError("The report destination changed during generation")
        # Replacing a distinct directory entry never truncates a linked input inode.
        os.replace(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


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


def order_captured_inputs(graph):
    """Stably order nodes, including outer-scope tensors consumed in nested graphs."""
    import onnx
    nodes = list(graph.node)
    producers = {name: i for i, node in enumerate(nodes) for name in node.output if name}
    available = {v.name for v in graph.input} | {v.name for v in graph.initializer}
    available.update(v.values.name for v in graph.sparse_initializer)
    external = {v.name for v in graph.output} - producers.keys() - available
    indegree, consumers = [], [[] for _ in nodes]
    for index, node in enumerate(nodes):
        inputs = {name for name in node.input if name}
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.GRAPH:
                inputs.update(order_captured_inputs(attribute.g))
            elif attribute.type == onnx.AttributeProto.GRAPHS:
                for child in attribute.graphs:
                    inputs.update(order_captured_inputs(child))
        external.update(inputs - producers.keys() - available)
        dependencies = {producers[name] for name in inputs if name in producers}
        indegree.append(len(dependencies))
        for dependency in dependencies:
            consumers[dependency].append(index)
    ready = [i for i, degree in enumerate(indegree) if degree == 0]
    heapq.heapify(ready)
    ordered = []
    while ready:
        current = heapq.heappop(ready)
        ordered.append(current)
        for consumer in consumers[current]:
            indegree[consumer] -= 1
            if indegree[consumer] == 0:
                heapq.heappush(ready, consumer)
    if len(ordered) != len(nodes):
        raise ValueError("Model contains a dependency cycle")
    if ordered != list(range(len(nodes))):
        del graph.node[:]
        graph.node.extend(nodes[i] for i in ordered)
    return external


def load_model(path, expected):
    import onnx
    model = onnx.load(str(checked_file(path, expected)), load_external_data=False)
    # An external initializer is another input artifact; this workflow deliberately
    # accepts self-contained models so an untracked sidecar cannot change a result.
    if any(t.data_location == onnx.TensorProto.EXTERNAL for t in all_tensors(model.graph)):
        raise ValueError("External initializers must first be bundled into a fingerprinted model")
    try:
        onnx.checker.check_model(model)
    except onnx.checker.ValidationError as error:
        if "must be topologically sorted" not in str(error):
            raise
        # The compact publisher model's Loop captures a sequence produced later
        # in the serialized node list. Only the diagnostic copy is reordered.
        order_captured_inputs(model.graph)
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


def npy_metadata(stream, member_bytes):
    if stream.read(6) != b"\x93NUMPY":
        raise ValueError("Tensor members must use the NPY format")
    version = stream.read(2)
    if version not in (b"\x01\x00", b"\x02\x00", b"\x03\x00"):
        raise ValueError("Unsupported NPY format version")
    width = 2 if version == b"\x01\x00" else 4
    encoded_length = stream.read(width)
    if len(encoded_length) != width:
        raise ValueError("Truncated NPY header length")
    length = struct.unpack("<H" if width == 2 else "<I", encoded_length)[0]
    if not 0 < length <= MAX_NPY_HEADER_BYTES or 8 + width + length > member_bytes:
        raise ValueError("NPY header exceeds its bounded member")
    encoded = stream.read(length)
    if len(encoded) != length or not encoded.endswith(b"\n"):
        raise ValueError("Truncated or malformed NPY header")
    try:
        expression = ast.parse(encoded.decode("utf-8" if version == b"\x03\x00" else "latin1").strip(), mode="eval")
        node = expression.body
        if not isinstance(node, ast.Dict) or len(node.keys) != 3:
            raise ValueError("NPY headers must contain exactly three fields")
        keys = [ast.literal_eval(key) for key in node.keys]
        if any(type(key) is not str for key in keys) or set(keys) != {"descr", "fortran_order", "shape"}:
            raise ValueError("NPY header fields must be unique and supported")
        header = ast.literal_eval(node)
    except (UnicodeError, SyntaxError, TypeError, ValueError, RecursionError) as failure:
        raise ValueError("Malformed NPY header") from failure
    descriptor, shape, fortran = header["descr"], header["shape"], header["fortran_order"]
    match = NUMERIC_DTYPE.fullmatch(descriptor) if isinstance(descriptor, str) else None
    if match is None or int(match[2]) not in NUMERIC_ITEM_SIZES[match[1]]:
        raise ValueError("Only fixed-width primitive numeric NPY tensors are permitted")
    kind, itemsize = match[1], int(match[2])
    if (type(fortran) is not bool or type(shape) is not tuple or len(shape) > MAX_NPY_DIMENSIONS or
            any(type(dimension) is not int or not 0 <= dimension <= sys.maxsize for dimension in shape)):
        raise ValueError("Invalid bounded NPY shape or storage order")
    count = 0 if 0 in shape else 1
    if count:
        for dimension in shape:
            if dimension > MAX_CAPTURE_BYTES // itemsize // count:
                raise ValueError("Declared NPY tensor exceeds the payload limit")
            count *= dimension
    payload = count * itemsize
    if payload != member_bytes - 8 - width - length:
        raise ValueError("NPY shape and dtype do not match the exact member payload")
    # Read through the member's CRC/EOF before any NumPy allocation, using bounded chunks.
    remaining = payload
    while remaining:
        chunk = stream.read(min(remaining, COPY_BYTES))
        if not chunk:
            raise ValueError("Truncated NPY payload")
        remaining -= len(chunk)
    if stream.read(1):
        raise ValueError("Unexpected trailing NPY payload")
    return {"shape": shape, "bytes": payload, "fortran_order": fortran,
            "kind": kind, "itemsize": itemsize}


def preflight_npz_directory(stream):
    """Bound actual ZIP directory records before ZipFile allocates their metadata."""
    stream.seek(0, os.SEEK_END)
    size = stream.tell()
    if not 22 <= size <= MAX_CAPTURE_BYTES:
        raise ValueError("Encoded tensor archive exceeds the payload limit or is truncated")
    tail_size = min(size, 22 + 65535)
    stream.seek(size - tail_size)
    tail = stream.read(tail_size)
    # Match ZipFile's no-comment fast path and otherwise its last-signature
    # selection. A signature embedded in a comment must not select a different
    # directory after this guard; truncated comments and trailing bytes fail.
    if tail[-22:-18] == b"PK\x05\x06" and tail[-2:] == b"\0\0":
        end = len(tail) - 22
    else:
        end = tail.rfind(b"PK\x05\x06")
    if end < 0 or len(tail) - end < 22:
        raise ValueError("Invalid tensor archive end record")
    _, disk, directory_disk, disk_count, count, directory_size, directory_offset, comment_size = \
        struct.unpack_from("<4s4H2IH", tail, end)
    end_offset = size - tail_size + end
    if end + 22 + comment_size != len(tail):
        raise ValueError("Invalid tensor archive comment or trailing bytes")
    # Python consults a ZIP64 locator even without sentinel values in the normal
    # end record. These bounded archives need no archive-level ZIP64 directory;
    # reject that override, while allowing NumPy's local ZIP64 sizes and extras.
    if end_offset >= 20:
        stream.seek(end_offset - 20)
        if stream.read(4) == b"PK\x06\x07":
            raise ValueError("Archive-level ZIP64 directories are unsupported")
    if disk or directory_disk or disk_count != count or not 1 <= count <= MAX_TRACE_TENSORS:
        raise ValueError("NPZ directory must describe a bounded, consistent tensor inventory")
    if (not 46 <= directory_size <= MAX_TRACE_TENSORS * (46 + 3 * 65535) or
            directory_offset + directory_size != end_offset):
        raise ValueError("Invalid tensor archive directory bounds")
    cursor, observed = directory_offset, 0
    while cursor < end_offset:
        if observed >= MAX_TRACE_TENSORS:
            raise ValueError("NPZ directory exceeds the bounded tensor inventory")
        if end_offset - cursor < 46:
            raise ValueError("Truncated tensor archive directory header")
        stream.seek(cursor)
        encoded = stream.read(46)
        if len(encoded) != 46 or encoded[:4] != b"PK\x01\x02":
            raise ValueError("Invalid tensor archive directory header")
        header = struct.unpack("<4s6H3I5H2I", encoded)
        compressed_size, member_size = header[8:10]
        name_size, extra_size, member_comment_size, member_disk = header[10:14]
        local_offset = header[16]
        next_cursor = cursor + 46 + name_size + extra_size + member_comment_size
        if (not name_size or next_cursor > end_offset or member_disk or
                0xffffffff in (compressed_size, member_size, local_offset) or
                local_offset + 30 > directory_offset):
            raise ValueError("Invalid tensor archive member directory bounds")
        stream.seek(local_offset)
        local = stream.read(30)
        if len(local) != 30 or local[:4] != b"PK\x03\x04":
            raise ValueError("Invalid tensor archive local header")
        local_header = struct.unpack("<4s5H3I2H", local)
        data_offset = local_offset + 30 + local_header[9] + local_header[10]
        if (local_header[2:4] != header[3:5] or local_header[9] != name_size or
                data_offset + compressed_size > directory_offset):
            raise ValueError("Invalid tensor archive member payload bounds")
        cursor, observed = next_cursor, observed + 1
    if observed != count:
        raise ValueError("NPZ directory count does not match its actual tensor inventory")
    return observed


def preflight_npz(source):
    """Validate every member before NumPy can trust a shape or allocate its payload."""
    # Keep one opened stream for the guard and ZipFile. Production already passes
    # a private, hashed snapshot; path callers must not reopen a replaced name.
    opened = Path(source).open("rb") if isinstance(source, (str, os.PathLike)) else nullcontext(source)
    try:
        with opened as snapshot:
            expected_count = preflight_npz_directory(snapshot)
            snapshot.seek(0)
            with zipfile.ZipFile(snapshot) as archive:
                return preflight_npz_members(archive, expected_count)
    except (zipfile.BadZipFile, EOFError, NotImplementedError) as failure:
        raise ValueError("Invalid or unsupported tensor archive") from failure


def preflight_npz_members(archive, expected_count):
    members = archive.infolist()
    names = [member.filename for member in members]
    logical = [name.removesuffix(".npy") for name in names]
    if (len(members) != expected_count or not 1 <= len(members) <= MAX_TRACE_TENSORS or len(set(names)) != len(names) or
            any(member.is_dir() or member.flag_bits & 1 or not name.endswith(".npy") or not key
                for member, name, key in zip(members, names, logical)) or
            len(set(logical)) != len(logical) or set(logical).intersection(names)):
        raise ValueError("NPZ members must be a bounded, unambiguous inventory of NPY tensors")
    if sum(member.file_size for member in members) > MAX_CAPTURE_BYTES:
        raise ValueError("Uncompressed tensor archive exceeds the payload limit")
    result = {}
    for member, name in zip(members, logical):
        with archive.open(member) as stream:
            result[name] = npy_metadata(stream, member.file_size)
    if sum(value["bytes"] for value in result.values()) > MAX_CAPTURE_BYTES:
        raise ValueError("Tensor capture exceeds the payload limit")
    return result


def read_npz(path, expected_sha256):
    # Both passes read one private snapshot. Replacing the source pathname after
    # validation cannot substitute an unvalidated NPY header at allocation time.
    if not isinstance(expected_sha256, str) or not SHA256.fullmatch(expected_sha256):
        raise ValueError("A tensor archive requires its explicit SHA-256 identity")
    with tempfile.TemporaryFile() as snapshot:
        total = 0
        identity = hashlib.sha256()
        with Path(path).open("rb") as source:
            while chunk := source.read(COPY_BYTES):
                total += len(chunk)
                if total > MAX_CAPTURE_BYTES:
                    raise ValueError("Encoded tensor archive exceeds the payload limit")
                identity.update(chunk)
                snapshot.write(chunk)
        # A pathname hash checked earlier cannot authenticate replacement bytes.
        # Bind the exact snapshot before parsing or allocating any tensor.
        if identity.hexdigest() != expected_sha256:
            raise ValueError("Tensor snapshot SHA-256 does not match the supplied identity")
        snapshot.seek(0)
        inventory = preflight_npz(snapshot)
        snapshot.seek(0)
        import numpy as np
        with np.load(snapshot, allow_pickle=False, max_header_size=MAX_NPY_HEADER_BYTES) as archive:
            if archive.files != list(inventory):
                raise ValueError("Tensor archive inventory changed after validation")
            result = {name: archive[name] for name in archive.files}
        for name, value in result.items():
            expected = inventory[name]
            if (value.dtype.hasobject or value.shape != expected["shape"] or value.nbytes != expected["bytes"] or
                    value.dtype.kind != expected["kind"] or value.dtype.itemsize != expected["itemsize"]):
                raise ValueError("Loaded tensor does not match its validated NPY metadata")
        if sum(value.nbytes for value in result.values()) > MAX_CAPTURE_BYTES:
            raise ValueError("Tensor capture exceeds the payload limit")
        return result


def calibration_snapshot(path, token_count):
    import numpy as np
    path = Path(path)
    manifest, manifest_hash = read_json_snapshot(path)
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
        feed = read_npz(source, case["sha256"])
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
    return result, manifest_hash


def calibration_cases(path, token_count):
    return calibration_snapshot(path, token_count)[0]


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
            feeds, calibration_hash = calibration_snapshot(args.calibration, args.token_count)

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
            overrides, overrides_hash = read_json_snapshot(args.overrides, manifest=False) if args.overrides else (None, None)
            config = get_qnn_qdq_config(str(quant_input), Reader(),
                                       activation_type=QuantType.QUInt16 if args.precision == "a16w8" else QuantType.QUInt8,
                                       weight_type=QuantType.QUInt8,
                                       init_overrides=overrides)
            quantized = directory / "quantized.onnx"
            quantize(str(quant_input), str(quantized), config)
            quantized.replace(output)
            if preprocessed.exists():
                preprocessed.unlink()
            calibration = {"manifestSha256": calibration_hash, "cases": len(feeds),
                           "onnxruntimeVersion": ort.__version__,
                           "overridesSha256": overrides_hash}
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


def short_reference(args):
    """Rebuild the public 43-token Heart reference using the pinned pack data."""
    import numpy as np
    publisher = read_json(Path(__file__).resolve().parents[1] / "docs/audits/kokoro-original-fp32.json")["files"]

    def pack_bytes(name):
        record = publisher[name]
        with local_file(args.pack, name).open("rb") as stream:
            data = stream.read(record["bytes"] + 1)
        if len(data) != record["bytes"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
            raise ValueError("Public reference requires the verified voice and frontend files")
        return data

    symbols = dict((token, int(index)) for line in pack_bytes("tokens.txt").decode().splitlines()
                   for token, index in [line.rsplit(" ", 1)])
    phrase = "the voice is ready for your next question."
    words = set(re.findall("[a-z]+", phrase))
    lexicon = {}
    for line in pack_bytes("lexicon-us-en.txt").decode().splitlines():
        fields = line.split()
        if fields and fields[0] in words:
            lexicon[fields[0]] = [symbols[p] for p in fields[1:] if p in symbols]
    tokens = [0]
    for part in re.findall("[a-z]+|[^a-z]", phrase):
        tokens.extend(lexicon[part] if part in words else [symbols[part]])
    tokens.append(0)
    if len(tokens) != 43:
        raise ValueError("Public reference token shape differs from the native fixture")
    voices = np.frombuffer(pack_bytes("voices.bin"), dtype="<f4").reshape(-1, 510, 256)
    speed = np.float32(1.1445191)
    def generate(directory):
        case = directory / "case.npz"
        np.savez(case, tokens=np.asarray([tokens], dtype=np.int64), style=voices[3, len(tokens) - 2].reshape(1, 256),
                 speed=np.asarray([speed], dtype=np.float32))
        return {"kind": "kokoro-fixed-input", "caseFile": case.name, "caseSha256": digest(case),
                "speechInputSha256": hashlib.sha256(phrase.encode()).hexdigest(), "tokenCount": len(tokens),
                "speakerId": 3, "speechRate": float(speed)}
    return new_artifact(args.output_dir, generate)


def duration_checkpoints(graph, types):
    """Choose bounded prediction checkpoints from the native two-output contract."""
    import onnx
    outputs = list(graph.output)
    if len(outputs) != 2 or outputs[1].type.tensor_type.elem_type != onnx.TensorProto.INT64:
        raise ValueError("Duration tracing requires the Kokoro waveform and int64 token-duration outputs")
    waveform, duration = (value.name for value in outputs)
    required = {duration}
    candidates = []
    # ONNX nodes are topologically ordered. Track only ancestors of duration,
    # excluding weights, shape calculation and the independent waveform branch.
    prediction_ops = {"LSTM", "Gemm", "MatMul", "Conv", "ConvInteger", "Sigmoid", "ReduceSum",
                      "Div", "Round", "Clip", "Max", "Cast", "QuantizeLinear", "DequantizeLinear"}
    for node in reversed(graph.node):
        selected = [name for name in node.output if name in required]
        if not selected:
            continue
        required.update(name for name in node.input if name)
        if node.op_type in prediction_ops:
            candidates.extend(name for name in selected if name in types and types[name].type.HasField("tensor_type"))
    return list(dict.fromkeys([*reversed(candidates[:12]), duration, waveform]))


def kernel_placement(events):
    by_provider = collections.Counter()
    durations = collections.Counter()
    operators = collections.Counter()
    for event in events:
        args = event.get("args", {})
        provider = args.get("provider")
        micros = event.get("dur", 0)
        if (event.get("cat") != "Node" or not event.get("name", "").endswith("_kernel_time") or
                not isinstance(provider, str) or not provider or not isinstance(micros, (int, float)) or
                not math.isfinite(micros) or micros <= 0):
            continue
        by_provider[provider] += 1
        durations[provider] += micros
        operators[(provider, args.get("op_name") or "unknown")] += micros
    total = sum(durations.values())
    return dict(by_provider), {
        "basis": "summed_profiled_node_kernel_microseconds",
        "providerMicros": dict(durations),
        "qnnKernelTimeShare": durations["QNNExecutionProvider"] / total if total else None,
        "topOperators": [{"provider": provider, "operator": op, "micros": micros}
                         for (provider, op), micros in operators.most_common(24)]}


def trace(args):
    import onnx
    model = load_model(args.model, args.model_sha256)
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
    wanted = duration_checkpoints(model.graph, types) if getattr(args, "duration_path", False) else list(dict.fromkeys(args.tensor))
    if not 1 <= len(wanted) <= MAX_TRACE_TENSORS:
        raise ValueError("Select between one and 64 named intermediate tensors")
    order = [name for node in model.graph.node for name in node.output if name in wanted]
    if set(order) != set(wanted) or any(name not in types or not types[name].type.HasField("tensor_type") for name in wanted):
        raise ValueError("Every selected intermediate requires an inferred tensor type in the top-level graph")
    if getattr(args, "duration_path", False):
        order = wanted
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
    feeds = read_npz(case, args.case_sha256)

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
            providers, placement = kernel_placement(events)
            if args.backend != "cpu" and not providers.get("QNNExecutionProvider"):
                raise ValueError("QNN registration without observed QNN execution is not a successful capture")
            checked_file(model, manifest["modelSha256"])
            checked_file(case, args.case_sha256, MAX_CAPTURE_BYTES)
            return {"kind": "kokoro-tensor-capture", "modelSha256": manifest["modelSha256"],
                    "sourceModelSha256": manifest["sourceModelSha256"],
                    "inputSha256": args.case_sha256, "backend": args.backend,
                    "onnxruntimeVersion": ort.__version__, "outputsFile": archive.name,
                    "outputsSha256": digest(archive), "traceOrder": manifest.get("traceOrder", names),
                    "kernelEvents": providers, "kernelPlacement": placement, "profileFile": profile_path.name,
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
    left, left_hash = read_json_snapshot(left_path)
    right, right_hash = read_json_snapshot(right_path)
    for key in ("modelSha256", "sourceModelSha256", "inputSha256", "traceOrder"):
        if not left.get(key) or left[key] != right.get(key):
            raise ValueError("Capture source, inputs and trace order must match")
    if left.get("backend") != "cpu":
        raise ValueError("The reference capture must execute the CPU provider")
    archives = []
    for path, manifest in ((left_path, left), (right_path, right)):
        archive = checked_file(local_file(path.parent, manifest["outputsFile"]), manifest["outputsSha256"], MAX_CAPTURE_BYTES)
        archives.append((archive, manifest["outputsSha256"]))
    inputs = [left_path, right_path, *(archive for archive, _ in archives)]
    report_destination(args.output, inputs)
    arrays = [read_npz(archive, expected) for archive, expected in archives]
    report = compare_arrays(*arrays, left["traceOrder"], args.atol, args.rtol)
    report.update(referenceManifestSha256=left_hash, candidateManifestSha256=right_hash)
    write_report(args.output, report, inputs)
    return args.output


def stages(args):
    import onnx
    model = load_model(args.model, args.model_sha256)
    specification, specification_hash = read_json_snapshot(args.specification)
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
                "specificationSha256": specification_hash, "stages": records,
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
            selection = sub.add_mutually_exclusive_group(required=True)
            selection.add_argument("--tensor", action="append")
            selection.add_argument("--duration-path", action="store_true", help="Trace prediction, token durations and waveform from the native output contract")
            sub.add_argument("--seed", type=int, default=7)
        elif command == "stages":
            sub.add_argument("--specification", required=True, type=Path)
    sub = commands.add_parser("short-reference")
    sub.add_argument("--pack", required=True, type=Path)
    sub.add_argument("--output-dir", required=True, type=Path)
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
        report_destination(args.output, [args.model])
        write_report(args.output, inspect_model(load_model(args.model, args.model_sha256), args.model_sha256), [args.model])
        result = args.output
    else:
        result = {"prepare": prepare, "trace": trace, "short-reference": short_reference, "capture": capture, "compare": compare, "stages": stages}[args.command](args)
    print(result)


if __name__ == "__main__":
    main()
