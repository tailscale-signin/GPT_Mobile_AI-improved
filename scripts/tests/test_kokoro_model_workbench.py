"""Dependency-free artifact safety tests; actual ONNX execution is a separate CI job."""
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import warnings
import zipfile

SPEC = importlib.util.spec_from_file_location("workbench", Path(__file__).resolve().parents[1] / "kokoro_model_workbench.py")
workbench = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workbench)


def npy_bytes(shape=(1,), descriptor="<f4", payload=struct.pack("<f", 1), version=(1, 0), header=None):
    if header is None:
        header = repr({"descr": descriptor, "fortran_order": False, "shape": shape})
    encoded = (header + "\n").encode("utf-8" if version == (3, 0) else "latin1")
    width = "<H" if version == (1, 0) else "<I"
    return b"\x93NUMPY" + bytes(version) + struct.pack(width, len(encoded)) + encoded + payload


def write_npz(path, members=None, compression=zipfile.ZIP_STORED):
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(path, "w", compression=compression) as archive:
            for name, content in members if members is not None else [("x.npy", npy_bytes())]:
                archive.writestr(name, content)
    return path


class PassiveNumpyArchive:
    """No numerical implementation: used only to test admission before comparison."""
    files = ["x"]

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def __getitem__(self, name):
        if name != "x":
            raise KeyError(name)
        return SimpleNamespace(shape=(1,), nbytes=4,
                               dtype=SimpleNamespace(hasobject=False, kind="f", itemsize=4))


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

    def test_report_destination_rejects_direct_normalized_symlink_and_hardlink_aliases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.npz"
            source.write_bytes(b"original input")
            (root / "nested").mkdir()
            symbolic = root / "symbolic.json"
            symbolic.symlink_to(source)
            hard = root / "hard.json"
            os.link(source, hard)
            for destination in [source, root / "nested/../source.npz", symbolic, hard]:
                with self.subTest(destination=destination):
                    with self.assertRaisesRegex(ValueError, "alias"):
                        workbench.report_destination(destination, [source])
            self.assertEqual(b"original input", source.read_bytes())
            self.assertEqual(root / "safe.json", workbench.report_destination(root / "safe.json", [source]))

    def test_inspection_alias_is_refused_before_model_loading(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "original.onnx"
            source.write_bytes(b"original input")
            with patch.object(workbench, "load_model") as loader:
                with self.assertRaisesRegex(ValueError, "alias"):
                    workbench.main(["inspect", "--model", str(source), "--model-sha256", workbench.digest(source),
                                    "--output", str(source)])
            loader.assert_not_called()
            self.assertEqual(b"original input", source.read_bytes())

    def comparison_fixture(self, root):
        manifests = []
        archives = []
        for name, backend in [("reference", "cpu"), ("candidate", "qnn-gpu")]:
            archive = write_npz(root / (name + ".npz"))
            manifest = root / (name + ".json")
            workbench.write_json(manifest, {"schemaVersion": 1, "modelSha256": "a" * 64,
                                           "sourceModelSha256": "b" * 64, "inputSha256": "c" * 64,
                                           "traceOrder": ["x"], "backend": backend,
                                           "outputsFile": archive.name, "outputsSha256": workbench.digest(archive)})
            manifests.append(manifest)
            archives.append(archive)
        args = SimpleNamespace(reference=manifests[0], candidate=manifests[1], output=root / "comparison.json", atol=0, rtol=0)
        return args, manifests, archives

    def test_comparison_rejects_all_manifest_and_archive_report_aliases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, manifests, archives = self.comparison_fixture(root)
            originals = {path: path.read_bytes() for path in manifests + archives}
            for source in manifests + archives:
                for alias in ("direct", "symbolic", "hard"):
                    with self.subTest(source=source.name, alias=alias):
                        target = root / (source.name + "." + alias)
                        if alias == "direct":
                            target = source
                        elif alias == "symbolic":
                            target.symlink_to(source)
                        else:
                            os.link(source, target)
                        args.output = target
                        with patch.object(workbench, "read_npz") as loader:
                            with self.assertRaisesRegex(ValueError, "alias"):
                                workbench.compare(args)
                        loader.assert_not_called()
            self.assertEqual(originals, {path: path.read_bytes() for path in originals})

    def test_report_write_is_atomic_and_cleans_up_serialization_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, report = root / "input", root / "report.json"
            source.write_bytes(b"input")
            report.write_bytes(b"prior report")
            with self.assertRaises(ValueError):
                workbench.write_report(report, {"value": float("nan")}, [source])
            self.assertEqual(b"prior report", report.read_bytes())
            self.assertEqual(b"input", source.read_bytes())
            self.assertEqual([], list(root.glob(".kokoro-report-*")))
            workbench.write_report(report, {"value": 1}, [source])
            self.assertEqual({"value": 1}, json.loads(report.read_text()))

    def test_report_symlink_retarget_during_generation_preserves_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, report, alias = root / "input", root / "report.json", root / "alias.json"
            source.write_bytes(b"input")
            report.write_bytes(b"prior report")
            alias.symlink_to(report)
            original_dumps = workbench.json.dumps
            def retarget(*args, **kwargs):
                alias.unlink()
                alias.symlink_to(source)
                return original_dumps(*args, **kwargs)
            with patch.object(workbench.json, "dumps", side_effect=retarget):
                with self.assertRaisesRegex(ValueError, "alias|changed"):
                    workbench.write_report(alias, {"value": 1}, [source])
            self.assertEqual(b"input", source.read_bytes())
            self.assertEqual(b"prior report", report.read_bytes())
            self.assertEqual([], list(root.glob(".kokoro-report-*")))

    def test_npy_metadata_accepts_bounded_numeric_scalars_empty_shapes_and_versions(self):
        for version in ((1, 0), (2, 0), (3, 0)):
            for shape, descriptor, payload in [((1,), "<f4", b"\0" * 4), ((), "|b1", b"\1"),
                                                ((0, 256), "<i8", b""), ((2, 2), ">c16", b"\0" * 64)]:
                with self.subTest(version=version, shape=shape, descriptor=descriptor):
                    encoded = npy_bytes(shape, descriptor, payload, version)
                    metadata = workbench.npy_metadata(io.BytesIO(encoded), len(encoded))
                    self.assertEqual(shape, metadata["shape"])
                    self.assertEqual(len(payload), metadata["bytes"])

    def test_npy_rejects_object_structured_strings_and_invalid_numeric_widths(self):
        for descriptor in ["|O8", "|S4", "<U1", "<M8", "<f3", "<i16", ["field", "<f4"], 4]:
            with self.subTest(descriptor=descriptor):
                encoded = npy_bytes(descriptor=descriptor)
                with self.assertRaisesRegex(ValueError, "numeric"):
                    workbench.npy_metadata(io.BytesIO(encoded), len(encoded))

    def test_npy_rejects_invalid_rank_dimensions_storage_and_allocation_claims(self):
        for shape, fortran in [((-1,), False), ((True,), False), ([1], False), ((1.0,), False),
                               ((sys.maxsize + 1,), False), ((1,) * 65, False), ((1,), 1),
                               ((workbench.MAX_CAPTURE_BYTES,), False)]:
            with self.subTest(shape=shape, fortran=fortran):
                header = repr({"descr": "<f4", "fortran_order": fortran, "shape": shape})
                encoded = npy_bytes(header=header)
                with self.assertRaises(ValueError):
                    workbench.npy_metadata(io.BytesIO(encoded), len(encoded))

    def test_npy_rejects_duplicate_extra_missing_and_executable_header_fields(self):
        headers = [
            "{'descr':'<f4','descr':'<f4','shape':(1,)}",
            "{'descr':'<f4','fortran_order':False,'shape':(1,),'extra':0}",
            "{'descr':'<f4','shape':(1,)}",
            "{'descr':'<f4','fortran_order':False,'shape':__import__('os').system('false')}",
            "{'descr':'<f4','fortran_order':False,'shape':(1,),**{}}",
            "['<f4', False, (1,)]",
        ]
        for header in headers:
            with self.subTest(header=header):
                encoded = npy_bytes(header=header)
                with self.assertRaises(ValueError):
                    workbench.npy_metadata(io.BytesIO(encoded), len(encoded))

    def test_npy_rejects_magic_version_header_length_and_truncated_header(self):
        valid = npy_bytes()
        invalid = [b"notnpy", valid[:7], valid[:6] + b"\x04\x00" + valid[8:],
                   valid[:8] + b"\0", valid[:8] + struct.pack("<H", 0),
                   valid[:8] + struct.pack("<H", workbench.MAX_NPY_HEADER_BYTES + 1) + valid[10:],
                   valid[:8] + struct.pack("<H", 1000) + b"{'descr': '<f4'}\n"]
        for encoded in invalid:
            with self.subTest(encoded=encoded[:12]):
                with self.assertRaises(ValueError):
                    workbench.npy_metadata(io.BytesIO(encoded), len(encoded))

    def test_npy_payload_must_exactly_match_declared_shape_without_truncation_or_tail(self):
        for encoded in [npy_bytes(shape=(2,)), npy_bytes(payload=b"\0" * 3), npy_bytes(payload=b"\0" * 5)]:
            with self.subTest(size=len(encoded)):
                with self.assertRaisesRegex(ValueError, "payload"):
                    workbench.npy_metadata(io.BytesIO(encoded), len(encoded))
        valid = npy_bytes()
        with self.assertRaisesRegex(ValueError, "Truncated"):
            workbench.npy_metadata(io.BytesIO(valid[:-1]), len(valid))
        with self.assertRaisesRegex(ValueError, "trailing"):
            workbench.npy_metadata(io.BytesIO(valid + b"x"), len(valid))

    def test_npz_inventory_is_bounded_unique_and_unambiguous(self):
        cases = [[], [("x.npy", npy_bytes()), ("x.npy", npy_bytes())],
                 [("x.npy", npy_bytes()), ("x.npy.npy", npy_bytes())],
                 [("directory/", b"")], [("x", npy_bytes())], [(".npy", npy_bytes())],
                 [(f"tensor-{index}.npy", npy_bytes()) for index in range(65)]]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "tensor.npz"
            for members in cases:
                with self.subTest(names=[name for name, _ in members]):
                    write_npz(path, members)
                    with self.assertRaisesRegex(ValueError, "inventory"):
                        workbench.preflight_npz(path)

    def test_npz_encoded_and_expanded_byte_caps_precede_numpy_loading(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "tensor.npz"
            write_npz(path, [("x.npy", npy_bytes((1000,), payload=b"\0" * 4000))])
            loader = Mock(side_effect=AssertionError("NumPy allocation must not start"))
            with patch.dict(sys.modules, {"numpy": SimpleNamespace(load=loader)}), patch.object(workbench, "MAX_CAPTURE_BYTES", 1024):
                with self.assertRaisesRegex(ValueError, "Encoded"):
                    workbench.read_npz(path, workbench.digest(path))
            write_npz(path, [("x.npy", npy_bytes((1000,), payload=b"\0" * 4000))], zipfile.ZIP_DEFLATED)
            with patch.object(workbench, "MAX_CAPTURE_BYTES", 1024):
                self.assertLess(path.stat().st_size, 1024)
                with self.assertRaisesRegex(ValueError, "Uncompressed"):
                    workbench.preflight_npz(path)
            loader.assert_not_called()

    def test_npz_crc_corruption_is_rejected_before_tensor_allocation(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = write_npz(Path(temporary) / "tensor.npz")
            with zipfile.ZipFile(path) as archive:
                member = archive.infolist()[0]
            encoded = bytearray(path.read_bytes())
            name_length, extra_length = struct.unpack_from("<HH", encoded, member.header_offset + 26)
            offset = member.header_offset + 30 + name_length + extra_length + member.file_size - 1
            encoded[offset] ^= 0xFF
            path.write_bytes(encoded)
            loader = Mock(side_effect=AssertionError("NumPy allocation must not start"))
            with patch.dict(sys.modules, {"numpy": SimpleNamespace(load=loader)}):
                with self.assertRaisesRegex(ValueError, "archive"):
                    workbench.read_npz(path, workbench.digest(path))
            loader.assert_not_called()

    def test_snapshot_requires_explicit_digest_and_rejects_wrong_digest_before_parsing(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = write_npz(Path(temporary) / "tensor.npz")
            with patch.object(workbench, "preflight_npz") as preflight:
                for expected in [None, "invalid", "0" * 64]:
                    with self.subTest(expected=expected):
                        with self.assertRaisesRegex(ValueError, "SHA-256"):
                            workbench.read_npz(path, expected)
            preflight.assert_not_called()

    def test_malformed_header_is_rejected_before_numpy_import_or_load(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = write_npz(Path(temporary) / "tensor.npz", [("x.npy", npy_bytes((2**50,)))])
            loader = Mock(side_effect=AssertionError("NumPy allocation must not start"))
            with patch.dict(sys.modules, {"numpy": SimpleNamespace(load=loader)}):
                with self.assertRaises(ValueError):
                    workbench.read_npz(path, workbench.digest(path))
            loader.assert_not_called()

    def substitute_after_check(self, target):
        original = workbench.checked_file
        def checked(path, expected, limit=workbench.MAX_MODEL_BYTES):
            result = original(path, expected, limit)
            if result == target:
                write_npz(target, [("x.npy", npy_bytes(payload=struct.pack("<f", 9)))])
            return result
        return checked

    def test_calibration_refuses_replacement_after_its_manifest_hash_check(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case = write_npz(root / "case.npz")
            manifest = root / "calibration.json"
            workbench.write_json(manifest, {"schemaVersion": 1, "purpose": "calibration", "cases": [
                {"id": "case", "file": case.name, "sha256": workbench.digest(case)}]})
            loader = Mock(side_effect=AssertionError("Unbound bytes must not reach NumPy"))
            with patch.object(workbench, "checked_file", side_effect=self.substitute_after_check(case)), \
                    patch.dict(sys.modules, {"numpy": SimpleNamespace(load=loader)}):
                with self.assertRaisesRegex(ValueError, "snapshot SHA-256"):
                    workbench.calibration_cases(manifest, 3)
            loader.assert_not_called()

    def test_capture_refuses_replacement_before_creating_any_runtime_session(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            model = root / "model.onnx"
            model.write_bytes(b"model fixture; no session may consume it")
            case = write_npz(root / "case.npz")
            manifest = root / "manifest.json"
            workbench.write_json(manifest, {"schemaVersion": 1, "modelFile": model.name,
                                           "modelSha256": workbench.digest(model), "sourceModelSha256": workbench.digest(model)})
            args = SimpleNamespace(model_manifest=manifest, case=case, case_sha256=workbench.digest(case), output_dir=root / "capture")
            loader = Mock(side_effect=AssertionError("Unbound bytes must not reach NumPy"))
            session = Mock(side_effect=AssertionError("No runtime session is permitted"))
            with patch.object(workbench, "checked_file", side_effect=self.substitute_after_check(case)), \
                    patch.dict(sys.modules, {"numpy": SimpleNamespace(load=loader), "onnxruntime": SimpleNamespace(SessionOptions=session)}):
                with self.assertRaisesRegex(ValueError, "snapshot SHA-256"):
                    workbench.capture(args)
            loader.assert_not_called()
            session.assert_not_called()
            self.assertFalse(args.output_dir.exists())

    def test_comparison_refuses_substituted_snapshot_instead_of_publishing_false_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            args, _, archives = self.comparison_fixture(Path(temporary))
            loader = Mock(side_effect=lambda *_args, **_kwargs: PassiveNumpyArchive())
            comparison = Mock(return_value={"schemaVersion": 1, "status": "CONSISTENT"})
            with patch.object(workbench, "checked_file", side_effect=self.substitute_after_check(archives[1])), \
                    patch.dict(sys.modules, {"numpy": SimpleNamespace(load=loader)}), \
                    patch.object(workbench, "compare_arrays", comparison):
                with self.assertRaisesRegex(ValueError, "snapshot SHA-256"):
                    workbench.compare(args)
            self.assertEqual(1, loader.call_count)  # Only the still-matching reference snapshot.
            comparison.assert_not_called()
            self.assertFalse(args.output.exists())

    def test_snapshot_is_reused_if_source_path_changes_after_preflight(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = write_npz(Path(temporary) / "tensor.npz")
            expected = workbench.digest(path)
            original_preflight = workbench.preflight_npz
            def replace_after_preflight(snapshot):
                inventory = original_preflight(snapshot)
                path.write_bytes(b"replacement is not an archive")
                return inventory
            def load(snapshot, **kwargs):
                import hashlib
                self.assertEqual(expected, hashlib.sha256(snapshot.read()).hexdigest())
                self.assertFalse(kwargs["allow_pickle"])
                return PassiveNumpyArchive()
            with patch.object(workbench, "preflight_npz", side_effect=replace_after_preflight), \
                    patch.dict(sys.modules, {"numpy": SimpleNamespace(load=load)}):
                result = workbench.read_npz(path, expected)
            self.assertEqual(["x"], list(result))
            self.assertEqual(b"replacement is not an archive", path.read_bytes())

    def test_json_snapshot_hashes_exact_bytes_and_preserves_manifest_schema(self):
        import hashlib
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "manifest.json"
            encoded = b'{ "schemaVersion": 1, "name": "fixture" }\n'
            path.write_bytes(encoded)
            value, identity = workbench.read_json_snapshot(path)
            self.assertEqual({"schemaVersion": 1, "name": "fixture"}, value)
            self.assertEqual(hashlib.sha256(encoded).hexdigest(), identity)
            self.assertEqual(value, workbench.read_json(path))
            for raw in [{}, [], {"schemaVersion": 2}]:
                path.write_text(json.dumps(raw))
                with self.assertRaisesRegex(ValueError, "schema"):
                    workbench.read_json(path)
            path.write_text('{"weights": [{"axis": 0}]}')
            overrides, identity = workbench.read_json_snapshot(path, manifest=False)
            self.assertEqual({"weights": [{"axis": 0}]}, overrides)
            self.assertEqual(workbench.digest(path), identity)
            path.write_bytes(b'{"schemaVersion":1}' + b" " * (2 * 1024**2))
            with self.assertRaisesRegex(ValueError, "too large"):
                workbench.read_json_snapshot(path, manifest=False)

    def test_json_growth_before_open_is_rejected_by_one_bounded_byte_read(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "manifest.json"
            path.write_text('{"schemaVersion":1}')
            limit = 2 * 1024**2
            replacement = json.dumps({"schemaVersion": 1, "padding": "x" * (limit + 64)})
            original_open = Path.open
            requests = []
            class ObservedRead:
                def __init__(self, stream):
                    self.stream = stream

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    return self.stream.__exit__(*args)

                def read(self, size=-1):
                    requests.append(size)
                    return self.stream.read(size)

            def open_after_growth(selected, mode="r", *args, **kwargs):
                if selected == path and "r" in mode:
                    # The old stat sees the small file; the opened stream sees a
                    # real larger file, without needing a timing-sensitive race.
                    with original_open(path, "w", encoding="utf-8") as writer:
                        writer.write(replacement)
                    return ObservedRead(original_open(selected, mode, *args, **kwargs))
                return original_open(selected, mode, *args, **kwargs)

            with patch.object(Path, "open", new=open_after_growth):
                with self.assertRaisesRegex(ValueError, "too large"):
                    workbench.read_json(path)
            self.assertEqual([limit + 1], requests)


if __name__ == "__main__":
    unittest.main()
