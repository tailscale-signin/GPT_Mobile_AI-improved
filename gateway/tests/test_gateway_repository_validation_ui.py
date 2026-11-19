"""Native settings tests: Tk is required on Windows; Linux needs an X display."""
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import tkinter as tk
from tkinter import ttk
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import repository_validation as validation
from desktop.repository_validation_panel import RepositoryValidationPanel, CONTRACT_FIELDS
from desktop.configuration import atomic_json, DEFAULTS
from desktop.validation_policy import SETTING, load_policy_snapshot, save_policy_snapshot


def descendants(widget):
    for child in widget.winfo_children():
        yield child
        yield from descendants(child)


class RepositoryValidationUiTests(unittest.TestCase):
    def setUp(self):
        try:
            self.window = tk.Tk()
        except tk.TclError as error:
            if sys.platform == 'win32':
                raise
            self.skipTest('Native display unavailable: ' + str(error))
        self.addCleanup(self.window.destroy)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {SETTING: ''})
        self.env.start()
        self.addCleanup(self.env.stop)
        atomic_json(self.root / 'settings.json', DEFAULTS)
        self.frame = ttk.Frame(self.window)
        self.frame.pack(fill='both', expand=True)
        self.panel = RepositoryValidationPanel(self.frame, self.root, DEFAULTS)
        self.window.update()

    def test_builtin_fields_and_safe_defaults_do_not_require_a_file_chooser(self):
        self.assertEqual(validation.DEFAULT_REPOSITORY, self.panel.fields['repository'].get())
        self.assertEqual(validation.SOURCE_PROFILE, self.panel.fields['profile'].get())
        self.assertEqual('600', self.panel.fields['timeout_seconds'].get())
        self.assertFalse(self.panel.advanced_visible.get())
        self.assertEqual(str(self.panel.commands.cget('state')), 'disabled')
        self.assertFalse((self.root / 'data/validation-settings.json').exists())
        contract = json.loads(self.panel.contract.get('1.0', 'end'))
        self.assertEqual(set(CONTRACT_FIELDS) | {'workspace_hooks', 'workspace_environment'}, set(contract))
        self.assertEqual({}, contract['workspace_hooks'])
        self.assertEqual({}, contract['workspace_environment'])

    def test_git_hub_settings_contains_all_three_subtabs_and_no_external_policy_field(self):
        from desktop.ui import build_window
        settings = build_window(self.root, self.window, section='GitHub')
        try:
            settings.update()
            notebooks = [w for w in descendants(settings) if isinstance(w, ttk.Notebook)]
            inner = next(w for w in notebooks if [w.tab(t, 'text') for t in w.tabs()] == ['Connection', 'Repository Validation', 'Job admission'])
            inner.select(inner.tabs()[1])
            settings.update()
            self.assertTrue(settings.repository_validation_panel.frame.winfo_ismapped())
            texts = {str(w.cget('text')) for w in descendants(settings) if isinstance(w, ttk.Button)}
            self.assertIn('Prepare & save', texts)
            self.assertIn('Check setup', texts)
            self.assertNotIn('Choose policy…', texts)
            self.assertNotIn('Check policy', texts)
        finally:
            settings.destroy()

    def test_repository_switch_requires_load_and_lowercases_the_url(self):
        self.panel.fields['repository'].set('https://github.com/Another/Project.git')
        with self.assertRaisesRegex(ValueError, 'Load / add'):
            self.panel.snapshot()
        self.panel.load_repository()
        self.assertEqual('another/project', self.panel.fields['repository'].get())
        self.assertNotEqual(validation.defaults(self.root)['mirror'], self.panel.fields['mirror'].get())
        self.panel.fields['profile'].set(validation.CUSTOM_PROFILE)
        self.panel.profile_changed()
        self.assertTrue(self.panel.advanced_visible.get())

    def test_editing_image_source_removes_old_pin_without_mutating_saved_options(self):
        self.panel.loaded['image'] = 'python@sha256:' + 'a' * 64
        self.panel.fields['image_source'].set('python:3.12-bookworm')
        options = self.panel.snapshot()
        self.assertEqual('', options['image'])
        self.assertNotEqual('', self.panel.loaded['image'])

    def test_custom_command_and_contract_inputs_are_validated(self):
        self.panel.fields['profile'].set(validation.CUSTOM_PROFILE)
        self.panel.profile_changed()
        self.panel.commands.delete('1.0', 'end')
        self.panel.commands.insert('1.0', json.dumps([['python3', 'tests/run.py']]))
        self.assertEqual([['python3', 'tests/run.py']], self.panel.snapshot()['commands'])
        self.panel.contract.delete('1.0', 'end')
        self.panel.contract.insert('1.0', '{}')
        with self.assertRaises(ValueError):
            self.panel.snapshot()

    def test_no_background_execution_without_prepare_confirmation(self):
        with patch('desktop.repository_validation_panel.messagebox.askyesno', return_value=False), \
                patch.object(validation, 'setup') as execute:
            self.panel.start(True)
        execute.assert_not_called()
        self.assertFalse(self.panel.busy)

    def test_cancel_button_targets_current_worker_event_not_the_initial_event(self):
        started, finished = threading.Event(), threading.Event()
        observed = []
        def waiting(*args, **kwargs):
            observed.append(threading.current_thread())
            started.set()
            kwargs['cancel'].wait(5)
            finished.set()
            raise validation.SetupCancelled('Setup cancelled')
        with patch.object(validation, 'setup', side_effect=waiting):
            self.panel.start(False)
            self.assertTrue(started.wait(2))
            self.assertTrue(self.panel.busy)
            self.panel.cancel_button.invoke()
            self.assertTrue(finished.wait(2))
            self.assertIsNot(observed[0], threading.main_thread())
            deadline = time.monotonic() + 2
            while self.panel.busy and time.monotonic() < deadline:
                self.panel.refresh()
                time.sleep(.01)
        self.assertFalse(self.panel.busy)
        self.assertEqual('Setup cancelled', self.panel.status.get())

    def test_destroy_cancels_worker_and_ignores_late_ui_results(self):
        event = self.panel.cancel
        self.frame.destroy()
        self.assertTrue(event.is_set())
        self.panel.results.put(('progress', 'late result'))
        self.panel.refresh()  # Must not access a destroyed Tk variable/widget.
        self.assertTrue(self.panel.closed)

    def test_failed_setup_does_not_display_ready(self):
        self.panel.set_busy(True)
        self.panel.results.put(('error', 'Start Docker Desktop'))
        self.panel.refresh()
        self.assertEqual('Start Docker Desktop', self.panel.status.get())
        self.assertFalse(self.panel.busy)

    def test_small_window_keeps_every_input_within_horizontal_bounds(self):
        from desktop.ui import build_window
        settings = build_window(self.root, self.window, section='GitHub')
        try:
            settings.geometry('660x500')
            inner = next(w for w in descendants(settings) if isinstance(w, ttk.Notebook)
                         and [w.tab(t, 'text') for t in w.tabs()] == ['Connection', 'Repository Validation', 'Job admission'])
            inner.select(inner.tabs()[1])
            settings.update()
            panel = settings.repository_validation_panel
            for advanced in (False, True):
                panel.advanced_visible.set(advanced)
                panel.toggle_advanced()
                settings.update()
                for control in panel.inputs:
                    if not control.winfo_ismapped():
                        continue
                    self.assertGreaterEqual(control.winfo_rootx(), settings.winfo_rootx())
                    self.assertLessEqual(control.winfo_rootx() + control.winfo_width(),
                                         settings.winfo_rootx() + settings.winfo_width(), str(control))
        finally:
            settings.destroy()

    def saved_policy(self, *, second=False, enabled=True):
        mapping = [{'requirement_id': 'behavior', 'case_id': 'Fixture.case', 'command_index': 0,
                    'scope': 'behavior', 'report_path': 'reports/cases.xml'}]
        requirement = {'id': 'behavior', 'paths': ['app/feature.py'], 'checks': ['tests']}
        entry = {
            'mirror': str(self.root / 'mirror.git'), 'image': 'fixture@sha256:' + 'a' * 64,
            'timeout_seconds': 600, 'commands': [['python3', '--version']], 'allowed_checks': ['tests'],
            'acceptance_map': mapping, 'parallel_command_groups': [],
            'acceptance_templates': [{'objectives': ['Repair a behavior'], 'requirements': [requirement]}],
            'draft_repair_requirements': [requirement], 'retained_host_metadata': {'keep': 'legacy value'},
            'desktop_setup': {'schema': 1, 'owner_annotation': 'retain this'},
        }
        document = {validation.DEFAULT_REPOSITORY: entry}
        if second:
            document['owner/other'] = copy.deepcopy(entry)
        saved = save_policy_snapshot(self.root, document, enabled=enabled,
                                     expected_revision=load_policy_snapshot(self.root)['revision'])
        self.panel.reload(confirm=False)
        return saved

    def contract_value(self, value):
        self.panel.contract.delete('1.0', 'end')
        self.panel.contract.insert('1.0', value if isinstance(value, str) else json.dumps(value))
        self.window.update()

    def select_repository(self, name):
        self.panel.fields['repository'].set(name)
        self.panel.load_repository()
        self.window.update()

    def test_direct_save_preserves_full_contract_and_unknown_legacy_keys_without_setup(self):
        saved = self.saved_policy(second=True)
        original = saved['repositories'][validation.DEFAULT_REPOSITORY]
        self.panel.fields['timeout_seconds'].set('700')
        with patch.object(validation, 'setup') as setup:
            self.panel.save_button.invoke()
        setup.assert_not_called()
        result = load_policy_snapshot(self.root)
        current = result['repositories'][validation.DEFAULT_REPOSITORY]
        self.assertTrue(result['enabled'])
        self.assertEqual(700, current['timeout_seconds'])
        self.assertEqual(original['retained_host_metadata'], current['retained_host_metadata'])
        self.assertEqual('retain this', current['desktop_setup']['owner_annotation'])
        self.assertEqual(saved['repositories']['owner/other'], result['repositories']['owner/other'])
        for key in CONTRACT_FIELDS:
            self.assertEqual(original[key], current[key], key)
        self.assertFalse(self.panel.dirty)

    def test_conflict_preserves_other_repository_and_edits_until_confirmed_reload(self):
        saved = self.saved_policy()
        newer = copy.deepcopy(saved['repositories'])
        newer['owner/new'] = copy.deepcopy(newer[validation.DEFAULT_REPOSITORY])
        save_policy_snapshot(self.root, newer, enabled=True, expected_revision=saved['revision'])
        self.panel.fields['timeout_seconds'].set('700')
        self.panel.save_button.invoke()
        self.assertTrue(self.panel.conflicted)
        self.assertTrue(self.panel.save_button.instate(['disabled']))
        self.assertTrue(self.panel.check_button.instate(['disabled']))
        self.assertTrue(self.panel.behavior_button.instate(['disabled']))
        with patch('desktop.repository_validation_panel.tk.Toplevel') as dialog:
            self.panel.add_behavior_check()
            dialog.assert_not_called()
        self.assertEqual('700', self.panel.fields['timeout_seconds'].get())
        self.assertEqual(newer, load_policy_snapshot(self.root)['repositories'])
        with patch('desktop.repository_validation_panel.messagebox.askyesno', return_value=False) as confirm:
            self.panel.reload_button.invoke()
        confirm.assert_called_once()
        self.assertEqual('700', self.panel.fields['timeout_seconds'].get())
        self.assertTrue(self.panel.conflicted)
        with patch('desktop.repository_validation_panel.messagebox.askyesno', return_value=True):
            self.panel.reload_button.invoke()
        self.assertFalse(self.panel.conflicted)
        self.assertFalse(self.panel.save_button.instate(['disabled']))
        self.assertFalse(self.panel.check_button.instate(['disabled']))
        self.assertFalse(self.panel.behavior_button.instate(['disabled']))
        self.assertEqual('600', self.panel.fields['timeout_seconds'].get())
        self.assertIn('owner/new', self.panel.document)

    def test_disable_rejects_invalid_draft_without_losing_other_repository_edits(self):
        saved = self.saved_policy(second=True)
        self.panel.fields['timeout_seconds'].set('700')
        self.select_repository('owner/other')
        invalid = {key: copy.deepcopy(saved['repositories']['owner/other'][key]) for key in CONTRACT_FIELDS}
        invalid['allowed_checks'] = ['']
        self.contract_value(invalid)
        self.panel.enabled.set(False)
        self.panel.enabled_changed()
        self.panel.save_button.invoke()
        self.assertFalse(load_policy_snapshot(self.root)['enabled'])
        self.assertEqual(saved['repositories'], load_policy_snapshot(self.root)['repositories'])
        self.assertTrue(self.panel.dirty)
        self.assertEqual(700, self.panel.drafts[validation.DEFAULT_REPOSITORY]['timeout_seconds'])
        self.assertEqual([''], self.panel.drafts['owner/other']['allowed_checks'])
        self.assertEqual(invalid, json.loads(self.panel.contract.get('1.0', 'end')))
        self.select_repository(validation.DEFAULT_REPOSITORY)
        self.assertEqual('700', self.panel.fields['timeout_seconds'].get())
        self.select_repository('owner/other')
        invalid['allowed_checks'] = ['tests']
        self.contract_value(invalid)
        self.panel.save_button.invoke()
        result = load_policy_snapshot(self.root)
        self.assertFalse(result['enabled'])
        self.assertEqual(700, result['repositories'][validation.DEFAULT_REPOSITORY]['timeout_seconds'])
        self.assertFalse(self.panel.dirty)

    def test_disable_and_repair_remain_available_with_malformed_or_oversized_editor_json(self):
        saved = self.saved_policy()
        for raw in ('{broken', ' ' * (validation.MAX_POLICY_BYTES + 1), '[' * 1100 + ']' * 1100):
            with self.subTest(size=len(raw)):
                self.panel.reload(confirm=False)
                self.contract_value(raw)
                self.panel.enabled.set(False)
                self.panel.save_button.invoke()
                current = load_policy_snapshot(self.root)
                self.assertFalse(current['enabled'])
                self.assertEqual(saved['repositories'], current['repositories'])
                self.assertEqual(raw, self.panel.contract.get('1.0', 'end-1c'))
                self.assertTrue(self.panel.dirty)
                self.assertIn('not saved', self.panel.status.get())
        self.panel.reload(confirm=False)
        self.panel.enabled.set(True)
        self.panel.save_button.invoke()
        self.assertTrue(load_policy_snapshot(self.root)['enabled'])

    def test_invalid_saved_selector_is_repairable_without_blind_reset(self):
        path = self.root / 'data' / 'validation-settings.json'
        atomic_json(path, {'schema': 1, 'policy_path': 'invalid-relative-path'})
        self.panel.reload(confirm=False)
        self.assertIsNotNone(self.panel.revision)
        self.assertEqual('INVALID', self.panel.saved_snapshot['state'])
        self.assertFalse(self.panel.save_button.instate(['disabled']))
        self.panel.enabled.set(False)
        self.panel.save_button.invoke()
        self.assertFalse(load_policy_snapshot(self.root)['enabled'])
        self.assertEqual('', json.loads(path.read_text())['policy_path'])

    def test_prepare_keeps_disabled_choice_and_other_drafts_even_after_late_cancel(self):
        saved = self.saved_policy(second=True, enabled=False)
        self.panel.fields['timeout_seconds'].set('700')
        self.select_repository('owner/other')
        def prepared(root, options, revision, **kwargs):
            self.assertTrue(kwargs['prepare'])
            self.assertFalse(kwargs['enabled'])
            entry = validation.entry_from_options(options, saved['repositories']['owner/other'])
            snapshot = validation.save_snapshot(root, options['repository'], entry, revision, enabled=False)
            return {'saved': True, 'snapshot': snapshot, 'repository': options['repository'],
                    'entry': entry, 'image': entry['image']}
        with patch('desktop.repository_validation_panel.messagebox.askyesno', return_value=True), \
                patch('desktop.github_setup.configured_connection', return_value=('none', None)), \
                patch.object(validation, 'setup', side_effect=prepared):
            self.panel.start(True)
            self.panel.worker.join(3)
            self.assertFalse(self.panel.worker.is_alive())
            self.panel.cancel.set()  # A later cancel cannot retract an already committed selector.
            self.panel.refresh()
        self.assertFalse(self.panel.enabled.get())
        self.assertIn('validation remains disabled', self.panel.status.get())
        self.assertEqual(700, self.panel.drafts[validation.DEFAULT_REPOSITORY]['timeout_seconds'])
        self.assertEqual(600, load_policy_snapshot(self.root)['repositories'][validation.DEFAULT_REPOSITORY]['timeout_seconds'])
        self.assertTrue(self.panel.dirty)

    def test_check_setup_is_read_only_and_never_changes_the_enable_choice(self):
        saved = self.saved_policy(enabled=False)
        with patch.object(validation, 'setup', return_value={'saved': False, 'image': 'fixture@sha256:' + 'a' * 64}) as setup:
            self.panel.start(False)
            self.panel.worker.join(3)
            self.panel.refresh()
        self.assertFalse(setup.call_args.kwargs['prepare'])
        self.assertFalse(setup.call_args.kwargs['enabled'])
        self.assertEqual(saved['revision'], load_policy_snapshot(self.root)['revision'])
        self.assertFalse(self.panel.enabled.get())
        self.assertIn('No repository tests ran', self.panel.status.get())

    def test_later_invalid_repository_can_be_repaired_without_replacing_other_entries(self):
        saved = self.saved_policy(second=True)
        original_path = Path(saved['selection'])
        corrupted = copy.deepcopy(saved['repositories'])
        corrupted['owner/other'].pop('commands')
        corrupted['owner/other']['desktop_setup'] = 'broken'
        atomic_json(original_path, corrupted)
        original_bytes = original_path.read_bytes()
        self.panel.reload(confirm=False)
        self.assertEqual('INVALID', self.panel.saved_snapshot['state'])
        self.select_repository('owner/other')
        self.assertEqual('owner/other', self.panel.loaded['repository'])
        self.assertEqual(validation.CUSTOM_PROFILE, self.panel.fields['profile'].get())
        self.assertTrue(self.panel.advanced_visible.get())
        self.assertTrue(self.panel.dirty)
        self.assertIn('needs repair', self.panel.status.get())
        self.assertEqual([], json.loads(self.panel.commands.get('1.0', 'end')))
        self.assertEqual(original_bytes, original_path.read_bytes(), 'Opening a repair view must not mutate saved files')
        self.panel.commands.delete('1.0', 'end')
        self.panel.commands.insert('1.0', json.dumps(saved['repositories']['owner/other']['commands']))
        self.panel.fields['timeout_seconds'].set('700')
        self.panel.save_button.invoke()
        current = load_policy_snapshot(self.root)
        self.assertEqual('READY', current['state'])
        self.assertTrue(current['enabled'])
        self.assertEqual(saved['repositories'][validation.DEFAULT_REPOSITORY], current['repositories'][validation.DEFAULT_REPOSITORY])
        repaired = current['repositories']['owner/other']
        self.assertEqual(700, repaired['timeout_seconds'])
        self.assertEqual(saved['repositories']['owner/other']['retained_host_metadata'], repaired['retained_host_metadata'])
        self.assertIsInstance(repaired['desktop_setup'], dict)
        self.assertNotIn('repair_notice', repaired)
        self.assertEqual(original_bytes, original_path.read_bytes(), 'Repair writes a fresh revision and retains old files')

    def test_new_acceptance_edits_block_switch_to_source_profile(self):
        self.panel.fields['profile'].set(validation.CUSTOM_PROFILE)
        self.panel.profile_changed()
        self.panel.commands.delete('1.0', 'end')
        self.panel.commands.insert('1.0', json.dumps([['python3', 'tests/custom.py']]))
        contract = json.loads(self.panel.contract.get('1.0', 'end'))
        contract['acceptance_map'] = [{'requirement_id': 'behavior', 'case_id': 'Fixture.case',
                                       'command_index': 0, 'scope': 'behavior', 'report_path': 'reports/cases.xml'}]
        self.contract_value(contract)
        self.panel.fields['profile'].set(validation.SOURCE_PROFILE)
        self.panel.profile_changed()
        self.assertEqual(validation.CUSTOM_PROFILE, self.panel.fields['profile'].get())
        self.assertEqual([['python3', 'tests/custom.py']], json.loads(self.panel.commands.get('1.0', 'end')))
        self.assertEqual(contract, json.loads(self.panel.contract.get('1.0', 'end')))


class RepositoryValidationParsingTests(unittest.TestCase):
    def test_json_editor_accepts_bounded_valid_content(self):
        control = Mock()
        control.get.return_value = json.dumps({'allowed_checks': ['tests']})
        self.assertEqual({'allowed_checks': ['tests']}, RepositoryValidationPanel.read_json(control))

    def test_json_editor_rejects_large_unicode_before_parse(self):
        control = Mock()
        control.get.return_value = '"' + 'ü' * (validation.MAX_POLICY_BYTES // 2) + '"'
        with patch('desktop.repository_validation_panel.json.loads') as decode, self.assertRaisesRegex(ValueError, '64 KiB'):
            RepositoryValidationPanel.read_json(control)
        decode.assert_not_called()

    def test_json_editor_turns_excessive_nesting_into_clean_validation_error(self):
        control = Mock()
        control.get.return_value = '[' * 1100 + ']' * 1100
        with self.assertRaisesRegex(ValueError, 'not deeply nested'):
            RepositoryValidationPanel.read_json(control)


if __name__ == '__main__':
    unittest.main()
