import copy
import importlib.util
import io
from pathlib import Path
import re
import struct
import unittest
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location("jni_gate", Path(__file__).resolve().parents[1] / "check_mediapipe_jni.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

PREFIX = "Lcom/google/mediapipe/framework/"
PACKET = PREFIX + "Packet;"
CALLBACK = PREFIX + "PacketListCallback;"
# Independent fixture contract. Keep identifiers even after removing definitions
# to reproduce a dangling method reference, rather than testing string presence.
CONTRACT = {
    PACKET: {"methods": [("create", "(J)" + PACKET, 9), ("getNativeHandle", "()J", 1), ("release", "()V", 1)]},
    CALLBACK: {"methods": [("process", "(Ljava/util/List;)V", 0x401)]},
    PREFIX + "MediaPipeException;": {"methods": [("<init>", "(I[B)V", 1)]},
    PREFIX + "ProtoUtil$SerializedMessage;": {"fields": [("typeName", "Ljava/lang/String;"), ("value", "[B")]},
}


def uleb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def dex_fixture(classes):
    """Build small DEX identifier/class-data fixtures; no executable code."""
    method_rows = {(owner, name, desc) for owner, item in CONTRACT.items() for name, desc, _ in item.get("methods", [])}
    method_rows.update((owner, name, desc) for owner, item in classes.items() for name, desc, _ in item.get("methods", []))
    field_rows = {(owner, name, desc) for owner, item in CONTRACT.items() for name, desc in item.get("fields", [])}
    field_rows.update((owner, name, desc) for owner, item in classes.items() for name, desc in item.get("fields", []))

    def signature(desc):
        args, result = desc[1:].split(")")
        return result, tuple(re.findall(r"\[*L[^;]+;|\[*[BCDFIJSZ]", args))

    protos = sorted({signature(desc) for _, _, desc in method_rows})
    types = {"Ljava/lang/Object;", *classes, *(owner for owner, _, _ in method_rows), *(owner for owner, _, _ in field_rows)}
    for result, args in protos:
        types.update((result, *args))
    types.update(desc for _, _, desc in field_rows)
    types.update(interface for item in classes.values() for interface in item.get("interfaces", ()))
    types = sorted(types)
    shorties = {proto: "".join(t if len(t) == 1 else "L" for t in (proto[0], *proto[1])) for proto in protos}
    strings = sorted({*types, *shorties.values(), *(name for _, name, _ in method_rows), *(name for _, name, _ in field_rows)})
    sidx = {value: i for i, value in enumerate(strings)}
    tidx = {value: i for i, value in enumerate(types)}
    pidx = {value: i for i, value in enumerate(protos)}
    methods = sorted(method_rows, key=lambda row: (tidx[row[0]], sidx[row[1]], pidx[signature(row[2])]))
    fields = sorted(field_rows, key=lambda row: (tidx[row[0]], sidx[row[1]], tidx[row[2]]))
    midx = {value: i for i, value in enumerate(methods)}
    fidx = {value: i for i, value in enumerate(fields)}
    owners = sorted(classes, key=tidx.get)
    tables = [(len(strings), 4), (len(types), 4), (len(protos), 12), (len(fields), 8), (len(methods), 8), (len(owners), 32)]
    offsets = []
    end = 112
    for count, width in tables:
        offsets.append(end)
        end += count * width
    result = bytearray(end)

    def emit(content, aligned=False):
        if aligned:
            result.extend(bytes((-len(result)) % 4))
        offset = len(result)
        result.extend(content)
        return offset

    def type_list(values):
        return 0 if not values else emit(struct.pack("<I", len(values)) + b"".join(struct.pack("<H", tidx[value]) for value in values), True)

    for index, text in enumerate(strings):
        offset = emit(uleb(len(text)) + text.encode() + b"\0")
        struct.pack_into("<I", result, offsets[0] + index * 4, offset)
    for index, text in enumerate(types):
        struct.pack_into("<I", result, offsets[1] + index * 4, sidx[text])
    for index, proto in enumerate(protos):
        args_offset = type_list(proto[1])
        struct.pack_into("<III", result, offsets[2] + index * 12, sidx[shorties[proto]], tidx[proto[0]], args_offset)
    for index, (owner, name, desc) in enumerate(fields):
        struct.pack_into("<HHI", result, offsets[3] + index * 8, tidx[owner], tidx[desc], sidx[name])
    for index, (owner, name, desc) in enumerate(methods):
        struct.pack_into("<HHI", result, offsets[4] + index * 8, tidx[owner], pidx[signature(desc)], sidx[name])
    for index, owner in enumerate(owners):
        item = classes[owner]
        interface_offset = type_list(item.get("interfaces", ()))
        members = sorted(fidx[(owner, name, desc)] for name, desc in item.get("fields", []))
        direct, virtual = [], []
        for name, desc, flags in item.get("methods", []):
            (direct if flags & 8 or name == "<init>" else virtual).append((midx[(owner, name, desc)], flags))
        content = bytearray(uleb(0) + uleb(len(members)) + uleb(len(direct)) + uleb(len(virtual)))
        previous = 0
        for field_index in members:
            content.extend(uleb(field_index - previous) + uleb(1))
            previous = field_index
        for group in (direct, virtual):
            previous = 0
            for method_index, flags in sorted(group):
                content.extend(uleb(method_index - previous) + uleb(flags) + uleb(0))
                previous = method_index
        data_offset = emit(content)
        struct.pack_into("<IIIIIIII", result, offsets[5] + index * 32, tidx[owner], 1, tidx["Ljava/lang/Object;"], interface_offset, 0xffffffff, 0, data_offset, 0)
    result[:8] = b"dex\n039\0"
    struct.pack_into("<III", result, 32, len(result), 112, 0x12345678)
    for index, ((count, _), offset) in enumerate(zip(tables, offsets)):
        struct.pack_into("<II", result, 56 + index * 8, count, offset)
    return bytes(result)


class MediaPipeJniGateTest(unittest.TestCase):
    def check(self, *dexes, bundle=False):
        content = io.BytesIO()
        with ZipFile(content, "w") as archive:
            for index, dex in enumerate(dexes):
                archive.writestr(("base/dex/" if bundle else "") + f"classes{index + 1 if index else ''}.dex", dex)
        with ZipFile(content) as archive:
            gate.check_bindings(archive, "fixture")

    def test_split_apk_and_bundle_definitions_pass(self):
        first = {PACKET: CONTRACT[PACKET]}
        second = {owner: item for owner, item in CONTRACT.items() if owner != PACKET}
        for bundle in (False, True):
            self.check(dex_fixture(first), dex_fixture(second), bundle=bundle)

    def test_reference_to_removed_definition_cannot_pass(self):
        for owner in CONTRACT:
            classes = copy.deepcopy(CONTRACT)
            del classes[owner]
            with self.subTest(owner=owner), self.assertRaisesRegex(ValueError, "missing/renamed"):
                self.check(dex_fixture(classes))

    def test_callback_renaming_wrong_return_type_and_static_flag_fail(self):
        for owner, replacement in [
            (CALLBACK, [("a", "(Ljava/util/List;)V", 0x401)]),
            (PACKET, [("create", "(J)Ljava/lang/Object;", 9), *CONTRACT[PACKET]["methods"][1:]]),
            (PACKET, [("create", "(J)" + PACKET, 1), *CONTRACT[PACKET]["methods"][1:]]),
        ]:
            classes = copy.deepcopy(CONTRACT)
            classes[owner]["methods"] = replacement
            with self.subTest(replacement=replacement), self.assertRaisesRegex(ValueError, "missing/renamed"):
                self.check(dex_fixture(classes))

    def test_renamed_callback_implementation_outside_framework_fails(self):
        classes = copy.deepcopy(CONTRACT)
        classes["La;"] = {"interfaces": (CALLBACK,), "methods": [("a", "(Ljava/util/List;)V", 1)]}
        with self.assertRaisesRegex(ValueError, "La;->process"):
            self.check(dex_fixture(classes))
        classes["La;"]["methods"] = [("process", "(Ljava/util/List;)V", 1)]
        self.check(dex_fixture(classes))

    def test_stripped_or_renamed_native_field_fails(self):
        classes = copy.deepcopy(CONTRACT)
        classes[PREFIX + "ProtoUtil$SerializedMessage;"]["fields"] = [("typeName", "Ljava/lang/String;"), ("a", "[B")]
        with self.assertRaisesRegex(ValueError, "value:\\[B"):
            self.check(dex_fixture(classes))

    def test_missing_duplicate_and_corrupt_dex_fail_closed(self):
        dex = dex_fixture(CONTRACT)
        for dexes in [(), (dex, dex), (dex[:100],), (dex[:-1],)]:
            with self.subTest(count=len(dexes)), self.assertRaises(ValueError):
                self.check(*dexes)


if __name__ == "__main__":
    unittest.main()
