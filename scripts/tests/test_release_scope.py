import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value
scope = module('check_release_scope')
checks = module('await_release_checks')

class ReleaseScopeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'app').mkdir()
        (self.root / 'app/build.gradle.kts').write_text('versionName = "1.2.3"\nversionCode = 5\n')
        (self.root / 'RELEASE_NOTES.md').write_text('1.2.3\nUnified Reliability v2 is not complete\nDevice evidence unavailable')
        self.manifest = dict(version='1.2.3', versionCode=5, qualification='scoped', baseCommit='a'*40, changes=['UI repair'], limitations=['Device evidence unavailable'], requiredWorkflows=['Build'], allowedPaths=['app/', 'RELEASE_NOTES.md'])

    def test_valid_scope_does_not_claim_full_qualification(self):
        self.assertEqual('scoped', scope.validate(self.root, self.manifest, ['app/main.kt']))

    def test_full_release_keeps_exact_commit_checks_without_scoped_metadata(self):
        self.assertEqual('full', scope.validate(self.root, dict(version='1.2.3', versionCode=5, qualification='full'), []))
        self.assertEqual(list(checks.REQUIRED_WORKFLOWS), checks.required_workflows({}))
        self.assertEqual([*checks.REQUIRED_WORKFLOWS, 'Extra'], checks.required_workflows({'requiredWorkflows': ['Extra']}))

    def test_mismatched_versions_missing_disclosure_and_unscoped_files_fail(self):
        for field, value in [('version', 'old'), ('versionCode', 4), ('limitations', []), ('allowedPaths', ['*']), ('baseCommit', 'main')]:
            manifest = {**self.manifest, field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                scope.validate(self.root, manifest, ['app/main.kt'])
        with self.assertRaises(ValueError):
            scope.validate(self.root, self.manifest, ['unexpected/private.txt'])
        (self.root / 'RELEASE_NOTES.md').write_text('1.2.3 all complete')
        with self.assertRaises(ValueError):
            scope.validate(self.root, self.manifest, [])

    def test_checks_require_exact_commit_success_and_do_not_accept_skips(self):
        success = dict(id=1, name='Build', head_sha='candidate', event='push', status='completed', conclusion='success')
        self.assertEqual(([], []), checks.conclusions([success], 'candidate', ['Build']))
        self.assertEqual(([], ['Build']), checks.conclusions([success], 'different', ['Build']))
        self.assertEqual((['Build'], []), checks.conclusions([{**success, 'conclusion': 'skipped'}], 'candidate', ['Build']))
        self.assertEqual(([], ['Build']), checks.conclusions([success, {**success, 'id': 2, 'status': 'in_progress'}], 'candidate', ['Build']))
