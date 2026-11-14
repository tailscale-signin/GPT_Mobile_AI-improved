"""Bundled, app-derived navigation artwork; text labels remain the accessible name."""
from pathlib import Path
import tkinter as tk

ASSETS = Path(__file__).with_name('assets') / 'navigation'
NAMES = {'Overview': 'overview', 'Chat': 'chat', 'Saved': 'saved', 'Jobs': 'jobs', 'Benchmarks': 'benchmarks',
         'Settings': 'settings', 'New prompt': 'new_prompt'}


def load(master, name, active=False):
    key = NAMES.get(name)
    if not key:
        return None
    # DPI-sized source assets avoid soft integer-upscaled icons on Windows.
    scale = float(master.tk.call('tk', 'scaling')) / (96 / 72)
    size = 48 if scale >= 1.75 else 36 if scale >= 1.25 else 24
    try:
        return tk.PhotoImage(master=master, file=str(ASSETS / f'{key}_{"active" if active else "muted"}_{size}.png'))
    except tk.TclError:
        return None  # The labeled native control remains fully usable.
