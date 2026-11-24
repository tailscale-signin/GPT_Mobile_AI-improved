import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("candidate_builder", SCRIPTS / "build_qualcomm_dispatch_candidate.py")
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class CandidateBuildInputsTest(unittest.TestCase):
    def test_production_pins_are_independent_and_candidate_inputs_bind_sdk_headers(self):
        candidate = json.loads(builder.DEFAULT_INPUTS.read_text())
        original = copy.deepcopy(candidate)
        pins = builder.validate_inputs(candidate)
        self.assertEqual("2.52.0", pins["qairtVersion"])
        self.assertEqual(original, candidate)
        candidate["qairtSdk"]["qnnHeadersSha256"] = "0" * 64
        with self.assertRaises(ValueError):
            builder.validate_inputs(candidate)

    def test_only_verified_matching_cached_artifact_is_reused_without_network(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "artifact"
            output.write_bytes(b"fixture")
            record = {"url": "https://publisher.example/artifact", "sha256": hashlib.sha256(b"fixture").hexdigest()}
            with patch.object(builder.urllib.request, "urlopen", side_effect=AssertionError("No request expected")):
                self.assertEqual(output, builder.checked_download(record, output))
            record["sha256"] = "0" * 64
            with patch.object(builder.urllib.request, "urlopen", side_effect=OSError("fixture network failure")):
                with self.assertRaises(OSError):
                    builder.checked_download(record, output)
            self.assertFalse(output.with_suffix(".part").exists())

    def test_sdk_extraction_keeps_only_headers_and_rejects_escaping_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "sdk.zip"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("qairt/2.52/include/QNN/QnnInterface.h", "fixture header")
                package.writestr("qairt/2.52/lib/vendor.so", "not redistributed")
            builder.extract_sdk_headers(archive, root / "output", "2.52")
            self.assertTrue((root / "output/include/QNN/QnnInterface.h").is_file())
            self.assertFalse((root / "output/lib").exists())
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("qairt/2.52/include/../../outside", "bad")
            with self.assertRaises(ValueError):
                builder.extract_sdk_headers(archive, root / "other", "2.52")


if __name__ == "__main__":
    unittest.main()
