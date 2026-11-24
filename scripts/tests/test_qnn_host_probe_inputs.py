"""Publisher archive extraction must not permit ambiguity, corruption or traversal."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_qnn_host_probe import extract_libraries


class HostProbeInputsTest(unittest.TestCase):
    def extract(self, entries, expected_hash=None, expected_name="libQnnHtp.so"):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "wheel.zip"
            with zipfile.ZipFile(archive, "w") as package:
                for name, contents in entries:
                    package.writestr(name, contents)
            output = root / "output"
            output.mkdir()
            publisher = {"files": {expected_name: {"bytes": 4, "sha256": expected_hash or hashlib.sha256(b"QNN!").hexdigest()}}}
            extract_libraries(archive, output, publisher)
            self.assertEqual(b"QNN!", (output / expected_name).read_bytes())

    def test_verified_library_extracts_without_installing_wheel(self):
        self.extract([("onnxruntime_qnn/capi/libQnnHtp.so", b"QNN!")])

    def test_duplicate_basenames_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.extract([("a/libQnnHtp.so", b"QNN!"), ("b/libQnnHtp.so", b"QNN!")])

    def test_corrupt_library_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "hash differs"):
            self.extract([("a/libQnnHtp.so", b"QNN!")], expected_hash="0" * 64)

    def test_traversal_in_manifest_or_archive_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "basenames"):
            self.extract([("a/libQnnHtp.so", b"QNN!")], expected_name="../libQnnHtp.so")
        with self.assertRaisesRegex(ValueError, "archive entry"):
            self.extract([("../libQnnHtp.so", b"QNN!")])


if __name__ == "__main__":
    unittest.main()
