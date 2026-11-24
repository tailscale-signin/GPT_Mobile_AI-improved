"""Dependency-free artifact safety tests; actual ONNX execution is a separate CI job."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("workbench", Path(__file__).resolve().parents[1] / "kokoro_model_workbench.py")
workbench = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workbench)


class ModelArtifactTest(unittest.TestCase):
    def test_hash_and_size_bind_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "model"
            path.write_bytes(b"model fixture")
            expected = workbench.digest(path)
            self.assertEqual(path, workbench.checked_file(path, expected))
            for identity, limit in [("0" * 64, 100), (expected, 2), ("invalid", 100)]:
                with self.assertRaises(ValueError):
                    workbench.checked_file(path, identity, limit)

    def test_manifest_cannot_escape_through_parent_absolute_or_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inside = root / "inside"
            inside.mkdir()
            (inside / "link").symlink_to(root, target_is_directory=True)
            for name in ("../model", str(root / "model"), "link/model", "."):
                with self.assertRaises(ValueError):
                    workbench.local_file(inside, name)
            self.assertEqual(inside / "model", workbench.local_file(inside, "model"))

    def test_failed_generation_never_publishes_partial_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "result"
            def fail(directory):
                (directory / "partial").write_text("not a model")
                raise RuntimeError("fixture failure")
            with self.assertRaises(RuntimeError):
                workbench.new_artifact(output, fail)
            self.assertFalse(output.exists())
            self.assertEqual([], list(output.parent.iterdir()))

    def test_artifact_cannot_self_declare_admission_or_overwrite(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "result"
            manifest = workbench.new_artifact(output, lambda _: {"physicalDeviceQualified": True, "playbackAdmission": "GRANTED"})
            value = workbench.read_json(manifest)
            self.assertFalse(value["physicalDeviceQualified"])
            self.assertFalse(value["listeningQualified"])
            self.assertEqual("NOT_GRANTED", value["playbackAdmission"])
            with self.assertRaises(ValueError):
                workbench.new_artifact(output, lambda _: {})


if __name__ == "__main__":
    unittest.main()
