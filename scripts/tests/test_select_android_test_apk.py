import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("apk_selector", Path(__file__).resolve().parents[1] / "select_android_test_apk.py")
selector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(selector)


class ApkSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)

    def apk(self, name, abis=(), filters=()):
        with zipfile.ZipFile(self.folder / name, "w") as archive:
            archive.writestr("AndroidManifest.xml", "fixture")
            for abi in abis:
                archive.writestr(f"lib/{abi}/libfixture.so", "fixture")
        return {"outputFile": name, "filters": [{"filterType": "ABI", "value": abi} for abi in filters]}

    def metadata(self, elements, application="example.app"):
        (self.folder / "output-metadata.json").write_text(json.dumps({"applicationId": application, "elements": elements}))

    def test_targeted_ide_apk_does_not_need_abi_in_its_filename(self):
        self.metadata([self.apk("app-debug.apk", ["x86_64"])])
        self.assertEqual("app-debug.apk", selector.select_apk(self.folder, "example.app", "x86_64").name)

    def test_explicit_abi_split_wins_over_universal(self):
        self.metadata([self.apk("universal.apk", ["arm64-v8a", "x86_64"]), self.apk("renamed.apk", ["x86_64"], ["x86_64"]), self.apk("arm.apk", ["arm64-v8a"], ["arm64-v8a"])])
        self.assertEqual("renamed.apk", selector.select_apk(self.folder, "example.app", "x86_64").name)

    def test_incompatible_native_code_is_rejected_despite_its_filename(self):
        self.metadata([self.apk("app-x86_64-debug.apk", ["arm64-v8a"])])
        with self.assertRaisesRegex(ValueError, "No compatible APK"):
            selector.select_apk(self.folder, "example.app", "x86_64")

    def test_test_apk_can_be_java_only_but_must_match_its_application(self):
        self.metadata([self.apk("instrumentation.apk")], "example.app.test")
        self.assertEqual("instrumentation.apk", selector.select_apk(self.folder, "example.app.test").name)
        with self.assertRaisesRegex(ValueError, "unexpected application"):
            selector.select_apk(self.folder, "example.app")

    def test_ambiguous_compatible_outputs_are_rejected(self):
        self.metadata([self.apk("first.apk", ["x86_64"]), self.apk("second.apk", ["x86_64"])])
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            selector.select_apk(self.folder, "example.app", "x86_64")

    def test_stale_unlisted_apk_is_not_selected(self):
        self.apk("stale-x86_64.apk", ["x86_64"])
        self.metadata([self.apk("current.apk", ["arm64-v8a"], ["arm64-v8a"])])
        with self.assertRaisesRegex(ValueError, "No compatible APK"):
            selector.select_apk(self.folder, "example.app", "x86_64")

    def test_missing_metadata_and_outputs_fail_explicitly(self):
        with self.assertRaisesRegex(ValueError, "Missing APK build metadata"):
            selector.select_apk(self.folder, "example.app", "x86_64")
        self.metadata([{"outputFile": "missing.apk", "filters": []}])
        with self.assertRaisesRegex(ValueError, "missing or out-of-directory"):
            selector.select_apk(self.folder, "example.app", "x86_64")

    def test_metadata_cannot_select_files_outside_its_build_directory(self):
        self.metadata([{"outputFile": "../outside.apk", "filters": []}])
        with self.assertRaisesRegex(ValueError, "out-of-directory"):
            selector.select_apk(self.folder, "example.app", "x86_64")


if __name__ == "__main__":
    unittest.main()
