import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("apk_size", Path(__file__).resolve().parents[1] / "check_apk_size.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ApkSizeTest(unittest.TestCase):
    def test_abi_budget_and_contributions_come_from_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "arbitrary-name.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("lib/arm64-v8a/runtime.so", b"x" * 1024)
            report = module.inspect(path)
            self.assertEqual("arm64-v8a", report["budget_class"])
            self.assertEqual(path.stat().st_size, report["bytes"])
            self.assertEqual("lib/arm64-v8a/runtime.so", report["largest_entries"][0]["path"])
            self.assertTrue(report["passed"])

    def test_exceeded_budget_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "universal.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"data")
            previous = module.BUDGETS["universal"]
            try:
                module.BUDGETS["universal"] = 1
                self.assertFalse(module.inspect(path)["passed"])
            finally:
                module.BUDGETS["universal"] = previous

    def test_debug_profile_does_not_relax_release_default(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app-debug.apk"
            # Sparse padding models a large artifact without allocating its payload.
            with path.open("wb") as stream:
                stream.truncate(190 * 1024 * 1024)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("lib/arm64-v8a/runtime.so", b"native")
            self.assertFalse(module.inspect(path)["passed"])
            self.assertEqual("release", module.inspect(path)["build_type"])
            self.assertTrue(module.inspect(path, "debug")["passed"])

    def test_debug_limits_accept_measured_baseline_for_every_abi(self):
        with tempfile.TemporaryDirectory() as directory:
            for abis, measured in [(["arm64-v8a"], 314968509),
                                   (["x86_64"], 244103214),
                                   (["arm64-v8a", "x86_64"], 380703034)]:
                with self.subTest(abis=abis):
                    path = Path(directory) / "arbitrary.apk"
                    with path.open("wb") as stream:
                        stream.truncate(measured)
                    with zipfile.ZipFile(path, "a") as archive:
                        for abi in abis:
                            archive.writestr(f"lib/{abi}/runtime.so", b"native")
                    self.assertTrue(module.inspect(path, "debug")["passed"])
                    self.assertFalse(module.inspect(path, "release")["passed"])

    def test_each_profile_enforces_its_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "universal.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"data")
            size = path.stat().st_size
            for profile, budgets in module.BUDGET_PROFILES.items():
                with self.subTest(profile=profile):
                    with patch.dict(budgets, {"universal": size}):
                        self.assertTrue(module.inspect(path, profile)["passed"])
                    with patch.dict(budgets, {"universal": size - 1}):
                        self.assertFalse(module.inspect(path, profile)["passed"])

    def test_cli_reports_failed_release_and_explicit_debug_success(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app.apk"
            report = Path(directory) / "report.json"
            with path.open("wb") as stream:
                stream.truncate(190 * 1024 * 1024)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("lib/arm64-v8a/runtime.so", b"native")
            command = [sys.executable, str(Path(module.__file__)), str(path), "--report", str(report)]
            self.assertEqual(1, subprocess.run(command, capture_output=True).returncode)
            self.assertFalse(json.loads(report.read_text())["artifacts"][0]["passed"])
            self.assertEqual(0, subprocess.run(command + ["--build-type", "debug"], capture_output=True).returncode)
            self.assertEqual("debug", json.loads(report.read_text())["artifacts"][0]["build_type"])

    def test_cli_rejects_empty_artifact_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(Path(module.__file__)), directory], capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertIn("No APK files found", result.stderr)
