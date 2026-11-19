"""Windows operator view of the same revision-checked admissions enforced for new jobs."""
import queue
import threading
import tkinter as tk
from tkinter import ttk

from desktop.manager import request
from desktop.window_lifecycle import WindowCallbacks


def build_panel(parent, root):
    frame = ttk.Frame(parent, padding=14)
    scope = tk.StringVar(frame)
    status = tk.StringVar(frame, value='Read a Gateway account to manage its new saved jobs.')
    ttk.Label(frame, text='Saved engineering jobs', style='Section.TLabel').pack(anchor='w')
    picker = ttk.Combobox(frame, textvariable=scope, state='readonly')
    picker.pack(fill='x', pady=(10, 4))
    ttk.Label(frame, textvariable=status, wraplength=470).pack(anchor='w', pady=6)
    actions = ttk.Frame(frame)
    actions.pack(anchor='w')
    inbox = queue.Queue()
    callbacks = WindowCallbacks(frame)
    accounts = {}
    busy = False

    def render(*_):
        item = accounts.get(scope.get())
        change.configure(state='normal' if item and not busy else 'disabled',
                         text='Pause new jobs' if item and item['enabled'] else 'Allow new jobs')
        if item:
            label = 'Allowed' if item['enabled'] else 'Paused'
            status.set(f"{label} · revision {item['revision']} on this PC")

    def read_result():
        nonlocal busy
        try:
            action, result, error = inbox.get_nowait()
        except queue.Empty:
            callbacks.schedule(80, read_result)
            return
        busy = False
        refresh.configure(state='normal')
        picker.configure(state='readonly')
        if error:
            accounts.clear()
            picker.configure(values=())
            scope.set('')
            render()
            status.set(error)
            return
        if action == 'read-admission':
            accounts.clear()
            accounts.update({item['principal_id']: dict(item, gateway_id=result['gateway_id']) for item in result['accounts']})
            picker.configure(values=list(accounts))
            if scope.get() not in accounts:
                scope.set(next(iter(accounts), ''))
        else:
            accounts[result['principal_id']] = result
        render()

    def submit(action):
        nonlocal busy
        if busy:
            return
        item = accounts.get(scope.get())
        update = None
        if action == 'change-admission':
            if not item:
                return
            update = dict(item, enabled=not item['enabled'])
        busy = True
        refresh.configure(state='disabled')
        picker.configure(state='disabled')
        render()
        status.set('Waiting for the Gateway supervisor…')
        def worker():
            try:
                reply = request(root, action, settings=update)
                inbox.put((action, reply['settings'], None))
            except (KeyError, TimeoutError, ValueError, OSError, RuntimeError) as error:
                inbox.put((action, None, str(error)))
        threading.Thread(target=worker, daemon=True).start()
        callbacks.schedule(80, read_result)

    refresh = ttk.Button(actions, text='Read current settings', command=lambda: submit('read-admission'))
    refresh.pack(side='left', padx=(0, 8))
    change = ttk.Button(actions, command=lambda: submit('change-admission'))
    change.pack(side='left')
    scope.trace_add('write', render)
    render()
    return frame
