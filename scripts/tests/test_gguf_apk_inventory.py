"""The independent GGUF library cannot bind to the separate GenieX runtime."""
import hashlib
import io
from pathlib import Path
import struct
import sys
import unittest
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_local_runtime_apk import check_gguf_dependencies, elf_needed


def elf(dependencies):
    data = bytearray(2048)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 4, 0, 4096, 0, len(data), len(data), 16384)
    table = bytearray(b"\0")
    entries = []
    for name in dependencies:
        entries.append((1, len(table)))
        table += name.encode("ascii") + b"\0"
    entries += [(5, 4096 + 512), (10, len(table)), (0, 0)]
    struct.pack_into("<IIQQQQQQ", data, 120, 2, 4, 256, 4352, 0, len(entries) * 16, len(entries) * 16, 8)
    for index, entry in enumerate(entries):
        struct.pack_into("<qQ", data, 256 + 16 * index, *entry)
    data[512:512 + len(table)] = table
    return bytes(data)


class GgufApkInventoryTest(unittest.TestCase):
    def check(self, dependencies=("libc.so", "libm.so"), owner=False, extra=None, corrupt=False):
        manifest = {"libraries": {}}
        pinned = "lib/arm64-v8a/libggml-base.so"
        if owner:
            manifest["geniexVersion"] = "0.8.0"
            manifest["libraries"][pinned] = {"bytes": 7, "sha256": hashlib.sha256(b"GenieX!").hexdigest(), "source": "com.qualcomm.qti:geniex-android:0.8.0"}
        buffer = io.BytesIO()
        with ZipFile(buffer, "w") as apk:
            apk.writestr("lib/arm64-v8a/libgptmobile_gguf.so", elf(dependencies))
            if owner:
                apk.writestr(pinned, b"Changed" if corrupt else b"GenieX!")
            if extra:
                apk.writestr(extra, b"untracked")
        buffer.seek(0)
        with ZipFile(buffer) as apk:
            check_gguf_dependencies(apk, manifest, {"arm64-v8a"})

    def test_independent_cpu_library_has_only_system_dependencies(self):
        self.check()

    def test_separately_pinned_geniex_library_is_allowed(self):
        self.check(owner=True)

    def test_gguf_cannot_link_to_geniex_even_when_its_library_is_pinned(self):
        with self.assertRaisesRegex(AssertionError, "non-system dependencies"):
            self.check(dependencies=("libc.so", "libggml-base.so"), owner=True)

    def test_untracked_or_changed_provider_libraries_are_rejected(self):
        for owner in (False, True):
            with self.assertRaisesRegex(AssertionError, "Unowned"):
                self.check(owner=owner, extra="lib/arm64-v8a/libllama.so")
        with self.assertRaisesRegex(AssertionError, "owner/hash mismatch"):
            self.check(owner=True, corrupt=True)

    def test_stripped_elf_uses_load_mapping_not_section_headers(self):
        self.assertEqual({"libc.so", "liblog.so"}, elf_needed(elf(("libc.so", "liblog.so"))))
        malformed = bytearray(elf(("libc.so",)))
        struct.pack_into("<Q", malformed, 264, 9000)
        with self.assertRaisesRegex(AssertionError, "name offset"):
            elf_needed(malformed)


if __name__ == "__main__":
    unittest.main()
