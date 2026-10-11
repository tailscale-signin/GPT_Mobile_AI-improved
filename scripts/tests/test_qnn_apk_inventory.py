"""The device's trusted inventory must describe every packaged QNN library."""
import io
import json
from pathlib import Path
import sys
import unittest
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_local_runtime_apk import check_runtime_inventory


class QnnApkInventoryTest(unittest.TestCase):
    def check(self, extra=False, stale=False, abi="arm64-v8a"):
        name = "lib/arm64-v8a/libQnnSystem.so"
        manifest = {"qairtVersion": "2.51.0", "libraries": {name: {"sha256": "fixture"}}}
        asset = dict(manifest, qairtVersion="2.50.0") if stale else manifest
        buffer = io.BytesIO()
        with ZipFile(buffer, "w") as apk:
            apk.writestr("assets/local-runtime-native-libraries.json", json.dumps(asset))
            if abi == "arm64-v8a":
                apk.writestr(name, b"fixture")
            if extra:
                apk.writestr("lib/arm64-v8a/libQnnHtpV68Stub.so", b"untracked")
        buffer.seek(0)
        with ZipFile(buffer) as apk:
            check_runtime_inventory(apk, manifest, {abi})

    def test_matching_inventory(self):
        self.check()

    def test_x86_split_does_not_require_arm64_libraries(self):
        self.check(abi="x86_64")

    def test_stale_installed_asset_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "audit asset differs"):
            self.check(stale=True)

    def test_extra_qnn_library_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Unaudited or missing"):
            self.check(extra=True)


if __name__ == "__main__":
    unittest.main()
