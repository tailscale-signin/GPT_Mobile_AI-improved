"""Private Pocket plugins are admitted only at their reviewed path and digest."""
import hashlib
import io
from pathlib import Path
import struct
import sys
import unittest
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_local_runtime_apk import check_pocket_compiled_runtime


class PocketCompiledApkTest(unittest.TestCase):
    def fixture(self, tamper=False, geniex=False, misplaced=False):
        binary = bytearray(64)
        binary[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", binary, 18, 183)
        path = "assets/pocket-compiled-npu-2.4.1/libLiteRtCompilerPlugin_Qualcomm.so"
        core = "lib/arm64-v8a/libLiteRt.so"
        record = {"bytes": len(binary), "sha256": hashlib.sha256(binary).hexdigest()}
        manifest = {"libraries": {core: record}, "privateNpuAssets": {path: record}}
        data = io.BytesIO()
        with ZipFile(data, "w") as apk:
            apk.writestr(core, binary)
            apk.writestr(path.replace("pocket-compiled-npu-2.4.1", "unexpected") if misplaced else path,
                         binary + b"changed" if tamper else binary)
        data.seek(0)
        with ZipFile(data) as apk:
            return check_pocket_compiled_runtime(apk, {"arm64-v8a"}, geniex, manifest)

    def test_exact_private_plugin_is_allowed(self):
        self.assertEqual(self.fixture(), {"assets/pocket-compiled-npu-2.4.1/libLiteRtCompilerPlugin_Qualcomm.so"})

    def test_modified_plugin_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "unreviewed Pocket NPU"):
            self.fixture(tamper=True)

    def test_plugin_at_another_path_does_not_satisfy_inventory(self):
        with self.assertRaisesRegex(AssertionError, "Unexpected or missing private Pocket"):
            self.fixture(misplaced=True)

    def test_geniex_must_not_package_the_pocket_qnn_pair(self):
        with self.assertRaisesRegex(AssertionError, "Do not combine"):
            self.fixture(geniex=True)


if __name__ == "__main__":
    unittest.main()
