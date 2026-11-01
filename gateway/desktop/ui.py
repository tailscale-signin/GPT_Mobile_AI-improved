"""Native local settings. Network operations run off the UI thread."""
import json
from pathlib import Path
import threading
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from desktop.configuration import atomic_json, load, validate
from desktop.manager import request, startup_registration

FIELDS = [
    ('backend', 'Model backend origin'), ('port', 'Gateway port'), ('workers', 'Concurrent workers'),
    ('active_chats', 'Maximum admitted chats'), ('shutdown_seconds', 'Shutdown grace (seconds)'),
    ('access', 'Connection access'), ('tailnet_host', 'Tailscale HTTPS hostname'), ('tailnet_user', 'Allowed Tailscale login'),
    ('mcp_config', 'MCP configuration file (optional)'), ('update_mode', 'Updates'),
    ('update_interval_hours', 'Update check interval (hours)')]


def show(root):
    root = Path(root)
    cfg = load(root)
    window = tk.Tk()
    window.title('GPT Mobile Gateway · Settings')
    window.minsize(660, 670)
    frame = ttk.Frame(window, padding=22)
    frame.pack(fill='both', expand=True)
    ttk.Label(frame, text='Gateway settings', font=('Segoe UI', 20, 'bold')).grid(row=0, column=0, columnspan=3, sticky='w', pady=(0, 8))
    ttk.Label(frame, text='Runs privately on this PC. Use Tailscale Serve for phone access.\nThe model server and Tailscale are configured separately.', wraplength=600).grid(row=1, column=0, columnspan=3, sticky='w', pady=(0, 16))
    values = {}
    for row, (name, label) in enumerate(FIELDS, 2):
        ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', pady=4, padx=(0, 12))
        value = tk.StringVar(value=str(cfg[name]))
        values[name] = value
        choices = {'access': ['loopback', 'tailscale'], 'update_mode': ['off', 'notify', 'auto']}.get(name)
        widget = ttk.Combobox(frame, textvariable=value, values=choices, state='readonly') if choices else ttk.Entry(frame, textvariable=value)
        widget.grid(row=row, column=1, sticky='ew', pady=4)
        if name == 'mcp_config':
            def browse():
                selected = filedialog.askopenfilename(filetypes=[('JSON configuration', '*.json')])
                if selected:
                    values['mcp_config'].set(selected)
            ttk.Button(frame, text='Browse', command=browse).grid(row=row, column=2, padx=6)
    row = len(FIELDS) + 2
    for name, label in [('start_at_login', 'Start Gateway when I sign in'), ('engineering', 'Allow the selected Tailscale user to run engineering tasks')]:
        values[name] = tk.BooleanVar(value=cfg[name])
        ttk.Checkbutton(frame, text=label, variable=values[name]).grid(row=row, column=0, columnspan=3, sticky='w', pady=5)
        row += 1
    ttk.Label(frame, text='Notify is the default. Auto installs verified stable 15.x updates only when idle.\nPaused engineering jobs also block updates. Settings apply after Restart.', wraplength=600).grid(row=row, column=0, columnspan=3, sticky='w', pady=10)
    row += 1
    status = tk.StringVar()
    ttk.Label(frame, textvariable=status, wraplength=600).grid(row=row, column=0, columnspan=3, sticky='w', pady=8)
    def refresh():
        try:
            state = json.loads((root / 'status.json').read_text(encoding='utf-8'))
            status.set(f"Gateway {state.get('version', '?')}: {state.get('state', '?')} · Updates: {state.get('updateState', 'not checked')}\n{state.get('error', '')}")
        except (OSError, ValueError):
            status.set('Start Gateway to see its status.')
        window.after(2000, refresh)
    def save():
        try:
            updated = dict(cfg)
            for name, variable in values.items():
                updated[name] = int(variable.get()) if type(cfg[name]) is int else variable.get()
            updated = validate(updated)
            startup_registration(root, updated['start_at_login'])
            atomic_json(root / 'settings.json', updated)
            messagebox.showinfo('Settings saved', 'Settings saved. Restart when your active work is complete.')
        except Exception as error:
            messagebox.showerror('Check settings', str(error))
    buttons = ttk.Frame(frame)
    buttons.grid(row=row + 1, column=0, columnspan=3, sticky='w', pady=12)
    def operation(action):
        def work():
            try:
                request(root, action)
            except Exception as error:
                text = str(error)
                window.after(0, lambda: messagebox.showerror('Gateway', text))
        threading.Thread(target=work, daemon=True).start()
    ttk.Button(buttons, text='Save settings', command=save).pack(side='left', padx=(0, 6))
    for label, action in [('Restart', 'restart'), ('Stop', 'stop'), ('Check updates', 'check'), ('Install update', 'install')]:
        ttk.Button(buttons, text=label, command=lambda a=action: operation(a)).pack(side='left', padx=3)
    frame.columnconfigure(1, weight=1)
    refresh()
    window.mainloop()
