"""App-built speech timing and QNN adapter libraries remain usable in every split."""
import io
from pathlib import Path
import struct
import sys
import unittest
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_local_runtime_apk import check_kokoro_dependencies


def elf(abi, dependencies=()):
    data = bytearray(2048)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, {"arm64-v8a": 183, "x86_64": 62}[abi])
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


class KokoroApkInventoryTest(unittest.TestCase):
    def files(self, abis=("arm64-v8a", "x86_64"), dependencies=("libc.so", "libm.so", "libdl.so")):
        return {
            f"lib/{abi}/{library}": elf(abi, dependencies if library == "libkokoro_qnn_adapter.so" else ())
            for abi in abis
            for library in ("libtimed_kokoro.so", "libkokoro_qnn_adapter.so")
        }

    def check(self, files, abis=("arm64-v8a", "x86_64")):
        buffer = io.BytesIO()
        with ZipFile(buffer, "w") as apk:
            for name, content in files.items():
                apk.writestr(name, content)
        buffer.seek(0)
        with ZipFile(buffer) as apk:
            check_kokoro_dependencies(apk, set(abis))

    def test_both_split_and_universal_apks_include_timing_and_adapter(self):
        for abis in (("arm64-v8a",), ("x86_64",), ("arm64-v8a", "x86_64")):
            with self.subTest(abis=abis):
                self.check(self.files(abis), abis)

    def test_each_kokoro_library_is_required_for_each_packaged_abi(self):
        for abi in ("arm64-v8a", "x86_64"):
            for library in ("libtimed_kokoro.so", "libkokoro_qnn_adapter.so"):
                with self.subTest(abi=abi, library=library):
                    files = self.files()
                    del files[f"lib/{abi}/{library}"]
                    with self.assertRaisesRegex(AssertionError, f"Missing Kokoro runtime {abi}/{library}"):
                        self.check(files)

    def test_missing_dynamic_dependency_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "missing dependencies.*libmissing.so"):
            self.check(self.files(dependencies=("libc.so", "libmissing.so")))

    def test_same_abi_packaged_dynamic_dependency_is_allowed(self):
        files = self.files(dependencies=("libc.so", "libc++_shared.so"))
        for abi in ("arm64-v8a", "x86_64"):
            files[f"lib/{abi}/libc++_shared.so"] = elf(abi)
        self.check(files)

    def test_other_abi_or_asset_copy_does_not_supply_a_dependency(self):
        for misplaced in ("lib/x86_64/libhelper.so", "assets/lib/arm64-v8a/libhelper.so"):
            with self.subTest(misplaced=misplaced):
                files = self.files(("arm64-v8a",), dependencies=("libhelper.so",))
                files[misplaced] = elf("x86_64")
                with self.assertRaisesRegex(AssertionError, "missing dependencies.*libhelper.so"):
                    self.check(files, ("arm64-v8a",))

    def test_kokoro_elf_machine_must_match_its_abi_directory(self):
        for library in ("libtimed_kokoro.so", "libkokoro_qnn_adapter.so"):
            with self.subTest(library=library):
                files = self.files()
                files[f"lib/arm64-v8a/{library}"] = elf("x86_64")
                with self.assertRaisesRegex(AssertionError, "ELF machine does not match packaged ABI"):
                    self.check(files)

    def test_malformed_adapter_cannot_bypass_dynamic_dependency_checks(self):
        files = self.files()
        malformed = bytearray(elf("arm64-v8a", ("libc.so",)))
        struct.pack_into("<Q", malformed, 264, 9000)
        files["lib/arm64-v8a/libkokoro_qnn_adapter.so"] = bytes(malformed)
        with self.assertRaisesRegex(AssertionError, "Invalid ELF dependency name offset"):
            self.check(files)


if __name__ == "__main__":
    unittest.main()
