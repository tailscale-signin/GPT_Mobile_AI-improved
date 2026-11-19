"""Compatibility entry point for the single, supervisor-backed owner UI.

Legacy host utilities launch the native page. They never create a second pairing
database, grant an owner, or mutate the settings/admission journal themselves.
"""
import subprocess
from tkinter import messagebox

from desktop.manager import executable, read_pointer
from desktop.native_launch import native_executable


def show(parent, root):
    if not native_executable().is_file():
        messagebox.showinfo('Android owner controls',
            'Open the installed Windows Gateway app and choose Android controls to pair an owner device.', parent=parent)
        return None
    try:
        call = executable(root, read_pointer(root)['version'], 'dashboard') + ['--view', 'Android controls', '--quiet']
        return subprocess.Popen(call, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    except (OSError, ValueError):
        messagebox.showerror('Android owner controls',
            'The installed Gateway workspace could not open. Repair this installation and try again.', parent=parent)
        return None
