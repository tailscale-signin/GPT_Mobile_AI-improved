#!/usr/bin/env python3
"""Verify pinned LiteRT-LM/QAIRT payloads and all Android host ELF LOAD alignments."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile
from check_mediapipe_jni import check_bindings
from check_objectbox_jni import check_bindings as check_objectbox_bindings


def check_host_alignment(name, binary):
    assert binary[:4] == b"\x7fELF", f"{name}: not an ELF library"
    # Qualcomm skeletons are DSP binaries, not Android host shared libraries.
    machine = struct.unpack_from("<H", binary, 18)[0]
    if machine not in (183, 62):  # AArch64 and x86-64
        return False
    assert binary[4:6] == b"\x02\x01", f"{name}: expected little-endian ELF64"
    offset = struct.unpack_from("<Q", binary, 32)[0]
    entry_size, count = struct.unpack_from("<HH", binary, 54)
    for index in range(count):
        header = offset + index * entry_size
        if struct.unpack_from("<I", binary, header)[0] == 1:
            alignment = struct.unpack_from("<Q", binary, header + 48)[0]
            assert alignment >= 16384, f"{name}: LOAD segment is not 16KB aligned"
    return True


def check_runtime_inventory(apk, manifest, abis):
    if manifest.get("geniexVersion"):
        return
    installed_audit = json.loads(apk.read("assets/local-runtime-native-libraries.json"))
    assert installed_audit == manifest, "Installed QNN audit asset differs from the verified native manifest"
    packaged = {
        name for name in apk.namelist()
        if name.startswith("lib/") and Path(name).name.startswith("libQnn") and name.endswith(".so")
    }
    expected = {
        name for name in manifest["libraries"]
        if name.split("/")[1] in abis and Path(name).name.startswith("libQnn")
    }
    assert packaged == expected, f"Unaudited or missing QNN payloads: {sorted(packaged ^ expected)}"


def elf_needed(binary):
    """Read ELF64 DT_NEEDED from program headers, including stripped libraries."""
    assert len(binary) >= 64 and binary[:6] == b"\x7fELF\x02\x01", "Expected little-endian ELF64"
    offset = struct.unpack_from("<Q", binary, 32)[0]
    size, count = struct.unpack_from("<HH", binary, 54)
    assert size >= 56 and offset + size * count <= len(binary), "Invalid ELF program headers"
    loads, dynamic = [], None
    for index in range(count):
        header = offset + index * size
        kind = struct.unpack_from("<I", binary, header)[0]
        file_offset, address = struct.unpack_from("<QQ", binary, header + 8)
        file_size = struct.unpack_from("<Q", binary, header + 32)[0]
        assert file_offset + file_size <= len(binary), "Invalid ELF segment bounds"
        if kind == 1:
            loads.append((address, file_offset, file_size))
        elif kind == 2:
            assert dynamic is None and file_size % 16 == 0, "Invalid ELF dynamic segment"
            dynamic = (file_offset, file_size)
    assert dynamic is not None, "Missing ELF dynamic segment"
    needed, strings, string_size, terminated = [], None, None, False
    for position in range(dynamic[0], sum(dynamic), 16):
        tag, value = struct.unpack_from("<qQ", binary, position)
        if tag == 0:
            terminated = True
            break
        if tag == 1:
            needed.append(value)
        elif tag == 5:
            strings = value
        elif tag == 10:
            string_size = value
    assert terminated and strings is not None and string_size is not None, "Incomplete ELF dynamic strings"
    matches = [start + strings - address for address, start, length in loads
               if address <= strings and strings + string_size <= address + length]
    assert len(matches) == 1, "Unmapped ELF dynamic strings"
    table = binary[matches[0]:matches[0] + string_size]
    result = set()
    for index in needed:
        assert index < len(table), "Invalid ELF dependency name offset"
        end = table.find(b"\0", index)
        assert end > index, "Invalid ELF dependency name"
        result.add(table[index:end].decode("ascii"))
    return result


def check_gguf_dependencies(apk, manifest, abis):
    names = apk.namelist()
    # Vulkan is a platform library on Android. The GGUF Vulkan backend links to
    # it dynamically and relies on the device driver supplied by the OS.
    system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libvulkan.so"}
    for abi in abis:
        path = f"lib/{abi}/libgptmobile_gguf.so"
        assert path in names, f"Missing GGUF runtime for {abi}"
        unexpected = elf_needed(apk.read(path)) - system
        assert not unexpected, f"GGUF runtime has non-system dependencies: {sorted(unexpected)}"
    # GenieX owns its separately pinned llama/ggml implementation. It must never
    # become a dynamic dependency of the normal app's independent GGUF library.
    for name in names:
        if not name.startswith("lib/") or not Path(name).name.startswith(("libllama", "libggml")):
            continue
        expected = manifest["libraries"].get(name)
        assert manifest.get("geniexVersion") and expected and expected.get("source") == f"com.qualcomm.qti:geniex-android:{manifest['geniexVersion']}", f"Unowned dynamic GGUF library: {name}"
        binary = apk.read(name)
        assert len(binary) == expected["bytes"] and hashlib.sha256(binary).hexdigest() == expected["sha256"], f"{name}: GenieX owner/hash mismatch"


def check_apk(apk_path, manifest):
    with ZipFile(apk_path) as apk:
        names = apk.namelist()
        assert len(names) == len(set(names)), f"{apk_path}: duplicate ZIP entries"
        model = apk.read("assets/memory/universal_sentence_encoder.tflite")
        assert hashlib.sha256(model).hexdigest() == "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749", "Memory model checksum mismatch"
        abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        assert abis and abis <= {"arm64-v8a", "x86_64"}, f"Unexpected APK ABIs: {abis}"
        check_runtime_inventory(apk, manifest, abis)
        for abi in abis:
            for library in ("libobjectbox-jni.so", "libmediapipe_tasks_jni.so", "libmediapipe_tasks_textgenai_jni.so"):
                assert f"lib/{abi}/{library}" in names, f"Missing memory runtime {abi}/{library}"
        gguf = json.loads(apk.read("assets/gguf-runtime-inventory.json"))
        assert gguf["revision"] == "620d2225cc436ab0b001e3200e590e3b286ef9ab"
        assert gguf["backends"] in (["cpu"], ["cpu", "gpu"]), "Unsupported GGUF backend inventory"
        check_gguf_dependencies(apk, manifest, abis)
        checked = 0
        for name, expected in manifest["libraries"].items():
            if name.split("/")[1] not in abis:
                continue
            assert name in names, f"{apk_path}: missing {name}"
            binary = apk.read(name)
            assert len(binary) == expected["bytes"], f"{name}: unexpected size"
            assert hashlib.sha256(binary).hexdigest() == expected["sha256"], f"{name}: version/hash mismatch"
            checked += 1
        aligned = sum(
            check_host_alignment(name, apk.read(name))
            for name in names if name.startswith("lib/") and name.endswith(".so")
        )
        forbidden = {"libLiteRtCompilerPlugin_Qualcomm.so", "libqnn_delegate_jni.so", "libQnnTFLiteDelegate.so", "libQnnIr.so", "libQnnSaver.so"}
        if manifest.get("geniexVersion"):
            assert abis == {"arm64-v8a"}, "GenieX preview is arm64 only"
            assert "lib/arm64-v8a/libLiteRtDispatch_Qualcomm.so" not in names, "Do not combine LiteRT dispatch and GenieX QAIRT"
        else:
            assert not any(Path(name).name in forbidden for name in names), "Unexpected second QNN integration/compiler payload"
        check_bindings(apk, apk_path.name)
        check_objectbox_bindings(apk, apk_path.name)
        print(f"{apk_path.name}: {checked} pinned runtime libraries verified; {aligned} Android host libraries passed 16KB LOAD alignment")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-stack", choices=("litert", "geniex"), default="litert")
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    manifest_name = "geniex-preview-native-libraries.json" if args.native_stack == "geniex" else "local-runtime-native-libraries.json"
    manifest = json.loads((root / "docs/audits" / manifest_name).read_text())
    for apk in args.apks:
        check_apk(apk, manifest)


if __name__ == "__main__":
    main()
