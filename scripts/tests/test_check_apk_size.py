import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

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
