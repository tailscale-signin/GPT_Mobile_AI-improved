import copy
import importlib.util
import io
from pathlib import Path
import sys
import unittest
from zipfile import ZipFile
from test_mediapipe_jni import dex_fixture

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location("objectbox_gate", Path(__file__).resolve().parents[1] / "check_objectbox_jni.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

OBJECT = "Lio/objectbox/query/ObjectWithScore;"
IDS = "Lio/objectbox/query/IdWithScore;"
# Independent native signatures, retained as references when definitions vanish.
CONTRACT = {
    OBJECT: {"methods": [("<init>", "(Ljava/lang/Object;D)V", 0x10001)]},
    IDS: {"methods": [("<init>", "(JD)V", 0x10001)]},
}


class ObjectBoxJniGateTest(unittest.TestCase):
    def check(self, *classes, bundle=False):
        content = io.BytesIO()
        with ZipFile(content, "w") as archive:
            for index, item in enumerate(classes):
                name = ("base/dex/" if bundle else "") + f"classes{index + 1 if index else ''}.dex"
                archive.writestr(name, dex_fixture(item, references=CONTRACT))
        with ZipFile(content) as archive:
            gate.check_bindings(archive, "fixture")

    def test_split_apk_and_bundle_constructors_pass(self):
        for bundle in (False, True):
            self.check({OBJECT: CONTRACT[OBJECT]}, {IDS: CONTRACT[IDS]}, bundle=bundle)

    def test_dangling_reference_to_removed_class_cannot_pass(self):
        for owner in CONTRACT:
            classes = copy.deepcopy(CONTRACT)
            del classes[owner]
            with self.subTest(owner=owner), self.assertRaisesRegex(ValueError, "missing/renamed ObjectBox"):
                self.check(classes)

    def test_removed_constructor_cannot_pass(self):
        for owner in CONTRACT:
            classes = copy.deepcopy(CONTRACT)
            classes[owner]["methods"] = []
            with self.subTest(owner=owner), self.assertRaisesRegex(ValueError, "-><init>"):
                self.check(classes)

    def test_renamed_class_wrong_signature_and_static_constructor_fail(self):
        variants = []
        renamed = copy.deepcopy(CONTRACT)
        renamed["La;"] = renamed.pop(OBJECT)
        variants.append(renamed)
        for owner, methods in [
            (OBJECT, [("<init>", "(Ljava/lang/Object;F)V", 0x10001)]),
            (IDS, [("<init>", "(JD)V", 0x10009)]),
        ]:
            classes = copy.deepcopy(CONTRACT)
            classes[owner]["methods"] = methods
            variants.append(classes)
        for classes in variants:
            with self.subTest(classes=classes), self.assertRaisesRegex(ValueError, "missing/renamed ObjectBox"):
                self.check(classes)

    def test_missing_and_duplicate_dex_fail_closed(self):
        for classes in [(), (CONTRACT, CONTRACT)]:
            with self.subTest(count=len(classes)), self.assertRaises(ValueError):
                self.check(*classes)


if __name__ == "__main__":
    unittest.main()
