"""Full-model CI preparation rejects ambiguous, incomplete and unpinned inputs."""
import hashlib
import io
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_kokoro_native_host as host


class NativeHostInputsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "output"
        self.output.mkdir()

    def model(self, entries, expected=None):
        archive = self.root / "model.tar.bz2"
        with tarfile.open(archive, "w:bz2") as package:
            for name, value, kind in entries:
                entry = tarfile.TarInfo(name)
                entry.type = kind
                entry.size = len(value) if kind == tarfile.REGTYPE else 0
                entry.linkname = "elsewhere"
                package.addfile(entry, io.BytesIO(value) if kind == tarfile.REGTYPE else None)
        record = {"model.int8.onnx": {"bytes": 4, "sha256": hashlib.sha256(b"ONNX").hexdigest()}}
        host.extract_model(archive, self.output, expected or record)

    def ort(self, *, version="1.28.2", omit=None, extra=()):
        archive = self.root / "ort.zip"
        names = ["VERSION_NUMBER", "include/onnxruntime_c_api.h", "include/onnxruntime_cxx_api.h",
                 "include/onnxruntime_cxx_inline.h", "include/onnxruntime_ep_c_api.h", "lib/libonnxruntime.so"]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(archive, "w") as package:
                for name in names:
                    if name != omit:
                        package.writestr(host.ORT_ROOT + "/" + name, version if name == "VERSION_NUMBER" else "fixture")
                for name, mode in extra:
                    member = zipfile.ZipInfo(name)
                    member.external_attr = mode << 16
                    package.writestr(member, "fixture")
        host.extract_ort(archive, self.output)

    def test_pinned_model_extracts_only_selected_regular_files(self):
        self.model([(host.MODEL_ROOT + "/model.int8.onnx", b"ONNX", tarfile.REGTYPE),
                    (host.MODEL_ROOT + "/unused.onnx", b"ignored", tarfile.REGTYPE)])
        self.assertEqual(["model.int8.onnx"], [p.name for p in self.output.iterdir()])
        self.assertEqual(b"ONNX", (self.output / "model.int8.onnx").read_bytes())

    def test_corrupt_or_incomplete_model_cannot_become_prepared_inputs(self):
        with self.assertRaisesRegex(ValueError, "production pin"):
            self.model([(host.MODEL_ROOT + "/model.int8.onnx", b"BAD!", tarfile.REGTYPE)])
        with self.assertRaisesRegex(ValueError, "incomplete"):
            self.model([])

    def test_model_duplicate_link_and_traversal_are_rejected(self):
        valid = (host.MODEL_ROOT + "/model.int8.onnx", b"ONNX", tarfile.REGTYPE)
        for entries in ([valid, valid], [(valid[0], b"", tarfile.SYMTYPE)], [("../model.int8.onnx", b"ONNX", tarfile.REGTYPE)]):
            with self.subTest(entries=entries), tempfile.TemporaryDirectory(dir=self.root) as output:
                self.output = Path(output)
                with self.assertRaisesRegex(ValueError, "Invalid or duplicate|Unsafe"):
                    self.model(entries)

    def test_ort_requires_real_header_library_and_matching_version_inventory(self):
        self.ort()
        self.assertEqual("1.28.2", (self.output / "VERSION_NUMBER").read_text())
        for options in ({"omit": "lib/libonnxruntime.so"}, {"version": "1.28.0"}):
            with self.subTest(options=options), tempfile.TemporaryDirectory(dir=self.root) as output:
                self.output = Path(output)
                with self.assertRaisesRegex(ValueError, "incomplete or mismatched"):
                    self.ort(**options)

    def test_ort_duplicate_symlink_and_path_traversal_are_rejected(self):
        entries = [(host.ORT_ROOT + "/lib/libonnxruntime.so", stat.S_IFREG),
                   (host.ORT_ROOT + "/include/linked.h", stat.S_IFLNK),
                   (host.ORT_ROOT + "/../escape", stat.S_IFREG)]
        for entry in entries:
            with self.subTest(entry=entry), tempfile.TemporaryDirectory(dir=self.root) as output:
                self.output = Path(output)
                with self.assertRaisesRegex(ValueError, "Invalid or duplicate|Unsafe"):
                    self.ort(extra=[entry])

    def test_failed_download_does_not_publish_partial_input_directory(self):
        destination = self.root / "prepared"
        def fail(record, path):
            path.write_bytes(b"partial")
            raise ValueError("download mismatch")
        with patch.object(host, "checked_download", side_effect=fail):
            with self.assertRaisesRegex(ValueError, "download mismatch"):
                host.prepare(destination)
        self.assertFalse(destination.exists())
        self.assertFalse(list(self.root.glob(".kokoro-native-*")))

    def test_host_pins_remain_the_actual_production_native_and_model_inputs(self):
        cmake = (host.ROOT / "local-kokoro-runtime/src/main/cpp/CMakeLists.txt").read_text()
        for record in host.DEPENDENCIES:
            for key in ("url", "file", "sha256"):
                self.assertIn(record[key], cmake)
        model_source = (host.ROOT / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/KokoroModelPack.kt").read_text()
        variant_source = (host.ROOT / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/KokoroModelVariant.kt").read_text()
        self.assertIn(host.MODEL["sha256"], variant_source)
        for record in host.MODEL_FILES.values():
            self.assertIn(record["sha256"], model_source)

    def test_full_native_context_execution_is_a_required_workflow_job(self):
        workflow = (host.ROOT / ".github/workflows/speech-engine-smoke.yml").read_text()
        job = workflow.split("  native_context:\n", 1)[1].split("  checkpoint:\n", 1)[0]
        self.assertIn("kokoro_native_profile_lifecycle.py", job)
        self.assertIn("--qnn-version 2.8.0", job)
        self.assertIn("--pack", job)
        self.assertNotIn("continue-on-error", job)
        self.assertNotIn("    if:", job.split("    steps:", 1)[0])
        runner = (host.ROOT / "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py").read_text()
        self.assertIn('results["context_roundtrip"] = run(', runner)
        self.assertIn('"dev.melo.kokoro.KokoroNativeContextIntegration"', runner)
        self.assertIn("all(code == 0 for code in results.values())", runner)


if __name__ == "__main__":
    unittest.main()
