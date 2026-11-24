"""Actual ONNX preparation, calibration and tensor capture on a tiny synthetic fixture.

These fixtures prove tool behavior, never Kokoro listening or phone qualification.
"""
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("workbench", ROOT / "scripts/kokoro_model_workbench.py")
workbench = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workbench)


class ModelWorkbenchExecutionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.model = self.root / "original.onnx"
        graph = helper.make_graph([
            helper.make_node("Cast", ["tokens"], ["float_tokens"], to=TensorProto.FLOAT),
            helper.make_node("Mul", ["float_tokens", "weights"], ["scaled"]),
            helper.make_node("Sigmoid", ["scaled"], ["audio"])
        ], "synthetic-tool-fixture", [
            helper.make_tensor_value_info("tokens", TensorProto.INT64, [1, "tokens"]),
            helper.make_tensor_value_info("style", TensorProto.FLOAT, [1, 256]),
            helper.make_tensor_value_info("speed", TensorProto.FLOAT, [1])
        ], [helper.make_tensor_value_info("audio", TensorProto.FLOAT, [1, "tokens"])],
            [numpy_helper.from_array(np.array([.25], dtype=np.float32), "weights")])
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 21)], ir_version=10)
        helper.set_model_props(model, {"sample_rate": "24000", "model_type": "synthetic-test", "style_dim": "256"})
        onnx.save(model, self.model)
        self.model_hash = workbench.digest(self.model)
        self.case = self.root / "fixture.npz"
        np.savez(self.case, tokens=np.array([[1, 2, 3]], dtype=np.int64),
                 style=np.zeros((1, 256), dtype=np.float32), speed=np.array([1], dtype=np.float32))

    def tearDown(self):
        self.temporary.cleanup()

    def run_model(self, command, output, *args):
        workbench.main([command, "--model", str(self.model), "--model-sha256", self.model_hash,
                        "--output-dir", str(output), *args])
        return workbench.read_json(output / "manifest.json")

    def test_prepare_preserves_metadata_and_exact_shape_without_claiming_admission(self):
        output = self.root / "prepared"
        manifest = self.run_model("prepare", output, "--backend", "gpu", "--precision", "fp32", "--token-count", "3")
        model = onnx.load(output / "model.onnx")
        self.assertEqual([1, 3], [d.dim_value for d in model.graph.input[0].type.tensor_type.shape.dim])
        self.assertEqual("24000", {v.key: v.value for v in model.metadata_props}["sample_rate"])
        self.assertFalse(manifest["paddingApplied"])
        self.assertFalse(manifest["physicalDeviceQualified"])
        self.assertEqual(self.model_hash, workbench.digest(self.model))

    def test_duration_trace_follows_the_prediction_branch_before_the_waveform(self):
        model = onnx.load(self.model)
        model.graph.node.extend([helper.make_node("Round", ["scaled"], ["rounded"]),
                                 helper.make_node("Cast", ["rounded"], ["duration"], to=TensorProto.INT64)])
        model.graph.output.extend([helper.make_tensor_value_info("duration", TensorProto.INT64, [1, "tokens"])])
        onnx.save(model, self.model)
        self.model_hash = workbench.digest(self.model)
        traced = self.run_model("trace", self.root / "duration-checkpoints", "--duration-path")
        self.assertEqual(["float_tokens", "rounded", "duration", "audio"], traced["traceOrder"])
        self.assertTrue(traced["instrumentationMayChangePartitions"])

    def test_real_cpu_capture_compares_identical_graph_and_detects_first_divergence(self):
        trace = self.root / "trace"
        manifest = self.run_model("trace", trace, "--tensor", "scaled", "--tensor", "audio")
        self.assertEqual(["scaled", "audio"], manifest["traceOrder"])
        captures = []
        for name in ("reference", "candidate"):
            directory = self.root / name
            workbench.main(["capture", "--model-manifest", str(trace / "manifest.json"),
                            "--case", str(self.case), "--case-sha256", workbench.digest(self.case),
                            "--backend", "cpu", "--output-dir", str(directory)])
            captures.append(directory / "manifest.json")
        report = self.root / "comparison.json"
        arguments = ["compare", "--reference", str(captures[0]), "--candidate", str(captures[1]), "--output", str(report)]
        workbench.main(arguments)
        self.assertEqual("CONSISTENT", workbench.read_json(report)["status"])
        candidate = workbench.read_json(captures[1])
        archive = captures[1].parent / candidate["outputsFile"]
        values = workbench.read_npz(archive, workbench.digest(archive))
        values["scaled"][0, 1] += 1
        np.savez(archive, **values)
        candidate["outputsSha256"] = workbench.digest(archive)
        workbench.write_json(captures[1], candidate)
        workbench.main(arguments)
        self.assertEqual("scaled", workbench.read_json(report)["firstObservedDivergence"])
        candidate["modelSha256"] = "0" * 64
        workbench.write_json(captures[1], candidate)
        with self.assertRaisesRegex(ValueError, "must match"):
            workbench.main(arguments)

    def test_quantization_requires_real_inputs_and_executes_with_fixture_calibration(self):
        output = self.root / "quantized"
        with self.assertRaisesRegex(ValueError, "calibration corpus"):
            self.run_model("prepare", output, "--backend", "htp", "--precision", "a16w8", "--token-count", "3")
        self.assertFalse(output.exists())
        corpus = self.root / "corpus.json"
        workbench.write_json(corpus, {"schemaVersion": 1, "purpose": "calibration", "cases": [
            {"id": "synthetic-test-only", "file": self.case.name, "sha256": workbench.digest(self.case)}]})
        manifest = self.run_model("prepare", output, "--backend", "htp", "--precision", "a16w8", "--token-count", "3", "--calibration", str(corpus))
        model = onnx.load(output / "model.onnx")
        self.assertIn("QuantizeLinear", {node.op_type for node in model.graph.node})
        self.assertEqual(1, manifest["calibration"]["cases"])
        with self.assertRaisesRegex(ValueError, "original FP32"):
            workbench.assert_original_float32(model)

    def test_stage_extraction_is_explicit_and_preserves_metadata(self):
        spec = self.root / "stages.json"
        workbench.write_json(spec, {"schemaVersion": 1, "sourceModelSha256": self.model_hash,
                                   "stages": [{"id": "decoder-fixture", "inputs": ["scaled"], "outputs": ["audio"]}]})
        output = self.root / "stages"
        manifest = self.run_model("stages", output, "--specification", str(spec))
        extracted = onnx.load(output / "decoder-fixture.onnx")
        self.assertEqual(["Sigmoid"], [node.op_type for node in extracted.graph.node])
        self.assertFalse(manifest["recompositionValidated"])
        self.assertEqual("24000", {v.key: v.value for v in extracted.metadata_props}["sample_rate"])

    def test_comparison_fails_for_nonfinite_shapes_types_and_vacuous_order(self):
        reference = {"x": np.array([1], dtype=np.float32)}
        for candidate, reason in ((np.array([np.nan], np.float32), "NONFINITE"),
                                  (np.array([[1]], np.float32), "SHAPE_MISMATCH"),
                                  (np.array([1], np.float64), "TYPE_MISMATCH")):
            report = workbench.compare_arrays(reference, {"x": candidate}, ["x"], 0, 0)
            self.assertEqual("MISMATCH", report["status"])
            self.assertEqual(reason, report["tensors"][0]["reason"])
        with self.assertRaises(ValueError):
            workbench.compare_arrays(reference, reference, [], 0, 0)

    def test_numpy_round_trips_numeric_types_scalars_empty_shapes_and_fortran_order(self):
        arrays = {"bool": np.array([True, False], dtype=np.bool_),
                  "signed": np.array([-2, 3], dtype=np.int64),
                  "unsigned": np.array([0, 255], dtype=np.uint8),
                  "half": np.array([.5, 1], dtype=np.float16),
                  "single": np.array([.5, 1], dtype=np.float32),
                  "double": np.array([.5, 1], dtype=np.float64),
                  "complex": np.array([1 + 2j], dtype=np.complex128),
                  "scalar": np.array(7, dtype=np.int32),
                  "empty": np.zeros((0, 256), dtype=np.float32),
                  "fortran": np.asfortranarray(np.arange(6, dtype=np.float32).reshape(2, 3))}
        archive = self.root / "numeric.npz"
        np.savez(archive, **arrays)
        restored = workbench.read_npz(archive, workbench.digest(archive))
        self.assertEqual(list(arrays), list(restored))
        for name, expected in arrays.items():
            self.assertEqual(expected.dtype, restored[name].dtype)
            self.assertEqual(expected.shape, restored[name].shape)
            np.testing.assert_array_equal(expected, restored[name])
        self.assertTrue(restored["fortran"].flags.f_contiguous)

    def test_numpy_load_uses_the_validated_private_snapshot_after_source_replacement(self):
        archive = self.root / "snapshot.npz"
        original = np.array([1, 2, 3], dtype=np.float32)
        np.savez(archive, x=original)
        identity = workbench.digest(archive)
        preflight = workbench.preflight_npz
        def replace_path(snapshot):
            inventory = preflight(snapshot)
            np.savez(archive, x=np.array([99], dtype=np.float32))
            return inventory
        with patch.object(workbench, "preflight_npz", side_effect=replace_path):
            restored = workbench.read_npz(archive, identity)
        self.assertNotEqual(identity, workbench.digest(archive))
        np.testing.assert_array_equal(original, restored["x"])

    def test_real_numpy_local_zip64_archive_passes_directory_guard_and_round_trip(self):
        expected = np.arange(12, dtype=np.float32).reshape(3, 4)
        archive = self.root / "numpy-local-zip64.npz"
        for writer in [np.savez, np.savez_compressed]:
            with self.subTest(writer=writer.__name__):
                writer(archive, x=expected)
                encoded = archive.read_bytes()
                self.assertEqual(b"PK\x03\x04", encoded[:4])
                self.assertEqual((0xffffffff, 0xffffffff), struct.unpack_from("<II", encoded, 18))
                name_size, extra_size = struct.unpack_from("<HH", encoded, 26)
                self.assertGreaterEqual(extra_size, 20)
                self.assertEqual(1, struct.unpack_from("<H", encoded, 30 + name_size)[0])
                restored = workbench.read_npz(archive, workbench.digest(archive))
                np.testing.assert_array_equal(expected, restored["x"])
                self.assertEqual(expected.dtype, restored["x"].dtype)

    def test_malformed_npy_never_reaches_the_real_numpy_loader(self):
        for shape, descriptor in [((2**50,), "<f4"), ((1,), "|O8"), ((2,), "<f4")]:
            with self.subTest(shape=shape, descriptor=descriptor):
                member = io.BytesIO()
                np.lib.format.write_array_header_1_0(member, {"descr": descriptor, "fortran_order": False, "shape": shape})
                member.write(b"\0" * 4)
                archive = self.root / "invalid.npz"
                with zipfile.ZipFile(archive, "w") as package:
                    package.writestr("x.npy", member.getvalue())
                with patch.object(np, "load", wraps=np.load) as loader:
                    with self.assertRaises(ValueError):
                        workbench.read_npz(archive, workbench.digest(archive))
                loader.assert_not_called()

    def test_compare_binds_parsed_manifest_hashes_when_paths_change_during_tensor_loading(self):
        manifests = []
        for name in ("reference", "candidate"):
            archive = self.root / (name + ".npz")
            np.savez(archive, x=np.array([1, 2], dtype=np.float32))
            manifest = self.root / (name + ".json")
            workbench.write_json(manifest, {"schemaVersion": 1, "modelSha256": self.model_hash,
                                           "sourceModelSha256": self.model_hash,
                                           "inputSha256": workbench.digest(self.case), "traceOrder": ["x"],
                                           "backend": "cpu", "outputsFile": archive.name,
                                           "outputsSha256": workbench.digest(archive)})
            manifests.append(manifest)
        identities = [workbench.digest(path) for path in manifests]
        read_npz = workbench.read_npz
        def replace_manifests(path, expected):
            for manifest in manifests:
                workbench.write_json(manifest, {"schemaVersion": 1, "changedAfterParsing": True})
            return read_npz(path, expected)
        report = self.root / "manifest-comparison.json"
        with patch.object(workbench, "read_npz", side_effect=replace_manifests):
            workbench.main(["compare", "--reference", str(manifests[0]), "--candidate", str(manifests[1]),
                            "--output", str(report)])
        result = workbench.read_json(report)
        self.assertEqual("CONSISTENT", result["status"])
        self.assertEqual(identities, [result["referenceManifestSha256"], result["candidateManifestSha256"]])
        self.assertTrue(all(workbench.digest(path) != expected for path, expected in zip(manifests, identities)))

    def test_prepare_binds_used_calibration_and_override_snapshots_during_quantization(self):
        from onnxruntime.quantization.execution_providers import qnn
        corpus, overrides = self.root / "corpus.json", self.root / "overrides.json"
        workbench.write_json(corpus, {"schemaVersion": 1, "purpose": "calibration", "cases": [
            {"id": "snapshot-fixture", "file": self.case.name, "sha256": workbench.digest(self.case)}]})
        overrides.write_text("{}\n")
        corpus_hash, overrides_hash = workbench.digest(corpus), workbench.digest(overrides)
        self.assertEqual(1, len(workbench.calibration_cases(corpus, 3)))
        original_config = qnn.get_qnn_qdq_config
        def replace_inputs(*args, **kwargs):
            self.assertEqual({}, kwargs["init_overrides"])
            workbench.write_json(corpus, {"schemaVersion": 1, "purpose": "calibration", "cases": []})
            overrides.write_text('{"replacement-not-used": []}')
            return original_config(*args, **kwargs)
        with patch.object(qnn, "get_qnn_qdq_config", side_effect=replace_inputs) as configure:
            result = self.run_model("prepare", self.root / "snapshot-quantized", "--backend", "htp", "--precision", "a16w8",
                                    "--token-count", "3", "--calibration", str(corpus), "--overrides", str(overrides))
        self.assertEqual(1, configure.call_count)
        self.assertEqual(corpus_hash, result["calibration"]["manifestSha256"])
        self.assertEqual(overrides_hash, result["calibration"]["overridesSha256"])
        self.assertEqual(1, result["calibration"]["cases"])
        self.assertNotEqual(corpus_hash, workbench.digest(corpus))
        self.assertNotEqual(overrides_hash, workbench.digest(overrides))

    def test_stage_extraction_binds_the_used_specification_snapshot(self):
        specification = self.root / "stages.json"
        workbench.write_json(specification, {"schemaVersion": 1, "sourceModelSha256": self.model_hash,
                                            "stages": [{"id": "decoder-fixture", "inputs": ["scaled"], "outputs": ["audio"]}]})
        identity = workbench.digest(specification)
        extract_model = onnx.utils.extract_model
        def replace_specification(*args, **kwargs):
            workbench.write_json(specification, {"schemaVersion": 1, "sourceModelSha256": "0" * 64, "stages": []})
            return extract_model(*args, **kwargs)
        with patch.object(onnx.utils, "extract_model", side_effect=replace_specification):
            result = self.run_model("stages", self.root / "snapshot-stage", "--specification", str(specification))
        self.assertEqual(identity, result["specificationSha256"])
        self.assertEqual(["decoder-fixture"], [stage["id"] for stage in result["stages"]])
        self.assertNotEqual(identity, workbench.digest(specification))


if __name__ == "__main__":
    unittest.main()
