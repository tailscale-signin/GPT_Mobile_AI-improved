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
    ('bind_host', 'Listen address (IP)'), ('network_hostnames', 'Additional hostnames (comma separated)'),
    ('mcp_config', 'MCP configuration file (optional)'), ('update_mode', 'Updates'),
    ('update_interval_hours', 'Update check interval (hours)')]


def show(root, parent=None):
    if parent is not None:
        return _show(root, parent)
    from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned
    lock = StateDirectoryLock(Path(root) / 'settings-ui' / 'owner')
    try:
        lock.acquire()
    except StateDirectoryAlreadyOwned:
        return
    try:
        return _show(root)
    finally:
        lock.release()


def _show(root, parent=None):
    root = Path(root)
    cfg = load(root)
    window = tk.Toplevel(parent) if parent else tk.Tk()
    from desktop import theme
    theme.apply(window)
    window.title('GPT Mobile Gateway · Settings')
    width = min(800, max(660, window.winfo_screenwidth() - 32))
    height = min(820, max(500, window.winfo_screenheight() - 100))
    window.geometry(f'{width}x{height}+16+16')
    window.minsize(660, 500)
    canvas = tk.Canvas(window, bg=theme.BG, highlightthickness=0)
    scroll = ttk.Scrollbar(window, orient='vertical', command=canvas.yview)
    scroll.pack(side='right', fill='y')
    canvas.pack(side='left', fill='both', expand=True)
    canvas.configure(yscrollcommand=scroll.set)
    frame = ttk.Frame(canvas, padding=22)
    content = canvas.create_window((0, 0), window=frame, anchor='nw')
    frame.bind('<Configure>', lambda event: canvas.configure(scrollregion=canvas.bbox('all')))
    canvas.bind('<Configure>', lambda event: canvas.itemconfigure(content, width=event.width))
    def scroll_wheel(event):
        if not isinstance(event.widget, ttk.Combobox):
            canvas.yview_scroll(-int(event.delta / 120), 'units')
    window.bind('<MouseWheel>', scroll_wheel)
    ttk.Label(frame, text='Gateway settings', font=('Segoe UI', 20, 'bold')).grid(row=0, column=0, columnspan=3, sticky='w', pady=(0, 8))
    ttk.Label(frame, text='127.0.0.1: this PC only. A PC network IP: one interface. 0.0.0.0: all IPv4 interfaces.\nNetwork access is key-free: reachable devices can use enabled models and tools.', wraplength=600).grid(row=1, column=0, columnspan=3, sticky='w', pady=(0, 16))
    values = {}
    for row, (name, label) in enumerate(FIELDS, 2):
        ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', pady=4, padx=(0, 12))
        value = tk.StringVar(value=str(cfg[name]))
        values[name] = value
        choices = {'update_mode': ['off', 'notify', 'auto']}.get(name)
        widget = ttk.Combobox(frame, textvariable=value, values=['127.0.0.1', '0.0.0.0']) if name == 'bind_host' else ttk.Combobox(frame, textvariable=value, values=choices, state='readonly') if choices else ttk.Entry(frame, textvariable=value)
        widget.grid(row=row, column=1, sticky='ew', pady=4)
        if name == 'mcp_config':
            def browse():
                selected = filedialog.askopenfilename(filetypes=[('JSON configuration', '*.json')])
                if selected:
                    values['mcp_config'].set(selected)
            ttk.Button(frame, text='Browse', command=browse).grid(row=row, column=2, padx=6)
    row = len(FIELDS) + 2
    for name, label in [('start_at_login', 'Start Gateway when I sign in'), ('engineering', 'Allow network devices to run engineering tasks')]:
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
        nonlocal cfg
        try:
            updated = dict(cfg)
            for name, variable in values.items():
                updated[name] = int(variable.get()) if type(cfg[name]) is int else variable.get()
            updated = validate(updated)
            startup_registration(root, updated['start_at_login'])
            atomic_json(root / 'settings.json', updated)
            cfg = updated
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
    network = ttk.Frame(frame)
    network.grid(row=row + 2, column=0, columnspan=3, sticky='w', pady=8)
    def firewall(remove=False):
        def work():
            try:
                from desktop.firewall import apply
                apply(root, remove)
                window.after(0, lambda: messagebox.showinfo('Windows Firewall', 'Rule removed.' if remove else 'Saved port allowed from the local subnet on Private networks. Reapply after changing the port or listen address.'))
            except Exception as error:
                text = str(error)
                window.after(0, lambda: messagebox.showerror('Windows Firewall', text))
        threading.Thread(target=work, daemon=True).start()
    ttk.Button(network, text='Allow LAN through firewall…', command=firewall).pack(side='left', padx=3)
    ttk.Button(network, text='Remove firewall rule…', command=lambda: firewall(True)).pack(side='left', padx=3)
    frame.columnconfigure(1, weight=1)
    refresh()
    from desktop.window_lifecycle import watch
    watch(window, root, window.destroy)
    if parent is None:
        window.mainloop()
