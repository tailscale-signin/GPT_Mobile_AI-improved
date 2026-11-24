import os
from pathlib import Path
import shutil
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import optimize_release_apks as packaging


class ReleaseCompressionTest(unittest.TestCase):
    def test_original_stored_model_and_library_bytes_survive_before_signing(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "both-unsigned.apk"
            payload = os.urandom(100_000)
            with ZipFile(path, "w") as archive:
                archive.writestr("assets/model.tflite", b"mapped", ZIP_STORED)
                archive.writestr("lib/x86_64/test.so", payload, ZIP_DEFLATED)
                archive.writestr("classes.dex", b"dex", ZIP_DEFLATED)

            def tool(command, check):
                self.assertTrue(check)
                if command[0] == "advzip":
                    with ZipFile(command[-1]) as candidate:
                        self.assertEqual(["lib/x86_64/test.so"], candidate.namelist())
                elif "-f" in command:
                    shutil.copyfile(command[-2], command[-1])

            with patch.object(packaging.subprocess, "run", side_effect=tool):
                packaging.optimize(path, "zipalign")
            with ZipFile(path) as archive:
                self.assertEqual(ZIP_STORED, archive.getinfo("assets/model.tflite").compress_type)
                self.assertEqual(payload, archive.read("lib/x86_64/test.so"))
                self.assertEqual(b"dex", archive.read("classes.dex"))

    def test_corrupt_equal_length_output_fails_and_signed_input_is_untouched(self):
        with TemporaryDirectory() as directory:
            original = Path(directory) / "one-unsigned.apk"
            other = Path(directory) / "other.apk"
            for path, value in [(original, b"abc"), (other, b"xyz")]:
                with ZipFile(path, "w") as archive:
                    archive.writestr("classes.dex", value)
                    archive.writestr("META-INF/SIGNER.SF", b"signed")
            with ZipFile(original) as before, ZipFile(other) as after, self.assertRaisesRegex(ValueError, "content changed"):
                packaging.verify_contents(before, after)
            unchanged = original.read_bytes()
            with self.assertRaisesRegex(ValueError, "already signed"):
                packaging.optimize(original, "zipalign")
            self.assertEqual(unchanged, original.read_bytes())
