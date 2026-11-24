import os
from pathlib import Path
import shutil
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import call, patch
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

            def tool(command, check, timeout):
                self.assertTrue(check)
                self.assertGreater(timeout, 0)
                self.assertLessEqual(timeout, packaging.TOOL_TIMEOUT_SECONDS)
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

    def test_tool_failure_timeout_and_growing_output_keep_the_original(self):
        for outcome in ("failure", "timeout", "growth", "expired_before_promotion"):
            with self.subTest(outcome=outcome), TemporaryDirectory() as directory:
                path = Path(directory) / "one-unsigned.apk"
                with ZipFile(path, "w") as archive:
                    archive.writestr("lib/x86_64/test.so", os.urandom(100_000), ZIP_DEFLATED)
                    archive.writestr("assets/model.tflite", b"mapped", ZIP_STORED)
                original = path.read_bytes()
                clock = [100.0]

                def tool(command, check, timeout):
                    self.assertTrue(check)
                    self.assertGreater(timeout, 0)
                    self.assertLessEqual(timeout, packaging.TOOL_TIMEOUT_SECONDS)
                    if command[0] == "advzip":
                        if outcome == "failure":
                            raise packaging.subprocess.CalledProcessError(1, command)
                        if outcome == "timeout":
                            raise packaging.subprocess.TimeoutExpired(command, timeout)
                    elif "-f" in command:
                        shutil.copyfile(command[-2], command[-1])
                        if outcome == "growth":
                            with ZipFile(command[-1], "a") as aligned:
                                aligned.comment = b"larger but same entry bytes" * 100
                    elif outcome == "expired_before_promotion":
                        clock[0] = 1001.0

                expected = {
                    "failure": packaging.subprocess.CalledProcessError,
                    "timeout": packaging.subprocess.TimeoutExpired,
                    "growth": ValueError,
                    "expired_before_promotion": TimeoutError,
                }[outcome]
                with patch.object(packaging.time, "monotonic", side_effect=lambda: clock[0]), patch.object(packaging.subprocess, "run", side_effect=tool), self.assertRaises(expected):
                    packaging.optimize(path, "zipalign", deadline=1000.0)
                self.assertEqual(original, path.read_bytes())

    def test_resource_refusal_never_starts_a_tool_or_replaces_the_original(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "one-unsigned.apk"
            with ZipFile(path, "w") as archive:
                archive.writestr("lib/x86_64/test.so", os.urandom(100_000), ZIP_DEFLATED)
            original = path.read_bytes()
            with ZipFile(path) as archive:
                required = 2 * (len(original) + sum(entry.file_size for entry in archive.infolist())) + 128 * 1024 * 1024
            disk = shutil.disk_usage(path.parent)
            with patch.object(packaging.shutil, "disk_usage", return_value=disk._replace(free=required - 1)), patch.object(packaging.subprocess, "run") as tool:
                with self.assertRaisesRegex(OSError, "Insufficient staging"):
                    packaging.optimize(path, "zipalign")
            tool.assert_not_called()
            self.assertEqual(original, path.read_bytes())

    def test_remaining_batch_deadline_bounds_every_tool_and_refuses_expired_work(self):
        with patch.object(packaging.time, "monotonic", return_value=100.0), patch.object(packaging.subprocess, "run") as tool:
            packaging.run_tool(["tool", "first"], 130.0)
            packaging.run_tool(["tool", "second"], 1000.0)
            with self.assertRaisesRegex(TimeoutError, "deadline expired"):
                packaging.run_tool(["tool", "expired"], 100.0)
        self.assertEqual([
            call(["tool", "first"], check=True, timeout=30.0),
            call(["tool", "second"], check=True, timeout=180),
        ], tool.call_args_list)

    def test_all_apks_share_one_batch_deadline(self):
        paths = [Path("one-unsigned.apk"), Path("two-unsigned.apk")]
        with patch.object(packaging.time, "monotonic", return_value=100.0), patch.object(packaging, "optimize") as optimize:
            packaging.optimize_all(paths, "zipalign")
        self.assertEqual([
            call(paths[0], "zipalign", deadline=1000.0),
            call(paths[1], "zipalign", deadline=1000.0),
        ], optimize.call_args_list)

    def test_both_workflows_use_the_same_optimizer_before_consuming_apks(self):
        repository = Path(__file__).resolve().parents[2]
        signed = (repository / ".github/workflows/release-build.yml").read_text()
        unsigned = (repository / ".github/workflows/unsigned-release-build.yml").read_text()
        command = 'python3 scripts/optimize_release_apks.py --zipalign "$ANDROID_HOME/build-tools/37.0.0/zipalign" app/build/outputs/apk/release/*-unsigned.apk'
        for workflow in (signed, unsigned):
            self.assertEqual(1, workflow.count(command))
            self.assertLess(workflow.index("--phase package"), workflow.index(command))
        self.assertLess(signed.index("--phase bundle"), signed.index(command))
        self.assertLess(signed.index(command), signed.index("- name: Sign and verify APKs and bundle"))
        self.assertLess(unsigned.index(command), unsigned.index("python3 scripts/check_apk_size.py"))
        self.assertLess(unsigned.index(command), unsigned.index("cp app/build/outputs/apk/release/*-unsigned.apk release-assets/"))
