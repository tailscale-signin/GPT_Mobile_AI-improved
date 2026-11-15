"""Headless focus/lifetime controls; native geometry remains a Windows gate."""
from pathlib import Path
import sys
import threading
import tkinter as tk
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.repository_validation_panel import RepositoryValidationPanel


class Widget:
    def __init__(self, master=None, *, y=0, height=20):
        self.master, self.y, self.height = master, y, height
        self.alive, self.focused = True, False
        self.next = self.previous = None
        self.on_idle = None
        self.moves = []
        self.offset = 0
        self.canvas_window = False
        if master is None:
            self.owner = self
            self.pending, self.widgets, self.serial = {}, [], 0
        else:
            self.owner = master.owner
        self.owner.widgets.append(self)

    def check(self):
        if not self.alive:
            raise tk.TclError('Widget has been destroyed')

    def winfo_exists(self):
        return self.alive

    def winfo_viewable(self):
        self.check()
        return True

    def tk_focusNext(self):
        self.check()
        return self.next

    def tk_focusPrev(self):
        self.check()
        return self.previous

    def focus_set(self):
        self.check()
        self.focused = True

    def after_idle(self, callback):
        self.check()
        self.owner.serial += 1
        handle = str(self.owner.serial)
        self.owner.pending[handle] = callback
        return handle

    def after_cancel(self, handle):
        self.owner.pending.pop(handle, None)

    def update_idletasks(self):
        self.check()
        if self.on_idle is not None:
            callback, self.on_idle = self.on_idle, None
            callback()

    def bbox(self, _):
        self.check()
        return (0, 0, 500, 800)

    def winfo_y(self):
        self.check()
        return self.y

    def winfo_rooty(self):
        self.check()
        return (self.master.winfo_rooty() if self.master else 0) + self.y - (
            self.master.offset if self.canvas_window else 0)

    def canvasy(self, y):
        self.check()
        return self.offset + y

    def winfo_height(self):
        self.check()
        return self.height

    def yview_moveto(self, fraction):
        self.check()
        self.moves.append(fraction)
        self.offset = fraction * 800

    def pump(self):
        while self.owner.pending:
            handle = next(iter(self.owner.pending))
            self.owner.pending.pop(handle)()


class Variable:
    def __init__(self, widget, value):
        self.widget, self.value = widget, value

    def get(self):
        self.widget.check()
        return self.value


class RepositoryValidationFocusTests(unittest.TestCase):
    def setUp(self):
        self.window = Widget()
        panel = self.panel = RepositoryValidationPanel.__new__(RepositoryValidationPanel)
        panel.frame = Widget(self.window)
        panel.closed = False
        panel.cancel = threading.Event()
        panel._focus_after = None
        panel.advanced_visible = Variable(panel.frame, True)
        panel.viewport = Widget(panel.frame, height=100)
        panel.content = Widget(panel.viewport, height=800)
        panel.content.canvas_window = True
        panel.editor = Widget(panel.content)
        self.control = Widget(panel.editor)

    def destroy_panel(self):
        self.panel.destroyed(SimpleNamespace(widget=self.panel.frame))
        for widget in self.window.widgets:
            widget.alive = False

    def test_tab_to_outside_editor_keeps_focus_without_scrolling(self):
        # Conflict state disables the controls following Contract inside the
        # editor; Tk can then choose Reload or another Settings control.
        outside = Widget(self.panel.frame, y=400)
        self.control.next = self.control.previous = outside
        for backwards in (False, True):
            with self.subTest(backwards=backwards):
                self.assertEqual('break', self.panel.focus_next(self.control, backwards=backwards))
                self.window.pump()
                self.assertTrue(outside.focused)
                self.assertEqual([], self.panel.viewport.moves)

    def test_tab_reveals_nested_editor_target(self):
        group = Widget(self.panel.editor, y=200)
        target = Widget(group, y=200, height=20)
        self.control.next = target
        self.assertEqual('break', self.panel.focus_next(self.control))
        self.window.pump()
        self.assertTrue(target.focused)
        viewport_top = self.panel.viewport.moves[-1] * 800
        self.assertGreater(viewport_top, 0)
        self.assertLessEqual(viewport_top, 400)
        self.assertGreaterEqual(viewport_top + 100, 420)

    def test_basic_control_inside_scroll_content_is_revealed_without_advanced(self):
        self.panel.advanced_visible.value = False
        target = Widget(self.panel.content, y=400)
        self.control.next = target
        self.panel.focus_next(self.control)
        self.window.pump()
        self.assertTrue(target.focused)
        viewport_top = self.panel.viewport.moves[-1] * 800
        self.assertLessEqual(viewport_top, 400)
        self.assertGreaterEqual(viewport_top + 100, 420)

    def test_destroy_cancels_pending_reveal(self):
        self.control.next = Widget(self.panel.editor, y=400)
        self.panel.focus_next(self.control)
        self.assertTrue(self.window.pending)
        self.destroy_panel()
        self.assertTrue(self.panel.closed)
        self.assertTrue(self.panel.cancel.is_set())
        self.assertEqual({}, self.window.pending)

    def test_dequeued_callback_after_destroy_does_not_access_widgets(self):
        self.control.next = Widget(self.panel.editor, y=400)
        self.panel.focus_next(self.control)
        callback = self.window.pending.pop(next(iter(self.window.pending)))
        self.destroy_panel()
        callback()
        self.assertEqual([], self.panel.viewport.moves)

    def test_reveal_stops_if_idle_geometry_destroys_panel(self):
        target = Widget(self.panel.editor, y=400)
        self.panel.viewport.on_idle = self.destroy_panel
        self.panel.reveal(target)
        self.assertTrue(self.panel.closed)
        self.assertEqual([], self.panel.viewport.moves)

    def test_reveal_ignores_destroyed_descendant(self):
        target = Widget(self.panel.editor, y=400)
        target.alive = False
        self.panel.reveal(target)
        self.assertEqual([], self.panel.viewport.moves)


if __name__ == '__main__':
    unittest.main()
