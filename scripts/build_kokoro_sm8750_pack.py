#!/usr/bin/env python3
"""Compile the pinned Kokoro vocoder for Snapdragon 8 Elite and preserve its SDK streaming frontend."""
import argparse
import hashlib
import mmap
from pathlib import Path
import os
import re
import struct
import subprocess
import tempfile

SOURCE = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52"
BLOCK = 16384


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sections(header):
    def word(offset):
        return struct.unpack_from("<I", header, offset)[0]

    def follow(offset):
        return offset + word(offset)

    def field(table, index):
        vtable = table - struct.unpack_from("<i", header, table)[0]
        slot = struct.unpack_from("<H", header, vtable + 4 + index * 2)[0]
        if not slot:
            raise ValueError("Missing LiteRT-LM header field")
        return table + slot

    metadata = follow(field(follow(32), 1))
    vector = follow(field(metadata, 0))
    rows = []
    for index in range(word(vector)):
        table = follow(vector + 4 + index * 4)
        first, last = (field(table, i) for i in (1, 2))
        begin, end = (struct.unpack_from("<Q", header, pos)[0] for pos in (first, last))
        rows.append((first, last, header[field(table, 3)], begin, end))
    return rows


def interface(model):
    result = {}
    for signature in model.signatureDefs:
        graph = model.subgraphs[signature.subgraphIndex]
        def tensors(entries):
            return {x.name: (graph.tensors[x.tensorIndex].type, tuple(graph.tensors[x.tensorIndex].shape)) for x in entries}
        result[signature.signatureKey] = tensors(signature.inputs), tensors(signature.outputs)
    return result


def build(args):
    from ai_edge_litert import schema_py_generated as schema
    if sha(args.publisher_pack) != SOURCE:
        raise ValueError("Expected the pinned GPU publisher bundle")
    if args.output.exists():
        raise FileExistsError(args.output)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as temporary, args.publisher_pack.open("rb") as raw, mmap.mmap(raw.fileno(), 0, access=mmap.ACCESS_READ) as source:
        staging = Path(temporary)
        header = bytearray(source[:BLOCK])
        rows = sections(header)
        if [x[2] for x in rows[:3]] != [10, 3, 3]:
            raise ValueError("Expected metadata, acoustic and vocoder sections")
        def section(index):
            return source[rows[index][3]:rows[index][4]]
        original = section(2)
        if args.compiled_vocoder:
            compiled = args.compiled_vocoder.read_bytes()
        else:
            if not args.litert_source:
                raise ValueError("--litert-source is required for a compiler build")
            headers = args.litert_source.resolve()
            pinned = subprocess.check_output(["git", "-C", headers, "rev-parse", "HEAD"], text=True).strip()
            if pinned != "14b37ee5489894161548390f448c4a68c94f4ce5":
                raise ValueError("Expected LiteRT 2.4.0 headers")
            from importlib.metadata import version
            if version("ai-edge-litert") != "2.4.0":
                raise ValueError("Expected ai-edge-litert 2.4.0")
            import ai_edge_litert
            sdk = Path(ai_edge_litert.__file__).parent
            host = args.qairt_lib.resolve()
            plugin_dir = staging / "plugin"
            plugin_dir.mkdir()
            feature_header = staging / "litert/build_common/build_config.h"
            feature_header.parent.mkdir(parents=True)
            template = (headers / "litert/build_common/build_config.h.in").read_text()
            feature_header.write_text(re.sub(r"^#cmakedefine01 (\w+)$", r"#define \1 0", template, flags=re.M))
            plugin = plugin_dir / "libLiteRtCompilerPlugin_Kokoro.so"
            subprocess.run(["cc", "-std=gnu11", "-O2", "-fPIC", "-shared", "-I", staging, "-I", headers,
                            Path(__file__).parent / "native/kokoro_vocoder_partition.c", "-ldl", "-o", plugin], check=True)
            before, after = staging / "vocoder.tflite", staging / "compiled.tflite"
            before.write_bytes(original)
            env = dict(os.environ)
            env["KOKORO_QNN_COMPILER"] = str(sdk / "vendors/qualcomm/compiler/libLiteRtCompilerPlugin_Qualcomm.so")
            env["LD_LIBRARY_PATH"] = os.pathsep.join([str(host), str(sdk), env.get("LD_LIBRARY_PATH", "")])
            subprocess.run([str(sdk / "tools/apply_plugin_main"), "--cmd=apply", f"--model={before}",
                            f"--libs={plugin_dir}", f"--qualcomm_qnn_lib_dir={host}",
                            "--soc_manufacturer=Qualcomm", "--soc_model=SM8750", "--qualcomm_optimization_level=O2", f"--o={after}"], check=True, env=env)
            compiled = after.read_bytes()
        def model(data):
            if data[4:8] != b"TFL3":
                raise ValueError("Expected a LiteRT flatbuffer")
            return schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(data))
        a, b = model(original), model(compiled)
        if not interface(a) or interface(a) != interface(b):
            raise ValueError("Compiler changed the native vocoder signature")
        dispatch = sum(b.operatorCodes[op.opcodeIndex].customCode == b"DISPATCH_OP"
                       for g in b.subgraphs for op in g.operators)
        if dispatch != 17:
            raise ValueError("Expected 17 NPU dispatch partitions in the compiled vocoder")
        proto = section(0)
        # Text2SpeechMetadata.tts_model_type.kokoro.vocoder_backend = "npu".
        # The SHA-pinned publisher input has an empty Kokoro options message.
        if proto[:4] != bytes.fromhex("0a020a00"):
            raise ValueError("Unexpected Kokoro metadata")
        metadata = bytes.fromhex("0a070a052203") + b"npu" + proto[4:]
        result = staging / "kokoro_sm8750.litertlm"
        with result.open("wb") as stream:
            stream.write(header)
            for i, (first, last, _, begin, end) in enumerate(rows):
                aligned = (stream.tell() + BLOCK - 1) // BLOCK * BLOCK
                stream.seek(aligned)
                stream.write(metadata if i == 0 else compiled if i == 2 else source[begin:end])
                struct.pack_into("<Q", header, first, aligned)
                struct.pack_into("<Q", header, last, stream.tell())
            stream.seek(0)
            stream.write(header)
        digest = sha(result)
        # Only the reviewed digest in the Android catalog can be imported into the app.
        os.link(result, args.output)
        print(f"SM8750 dispatch partitions: {dispatch}; bytes: {result.stat().st_size}; SHA-256: {digest}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("publisher_pack", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--litert-source", type=Path, help="LiteRT v2.4.0 checkout")
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--qairt-lib", type=Path, help="QAIRT 2.52.0.261104 x86_64 host libraries with LiteRT 2.4.0")
    inputs.add_argument("--compiled-vocoder", type=Path, help="Output of the same SM8750 compiler invocation")
    build(parser.parse_args())
