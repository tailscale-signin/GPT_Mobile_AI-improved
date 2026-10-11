"""Regression checks for stale native artifacts and false source qualification."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("qnn_provenance", ROOT / "scripts/check_qnn_provenance.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ProvenanceTest(unittest.TestCase):
    def check_mutation(self, mutation, allow=False):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            paths = {}
            for name in ("VERSIONS", "MANIFEST", "PROVENANCE", "DISPATCH", "INPUTS"):
                original = getattr(checker, name)
                target = temporary / original.name
                target.write_bytes(original.read_bytes())
                paths[name] = target
            mutation(paths)
            with patch.multiple(checker, **paths), patch.object(sys, "argv", ["check"] + (["--allow-unqualified"] if allow else [])), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return checker.main()

    def test_current_binary_matches_release_inputs(self):
        self.assertEqual(0, self.check_mutation(lambda paths: None))

    def test_future_dependency_upgrade_requires_native_rebuild(self):
        def mutate(paths):
            paths["VERSIONS"].write_text(paths["VERSIONS"].read_text().replace('litertlm = "0.18.0"', 'litertlm = "0.19.0"'))
        self.assertEqual(1, self.check_mutation(mutate, allow=True))

    def test_editing_audit_alone_cannot_qualify_stale_build(self):
        def mutate(paths):
            manifest = json.loads(paths["MANIFEST"].read_text())
            manifest["dispatchBuild"]["litertRevision"] = "9fe5be45564c868408e6514c8aabb83e211a0911"
            paths["MANIFEST"].write_text(json.dumps(manifest))
        self.assertEqual(1, self.check_mutation(mutate))
        self.assertEqual(0, self.check_mutation(mutate, allow=True))

    def test_qnn_upgrade_requires_matching_dispatch_inputs(self):
        def mutate(paths):
            paths["VERSIONS"].write_text(paths["VERSIONS"].read_text().replace('qnn = "2.51.0"', 'qnn = "2.52.0"'))
        self.assertEqual(1, self.check_mutation(mutate, allow=True))

    def test_mixed_qnn_distribution_is_rejected(self):
        def mutate(paths):
            manifest = json.loads(paths["MANIFEST"].read_text())
            manifest["libraries"]["lib/arm64-v8a/libQnnSystem.so"]["source"] = "com.qualcomm.qti:qnn-runtime:2.50.0"
            paths["MANIFEST"].write_text(json.dumps(manifest))
        self.assertEqual(1, self.check_mutation(mutate, allow=True))

    def test_corrupt_binary_is_rejected_even_in_source_only_mode(self):
        def mutate(paths):
            binary = bytearray(paths["DISPATCH"].read_bytes())
            binary[-1] ^= 1
            paths["DISPATCH"].write_bytes(binary)
        self.assertEqual(1, self.check_mutation(mutate, allow=True))

    def test_stale_markdown_hash_is_rejected(self):
        def mutate(paths):
            manifest = json.loads(paths["MANIFEST"].read_text())
            digest = manifest["libraries"]["lib/arm64-v8a/libLiteRtDispatch_Qualcomm.so"]["sha256"]
            paths["PROVENANCE"].write_text(paths["PROVENANCE"].read_text().replace(digest, "0" * 64))
        self.assertEqual(1, self.check_mutation(mutate, allow=True))


if __name__ == "__main__":
    unittest.main()
