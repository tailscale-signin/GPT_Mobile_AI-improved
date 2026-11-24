"""Actual ONNX preparation, calibration and tensor capture on a tiny synthetic fixture.

These fixtures prove tool behavior, never Kokoro listening or phone qualification.
"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

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
        values = workbench.read_npz(archive)
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


if __name__ == "__main__":
    unittest.main()
