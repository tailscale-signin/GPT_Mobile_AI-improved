"""Headless behavior controls for dialog ownership and explicit policy repair.

Widget doubles exercise the real dialog commands; native layout remains covered
by the required Windows UI tests, not by these controls.
"""
from contextlib import contextmanager, ExitStack
from copy import deepcopy
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import acceptance_discovery_dialog as discovery
from desktop import release_checks_dialog as release
from desktop import repository_validation as validation
from desktop import repository_validation_panel as panels
from desktop.configuration import atomic_json
from desktop.ui import WindowCallbacks
from desktop.validation_policy import SETTING, PolicyConflictError, load_policy_snapshot, save_policy_snapshot


class Widget:
    def __init__(self, master=None, **options):
        self.master, self.options = master, options
        self.children, self.rows, self.selected = [], [], []
        self.bindings, self.protocols, self.scheduled = {}, {}, {}
        self.destroyed = False
        self.top = master is None
        self.serial = 0
        if master is not None:
            master.children.append(self)

    def alive(self):
        if self.destroyed:
            raise AssertionError('A destroyed widget was accessed')

    def configure(self, **options):
        self.alive()
        self.options.update(options)

    def grid(self, **options):
        self.alive()

    pack = grid

    def columnconfigure(self, *args, **options):
        self.alive()

    rowconfigure = columnconfigure
    title = columnconfigure
    geometry = columnconfigure

    def transient(self, parent=None):
        if parent is not None:
            self.options['transient'] = parent
        return self.options.get('transient')

    def bind(self, event, callback, add=None):
        self.bindings.setdefault(event, []).append(callback)

    def protocol(self, event, callback):
        self.protocols[event] = callback

    def winfo_toplevel(self):
        return self if self.top else self.master.winfo_toplevel()

    def after(self, milliseconds, callback):
        self.alive()
        owner = self.winfo_toplevel()
        owner.serial += 1
        handle = str(owner.serial)
        owner.scheduled[handle] = callback
        return handle

    def after_cancel(self, handle):
        self.winfo_toplevel().scheduled.pop(handle, None)

    def pump(self):
        for handle in tuple(self.scheduled):
            callback = self.scheduled.pop(handle, None)
            if callback:
                callback()

    def destroy(self):
        if self.destroyed:
            return
        for child in tuple(self.children):
            child.destroy()
        self.destroyed = True
        for callback in self.bindings.get('<Destroy>', []):
            callback(SimpleNamespace(widget=self))

    def invoke(self):
        self.alive()
        if self.options.get('state') != 'disabled':
            self.options['command']()

    def insert(self, index, value):
        self.alive()
        self.rows.append(value)

    def delete(self, first, last=None):
        self.alive()
        if last is not None:
            self.rows.clear()
            self.selected.clear()
        else:
            del self.rows[int(first)]

    def curselection(self):
        self.alive()
        return tuple(self.selected)

    def selection_set(self, index):
        self.alive()
        self.selected.append(index)


class Variable:
    def __init__(self, master=None, value=''):
        self.master, self.value = master, value

    def get(self):
        self.master.alive()
        return self.value

    def set(self, value):
        self.master.alive()
        self.value = value


class Panel:
    def __init__(self, root):
        self.root = root
        self.window = Widget()
        self.frame = Widget(self.window)
        self.frame.bind('<Destroy>', lambda event: panels.RepositoryValidationPanel.destroyed(self, event))
        self.window.settings_callbacks = WindowCallbacks(self.window)
        self.options = validation.defaults(root)
        self.revision = 'a' * 64
        self.busy = self.closed = self.conflicted = False
        self.cancel, self.worker = threading.Event(), None
        self.filled, self.notices, self.edits = [], [], 0

    def snapshot(self, *, validate=True):
        return validation.validate_options(self.options) if validate else deepcopy(self.options)

    def set_busy(self, value):
        self.frame.alive()
        self.busy = value

    def notice(self, text):
        self.frame.alive()
        self.notices.append(text)

    def fill(self, options):
        self.frame.alive()
        self.options = deepcopy(options)
        self.filled.append(deepcopy(options))

    def edited(self):
        self.edits += 1


def descendants(widget):
    for child in widget.children:
        yield child
        yield from descendants(child)


def button(window, label):
    return next(widget for widget in descendants(window) if widget.options.get('text') == label)


def statuses(window):
    return [widget.options['textvariable'].get() for widget in descendants(window)
            if 'textvariable' in widget.options]


class DesktopFeatureRepairTests(unittest.TestCase):
    result = {'head_sha': 'b' * 40, 'truncated': False, 'candidates': [
        {'case_id': 'test_behavior.Behavior.test_answer', 'path': 'tests/test_behavior.py',
         'framework': 'unittest', 'class_name': 'test_behavior.Behavior'}]}

    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.root = Path(self.stack.enter_context(tempfile.TemporaryDirectory())).resolve()
        self.stack.enter_context(patch.dict(os.environ, {SETTING: ''}))
        for name in ('Toplevel', 'Listbox', 'Text'):
            self.stack.enter_context(patch.object(discovery.tk, name, Widget))
        self.stack.enter_context(patch.object(discovery.tk, 'StringVar', Variable))
        for name in ('Label', 'Entry', 'Combobox', 'Button', 'Frame'):
            self.stack.enter_context(patch.object(discovery.ttk, name, Widget))
        self.panel = Panel(self.root)
        self.addCleanup(self.panel.window.destroy)

    @contextmanager
    def slow_discovery(self):
        started, cleanup = threading.Event(), threading.Event()
        workers = []

        def work(options, query, cancel, *, source_sha=None):
            workers.append((threading.current_thread(), cancel))
            started.set()
            cancel.wait(3)
            cleanup.wait(3)
            return deepcopy(self.result)

        with patch.object(discovery, 'discover', side_effect=work) as call:
            try:
                yield started, cleanup, call
            finally:
                for worker, cancel in workers:
                    cancel.set()
                cleanup.set()
                for worker, cancel in workers:
                    worker.join(3)
                    self.assertFalse(worker.is_alive())

    def test_second_dialog_cannot_replace_active_worker_or_cancel_its_event(self):
        first, second = discovery.open_dialog(self.panel), discovery.open_dialog(self.panel)
        with self.slow_discovery() as (started, cleanup, call):
            button(first, 'Find tests').invoke()
            self.assertTrue(started.wait(2))
            worker, cancel = self.panel.worker, self.panel.cancel
            button(second, 'Find tests').invoke()
            self.assertEqual(1, call.call_count)
            self.assertIs(worker, self.panel.worker)
            self.assertIs(cancel, self.panel.cancel)
            second.protocols['WM_DELETE_WINDOW']()
            self.assertFalse(cancel.is_set(), 'Closing an idle sibling must not cancel the active operation')

    def test_previously_discovered_result_cannot_apply_while_another_worker_is_active(self):
        first, second = discovery.open_dialog(self.panel), discovery.open_dialog(self.panel)
        with patch.object(discovery, 'discover', return_value=deepcopy(self.result)):
            button(second, 'Find tests').invoke()
            self.panel.worker.join(2)
            self.panel.window.pump()
        listing = next(widget for widget in descendants(second) if widget.rows and self.result['candidates'][0]['case_id'] in widget.rows[0])
        listing.selection_set(0)
        with self.slow_discovery() as (started, cleanup, call), patch.object(discovery, 'add_discovered', return_value=self.panel.options) as add:
            button(first, 'Find tests').invoke()
            self.assertTrue(started.wait(2))
            button(second, 'Add selected task check').invoke()
            add.assert_not_called()
            self.assertEqual([], self.panel.filled)

    def test_close_cancels_delivery_but_keeps_busy_until_owned_cleanup_finishes(self):
        dialog = discovery.open_dialog(self.panel)
        callbacks = self.panel.window.settings_callbacks
        with self.slow_discovery() as (started, cleanup, call):
            button(dialog, 'Find tests').invoke()
            self.assertTrue(started.wait(2))
            worker, cancel = self.panel.worker, self.panel.cancel
            queued = set(callbacks.pending)
            self.assertTrue(queued, 'Discovery callbacks must belong to the Settings window')
            dialog.protocols['WM_DELETE_WINDOW']()
            self.assertTrue(dialog.destroyed)
            self.assertTrue(cancel.is_set())
            self.assertTrue(queued.isdisjoint(callbacks.pending))
            self.panel.window.pump()
            self.assertTrue(self.panel.busy)
            self.assertIs(worker, self.panel.worker)
            cleanup.set()
            worker.join(2)
            self.assertFalse(worker.is_alive())
            self.panel.window.pump()  # Access to any destroyed dialog widget fails this test.
            self.assertFalse(self.panel.busy)
            self.assertFalse(callbacks.pending)
            with patch.object(discovery, 'discover', return_value=deepcopy(self.result)):
                replacement = discovery.open_dialog(self.panel)
                button(replacement, 'Find tests').invoke()
                next_cancel = self.panel.cancel
                dialog.protocols['WM_DELETE_WINDOW']()
                self.assertFalse(next_cancel.is_set(), 'Late close must target only its own event')
                self.panel.worker.join(2)
                self.panel.window.pump()

    def test_parent_destroy_cancels_callbacks_and_owner_waits_for_cleanup(self):
        dialog = discovery.open_dialog(self.panel)
        panels._PANELS.add(self.panel)
        self.addCleanup(panels._PANELS.discard, self.panel)
        with self.slow_discovery() as (started, cleanup, call):
            button(dialog, 'Find tests').invoke()
            self.assertTrue(started.wait(2))
            worker, callbacks = self.panel.worker, self.panel.window.settings_callbacks
            self.assertFalse(worker.daemon)
            self.panel.window.destroy()
            self.assertTrue(self.panel.closed)
            self.assertTrue(self.panel.cancel.is_set())
            self.assertFalse(callbacks.pending)
            self.assertFalse(self.panel.window.scheduled)
            self.assertIs(worker, self.panel.worker)
            settled = threading.Event()
            owner = threading.Thread(target=lambda: (panels.settle_setup_workers(self.root), settled.set()))
            owner.start()
            try:
                self.assertFalse(settled.wait(.05), 'Desktop ownership must outlive pending child cleanup')
                cleanup.set()
                owner.join(2)
                self.assertTrue(settled.is_set())
            finally:
                cleanup.set()
                owner.join(2)
            self.panel.window.pump()

    def test_unexpected_discovery_failure_releases_busy_without_exposing_raw_error(self):
        dialog = discovery.open_dialog(self.panel)
        with patch.object(discovery, 'discover', side_effect=RuntimeError('private subprocess fixture detail')):
            button(dialog, 'Find tests').invoke()
            self.panel.worker.join(2)
            self.panel.window.pump()
        self.assertFalse(self.panel.busy)
        self.assertFalse(self.panel.window.settings_callbacks.pending)
        self.assertTrue(any('cancelled or unavailable' in str(value) for value in statuses(dialog)))
        self.assertFalse(any('private subprocess' in str(value) for value in statuses(dialog)))

    def test_explicit_source_sha_reaches_owned_discovery_worker(self):
        dialog = discovery.open_dialog(self.panel)
        label = button(dialog, 'Source SHA (optional)')
        entry = dialog.children[dialog.children.index(label) + 1]
        entry.options['textvariable'].set('a' * 40)
        with patch.object(discovery, 'discover', return_value=deepcopy(self.result)) as call:
            button(dialog, 'Find tests').invoke()
            self.panel.worker.join(3)
            self.assertFalse(self.panel.worker.is_alive())
            self.panel.window.pump()
        self.assertEqual('a' * 40, call.call_args.kwargs['source_sha'])
        self.assertIs(self.panel.cancel, call.call_args.args[2])
        self.assertFalse(self.panel.worker.daemon)
        self.assertFalse(self.panel.busy)

    def test_malformed_release_contract_can_be_reviewed_and_saved_without_changing_external_bytes(self):
        artifact = {'workflow': '.github/workflows/build.yml', 'name': 'package', 'maximum_bytes': 1024}
        empty = {'artifacts': [], 'installation_checks': [], 'smoke_checks': []}
        cases = [(True, empty),
                 ({'artifacts': [artifact], 'installation_checks': 'bad', 'smoke_checks': [0]},
                  {'artifacts': [artifact], 'installation_checks': [], 'smoke_checks': [0]}),
                 ({'artifacts': 'bad', 'installation_checks': [0], 'smoke_checks': [1]},
                  {'artifacts': [], 'installation_checks': [0], 'smoke_checks': [1]})]
        for malformed, expected in cases:
            with self.subTest(malformed=malformed), tempfile.TemporaryDirectory(dir=self.root) as folder:
                root = Path(folder)
                options = validation.defaults(root)
                options['image'] = 'docker.io/library/python@sha256:' + 'a' * 64
                entry = validation.entry_from_options(options, {'unknown_policy_field': {'retain': True}})
                sibling = deepcopy(entry)
                entry['post_merge'] = deepcopy(malformed)
                document = {options['repository']: entry, 'other/repository': sibling}
                external = root / 'external.json'
                atomic_json(external, document)
                selector = root / 'data' / 'validation-settings.json'
                atomic_json(selector, {'schema': 1, 'policy_path': str(external)})
                old_bytes, old_selector = external.read_bytes(), selector.read_bytes()
                snapshot = load_policy_snapshot(root)
                self.assertEqual('INVALID', snapshot['state'])
                self.panel.options = validation.options_for(root, options['repository'], snapshot['repositories'])
                self.panel.revision = snapshot['revision']
                opened = deepcopy(self.panel.options)
                dialog = release.open_dialog(self.panel)
                self.assertIsNotNone(dialog, 'Malformed release fields must open an explicit repair form')
                self.assertTrue(any('Repair draft' in str(value) for value in statuses(dialog)))
                self.assertEqual(opened, self.panel.options)
                button(dialog, 'Apply requirements').invoke()
                self.assertTrue(dialog.destroyed)
                self.assertEqual(expected, self.panel.options['post_merge'])
                self.assertEqual(old_bytes, external.read_bytes())
                self.assertEqual(old_selector, selector.read_bytes(), 'Apply is not approval or activation')
                replacement = deepcopy(snapshot['repositories'])
                replacement[options['repository']] = validation.entry_from_options(self.panel.options, entry)
                saved = save_policy_snapshot(root, replacement, enabled=True, expected_revision=snapshot['revision'])
                self.assertEqual('READY', saved['state'])
                self.assertEqual(expected, saved['repositories'][options['repository']]['post_merge'])
                self.assertEqual({'retain': True}, saved['repositories'][options['repository']]['unknown_policy_field'])
                self.assertEqual(sibling, saved['repositories']['other/repository'])
                self.assertEqual(old_bytes, external.read_bytes())
                with self.assertRaises(PolicyConflictError):
                    save_policy_snapshot(root, document, enabled=False, expected_revision=snapshot['revision'])

    def test_release_repair_rejects_busy_conflicted_or_changed_opening_snapshot(self):
        for changed in ('busy', 'conflicted', 'revision', 'options'):
            with self.subTest(changed=changed):
                self.panel.options = validation.defaults(self.root)
                self.panel.options['post_merge'] = True
                self.panel.busy = self.panel.conflicted = False
                self.panel.revision = 'a' * 64
                self.panel.filled.clear()
                dialog = release.open_dialog(self.panel)
                self.assertIsNotNone(dialog)
                if changed == 'options':
                    self.panel.options['timeout_seconds'] += 1
                else:
                    setattr(self.panel, changed, 'b' * 64 if changed == 'revision' else True)
                button(dialog, 'Apply requirements').invoke()
                self.assertEqual([], self.panel.filled)
                self.assertFalse(dialog.destroyed)
                self.assertTrue(any('Settings changed' in str(value) for value in statuses(dialog)))
                dialog.destroy()

    def test_release_repair_still_validates_all_other_policy_fields(self):
        for key, value in (('timeout_seconds', 0), ('mirror', 'relative'), ('allowed_checks', [''])):
            with self.subTest(key=key):
                self.panel.options = validation.defaults(self.root)
                self.panel.options.update(post_merge=True)
                self.panel.options[key] = value
                self.assertIsNone(release.open_dialog(self.panel))
                self.assertEqual([], self.panel.filled)
                self.assertTrue(self.panel.notices)


if __name__ == '__main__':
    unittest.main()
