"""Desktop policy selection survives both launcher stages without inheriting unrelated overrides."""
import copy
import json
import multiprocessing
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.configuration import atomic_json, environment, load, validate
from desktop.validation_policy import (
    DEFAULT_REPOSITORY, SETTING, PolicyConflictError, active_policy_path, check_policy_file, default_policy,
    load_policy_snapshot, managed_policy_path, read_desktop_policy, save_desktop_policy,
    save_policy_path, save_policy_snapshot, selected_policy_path,
)
from v14.engineering_validation import ValidationPolicyError, load_policy


def competing_editor(root, document, revision, gate, results):
    """Each editor is a separate process, including on Windows spawn."""
    gate.wait(10)
    try:
        saved = save_policy_snapshot(root, document, enabled=True, expected_revision=revision)
        results.put(('saved', saved['selection']))
    except PolicyConflictError:
        results.put(('conflict', ''))
    except Exception as failure:
        results.put(('error', type(failure).__name__ + ': ' + str(failure)))


class DesktopValidationPolicyTests(unittest.TestCase):
    def policy(self, root):
        path = Path(root) / 'approved.json'
        path.write_text(json.dumps({'owner/repo': {'mirror': str(Path(root) / 'mirror.git'),
            'image': 'registry.example/validation@sha256:' + 'a' * 64,
            'commands': [['python', '-m', 'unittest']]}}), encoding='utf-8')
        return path

    def test_bad_saved_selection_keeps_gateway_available_without_inheriting_a_policy(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            approved = self.policy(root)
            settings = root / 'data' / 'validation-settings.json'
            settings.parent.mkdir()
            cases = ['{broken', ' ' * 8193, json.dumps({'schema': 1, 'policy_path': 'relative.json'})]
            for content in cases:
                with self.subTest(content=content[:30]), patch.dict(os.environ, {SETTING: str(approved)}, clear=True):
                    settings.write_text(content, encoding='utf-8')
                    with self.assertRaises(ValueError):
                        selected_policy_path(root)
                    env = environment(root, validate({}))
                    self.assertNotIn(SETTING, env)
                    self.assertEqual('127.0.0.1', env['GATEWAY_HOST'])
                    with patch.dict(os.environ, env, clear=True), self.assertRaises(ValidationPolicyError) as blocked:
                        load_policy('owner/repo')
                    self.assertEqual('NOT_CONFIGURED', blocked.exception.state)
                    # Saving an empty selection repairs the invalid file and
                    # explicitly disables the still-present inherited policy.
                    save_policy_path(root, '')
                    self.assertEqual('', selected_policy_path(root))
                    self.assertNotIn(SETTING, environment(root, validate({})))

    def test_unreadable_saved_selection_does_not_stop_gateway_or_reinherit_policy(self):
        with tempfile.TemporaryDirectory() as temp:
            alias = Path(temp) / 'root-alias'
            alias.mkdir()
            root = alias / '..'
            approved = self.policy(root)
            settings = root / 'data' / 'validation-settings.json'
            atomic_json(settings, {'schema': 1, 'policy_path': str(approved)})
            resolved_settings = settings.resolve()
            original_open = Path.open
            denied_reads = []

            def restricted_open(path, *args, **kwargs):
                # environment() resolves the root, including Windows 8.3 aliases.
                # Deny the same file through both original and canonical paths.
                if path.resolve() == resolved_settings:
                    denied_reads.append(path)
                    raise PermissionError('Saved policy selection cannot be read')
                return original_open(path, *args, **kwargs)

            with patch.dict(os.environ, {SETTING: str(approved)}, clear=True), patch.object(Path, 'open', restricted_open):
                with self.assertRaises(PermissionError):
                    selected_policy_path(root)
                self.assertNotIn(SETTING, environment(root, validate({})))
            self.assertEqual([settings, resolved_settings], denied_reads)

    def test_invalid_inherited_selection_blocks_only_implementation_until_repaired(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: 'relative.json'}, clear=True):
            with self.assertRaises(ValueError):
                selected_policy_path(temp)
            self.assertNotIn(SETTING, environment(temp, validate({})))
            approved = self.policy(temp)
            save_policy_path(temp, str(approved))
            self.assertEqual(str(approved), environment(temp, validate({}))[SETTING])

    def test_windows_utf8_bom_policy_preserves_non_ascii_mirror_through_selection_and_load(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            policy = self.policy(root)
            document = json.loads(policy.read_text(encoding='utf-8'))
            mirror = root / 'mirror ü-日本.git'
            document['owner/repo']['mirror'] = str(mirror)
            policy.write_text(json.dumps(document, ensure_ascii=False), encoding='utf-8-sig')
            self.assertTrue(policy.read_bytes().startswith(b'\xef\xbb\xbf'))
            self.assertEqual('VALIDATED', save_policy_path(root, str(policy))['state'])
            with patch.dict(os.environ, {}, clear=True):
                env = environment(root, validate({}))
            with patch.dict(os.environ, env, clear=True):
                loaded, loaded_mirror, _, _, _ = load_policy('owner/repo')
            self.assertEqual(str(mirror), loaded['mirror'])
            self.assertEqual(mirror.resolve(), loaded_mirror)

    def test_explicit_host_policy_survives_supervisor_and_frozen_engine(self):
        with tempfile.TemporaryDirectory() as temp:
            policy = self.policy(temp)
            inherited = {SETTING: str(policy), 'GATEWAY_HOST': '0.0.0.0', 'GATEWAY_LEGACY_BEARER': 'true',
                         'GATEWAY_REMOTE_ENGINEERING': 'true'}
            with patch.dict(os.environ, inherited, clear=True):
                manager = environment(temp, validate({}))
            with patch.dict(os.environ, manager, clear=True):
                engine = environment(temp, validate({}))
            self.assertEqual(str(policy), engine[SETTING])
            self.assertEqual('127.0.0.1', engine['GATEWAY_HOST'])
            self.assertEqual('false', engine['GATEWAY_REMOTE_ENGINEERING'])
            self.assertNotIn('GATEWAY_LEGACY_BEARER', engine)
            with patch.dict(os.environ, engine, clear=True):
                approved, _, _, commands, _ = load_policy('owner/repo')
            self.assertEqual([['python', '-m', 'unittest']], commands)
            self.assertEqual('a' * 64, approved['image'].split('@sha256:')[1])

    def test_saved_selection_survives_clean_restart_and_keeps_rollback_settings(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cfg = validate({})
            atomic_json(root / 'settings.json', cfg)
            policy = self.policy(root)
            self.assertEqual({'state': 'VALIDATED', 'repositories': 1}, save_policy_path(root, str(policy)))
            with patch.dict(os.environ, {}, clear=True):
                first = environment(root, load(root))
            with patch.dict(os.environ, first, clear=True):
                second = environment(root, load(root))
            self.assertEqual(str(policy), second[SETTING])
            self.assertEqual(cfg, json.loads((root / 'settings.json').read_text()))
            self.assertEqual(cfg, load(root))

    def test_saved_selection_wins_and_explicit_removal_does_not_reinherit(self):
        with tempfile.TemporaryDirectory() as temp:
            policy = self.policy(temp)
            with patch.dict(os.environ, {SETTING: '/other/host-policy.json'}, clear=True):
                save_policy_path(temp, str(policy))
                self.assertEqual(str(policy), environment(temp, validate({}))[SETTING])
                save_policy_path(temp, '')
                self.assertNotIn(SETTING, environment(temp, validate({})))

    def test_disappearing_policy_remains_identified_for_admission_recovery(self):
        with tempfile.TemporaryDirectory() as temp:
            policy = self.policy(temp)
            save_policy_path(temp, str(policy))
            policy.unlink()
            with patch.dict(os.environ, {}, clear=True):
                env = environment(temp, validate({}))
            with patch.dict(os.environ, env, clear=True), self.assertRaises(ValidationPolicyError) as failed:
                load_policy('owner/repo')
            self.assertEqual('MISSING_OR_OVERSIZED', failed.exception.state)

    def test_repository_without_mapping_remains_unconfigured(self):
        with tempfile.TemporaryDirectory() as temp:
            path = self.policy(temp)
            with self.assertRaises(ValidationPolicyError) as failed:
                load_policy('owner/other', policy_path=str(path))
            self.assertEqual('REPOSITORY_UNCONFIGURED', failed.exception.state)

    def test_invalid_policy_cannot_replace_saved_selection(self):
        with tempfile.TemporaryDirectory() as temp:
            policy = self.policy(temp)
            save_policy_path(temp, str(policy))
            invalid = Path(temp) / 'invalid.json'
            cases = ['{}', '[]', '{invalid', json.dumps({'owner/repo': {'mirror': temp, 'image': 'unpinned', 'commands': [['true']]}}), ' ' * 65537]
            for content in cases:
                invalid.write_text(content)
                with self.subTest(content=content[:30]), self.assertRaises(ValueError):
                    save_policy_path(temp, str(invalid))
                self.assertEqual(str(policy), selected_policy_path(temp))
            with self.assertRaises(ValueError):
                save_policy_path(temp, 'relative.json')
            with self.assertRaises(ValueError):
                save_policy_path(temp, str(Path(temp) / 'missing.json'))

    def test_managed_policy_copies_legacy_entries_and_survives_deleting_the_external_file(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {}, clear=True):
            legacy = self.policy(temp)
            save_policy_path(temp, str(legacy))
            document = read_desktop_policy(temp)
            self.assertIn('owner/repo', document)
            entry = default_policy(temp)
            entry['image'] = 'python@sha256:' + 'a' * 64
            document[DEFAULT_REPOSITORY] = entry
            original = load_policy_snapshot(temp)
            saved = save_desktop_policy(temp, document, expected_revision=original['revision'])
            legacy.unlink()
            self.assertEqual(document, read_desktop_policy(temp))
            first = environment(temp, validate({}))
            with patch.dict(os.environ, first, clear=True):
                second = environment(temp, validate({}))
            self.assertEqual(saved['selection'], second[SETTING])
            self.assertEqual(saved['selection'], selected_policy_path(temp))
            # Old releases require exactly these schema-1 selector fields.
            self.assertEqual({'schema': 1, 'policy_path': saved['selection']},
                             json.loads((Path(temp) / 'data' / 'validation-settings.json').read_text()))
            with patch.dict(os.environ, second, clear=True):
                self.assertEqual(entry, load_policy(DEFAULT_REPOSITORY)[0])
                self.assertEqual([['python', '-m', 'unittest']], load_policy('owner/repo')[3])
            with self.assertRaisesRegex(ValueError, 'Timeout'):
                save_desktop_policy(temp, {DEFAULT_REPOSITORY: dict(entry, timeout_seconds=0)},
                                    expected_revision=saved['revision'])
            self.assertEqual(document, read_desktop_policy(temp))

    def save(self, root, document, enabled=True):
        return save_policy_snapshot(root, document, enabled=enabled,
                                    expected_revision=load_policy_snapshot(root)['revision'])

    def test_managed_file_existence_never_overrides_disable_or_external_selection(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            external = self.policy(temp)
            document = json.loads(external.read_text())
            atomic_json(managed_policy_path(temp), document)
            # The first built-in format's Save made this legacy file active.
            self.assertEqual(str(managed_policy_path(temp)), active_policy_path(temp))
            snapshot = load_policy_snapshot(temp)
            self.assertTrue(snapshot['enabled'])
            self.assertEqual(document, snapshot['repositories'])
            save_policy_path(temp, '')
            self.assertNotIn(SETTING, environment(temp, validate({})))
            saved = self.save(temp, document)
            self.assertNotEqual(str(external), saved['selection'])
            save_policy_path(temp, '')
            self.assertEqual('', active_policy_path(temp))
            self.assertNotIn(SETTING, environment(temp, validate({})))
            replacement = copy.deepcopy(document)
            replacement['owner/repo']['commands'] = [['python3', '-m', 'unittest', 'test_other']]
            external.write_text(json.dumps(replacement))
            save_policy_path(temp, str(external))
            self.assertEqual(str(external), active_policy_path(temp))
            self.assertEqual(replacement, read_desktop_policy(temp))
            self.assertEqual(str(external), environment(temp, validate({}))[SETTING])

    def test_managed_only_migration_matches_startup_and_inherited_explicit_path_wins(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            document = json.loads(self.policy(temp).read_text())
            legacy = managed_policy_path(temp)
            atomic_json(legacy, document)
            snapshot = load_policy_snapshot(temp)
            self.assertTrue(snapshot['enabled'])
            first = environment(temp, validate({}))
            self.assertEqual(snapshot['selection'], first[SETTING])
            with patch.dict(os.environ, first, clear=True):
                self.assertEqual(str(legacy), environment(temp, validate({}))[SETTING])
            external = Path(temp) / 'external.json'
            atomic_json(external, {'another/repo': document['owner/repo']})
            with patch.dict(os.environ, {SETTING: str(external)}):
                inherited = load_policy_snapshot(temp)
                self.assertEqual(str(external), inherited['selection'])
                self.assertEqual({'another/repo'}, set(inherited['repositories']))
                self.assertEqual(str(external), active_policy_path(temp))
            atomic_json(Path(temp) / 'data' / 'validation-settings.json', {'schema': 1, 'policy_path': ''})
            self.assertFalse(load_policy_snapshot(temp)['enabled'])
            self.assertEqual('', active_policy_path(temp))

    def test_invalid_managed_only_policy_remains_visible_but_never_activates_defaults(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            atomic_json(managed_policy_path(temp), {'owner/repo': {'image': 'not-pinned'}})
            snapshot = load_policy_snapshot(temp)
            self.assertEqual('INVALID', snapshot['state'])
            self.assertFalse(snapshot['enabled'])
            self.assertEqual({'owner/repo': {'image': 'not-pinned'}}, snapshot['repositories'])
            self.assertNotIn(SETTING, environment(temp, validate({})))
            (Path(temp) / 'data' / 'validation-settings.json').write_text('{invalid')
            self.assertEqual('INVALID', load_policy_snapshot(temp)['state'])
            self.assertNotIn(SETTING, environment(temp, validate({})))

    def broken_link(self, path):
        try:
            path.symlink_to(path.parent / 'missing-target.json')
        except OSError as failure:
            if os.name == 'nt' and getattr(failure, 'winerror', None) in {5, 1314}:
                self.skipTest('Windows account cannot create a filesystem symlink')
            raise

    def test_broken_selector_link_never_reactivates_legacy_or_inherited_policy(self):
        with tempfile.TemporaryDirectory() as temp:
            external = self.policy(temp)
            atomic_json(managed_policy_path(temp), json.loads(external.read_text()))
            selector = Path(temp) / 'data' / 'validation-settings.json'
            self.broken_link(selector)
            with patch.dict(os.environ, {SETTING: str(external)}):
                with self.assertRaisesRegex(ValueError, 'unreadable link'):
                    selected_policy_path(temp)
                self.assertNotIn(SETTING, environment(temp, validate({})))
                snapshot = load_policy_snapshot(temp)
                self.assertEqual('INVALID', snapshot['state'])
                self.assertFalse(snapshot['enabled'])
                repaired = save_policy_snapshot(temp, {}, enabled=False, expected_revision=snapshot['revision'])
                self.assertFalse(repaired['enabled'])
                self.assertEqual('', selected_policy_path(temp))
            self.assertFalse(selector.is_symlink())

    def test_broken_editor_link_is_observed_for_repair_instead_of_discarded_as_missing(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            save_policy_path(temp, '')
            editor = Path(temp) / 'data' / 'validation-editor.json'
            self.broken_link(editor)
            snapshot = load_policy_snapshot(temp)
            self.assertEqual('INVALID', snapshot['state'])
            self.assertFalse(snapshot['enabled'])
            document = json.loads(self.policy(temp).read_text())
            saved = save_policy_snapshot(temp, document, enabled=False, expected_revision=snapshot['revision'])
            self.assertEqual('DISABLED', saved['state'])
            self.assertEqual(document, saved['repositories'])
            self.assertFalse(editor.is_symlink())

    def test_stale_window_cannot_erase_another_windows_repository_or_settings(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            self.save(temp, json.loads(self.policy(temp).read_text()))
            first, second = load_policy_snapshot(temp), load_policy_snapshot(temp)
            second['repositories']['owner/added'] = copy.deepcopy(second['repositories']['owner/repo'])
            saved = save_policy_snapshot(temp, second['repositories'], enabled=True, expected_revision=second['revision'])
            first['repositories']['owner/repo']['timeout_seconds'] = 80
            with self.assertRaisesRegex(PolicyConflictError, 'Reload'):
                save_policy_snapshot(temp, first['repositories'], enabled=True, expected_revision=first['revision'])
            self.assertEqual(saved['repositories'], read_desktop_policy(temp))
            self.assertIn('owner/added', read_desktop_policy(temp))

    def test_parallel_process_editors_serialize_compare_and_selection_commit(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            initial = self.save(temp, json.loads(self.policy(temp).read_text()))
            context = multiprocessing.get_context('spawn')
            gate, results = context.Event(), context.Queue()
            processes = []
            for name in ('first', 'second'):
                document = copy.deepcopy(initial['repositories'])
                document['owner/' + name] = copy.deepcopy(document['owner/repo'])
                process = context.Process(target=competing_editor, args=(temp, document, initial['revision'], gate, results))
                processes.append(process)
            try:
                for process in processes:
                    process.start()
                gate.set()
                observed = [results.get(timeout=25) for _ in processes]
                self.assertEqual(['conflict', 'saved'], sorted(item[0] for item in observed), observed)
                actual = read_desktop_policy(temp)
                self.assertEqual(2, len(actual))
                self.assertIn('owner/repo', actual)
                self.assertEqual(1, sum(name in actual for name in ('owner/first', 'owner/second')))
            finally:
                gate.set()
                for process in processes:
                    process.join(5)
                    if process.is_alive():
                        process.terminate()
                        process.join(5)
                results.close()
                results.join_thread()

    def test_external_document_bytes_and_legacy_selector_changes_invalidate_snapshot(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            external = self.policy(temp)
            save_policy_path(temp, str(external))
            before = load_policy_snapshot(temp)
            # Same JSON meaning, different approved file bytes: still a new observation.
            external.write_text(external.read_text() + '\n')
            with self.assertRaises(PolicyConflictError):
                save_policy_snapshot(temp, before['repositories'], enabled=True, expected_revision=before['revision'])
            before = load_policy_snapshot(temp)
            save_policy_path(temp, '')
            with self.assertRaises(PolicyConflictError):
                save_policy_snapshot(temp, before['repositories'], enabled=True, expected_revision=before['revision'])
            self.assertEqual('', selected_policy_path(temp))

    def test_external_path_spelling_does_not_hide_selected_repository_entries(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            external = self.policy(temp)
            alias = str(external.parent) + os.sep + os.sep + external.name
            save_policy_path(temp, alias)
            snapshot = load_policy_snapshot(temp)
            self.assertEqual(alias, snapshot['selection'])
            self.assertEqual('READY', snapshot['state'])
            self.assertEqual(json.loads(external.read_text()), snapshot['repositories'])

    def test_failed_selector_swap_preserves_previous_active_bytes_and_rollback_reader(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            original = self.save(temp, json.loads(self.policy(temp).read_text()))
            selector = Path(temp).resolve() / 'data' / 'validation-settings.json'
            old_selector, old_document = selector.read_bytes(), Path(original['selection']).read_bytes()
            changed = copy.deepcopy(original['repositories'])
            changed['owner/repo']['timeout_seconds'] = 77

            def fail_selection(path, value, **kwargs):
                if Path(path) == selector:
                    raise PermissionError('Selector is unavailable')
                atomic_json(path, value, **kwargs)

            with patch('desktop.configuration.atomic_json', side_effect=fail_selection), self.assertRaises(PermissionError):
                save_policy_snapshot(temp, changed, enabled=True, expected_revision=original['revision'])
            self.assertEqual(old_selector, selector.read_bytes())
            self.assertEqual(old_document, Path(original['selection']).read_bytes())
            self.assertEqual(original['selection'], selected_policy_path(temp))
            first = environment(temp, validate({}))
            with patch.dict(os.environ, first, clear=True):
                second = environment(temp, validate({}))
            with patch.dict(os.environ, second, clear=True):
                self.assertEqual(original['repositories']['owner/repo'], load_policy('owner/repo')[0])
            # The failed activation may retain a new draft but cannot reuse the stale editor token.
            with self.assertRaises(PolicyConflictError):
                save_policy_snapshot(temp, changed, enabled=True, expected_revision=original['revision'])

    def test_failed_first_activation_leaves_validation_disabled(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            original = load_policy_snapshot(temp)
            document = json.loads(self.policy(temp).read_text())
            selector = Path(temp).resolve() / 'data' / 'validation-settings.json'

            def fail_selection(path, value, **kwargs):
                if Path(path) == selector:
                    raise PermissionError('Selector is unavailable')
                atomic_json(path, value, **kwargs)

            with patch('desktop.configuration.atomic_json', side_effect=fail_selection), self.assertRaises(PermissionError):
                save_policy_snapshot(temp, document, enabled=True, expected_revision=original['revision'])
            self.assertEqual('', selected_policy_path(temp))
            self.assertNotIn(SETTING, environment(temp, validate({})))
            restored = load_policy_snapshot(temp)
            self.assertFalse(restored['enabled'])
            self.assertEqual(document, restored['repositories'])

    def test_cancel_before_selector_commit_preserves_the_previous_active_policy(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            original = self.save(temp, json.loads(self.policy(temp).read_text()))
            changed = copy.deepcopy(original['repositories'])
            changed['owner/repo']['timeout_seconds'] = 70
            cancel = threading.Event()
            def cancel_after_draft(path, value, **kwargs):
                atomic_json(path, value, **kwargs)
                if Path(path).name == 'validation-editor.json':
                    cancel.set()
            with patch('desktop.configuration.atomic_json', side_effect=cancel_after_draft), self.assertRaises(InterruptedError):
                save_policy_snapshot(temp, changed, enabled=True, expected_revision=original['revision'], cancel=cancel)
            self.assertEqual(original['selection'], selected_policy_path(temp))
            self.assertEqual(original['repositories'], read_desktop_policy(temp))

    def test_cancel_after_selector_commit_reports_the_committed_save(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            original = load_policy_snapshot(temp)
            document = json.loads(self.policy(temp).read_text())
            cancel = threading.Event()
            def cancel_after_commit(path, value, **kwargs):
                atomic_json(path, value, **kwargs)
                if Path(path).name == 'validation-settings.json':
                    cancel.set()
            with patch('desktop.configuration.atomic_json', side_effect=cancel_after_commit):
                saved = save_policy_snapshot(temp, document, enabled=True, expected_revision=original['revision'], cancel=cancel)
            self.assertTrue(cancel.is_set())
            self.assertTrue(saved['enabled'])
            self.assertTrue(saved['draft_saved'])
            self.assertEqual(saved['selection'], selected_policy_path(temp))

    def test_disabled_valid_draft_survives_restart_and_needs_explicit_enable(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            document = json.loads(self.policy(temp).read_text())
            saved = self.save(temp, document, enabled=False)
            self.assertTrue(saved['draft_saved'])
            self.assertFalse(saved['enabled'])
            self.assertEqual(document, saved['repositories'])
            self.assertNotIn(SETTING, environment(temp, validate({})))
            reloaded = load_policy_snapshot(temp)
            self.assertEqual(saved['revision'], reloaded['revision'])
            enabled = save_policy_snapshot(temp, reloaded['repositories'], enabled=True, expected_revision=reloaded['revision'])
            self.assertTrue(enabled['enabled'])
            self.assertEqual(document, read_desktop_policy(temp))

    def test_disable_repairs_malformed_selection_without_persisting_invalid_edits(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            selector = Path(temp) / 'data' / 'validation-settings.json'
            selector.parent.mkdir()
            selector.write_text('{invalid')
            snapshot = load_policy_snapshot(temp)
            self.assertEqual('INVALID', snapshot['state'])
            self.assertTrue(snapshot['error'])
            disabled = save_policy_snapshot(temp, {'owner/repo': {'commands': 'broken'}}, enabled=False,
                                            expected_revision=snapshot['revision'])
            self.assertEqual('DISABLED', disabled['state'])
            self.assertFalse(disabled['draft_saved'])
            self.assertEqual('', selected_policy_path(temp))
            self.assertNotIn(SETTING, environment(temp, validate({})))
            self.assertFalse((Path(temp) / 'data' / 'validation-policies').exists())

    def test_invalid_selected_policy_can_be_repaired_with_observed_revision(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            external = self.policy(temp)
            saved = self.save(temp, json.loads(external.read_text()))
            broken = {'owner/repo': {'mirror': temp, 'image': 'unversioned', 'commands': [['true']]}}
            Path(saved['selection']).write_text(json.dumps(broken))
            observed = load_policy_snapshot(temp)
            self.assertEqual('INVALID', observed['state'])
            self.assertEqual(broken, observed['repositories'])
            fixed = json.loads(external.read_text())
            repaired = save_policy_snapshot(temp, fixed, enabled=True, expected_revision=observed['revision'])
            self.assertEqual('READY', repaired['state'])
            self.assertNotEqual(saved['selection'], repaired['selection'])
            self.assertEqual(broken, json.loads(Path(saved['selection']).read_text()))

    def complete_contract(self, root):
        entry = json.loads(self.policy(root).read_text())['owner/repo']
        requirement = {'id': 'feature', 'paths': ['app/feature.py'], 'checks': ['Behavior tests'],
                       'evidence': [{'kind': 'behavior_test', 'name': 'Feature.test_behavior'}]}
        entry.update(allowed_checks=['Behavior tests'],
                     acceptance_map=[{'requirement_id': 'feature', 'case_id': 'Feature.test_behavior', 'command_index': 0,
                                      'scope': 'behavior', 'report_path': 'reports/results.xml'}],
                     acceptance_templates=[{'objectives': ['Implement the feature'], 'requirements': [requirement]}],
                     draft_repair_requirements=[requirement],
                     operator_note={'retained_unexposed_key': 'Preserve this host annotation'})
        return entry

    @staticmethod
    def ready_probe(argv, *_args, **_kwargs):
        if argv[0] == 'docker':
            return {'exit_code': 0, 'output_tail': 'sha256:' + 'b' * 64}
        return {'exit_code': 0, 'output_tail': 'true' if '--is-bare-repository' in argv else ''}

    def test_complete_advanced_contract_preserves_fields_and_passes_actual_preflight(self):
        from v14.engineering import validate_request
        from v14.engineering_preflight import preflight
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            entry = self.complete_contract(temp)
            saved = self.save(temp, {'owner/repo': entry})
            self.assertEqual(entry, read_desktop_policy(temp)['owner/repo'])
            request = {'repository': 'owner/repo', 'branch': 'repair/feature', 'base': 'main',
                       'objective': 'Implement the feature', 'model': 'fixture', 'request_id': 'admission-check',
                       'source_sha': '1' * 40, 'source_branch': 'feature/source', 'task_scope': 'implement'}
            with patch.dict(os.environ, {SETTING: saved['selection']}):
                result = preflight(validate_request(request), runner=self.ready_probe)
                draft = dict(request, draft_repair={'repository': 'owner/repo', 'pull_number': 1,
                                                   'head_sha': '1' * 40, 'source_branch': 'feature/source', 'base_branch': 'main'})
                repair = preflight(validate_request(draft), runner=self.ready_probe)
            self.assertTrue(result['ready'], result)
            self.assertEqual('HOST_OBJECTIVE_TEMPLATE', result['acceptance_source'])
            self.assertTrue(repair['ready'], repair)
            self.assertEqual('HOST_DRAFT_REPAIR_CONTRACT', repair['acceptance_source'])
            self.assertEqual('NOT_ADMITTED', result['execution_owner'])
            self.assertEqual(entry['draft_repair_requirements'], result['acceptance'])

    def test_basic_defaults_have_no_icu_dependency_or_fabricated_acceptance(self):
        from v14.engineering_preflight import preflight
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            entry = default_policy(temp)
            self.assertEqual(str(Path(temp).resolve() / 'data' / 'mirrors' / 'GPT_Mobile_AI-improved.git'), entry['mirror'])
            self.assertEqual([['python3', '-m', 'compileall', '-q', 'gateway', 'authentication', 'scripts'],
                              ['python3', 'scripts/check_ci_policy.py']], entry['commands'])
            self.assertEqual([], entry['allowed_checks'])
            self.assertEqual([], entry['acceptance_map'])
            self.assertEqual([], entry['acceptance_templates'])
            self.assertEqual([], entry['draft_repair_requirements'])
            entry['image'] = 'python@sha256:' + 'a' * 64
            saved = self.save(temp, {DEFAULT_REPOSITORY: entry})
            request = {'repository': DEFAULT_REPOSITORY, 'branch': 'feature/new', 'base': 'main',
                       'objective': 'Implement a feature', 'model': 'fixture', 'request_id': 'default-check', 'source_sha': '1' * 40}
            with patch.dict(os.environ, {SETTING: saved['selection']}):
                result = preflight(request, runner=self.ready_probe)
            self.assertFalse(result['ready'])
            self.assertEqual({'ACCEPTANCE_CONTRACT_REQUIRED', 'VALIDATION_CHECKS_REQUIRED'},
                             {item['reason_code'] for item in result['blockers']})
            self.assertEqual('ready', result['checks']['validation_policy'])
            self.assertEqual('ready', result['checks']['source_revision'])
            self.assertEqual('ready', result['checks']['validation_runtime'])

    def test_invalid_advanced_fields_never_replace_active_policy(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: ''}):
            good = self.complete_contract(temp)
            saved = self.save(temp, {'owner/repo': good})
            active_bytes = Path(saved['selection']).read_bytes()
            cases = [
                {'allowed_checks': 'tests'}, {'allowed_checks': [False]}, {'allowed_checks': ['test'] * 257},
                {'acceptance_templates': {}}, {'acceptance_templates': [{'objectives': ['xy'], 'requirements': []}]},
                {'acceptance_templates': [{'objectives': ['Valid objective'], 'requirements': [{'id': 'missing'}]}]},
                {'draft_repair_requirements': [{'id': 'bad', 'paths': ['../escape'], 'checks': ['Behavior tests']}]},
                {'draft_repair_requirements': [{'id': 'bad', 'paths': ['file'], 'checks': ['Behavior tests'],
                                                'evidence': [{'kind': 'invented', 'name': 'case'}]}]},
                {'timeout_seconds': True}, {'mirror': 'relative'}, {'commands': [['python3', '\0']]},
                {'operator_note': 'x' * 65536},
            ]
            for changes in cases:
                with self.subTest(changes=list(changes)), self.assertRaises(ValueError):
                    save_policy_snapshot(temp, {'owner/repo': dict(good, **changes)}, enabled=True,
                                         expected_revision=saved['revision'])
                self.assertEqual(saved['selection'], selected_policy_path(temp))
                self.assertEqual(active_bytes, Path(saved['selection']).read_bytes())
                self.assertEqual(saved['revision'], load_policy_snapshot(temp)['revision'])

    def test_policy_check_does_not_modify_environment_or_execute_commands(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {SETTING: '/existing.json'}, clear=True), \
                patch('v14.engineering_validation.run_bounded') as execute:
            policy = self.policy(temp)
            self.assertEqual('VALIDATED', check_policy_file(str(policy))['state'])
            self.assertEqual('/existing.json', os.environ[SETTING])
            execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
