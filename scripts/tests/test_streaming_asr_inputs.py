"""Native recognition preparation must remain pinned, bounded and required by CI."""

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "local-kokoro-runtime/src/main/cpp"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


headers = load("asr_headers", NATIVE / "prepare_asr_qnn_headers.py")
dependencies = load("asr_dependencies", NATIVE / "prepare_streaming_asr_dependencies.py")
host = load("asr_host", ROOT / "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py")


class Response(io.BytesIO):
    def __init__(self, data, status=206, content_range="bytes 0-7/16"):
        super().__init__(data)
        self.status = status
        self.headers = {"Content-Range": content_range}


class StreamingAsrInputsTest(unittest.TestCase):
    def test_remote_sdk_reads_exact_ranges_and_never_downloads_the_whole_archive(self):
        data = b"abcdefghijklmnop"
        ranges = []

        def request(value, timeout):
            bounds = value.get_header("Range")
            ranges.append(bounds)
            start, stop = map(int, bounds.removeprefix("bytes=").split("-"))
            return Response(data[start:stop + 1], content_range=f"bytes {start}-{stop}/16")

        stream = headers.RemoteZip("https://example.invalid/sdk.zip", len(data))
        stream.block_size = 8
        with patch.object(headers.urllib.request, "urlopen", side_effect=request):
            stream.seek(5)
            self.assertEqual(b"fghijk", stream.read(6))
            stream.seek(7)
            self.assertEqual(b"hij", stream.read(3))
        self.assertEqual(["bytes=0-7", "bytes=8-15"], ranges)

    def test_ignored_mislabeled_or_truncated_sdk_ranges_fail(self):
        for response in (Response(b"abcdefgh", status=200), Response(b"abcdefgh", content_range="bytes 1-8/16"), Response(b"abc")):
            stream = headers.RemoteZip("https://example.invalid/sdk.zip", 16)
            stream.block_size = 8
            with self.subTest(status=response.status, headers=response.headers), patch.object(headers.urllib.request, "urlopen", return_value=response):
                with self.assertRaisesRegex(ValueError, "range"):
                    stream.read(4)

    def test_offline_qnn_headers_cannot_fall_back_to_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = root / "qnn.json"
            manifest.write_text(json.dumps({"qairtSdk": {"qnnHeadersSha256": "f" * 64}}))
            with patch.object(headers.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
                with self.assertRaisesRegex(ValueError, "missing or differ"):
                    headers.prepare(manifest, root / "headers", offline=True)

    def test_failed_dependency_download_preserves_existing_cache_bytes(self):
        good = b"verified archive"
        record = {"bytes": len(good), "sha256": hashlib.sha256(good).hexdigest(), "url": "https://example.invalid/model"}
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "archive"
            target.write_bytes(b"existing")
            for data in (b"corrupt", b"x" * (len(good) + 1)):
                with self.subTest(data=data), patch.object(dependencies.urllib.request, "urlopen", return_value=io.BytesIO(data)):
                    with self.assertRaisesRegex(ValueError, "pin"):
                        dependencies.checked_download(record, target)
                self.assertEqual(b"existing", target.read_bytes())
            with patch.object(dependencies.urllib.request, "urlopen", return_value=io.BytesIO(good)):
                dependencies.checked_download(record, target)
            with patch.object(dependencies.urllib.request, "urlopen", side_effect=AssertionError("verified cache must be reusable offline")):
                dependencies.checked_download(record, target)
            self.assertEqual(good, target.read_bytes())
            self.assertEqual([target], list(Path(temporary).iterdir()))

    def test_real_jni_fixtures_use_current_catalog_pins_and_immutable_revisions(self):
        fixtures = json.loads((ROOT / "local-kokoro-runtime/src/testHost/streaming_asr_fixtures.json").read_text())["fixtures"]
        self.assertEqual({"zipformer-en", "zipformer-fr", "nemotron-cpu"}, {fixture["id"] for fixture in fixtures})
        catalog = (ROOT / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/VoiceRecognitionModelCatalog.kt").read_text()
        for fixture in fixtures:
            self.assertTrue(fixture["expectedPrefix"].strip())
            for record in fixture["files"]:
                self.assertRegex(record["url"], r"/resolve/[0-9a-f]{40}/")
                self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
                if not record["file"].startswith("test_wavs/"):
                    self.assertIn(record["sha256"], catalog)
                    self.assertIn('"' + record["file"] + '"', catalog)

    def test_production_jni_execution_is_part_of_the_required_native_gate(self):
        driver = (ROOT / "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py").read_text()
        self.assertIn('"dev.melo.kokoro.StreamingAsrNativeIntegration"', driver)
        self.assertIn('results[label] = run(label,', driver)
        self.assertIn('"asr_fixture_sha256": asr_input_pins', driver)
        self.assertIn("all(code == 0 for code in results.values())", driver)
        fixture_java = (ROOT / "local-kokoro-runtime/src/test/java/dev/melo/kokoro/StreamingAsrNativeIntegration.java").read_text()
        self.assertIn("model.accept(", fixture_java)
        self.assertIn("Arrays.equals(batched.timestampsSeconds, streamed.timestampsSeconds)", fixture_java)
        self.assertIn("CPU_HOST_PRODUCTION_JNI_REGRESSION_ONLY", fixture_java)

    def test_unknown_or_insufficient_host_memory_fails_admission_without_qualifying_the_model(self):
        fixture = {"files": [{"file": "encoder.onnx", "bytes": 682_000_000}, {"file": "test_wavs/en.wav", "bytes": 200_000}]}
        unknown = host.asr_memory_admission(fixture, "")
        low = host.asr_memory_admission(fixture, "MemAvailable: 1900000 kB\n")
        sufficient = host.asr_memory_admission(fixture, "MemAvailable: 6000000 kB\n")
        self.assertFalse(unknown["admitted"])
        self.assertFalse(low["admitted"])
        self.assertTrue(sufficient["admitted"])
        self.assertFalse(sufficient["physicalDeviceQualified"])
        self.assertFalse(sufficient["isMeasuredModelRss"])
        driver = (ROOT / "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py").read_text()
        self.assertIn("results[label] = 78", driver)


if __name__ == "__main__":
    unittest.main()
