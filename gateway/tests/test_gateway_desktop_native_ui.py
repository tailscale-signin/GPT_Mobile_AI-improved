"""Native acceptance: runs on Windows CI; a display is required on Linux."""
from pathlib import Path
import sys
import tempfile
import threading
import tkinter as tk
from tkinter import ttk
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.configuration import atomic_json, DEFAULTS


def descendants(widget):
    for child in widget.winfo_children():
        yield child
        yield from descendants(child)


class DesktopNativeAcceptance(unittest.TestCase):
    def setUp(self):
        try:
            self.window = tk.Tk()
        except tk.TclError as error:
            if sys.platform == 'win32':
                raise
            self.skipTest('Native GUI display unavailable: ' + str(error))
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)

    def tearDown(self):
        if hasattr(self, 'window'):
            try:
                self.window.destroy()
            except tk.TclError:
                pass
        if hasattr(self, 'temp'):
            self.temp.cleanup()

    def test_daily_destinations_render_and_send_stays_visible_at_minimum_size(self):
        from desktop.dashboard import Dashboard, PAGES
        dashboard = Dashboard(self.root, self.window)
        dashboard.smoke = True
        for width, height in ((900, 600), (1180, 790)):
            self.window.geometry(f'{width}x{height}')
            for page in PAGES:
                dashboard.navigate(page)
                self.window.update()
                self.assertEqual(page, dashboard.page)
                controls = ()
                if page in {'Chat', 'Saved'}:
                    controls = (dashboard.chat_page.send_button, dashboard.chat_page.model_box, dashboard.chat_page.composer,
                                dashboard.chat_page.more_button, dashboard.chat_page.remove_saved_button)
                    listing = dashboard.chat_page.list
                    row = listing.insert('', 'end', values=('Visible layout verification', 'Now'))
                    self.window.update()
                    bounds = listing.bbox(row, 'title')
                    self.assertTrue(bounds, 'The first saved conversation must be visible')
                    x, y, row_width, row_height = bounds
                    column_x = x + min(12, row_width // 2)
                    row_y = y + row_height // 2
                    self.assertEqual('cell', listing.identify_region(column_x, row_y))
                    self.assertIs(listing, self.window.winfo_containing(listing.winfo_rootx() + column_x,
                                                                       listing.winfo_rooty() + row_y),
                                  'Saved rows must not be obscured by another widget')
                    heading_y = next((offset for offset in range(y) if listing.identify_region(column_x, offset) == 'heading'), None)
                    self.assertIsNotNone(heading_y, 'The saved-list heading must be present')
                    self.assertIs(listing, self.window.winfo_containing(listing.winfo_rootx() + column_x,
                                                                       listing.winfo_rooty() + heading_y),
                                  'Saved-list headings must not be obscured by another widget')
                    listing.delete(row)
                elif page == 'Benchmarks':
                    panel = dashboard.benchmark_panel
                    panel.tabs.select(panel.pages['History'])
                    self.window.update()
                    controls = (panel.start, panel.stop_button, panel.export, panel.more, panel.remove)
                for button in controls:
                    self.assertTrue(button.winfo_ismapped())
                    self.assertGreater(button.winfo_height(), 12)
                    self.assertGreater(button.winfo_width(), 40)
                    self.assertGreaterEqual(button.winfo_rootx(), self.window.winfo_rootx())
                    self.assertLessEqual(button.winfo_rootx() + button.winfo_width(), self.window.winfo_rootx() + self.window.winfo_width())
                    self.assertLessEqual(button.winfo_rooty() + button.winfo_height(), self.window.winfo_rooty() + self.window.winfo_height())
        dashboard.close()

    def test_advanced_settings_are_hidden_until_requested(self):
        from desktop.ui import build_window
        settings = build_window(self.root, self.window)
        self.window.update()
        tabs = next(widget for widget in descendants(settings) if isinstance(widget, ttk.Notebook))
        advanced = next(tab for tab in tabs.tabs() if tabs.tab(tab, 'text') == 'Advanced')
        self.assertEqual('hidden', tabs.tab(advanced, 'state'))
        toggle = next(widget for widget in descendants(settings) if isinstance(widget, ttk.Checkbutton)
                      and widget.cget('text') == 'Show advanced settings')
        toggle.invoke()
        self.window.update()
        self.assertEqual('normal', tabs.tab(advanced, 'state'))
        self.assertEqual(advanced, tabs.select())
        settings.destroy()

    def validation_window(self):
        from desktop.ui import build_window
        settings = build_window(self.root, self.window, section='GitHub')
        self.assertEqual(str(self.window), str(settings.transient()))
        settings.geometry('660x500')
        tab = next(tab for tab in settings.github_tabs.tabs()
                   if settings.github_tabs.tab(tab, 'text') == 'Repository Validation')
        settings.github_tabs.select(tab)
        self.window.update()
        return settings, settings.repository_validation_panel

    def test_repository_validation_basic_actions_and_keyboard_disclosure_at_minimum_size(self):
        from desktop import repository_validation as validation
        with patch.object(validation, 'setup') as prepare:
            settings, panel = self.validation_window()
            self.assertFalse(panel.advanced_visible.get())
            # Child mapping can survive a hidden ancestor; check actual viewability.
            self.assertFalse(panel.advanced.winfo_viewable())
            self.assertFalse(panel.commands.winfo_viewable())
            self.assertFalse(panel.contract.winfo_viewable())
            for control in (panel.enable_button, panel.repository_picker, panel.profile_box, panel.discovery_button, panel.release_button, panel.advanced_toggle,
                            panel.prepare_button, panel.check_button, panel.save_button, panel.reload_button):
                self.assertTrue(control.winfo_viewable())
                self.assertGreater(control.winfo_width(), 40)
                self.assertGreater(control.winfo_height(), 12)
                self.assertGreaterEqual(control.winfo_rootx(), settings.winfo_rootx())
                self.assertGreaterEqual(control.winfo_rooty(), settings.winfo_rooty())
                self.assertLessEqual(control.winfo_rootx() + control.winfo_width(), settings.winfo_rootx() + settings.winfo_width())
                self.assertLessEqual(control.winfo_rooty() + control.winfo_height(), settings.winfo_rooty() + settings.winfo_height())
                self.assertIs(control, settings.winfo_containing(control.winfo_rootx() + control.winfo_width() // 2,
                                                                control.winfo_rooty() + control.winfo_height() // 2),
                              f'{control} must remain the topmost hit target at its visible center')
            panel.fields['profile'].set(validation.CUSTOM_PROFILE)
            panel.profile_changed()
            self.assertTrue(panel.advanced_visible.get())
            settings.focus_force()
            panel.commands.focus_set()
            self.window.update()
            self.assertTrue(panel.advanced.winfo_viewable())
            self.assertTrue(panel.commands.winfo_viewable())
            original = panel.commands.get('1.0', 'end-1c')
            panel.commands.event_generate('<Tab>')
            self.window.update()
            self.assertEqual(original, panel.commands.get('1.0', 'end-1c'), 'Tab must move focus, not insert invisible JSON text')
            self.assertIs(panel.contract, settings.focus_get())
            self.assertTrue(panel.contract.winfo_viewable())
            self.assertIs(panel.contract, settings.winfo_containing(panel.contract.winfo_rootx() + panel.contract.winfo_width() // 2,
                                                                   panel.contract.winfo_rooty() + panel.contract.winfo_height() // 2))
            panel.advanced_toggle.invoke()
            self.window.update()
            self.assertFalse(panel.advanced.winfo_viewable())
            self.assertFalse(panel.commands.winfo_viewable())
            self.assertFalse(panel.contract.winfo_viewable())
            prepare.assert_not_called()

    def test_closing_settings_cancels_slow_setup_and_its_queued_callbacks(self):
        from desktop import repository_validation as validation
        settings, panel = self.validation_window()
        started = threading.Event()
        finished = threading.Event()
        def waiting(*args, **kwargs):
            started.set()
            kwargs['cancel'].wait(5)
            finished.set()
            raise validation.SetupCancelled('Setup cancelled')
        with patch.object(validation, 'setup', side_effect=waiting):
            panel.start(False)
            self.assertTrue(started.wait(2))
            self.assertFalse(panel.worker.daemon, 'Process cleanup must outlive the Settings window')
            self.assertTrue(panel.busy)
            callbacks = settings.settings_callbacks
            pending = set(callbacks.pending)
            self.assertTrue(pending)
            child = tk.Frame(panel.frame)
            child.destroy()
            self.assertFalse(panel.closed)
            self.assertFalse(panel.cancel.is_set(), 'Destroying a child must not cancel setup')
            self.assertEqual(pending, callbacks.pending)
            settings.destroy()
            self.assertTrue(panel.closed)
            self.assertTrue(panel.cancel.is_set())
            self.assertTrue(finished.wait(2))
            panel.worker.join(2)
            self.assertFalse(panel.worker.is_alive())
            self.assertTrue(callbacks.closed)
            self.assertFalse(callbacks.pending)
            scheduled = set(self.window.tk.splitlist(self.window.tk.call('after', 'info')))
            self.assertFalse(pending & scheduled, 'Settings must cancel its Tcl callbacks before destroy')
            panel.results.put(('progress', 'late result'))
            panel.refresh()  # Closed panels never access destroyed Tk widgets.

    def test_chat_and_window_destruction_remove_their_queued_callbacks(self):
        from desktop.chat_panel import ChatPanel
        from desktop.chat_state import ChatState
        from desktop.window_lifecycle import watch
        def scheduled():
            return set(self.window.tk.splitlist(self.window.tk.call('after', 'info')))
        panel = ChatPanel(self.window, self.root)
        panel.pack(fill='both', expand=True)
        panel.composer.insert('end', 'Draft retained on close')
        self.window.update()
        panel.poll_timer = panel.after(2000, panel.refresh_conversation)
        chat_callbacks = {value for value in (panel.draft_timer, panel.poll_timer, panel.event_timer) if value}
        before_watch = scheduled()
        watch(self.window, self.root, self.window.destroy)
        watch_callbacks = scheduled() - before_watch
        self.assertEqual(1, len(watch_callbacks))
        child = tk.Frame(panel)
        child.destroy()
        self.assertTrue(chat_callbacks | watch_callbacks <= scheduled(), 'Destroying a child must not stop its observers')
        panel.close()
        panel.destroy()
        self.assertFalse(chat_callbacks & scheduled(), 'Chat must cancel its own Tcl after callbacks')
        self.assertEqual('Draft retained on close', ChatState(self.root).value['draft'])
        self.window.destroy()
        self.assertFalse(watch_callbacks & scheduled(), 'Destroy must cancel the watched window callback')


class SettingsWorkerOwnerTests(unittest.TestCase):
    def assert_owner_waits_for_cleanup(self, kind):
        from desktop import repository_validation_panel as panels
        from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cancel, cleanup, started, finished = (threading.Event() for _ in range(4))
            panel = object.__new__(panels.RepositoryValidationPanel)
            panel.root, panel.closed, panel.cancel = root, True, cancel
            def work():
                started.set()
                cancel.wait(5)
                cleanup.wait(5)
            panel.worker = threading.Thread(target=work, daemon=False)
            live = object.__new__(panels.RepositoryValidationPanel)
            live.root, live.closed, live.cancel, live.worker = root, False, threading.Event(), None
            other = object.__new__(panels.RepositoryValidationPanel)
            other.root, other.closed, other.cancel, other.worker = root / 'other', True, threading.Event(), None
            for candidate in (panel, live, other):
                panels._PANELS.add(candidate)
            failures = []
            if kind == 'settings':
                from desktop import ui
                call = lambda: ui.show(root)
                target = 'desktop.ui.build_window'
                class ClosedWindow:
                    def mainloop(self):
                        return None
                replacement = ClosedWindow()
            else:
                from desktop import dashboard
                call = lambda: dashboard.show(root)
                target = 'desktop.dashboard._show'
                replacement = None
            def owner():
                try:
                    call()
                except BaseException as error:
                    failures.append(error)
                finally:
                    finished.set()
            owner_thread = threading.Thread(target=owner)
            probe = StateDirectoryLock(root / (kind + '-ui') / 'owner')
            panel.worker.start()
            try:
                self.assertTrue(started.wait(2))
                with patch(target, return_value=replacement):
                    owner_thread.start()
                    self.assertTrue(cancel.wait(2), 'Owner shutdown must cancel its closed Settings worker')
                    self.assertFalse(finished.is_set(), 'Owner must wait for pending process cleanup')
                    with self.assertRaises(StateDirectoryAlreadyOwned):
                        probe.acquire()
                    self.assertFalse(live.cancel.is_set(), 'A live unrelated Settings window remains active')
                    self.assertFalse(other.cancel.is_set(), 'Another installation is not part of this shutdown')
                    cleanup.set()
                    owner_thread.join(3)
                self.assertFalse(owner_thread.is_alive())
                self.assertFalse(failures, failures)
                probe.acquire()
                probe.release()
            finally:
                cancel.set()
                cleanup.set()
                if owner_thread.ident is not None:
                    owner_thread.join(3)
                panel.worker.join(3)
                probe.release()
                for candidate in (panel, live, other):
                    panels._PANELS.discard(candidate)

    def test_standalone_settings_owner_stays_locked_until_setup_cleanup_finishes(self):
        self.assert_owner_waits_for_cleanup('settings')

    def test_dashboard_owner_stays_locked_until_child_settings_cleanup_finishes(self):
        self.assert_owner_waits_for_cleanup('dashboard')


if __name__ == '__main__':
    unittest.main()
