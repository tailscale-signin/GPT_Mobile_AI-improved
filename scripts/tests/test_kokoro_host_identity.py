"""Historical host libraries must not acquire a newer application runtime label."""
import base64
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("profile_host", ROOT / "local-kokoro-runtime/src/testHost/kokoro_native_profile_lifecycle.py")
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)


class HostIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.qnn = self.root / "onnxruntime_qnn"
        self.qnn.mkdir()
        self.java = self.root / "TimedKokoro.java"

    def application(self, version="2.7", qairt="2.51"):
        self.java.write_text(f'public static final String RUNTIME_ID = "ort1.28.2-qnn{version}-qairt{qairt}-fixture";\n')

    def files(self, version="2.7.0", qairt="2.51.0"):
        (self.qnn / "build_and_package_info.py").write_text(
            f"package_name = 'onnxruntime-qnn'\n__version__ = '{version}'\nqnn_version = '{qairt}'\n")
        for name in ("libonnxruntime_providers_qnn.so", "libQnnHtp.so", "libQnnSystem.so", "libHtpPrepare.so"):
            (self.qnn / name).write_bytes(("fixture " + name).encode())

    def installed(self, actual_version="2.7.0", actual_qairt="2.51.0"):
        self.files(actual_version, actual_qairt)
        distribution = self.root / "onnxruntime_qnn-2.7.0.dist-info"
        distribution.mkdir()
        (distribution / "METADATA").write_text("Name: onnxruntime-qnn\nVersion: 2.7.0\n")
        runtime = self.root / "onnxruntime_qnn.libs"
        runtime.mkdir()
        (runtime / "libc++.so.1").write_bytes(b"fixture C++ runtime")
        with (distribution / "RECORD").open("w") as stream:
            writer = csv.writer(stream)
            for path in sorted(self.root.rglob("*")):
                if path.is_file() and path.name not in ("RECORD", "TimedKokoro.java"):
                    encoded = base64.urlsafe_b64encode(hashlib.sha256(path.read_bytes()).digest()).rstrip(b"=").decode()
                    writer.writerow([path.relative_to(self.root).as_posix(), "sha256=" + encoded, path.stat().st_size])

    def test_historical_matching_source_retains_its_actual_versions(self):
        self.application()
        self.installed()
        identity, files, directories = host.checked_provider_inputs(self.qnn, "2.7.0", self.java)
        self.assertEqual("2.7.0", identity["hostQnnPluginVersion"])
        self.assertEqual("2.51.0", identity["hostQairtVersion"])
        self.assertEqual("X86_CPU_EMULATOR_ONLY", identity["executionScope"])
        self.assertIn("onnxruntime_qnn.libs/libc++.so.1", files)
        self.assertEqual([self.root / "onnxruntime_qnn.libs"], directories)

    def test_old_host_cannot_be_labeled_as_current_application(self):
        self.application("2.8", "2.52")
        self.installed()
        with self.assertRaisesRegex(ValueError, "Application RUNTIME_ID"):
            host.checked_provider_inputs(self.qnn, "2.7.0", self.java)

    def test_package_metadata_and_requested_version_must_match(self):
        self.application()
        self.installed(actual_version="2.8.0", actual_qairt="2.52.0")
        with self.assertRaisesRegex(ValueError, "Actual QNN build metadata"):
            host.checked_provider_inputs(self.qnn, "2.7.0", self.java)
        with self.assertRaisesRegex(ValueError, "installed wheel"):
            host.checked_provider_inputs(self.qnn, "2.8.0", self.java)

    def test_library_corruption_is_rejected(self):
        self.application()
        self.installed()
        (self.qnn / "libQnnHtp.so").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "differs from RECORD"):
            host.checked_provider_inputs(self.qnn, "2.7.0", self.java)

    def test_audited_flat_current_package_is_accepted_and_tampering_rejected(self):
        self.application("2.8", "2.52")
        self.files("2.8.0", "2.52.0")
        (self.qnn / "libc++.so.1").write_bytes(b"fixture C++ runtime")
        manifest = {"files": {p.name: {"bytes": p.stat().st_size, "sha256": host.digest(p)} for p in self.qnn.iterdir()}}
        audited = self.root / "audited.json"
        audited.write_text(json.dumps(manifest))
        publisher = self.qnn / "publisher-manifest.json"
        publisher.write_bytes(audited.read_bytes())
        with patch.dict(host.HOST_MANIFESTS, {"2.8.0": audited}):
            identity, files, _ = host.checked_provider_inputs(self.qnn, "2.8.0", self.java)
            self.assertEqual("2.52.0", identity["hostQairtVersion"])
            self.assertEqual("AUDITED_PUBLISHER_MANIFEST", identity["inputIdentity"]["kind"])
            self.assertIn("libc++.so.1", files)
            publisher.write_text(publisher.read_text() + " ")
            with self.assertRaisesRegex(ValueError, "publisher manifest differs"):
                host.checked_provider_inputs(self.qnn, "2.8.0", self.java)


if __name__ == "__main__":
    unittest.main()
