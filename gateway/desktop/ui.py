"""Personal gateway control centre. All network/control work stays off the UI thread."""
import json
from pathlib import Path
import queue
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from desktop.configuration import atomic_json, load, validate
from desktop.manager import request, startup_registration
from desktop.addons import load_addons, save_addons
from desktop.dashboard import Dashboard, BACKGROUND, SURFACE, TEXT, MUTED, ACCENT


def build_window(root):
    root = Path(root)
    cfg = load(root)
    window = tk.Tk()
    window.title('GPT Mobile Gateway')
    window.geometry('1040x900')
    window.minsize(920, 760)
    window.configure(bg=BACKGROUND)
    style = ttk.Style(window)
    style.theme_use('clam')
    style.configure('.', font=('Segoe UI', 10), background=BACKGROUND, foreground=TEXT)
    style.configure('TFrame', background=BACKGROUND)
    style.configure('TLabel', background=BACKGROUND, foreground=TEXT)
    style.configure('Muted.TLabel', foreground=MUTED)
    style.configure('Title.TLabel', font=('Segoe UI', 22, 'bold'))
    style.configure('Section.TLabel', font=('Segoe UI', 13, 'bold'))
    style.configure('Card.TFrame', background=SURFACE)
    style.configure('CardMuted.TLabel', background=SURFACE, foreground=MUTED, font=('Segoe UI', 9))
    style.configure('Metric.TLabel', background=SURFACE, foreground=ACCENT, font=('Segoe UI', 24, 'bold'))
    style.configure('TButton', background='#28434A', foreground=TEXT, padding=(14, 9), borderwidth=0)
    style.map('TButton', background=[('active', '#365860'), ('disabled', SURFACE)])
    style.configure('TEntry', fieldbackground=SURFACE, foreground=TEXT, insertcolor=TEXT, padding=7)
    style.configure('TCombobox', fieldbackground=SURFACE, background=SURFACE, foreground=TEXT, padding=7)
    style.map('TCombobox', fieldbackground=[('readonly', SURFACE)], foreground=[('readonly', TEXT)])
    style.configure('TCheckbutton', background=BACKGROUND, foreground=TEXT, padding=5)
    style.map('TCheckbutton', background=[('active', SURFACE)])
    style.configure('TNotebook', background=BACKGROUND, borderwidth=0)
    style.configure('TNotebook.Tab', background=SURFACE, foreground=MUTED, padding=(24, 12))
    style.map('TNotebook.Tab', background=[('selected', '#25434A')], foreground=[('selected', ACCENT)])
    style.configure('Treeview', background=SURFACE, fieldbackground=SURFACE, foreground=TEXT, rowheight=29, borderwidth=0)
    style.configure('Treeview.Heading', background='#263336', foreground=MUTED, padding=7)
    style.map('Treeview', background=[('selected', '#365860')])

    header = ttk.Frame(window, padding=(24, 18))
    header.pack(fill='x')
    ttk.Label(header, text='GPT  /  GATEWAY', foreground=ACCENT, font=('Segoe UI', 15, 'bold')).pack(side='left')
    state_text = tk.StringVar(value='Connecting to your gateway…')
    ttk.Label(header, textvariable=state_text, style='Muted.TLabel').pack(side='right')
    tabs = ttk.Notebook(window)
    tabs.pack(fill='both', expand=True, padx=20)
    scroll_surfaces = {}
    def page(name):
        shell = ttk.Frame(tabs)
        tabs.add(shell, text=name)
        canvas = tk.Canvas(shell, bg=BACKGROUND, highlightthickness=0)
        scrollbar = ttk.Scrollbar(shell, orient='vertical', command=canvas.yview)
        canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right', fill='y')
        canvas.pack(side='left', fill='both', expand=True)
        scroll_surfaces[canvas] = canvas
        return canvas

    def attach(canvas, content):
        item = canvas.create_window((0, 0), window=content, anchor='nw')
        content.bind('<Configure>', lambda _: canvas.configure(scrollregion=canvas.bbox('all')))
        canvas.bind('<Configure>', lambda e: canvas.itemconfigure(item, width=e.width))

    def scroll(event):
        widget = window.winfo_containing(event.x_root, event.y_root)
        while widget is not None:
            if widget in scroll_surfaces:
                widget.yview_scroll(-1 if event.delta > 0 else 1, 'units')
                return 'break'
            widget = widget.master

    window.bind_all('<MouseWheel>', scroll)
    overview_canvas = page('Overview')
    overview = Dashboard(overview_canvas)
    attach(overview_canvas, overview)
    sections = {}
    for title in ('General', 'Advanced', 'Add-ons'):
        canvas = page(title)
        frame = ttk.Frame(canvas, padding=24)
        attach(canvas, frame)
        frame.columnconfigure(1, weight=1)
        sections[title] = frame
    values = {}

    def title(frame, text, detail):
        ttk.Label(frame, text=text, style='Title.TLabel').grid(row=0, column=0, columnspan=3, sticky='w', pady=(0, 8))
        ttk.Label(frame, text=detail, style='Muted.TLabel', wraplength=820).grid(row=1, column=0, columnspan=3, sticky='w', pady=(0, 22))

    def field(frame, row, name, label, choices=None):
        ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', pady=7, padx=(0, 24))
        variable = tk.StringVar(value=str(cfg[name]))
        values[name] = variable
        widget = ttk.Combobox(frame, textvariable=variable, values=choices, state='readonly') if choices else ttk.Entry(frame, textvariable=variable)
        widget.grid(row=row, column=1, columnspan=2, sticky='ew', pady=7)
        return widget

    general = sections['General']
    title(general, 'Make it yours', 'Your connection, startup preferences and updates in one place.')
    field(general, 2, 'backend', 'Model backend origin')
    field(general, 3, 'port', 'Gateway port')
    field(general, 4, 'access', 'Connection access', ['loopback', 'tailscale'])
    field(general, 5, 'tailnet_host', 'Tailscale HTTPS hostname')
    field(general, 6, 'tailnet_user', 'Allowed Tailscale login')
    field(general, 7, 'update_mode', 'Updates', ['off', 'notify', 'auto'])
    field(general, 8, 'update_interval_hours', 'Check updates every (hours)')
    values['start_at_login'] = tk.BooleanVar(value=cfg['start_at_login'])
    ttk.Checkbutton(general, text='Start Gateway when I sign in', variable=values['start_at_login']).grid(row=9, column=0, columnspan=3, sticky='w', pady=12)
    ttk.Label(general, text='Auto installs verified compatible updates when your work is idle.\nConnection changes take effect after Restart.', style='Muted.TLabel').grid(row=10, column=0, columnspan=3, sticky='w', pady=12)

    advanced = sections['Advanced']
    title(advanced, 'Fine-tune your PC', 'Control capacity and shutdown behaviour for your local model.')
    field(advanced, 2, 'workers', 'Concurrent model workers')
    field(advanced, 3, 'active_chats', 'Maximum admitted chats')
    field(advanced, 4, 'shutdown_seconds', 'Shutdown grace (seconds)')
    ttk.Label(advanced, text='For one phone, fewer workers can reduce competition for GPU memory.\nWaiting and paused jobs are preserved when an update or restart is refused.', style='Muted.TLabel', wraplength=820).grid(row=5, column=0, columnspan=3, sticky='w', pady=18)
    ttk.Label(advanced, text='Local diagnostics', style='Section.TLabel').grid(row=6, column=0, columnspan=3, sticky='w', pady=(18, 8))
    diagnostics = tk.StringVar(value='Waiting for measured runtime health.')
    ttk.Label(advanced, textvariable=diagnostics, wraplength=820, justify='left', style='Muted.TLabel').grid(row=7, column=0, columnspan=3, sticky='w')

    addons = sections['Add-ons']
    title(addons, 'Extend your gateway', 'Manage configured PC tools and GitHub engineering access.')
    values['engineering'] = tk.BooleanVar(value=cfg['engineering'])
    ttk.Checkbutton(addons, text='Allow GitHub engineering for the selected Tailscale user', variable=values['engineering']).grid(row=2, column=0, columnspan=3, sticky='w', pady=(0, 14))
    field(addons, 3, 'mcp_config', 'MCP configuration file')
    addon_buttons = ttk.Frame(addons)
    addon_buttons.grid(row=4, column=0, columnspan=3, sticky='w', pady=8)
    addon_list = tk.Canvas(addons, background=BACKGROUND, highlightthickness=0, height=235)
    addon_scroll = ttk.Scrollbar(addons, orient='vertical', command=addon_list.yview)
    addon_list.configure(yscrollcommand=addon_scroll.set)
    scroll_surfaces[addon_list] = addon_list
    addon_list.grid(row=5, column=0, columnspan=2, sticky='nsew', pady=10)
    addon_scroll.grid(row=5, column=2, sticky='ns', pady=10)
    cards = ttk.Frame(addon_list)
    cards_id = addon_list.create_window((0, 0), window=cards, anchor='nw')
    cards.bind('<Configure>', lambda _: addon_list.configure(scrollregion=addon_list.bbox('all')))
    addon_list.bind('<Configure>', lambda e: addon_list.itemconfigure(cards_id, width=e.width))
    addon_state = {'path': None, 'digest': None, 'values': {}}
    addon_note = tk.StringVar()
    ttk.Label(addons, textvariable=addon_note, style='Muted.TLabel', wraplength=800).grid(row=6, column=0, columnspan=3, sticky='w', pady=10)

    def reload_addons():
        for child in cards.winfo_children():
            child.destroy()
        addon_state.update(path=None, digest=None, values={})
        path = Path(values['mcp_config'].get()) if values['mcp_config'].get() else root / 'data' / 'mcp.json'
        try:
            config, digest = load_addons(path)
            addon_state.update(path=path, digest=digest)
            for name, item in sorted(config['mcpServers'].items()):
                enabled = tk.BooleanVar(value=not item.get('disabled', False))
                addon_state['values'][name] = enabled
                ttk.Checkbutton(cards, text=name, variable=enabled).pack(anchor='w', pady=4)
            if not config['mcpServers']:
                ttk.Label(cards, text='No add-ons configured yet.', style='Section.TLabel').pack(anchor='w', pady=14)
            addon_note.set('Enable or disable configured MCP services, then Save add-ons and Restart.\nThe gateway service allowlist still applies. Commands and credentials stay in the configuration file.')
        except (OSError, ValueError) as error:
            addon_note.set('Choose an existing MCP JSON configuration to manage your add-ons. ' + str(error))

    def browse_addons():
        selected = filedialog.askopenfilename(filetypes=[('MCP JSON configuration', '*.json')])
        if selected:
            values['mcp_config'].set(selected)
            reload_addons()

    def persist_addons():
        selected = Path(values['mcp_config'].get()) if values['mcp_config'].get() else root / 'data' / 'mcp.json'
        if selected != addon_state['path']:
            messagebox.showerror('Reload add-ons', 'The configuration path changed. Reload before saving.')
            return
        try:
            addon_state['digest'] = save_addons(selected, addon_state['digest'], {key: var.get() for key, var in addon_state['values'].items()})
            addon_note.set('Add-on choices saved. Save settings if you changed the file path, then Restart to apply.')
        except (OSError, ValueError) as error:
            messagebox.showerror('Add-ons', str(error))

    for label, command in [('Choose configuration', browse_addons), ('Reload', reload_addons), ('Save add-ons', persist_addons)]:
        ttk.Button(addon_buttons, text=label, command=command).pack(side='left', padx=(0, 8))
    reload_addons()

    footer = ttk.Frame(window, padding=(24, 16))
    footer.pack(fill='x')
    notice = tk.StringVar(value='Settings apply after Restart. Active work is preserved.')
    ttk.Label(footer, textvariable=notice, style='Muted.TLabel').pack(anchor='w', pady=(0, 10))
    actions = ttk.Frame(footer)
    actions.pack(fill='x')
    messages = queue.Queue()
    busy = False

    def save():
        try:
            updated = dict(cfg)
            for name, variable in values.items():
                updated[name] = int(variable.get()) if type(cfg[name]) is int else variable.get()
            updated = validate(updated)
            startup_registration(root, updated['start_at_login'])
            atomic_json(root / 'settings.json', updated)
            cfg.update(updated)
            notice.set('Settings saved. Restart when your current work is complete.')
        except Exception as error:
            messagebox.showerror('Check settings', str(error))

    def operation(action):
        nonlocal busy
        if busy:
            return
        busy = True
        notice.set('Applying ' + action + '…')
        def work():
            try:
                request(root, action)
                messages.put((True, action.capitalize() + ' accepted.'))
            except Exception as error:
                messages.put((False, str(error)))
        threading.Thread(target=work, daemon=True).start()

    ttk.Button(actions, text='Save settings', command=save).pack(side='left', padx=(0, 12))
    for label, action in [('Restart', 'restart'), ('Stop', 'stop'), ('Check updates', 'check'), ('Install update', 'install')]:
        ttk.Button(actions, text=label, command=lambda a=action: operation(a)).pack(side='left', padx=4)

    def refresh():
        nonlocal busy
        while not messages.empty():
            success, text = messages.get_nowait()
            busy = False
            notice.set(text)
            if not success:
                messagebox.showerror('Gateway', text)
        try:
            state = json.loads((root / 'status.json').read_text(encoding='utf-8'))
            state_text.set(f"{str(state.get('state', 'unknown')).capitalize()}  ·  v{state.get('version', '?')}")
            diagnostics.set(f"Gateway: {state.get('state', 'unknown')}\nUpdates: {state.get('updateState', 'not checked')}\n{state.get('error', '')}")
        except (OSError, ValueError):
            state_text.set('Start Gateway to connect')
            state = {}
        try:
            data = json.loads((root / 'dashboard.json').read_text(encoding='utf-8'))
            overview.update_data(data, stale=time.time() - data.get('collectedAt', 0) > 20 or state.get('state') != 'running')
        except (OSError, ValueError):
            pass
        window.after(2000, refresh)
    refresh()
    return window


def show(root):
    build_window(root).mainloop()
