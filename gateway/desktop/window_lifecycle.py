"""Close local GUI processes before the installer replaces their executable."""
import json
from pathlib import Path
import time

from desktop.configuration import atomic_json
from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned


class WindowCallbacks:
    """Cancel pending Tk callbacks when their owning widget is destroyed."""
    def __init__(self, window):
        self.window = window
        self.pending = set()
        self.closed = False
        window.bind('<Destroy>', self._destroyed, add='+')

    def schedule(self, milliseconds, callback):
        if self.closed:
            return None
        handle = None
        def run():
            self.pending.discard(handle)
            if not self.closed:
                callback()
        handle = self.window.after(milliseconds, run)
        self.pending.add(handle)
        return handle

    def cancel(self, handle):
        import tkinter as tk
        if handle in self.pending:
            self.pending.remove(handle)
            try:
                self.window.after_cancel(handle)
            except tk.TclError:
                pass

    def _destroyed(self, event):
        if event.widget is self.window:
            self.closed = True
            for handle in tuple(self.pending):
                self.cancel(handle)


def watch(window, root, close):
    import tkinter as tk
    started = time.time()
    after_id = None
    disposed = False

    def stop(event=None):
        nonlocal after_id, disposed
        if event is not None and event.widget is not window:
            return
        disposed = True
        if after_id:
            try:
                window.after_cancel(after_id)
            except tk.TclError:
                pass
            after_id = None

    def poll():
        nonlocal after_id
        after_id = None
        if disposed:
            return
        try:
            value = json.loads((Path(root) / 'close-desktop.json').read_text(encoding='utf-8'))
            if value.get('requestedAt', 0) >= started:
                stop()
                close()
                return
        except (OSError, ValueError, TypeError):
            pass
        if not disposed and window.winfo_exists():
            after_id = window.after(250, poll)
    window.bind('<Destroy>', stop, add='+')
    after_id = window.after(250, poll)
    return stop


def close_windows(root, timeout=10):
    root = Path(root)
    atomic_json(root / 'close-desktop.json', {'requestedAt': time.time()})
    deadline = time.monotonic() + timeout
    for name in ('dashboard', 'settings', 'native', 'schedules'):
        lock = StateDirectoryLock(root / (name + '-ui') / 'owner')
        while True:
            try:
                lock.acquire()
            except StateDirectoryAlreadyOwned:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Close Gateway desktop windows and retry installation.')
                time.sleep(.1)
            else:
                lock.release()
                break
