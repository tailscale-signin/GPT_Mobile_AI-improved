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


if __name__ == '__main__':
    unittest.main()
