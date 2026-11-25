import json
from pathlib import Path
import tempfile
import unittest
import subprocess

from scripts import generate_repository_reference as reference
from scripts import sync_agent_metadata as metadata


class RepositoryReferenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'docs/architecture').mkdir(parents=True)
        self.feature = dict(id='example-feature', title='Example', terms=['example'], paths=['example.kt'],
                            tests=['ExampleTest.kt'], commands=['a command is data'], documents=['guide.md'],
                            invariants=['Preserve identity'])
        self.feature['routing'] = dict(role='evidence', entry='example.kt', proof='Observed source',
            admission='Bound profile', decline='Retain the partial answer', positive_fixture='ExampleTest.kt#allowed',
            negative_fixture='ExampleTest.kt#denied', plugins=['example'], tools=['example'], decline_terms=[])
        plugin = self.root / reference.PLUGIN_SOURCE
        plugin.parent.mkdir(parents=True)
        plugin.write_text('object ToolPluginId {\n    const val EXAMPLE = "example"\n}\n')
        for path, content in {'example.kt': 'fun example() = 1\n', 'ExampleTest.kt': '@Test fun allowed() {}\n@Test fun denied() {}\n',
                              'guide.md': '# Example\nExplanation\n'}.items():
            (self.root / path).write_text(content)
        self.write_source()

    def write_source(self):
        (self.root / reference.SOURCE).write_text(json.dumps({'schema_version': 1, 'features': [self.feature]}))

    def git(self, *arguments):
        return subprocess.run(['git', '-C', str(self.root), *arguments], capture_output=True, check=True)

    def test_windows_checkout_newlines_bind_to_canonical_git_bytes(self):
        self.git('init', '--quiet')
        self.git('config', 'core.autocrlf', 'true')
        raw = b'fun example() = 1\r\n'
        (self.root / 'example.kt').write_bytes(raw)
        row = reference.document(self.root)['file_blobs']['example.kt']
        self.assertEqual(reference.blob_sha(raw.replace(b'\r\n', b'\n')), row['blob_sha'])
        self.assertEqual(len(raw) - 1, row['bytes'])
        (self.root / 'example.kt').write_bytes(b'fun example() = 2\r\n')
        self.assertNotEqual(row['blob_sha'], reference.document(self.root)['file_blobs']['example.kt']['blob_sha'])

    def test_reference_generation_never_runs_a_custom_clean_filter(self):
        self.git('init', '--quiet')
        self.git('config', 'filter.reference_probe.clean', 'touch should-not-exist')
        (self.root / '.gitattributes').write_text('*.kt filter=reference_probe\n')
        with self.assertRaisesRegex(ValueError, 'external filters'):
            reference.document(self.root)
        self.assertFalse((self.root / 'should-not-exist').exists())

    def test_changed_source_invalidates_generated_reference_without_changing_feature_names(self):
        self.assertTrue(reference.synchronize(self.root))
        first = json.loads((self.root / reference.TARGET).read_text())
        self.assertFalse(reference.synchronize(self.root, check=True))
        (self.root / 'example.kt').write_text('fun example() = 2\n')
        self.assertTrue(reference.synchronize(self.root, check=True))
        reference.synchronize(self.root)
        second = json.loads((self.root / reference.TARGET).read_text())
        self.assertNotEqual(first['file_blobs']['example.kt'], second['file_blobs']['example.kt'])
        self.assertEqual(first['features'], second['features'])

    def test_new_plugin_and_unbound_proofs_cannot_ship_without_classification(self):
        plugin = self.root / reference.PLUGIN_SOURCE
        original = plugin.read_text()
        plugin.write_text(original.replace('}', '    const val NEW_SOURCE = "unclassified"\n}'))
        with self.assertRaisesRegex(ValueError, 'coverage differs'):
            reference.document(self.root)
        plugin.write_text(original)
        self.feature['routing']['negative_fixture'] = 'ExampleTest.kt#missing'
        self.write_source()
        with self.assertRaisesRegex(ValueError, 'Missing routing fixture'):
            reference.document(self.root)

    def test_generated_tool_hints_use_only_tool_producing_features_and_detect_drift(self):
        reference.synchronize(self.root)
        compiled = self.root / reference.ROUTE_TARGET
        self.assertIn('ReviewedFeatureRoute("example-feature"', compiled.read_text())
        compiled.write_text('stale')
        self.assertTrue(reference.synchronize(self.root, check=True))
        self.feature['routing']['role'] = 'presentation'
        self.write_source()
        with self.assertRaisesRegex(ValueError, 'executable or evidence'):
            reference.document(self.root)

    def test_deleted_test_cannot_leave_a_valid_reference(self):
        (self.root / 'ExampleTest.kt').unlink()
        with self.assertRaisesRegex(ValueError, 'existing regular file'):
            reference.document(self.root)

    def test_traversal_and_symlink_references_are_rejected(self):
        for name in ['../outside.kt', '/outside.kt', '.git/config', 'a/../example.kt', 'a\\example.kt']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                reference.source_path(self.root, name)
        (self.root / 'alias.kt').symlink_to(self.root / 'example.kt')
        with self.assertRaises(ValueError):
            reference.source_path(self.root, 'alias.kt')

    def test_reference_commands_are_never_executed(self):
        self.feature['commands'] = ['touch should-not-exist']
        self.write_source()
        result = reference.document(self.root)
        self.assertFalse(result['commands_are_authority'])
        self.assertFalse((self.root / 'should-not-exist').exists())
        self.assertEqual(['touch should-not-exist'], result['features'][0]['commands'])

    def test_malformed_or_duplicate_terms_fail_validation(self):
        for terms in [['same', 'same'], [[]], [''], ['line\nbreak']]:
            self.feature['terms'] = terms
            self.write_source()
            with self.subTest(terms=terms), self.assertRaises(ValueError):
                reference.document(self.root)

    def test_build_and_schema_facts_update_every_reference_surface(self):
        gradle = self.root / 'app/build.gradle.kts'
        database = self.root / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2.kt'
        export = self.root / 'app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/54.json'
        for path in (gradle, database, export):
            path.parent.mkdir(parents=True, exist_ok=True)
        gradle.write_text('minSdk = 31\ncompileSdk = 37\ntargetSdk = 37\nversionCode = 147\nversionName = "0.9.66.13"\n')
        database.write_text('version = 54')
        export.write_text('{}')
        names = ('AGENTS.md', 'CLAUDE.md', 'AI-Memory.md', 'docs/architecture/repository-guide.md')
        for name in names:
            (self.root / name).write_text('# Guide\n')
        self.assertEqual(list(names), metadata.synchronize(self.root))
        for name in names:
            contents = (self.root / name).read_text()
            self.assertIn('0.9.66.13 (versionCode 147)', contents)
            self.assertIn('schema v54', contents)
            self.assertIn('**Compile SDK**: 37', contents)
        self.assertEqual([], metadata.synchronize(self.root, check=True))
        gradle.write_text(gradle.read_text().replace('147', '148'))
        self.assertEqual(list(names), metadata.synchronize(self.root, check=True))


if __name__ == '__main__':
    unittest.main()
