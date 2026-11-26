import importlib.util
import json
import tempfile
import unittest
from pathlib import Path, PureWindowsPath
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / 'generate_app_audit_inventory.py'
SPEC = importlib.util.spec_from_file_location('app_audit_inventory', SCRIPT)
inventory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inventory)


class AppAuditInventoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, relative, text='package fixture\n'):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_indexes_every_in_scope_file_without_exporting_source_text(self):
        self.write('app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/McpFixture.kt', 'private-token-canary\nsecond line')
        self.write('app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/newsubsystem/Unmapped.kt')
        self.write('app/src/main/res/values/strings.xml', '<resources/>\n')
        data = inventory.document(self.root)
        self.assertEqual(3, data['file_count'])
        self.assertEqual(4, data['indexed_line_count'])
        self.assertEqual({'mcp_plugins', 'other_production', 'accessibility_and_localization'}, {row['area_id'] for row in data['files']})
        self.assertNotIn('private-token-canary', json.dumps(data))
        self.assertFalse(data['line_coverage_available'])
        self.assertEqual(26, len(data['areas']))
        self.assertEqual(3, sum(area['file_count'] for area in data['areas']))

    def test_source_or_assignment_change_changes_digest_deterministically(self):
        file = self.write('gateway/server.py', 'value = 1\n')
        first = inventory.document(self.root)
        self.assertEqual(first, inventory.document(self.root))
        file.write_text('value = 2\n')
        changed = inventory.document(self.root)
        self.assertNotEqual(first['source_digest'], changed['source_digest'])
        with patch.object(inventory, 'source_area', return_value='other_production'):
            remapped = inventory.document(self.root)
        self.assertNotEqual(changed['source_digest'], remapped['source_digest'])

    def test_gateway_installer_and_firewall_scripts_are_bound_to_the_source_digest(self):
        install = self.write('gateway/install_gateway_v14.ps1', 'Write-Output "install fixture"\n')
        self.write('gateway/desktop/Firewall.ps1', 'Write-Output "firewall fixture"\n')
        self.write('gateway/desktop/Start-Gateway.ps1', 'Write-Output "launch fixture"\n')
        self.write('gateway/tests/test_install.ps1')
        self.write('gateway/desktop/Gateway.exe', 'binary excluded')
        first = inventory.document(self.root)
        self.assertEqual(3, first['file_count'])
        self.assertEqual({'gateway_software'}, {row['area_id'] for row in first['files']})
        self.assertFalse(first['line_coverage_available'])
        self.assertNotIn('install fixture', json.dumps(first))
        install.write_text('Write-Output "changed fixture"\n')
        self.assertNotEqual(first['source_digest'], inventory.document(self.root)['source_digest'])

    def test_test_fixtures_and_dependencies_are_excluded_but_runtime_fixture_code_is_indexed(self):
        for path in ('gateway/tests/test_server.py', 'authentication/test_app.py', 'mcp/tests/probe.py',
                     'mcp/node_modules/vendor/main.js', 'mcp/research/bridge.test.mjs', 'mcp/research/fixtures/upstream.mjs'):
            self.write(path)
        self.write('mcp/research/bridge.mjs')
        self.write('app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/fixtures/test_runtime.kt')
        data = inventory.document(self.root)
        self.assertEqual(2, data['file_count'])
        self.assertEqual({'external_integrations', 'benchmark'}, {row['area_id'] for row in data['files']})

    def test_preparing_research_companions_preserves_the_application_inventory(self):
        self.write('mcp/research/prepare-airbnb.mjs')
        before = inventory.document(self.root)
        self.write('mcp/research/upstreams/airbnb/index.ts')
        self.write('mcp/research/upstreams/hn-server/src/index.ts')
        self.assertEqual(before, inventory.document(self.root))

    def test_generated_outputs_do_not_create_a_self_referential_digest(self):
        self.write('gateway/main.py')
        expected = inventory.document(self.root)
        self.write(str(inventory.KOTLIN), 'changed generated source\n')
        self.write(str(inventory.ASSET), '{}\n')
        self.assertEqual(expected, inventory.document(self.root))

    def test_native_gateway_source_is_bound_but_dotnet_generated_files_are_excluded(self):
        window = self.write('gateway/desktop/native/MainWindow.cs', 'class MainWindow {}\n')
        self.write('gateway/desktop/native/App.xaml', '<Application/>\n')
        self.write('gateway/desktop/native/obj/Release/App.g.cs', 'generated binding\n')
        self.write('gateway/desktop/native/bin/Release/App.xaml', 'compiled output\n')
        self.write('gateway/desktop/native/tests/ContractTest.cs', 'test fixture\n')
        first = inventory.document(self.root)
        self.assertEqual(2, first['file_count'])
        self.assertEqual({'gateway_software'}, {row['area_id'] for row in first['files']})
        window.write_text('class MainWindow { public bool Enabled; }\n')
        self.assertNotEqual(first['source_digest'], inventory.document(self.root)['source_digest'])

    def test_refuses_linked_files_and_directories(self):
        outside = self.root / 'outside.kt'
        outside.write_text('not production\n')
        linked = self.root / 'app/src/main/kotlin/Link.kt'
        linked.parent.mkdir(parents=True)
        linked.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'symlinks'):
            inventory.document(self.root)
        linked.unlink()
        (linked.parent / 'linked').symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'linked source directories'):
            inventory.document(self.root)

    def test_bounds_inventory_before_reading_arbitrary_sized_source(self):
        self.write('gateway/large.py', '12345678')
        with patch.object(inventory, 'MAX_FILE_BYTES', 4):
            with self.assertRaisesRegex(ValueError, 'byte bound'):
                inventory.document(self.root)
        with patch.object(inventory, 'MAX_TOTAL_BYTES', 4):
            with self.assertRaisesRegex(ValueError, 'byte bound'):
                inventory.document(self.root)
        with patch.object(inventory, 'MAX_FILES', 0):
            with self.assertRaisesRegex(ValueError, 'file bound'):
                inventory.document(self.root)

    def test_new_gradle_module_requires_an_explicit_inventory_decision(self):
        self.write('settings.gradle.kts', 'include(":app", ":new-runtime")\n')
        with self.assertRaisesRegex(ValueError, 'new Gradle module'):
            inventory.document(self.root)

    def test_generated_kotlin_keeps_the_matching_digest_counts_and_no_line_coverage_claim(self):
        self.write('local-gguf-runtime/src/main/cpp/runtime.cpp', 'int value;\n')
        data = inventory.document(self.root)
        output = inventory.kotlin_document(data)
        self.assertIn('@Serializable\ndata class AppAuditSourceArea', output)
        self.assertIn(data['source_digest'], output)
        self.assertIn('val fileCount: Int = 1', output)
        self.assertIn('val lineCoverageAvailable: Boolean = false', output)
        self.assertEqual(26, output.count('        AppAuditSourceArea('))

    def test_new_github_audit_sources_are_classified_as_production(self):
        profile = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/GitHubProfileAuditSuite.kt'
        controls = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/GitHubAiProfileAuditControls.kt'
        self.write(profile)
        self.write(controls)
        snapshot = inventory.document(self.root)
        indexed = {item['path']: item for item in snapshot['files']}
        self.assertEqual(2, snapshot['file_count'])
        self.assertEqual('benchmark', indexed[profile]['area_id'])
        self.assertEqual('chat_ui', indexed[controls]['area_id'])

    def test_inventory_source_path_sort_matches_python_path(self):
        # pathlib.Path sorts directory components, not the raw path string.
        # "values" must precede "values-ar" even though "-" sorts before "/".
        regular = 'app/src/main/res/values/attrs.xml'
        localized = 'app/src/main/res/values-ar/strings.xml'
        self.write(localized, '<resources/>\\n')
        self.write(regular, '<resources/>\\n')
        found = [row['path'] for row in inventory.document(self.root)['files']]
        self.assertEqual([regular, localized], found)
        self.assertNotEqual(sorted(found), found)

    def test_mixed_case_paths_have_the_same_component_order_and_area_on_windows(self):
        paths = ['gateway/Zed.py', 'gateway/alpha.py']
        for relative in reversed(paths):
            self.write(relative)
        self.assertEqual(paths, [row['path'] for row in inventory.document(self.root)['files']])
        self.assertEqual('gateway_software', inventory.source_area(PureWindowsPath(paths[0])))

    def test_committed_inventory_matches_current_source(self):
        root = SCRIPT.parents[1]
        data = inventory.document(root)
        committed_text = (root / inventory.ASSET).read_text()
        committed = json.loads(committed_text)
        observed_paths = {row['path']: row for row in data['files']}
        committed_paths = {row['path']: row for row in committed['files']}
        only_actual = sorted(observed_paths.keys() - committed_paths.keys())
        only_committed = sorted(committed_paths.keys() - observed_paths.keys())
        changed_paths = sorted(
            path for path in observed_paths.keys() & committed_paths.keys()
            if observed_paths[path] != committed_paths[path]
        )
        # Do not print source content, credentials, or arbitrary exception text.
        # A failing CI job should identify the precise bounded source inventory
        # difference instead of a truncated 600-KB JSON string comparison.
        detail = {
            'actual_digest': data['source_digest'],
            'committed_digest': committed.get('source_digest'),
            'actual_file_count': data['file_count'],
            'committed_file_count': committed.get('file_count'),
            'only_actual_count': len(only_actual),
            'only_committed_count': len(only_committed),
            'changed_count': len(changed_paths),
            'only_actual_paths': only_actual[:12],
            'only_committed_paths': only_committed[:12],
            'changed_paths': changed_paths[:12],
        }
        self.assertEqual(
            json.dumps(data, indent=2, ensure_ascii=True) + '\n',
            committed_text,
            'Source inventory mismatch (source names only): ' + json.dumps(detail, sort_keys=True),
        )
        self.assertEqual(inventory.kotlin_document(data), (root / inventory.KOTLIN).read_text())


if __name__ == '__main__':
    unittest.main()
