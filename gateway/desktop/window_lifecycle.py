"""Close local GUI processes before the installer replaces their executable."""
import json
from pathlib import Path
import time

from desktop.configuration import atomic_json
from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned


def watch(window, root, close):
    started = time.time()
    def poll():
        try:
            value = json.loads((Path(root) / 'close-desktop.json').read_text(encoding='utf-8'))
            if value.get('requestedAt', 0) >= started:
                close()
                return
        except (OSError, ValueError, TypeError):
            pass
        if window.winfo_exists():
            window.after(250, poll)
    window.after(250, poll)


def close_windows(root, timeout=10):
    root = Path(root)
    atomic_json(root / 'close-desktop.json', {'requestedAt': time.time()})
    deadline = time.monotonic() + timeout
    for name in ('dashboard', 'settings'):
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
