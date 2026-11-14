"""Native acceptance: runs on Windows CI; a display is required on Linux."""
from pathlib import Path
import sys
import tempfile
import tkinter as tk
from tkinter import ttk
import unittest

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


if __name__ == '__main__':
    unittest.main()
