#!/usr/bin/env python3
"""Reject APKs/AABs whose DEX definitions break MediaPipe's Java/JNI contract.

Inspect class_data, not strings or method_ids alone: a reference to a stripped
method is not a definition. No Android SDK or third-party parser is required.
DEX layout: https://source.android.com/docs/core/runtime/dex-format
"""
import argparse
from pathlib import Path
import re
import struct
from zipfile import ZipFile


FRAMEWORK = "Lcom/google/mediapipe/framework/"
REQUIRED_METHODS = {
    FRAMEWORK + "Packet;": {
        ("create", "(J)" + FRAMEWORK + "Packet;"): 0x8,
        ("getNativeHandle", "()J"): 0,
        ("release", "()V"): 0,
    },
    FRAMEWORK + "PacketListCallback;": {("process", "(Ljava/util/List;)V"): 0},
    FRAMEWORK + "MediaPipeException;": {("<init>", "(I[B)V"): 0},
}
REQUIRED_FIELDS = {
    FRAMEWORK + "ProtoUtil$SerializedMessage;": {
        ("typeName", "Ljava/lang/String;"), ("value", "[B"),
    },
}
CALLBACK = FRAMEWORK + "PacketListCallback;"


def dex_definitions(data):
    if len(data) < 112 or data[:8] not in {
        b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0", b"dex\n040\0",
    }:
        raise ValueError("Unsupported or truncated DEX header")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Invalid DEX uleb128")

    if u32(32) != len(data) or u32(36) != 112 or u32(40) != 0x12345678:
        raise ValueError("Invalid DEX size/header/endianness")
    strings = []
    for index in range(u32(56)):
        offset = u32(u32(60) + 4 * index)
        _, offset = uleb(offset)
        end = data.index(0, offset)
        # JNI descriptors/names are ASCII. Other DEX strings can use MUTF-8.
        strings.append(data[offset:end].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]

    def type_list(offset):
        return () if not offset else tuple(types[u16(offset + 4 + i * 2)] for i in range(u32(offset)))

    def method(index):
        offset = u32(92) + index * 8
        owner = types[u16(offset)]
        proto = u32(76) + u16(offset + 2) * 12
        descriptor = "(" + "".join(type_list(u32(proto + 8))) + ")" + types[u32(proto + 4)]
        return owner, strings[u32(offset + 4)], descriptor

    def field(index):
        offset = u32(84) + index * 8
        return types[u16(offset)], strings[u32(offset + 4)], types[u16(offset + 2)]

    definitions = {}
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        owner = types[u32(offset)]
        interfaces = type_list(u32(offset + 12))
        if not owner.startswith(FRAMEWORK) and CALLBACK not in interfaces:
            continue
        item = {"methods": {}, "fields": set(), "interfaces": interfaces}
        definitions[owner] = item
        cursor = u32(offset + 24)
        if not cursor:
            continue
        counts = []
        for _ in range(4):
            count, cursor = uleb(cursor)
            counts.append(count)
        for count in counts[:2]:
            field_index = 0
            for _ in range(count):
                delta, cursor = uleb(cursor)
                _, cursor = uleb(cursor)  # access_flags
                field_index += delta
                field_owner, name, descriptor = field(field_index)
                if field_owner != owner:
                    raise ValueError("DEX field definition belongs to another class")
                item["fields"].add((name, descriptor))
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                delta, cursor = uleb(cursor)
                flags, cursor = uleb(cursor)
                _, cursor = uleb(cursor)  # code_off
                method_index += delta
                method_owner, name, descriptor = method(method_index)
                if method_owner != owner:
                    raise ValueError("DEX method definition belongs to another class")
                item["methods"][(name, descriptor)] = flags
    return definitions


def check_bindings(archive, label):
    definitions = {}
    dex_files = [name for name in archive.namelist() if re.fullmatch(r"(?:base/dex/)?classes(?:[0-9]+)?\.dex", name)]
    if not dex_files:
        raise ValueError(f"{label}: no application DEX files")
    for name in dex_files:
        try:
            found = dex_definitions(archive.read(name))
        except (IndexError, struct.error, ValueError) as error:
            raise ValueError(f"{label}/{name}: {error}") from error
        duplicates = definitions.keys() & found.keys()
        if duplicates:
            raise ValueError(f"{label}: duplicate JNI class definitions: {sorted(duplicates)}")
        definitions.update(found)
    missing = []
    for owner, required in REQUIRED_METHODS.items():
        methods = definitions.get(owner, {}).get("methods", {})
        for signature, static in required.items():
            if signature not in methods or methods[signature] & 0x8 != static:
                missing.append(owner + "->" + "".join(signature))
    for owner, fields in REQUIRED_FIELDS.items():
        for name, descriptor in sorted(fields - definitions.get(owner, {}).get("fields", set())):
            missing.append(owner + "->" + name + ":" + descriptor)
    # Desugared callback classes may live outside the framework package.
    for owner, item in definitions.items():
        if CALLBACK in item["interfaces"] and ("process", "(Ljava/util/List;)V") not in item["methods"]:
            missing.append(owner + "->process(Ljava/util/List;)V")
    if missing:
        raise ValueError(f"{label}: missing/renamed MediaPipe JNI definitions:\n  " + "\n  ".join(missing))
    print(f"{label}: MediaPipe Java/JNI definitions verified across {len(dex_files)} DEX files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", nargs="+", type=Path)
    for path in parser.parse_args().archives:
        with ZipFile(path) as archive:
            check_bindings(archive, path.name)


if __name__ == "__main__":
    main()
