"""Native settings tests: Tk is required on Windows; Linux needs an X display."""
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
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import repository_validation as validation
from desktop.repository_validation_panel import RepositoryValidationPanel, CONTRACT_FIELDS
from desktop.configuration import atomic_json, DEFAULTS
from desktop.validation_policy import SETTING


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
        self.assertEqual(set(CONTRACT_FIELDS), set(json.loads(self.panel.contract.get('1.0', 'end'))))

    def test_git_hub_settings_contains_two_real_subtabs_and_no_external_policy_field(self):
        from desktop.ui import build_window
        settings = build_window(self.root, self.window, section='GitHub')
        try:
            settings.update()
            notebooks = [w for w in descendants(settings) if isinstance(w, ttk.Notebook)]
            inner = next(w for w in notebooks if [w.tab(t, 'text') for t in w.tabs()] == ['Connection', 'Repository Validation'])
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
                         and [w.tab(t, 'text') for t in w.tabs()] == ['Connection', 'Repository Validation'])
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


if __name__ == '__main__':
    unittest.main()
