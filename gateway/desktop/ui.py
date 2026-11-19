"""Personal gateway control centre. All network/control work stays off the UI thread."""
import json
from pathlib import Path
import queue
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from desktop.configuration import atomic_json, load, public_repository_maps, copilot_enabled, save_copilot_enabled, settings_write_lock, validate
from v14.engineering_batch_policy import load_policy as load_execution_policy
from desktop.manager import request
from desktop.addons import load_addons, save_addons
from desktop.window_lifecycle import WindowCallbacks
from desktop import theme
from desktop.theme import BG as BACKGROUND, PANEL as SURFACE, TEXT, MUTED, CYAN as ACCENT


def build_window(root, parent=None, section='General'):
    root = Path(root)
    cfg = load(root)
    window = tk.Toplevel(parent) if parent else tk.Tk()
    if parent is not None:
        # Keep settings above its owning dashboard on Windows. A plain Toplevel
        # can otherwise map behind the parent despite visible child geometry.
        window.transient(parent.winfo_toplevel())
    callbacks = WindowCallbacks(window)
    window.settings_callbacks = callbacks
    last_open = time.time()
    style = theme.apply(window)
    window.title('GPT Mobile Gateway')
    width = min(900, max(660, window.winfo_screenwidth() - 32))
    height = min(820, max(500, window.winfo_screenheight() - 100))
    window.geometry(f'{width}x{height}+16+16')
    window.minsize(660, 500)
    style.configure('Muted.TLabel', foreground=MUTED)
    style.configure('Title.TLabel', font=('Segoe UI', 22, 'bold'))
    style.configure('Section.TLabel', font=('Segoe UI', 13, 'bold'))
    style.configure('Card.TFrame', background=SURFACE)
    style.configure('CardMuted.TLabel', background=SURFACE, foreground=MUTED, font=('Segoe UI', 9))
    style.configure('Metric.TLabel', background=SURFACE, foreground=ACCENT, font=('Segoe UI', 24, 'bold'))
    header = ttk.Frame(window, padding=(24, 12))
    header.pack(fill='x')
    ttk.Label(header, text='GPT  /  GATEWAY', foreground=ACCENT, font=('Segoe UI', 15, 'bold')).pack(side='left')
    state_text = tk.StringVar(value='Connecting to your gateway…')
    ttk.Label(header, textvariable=state_text, style='Muted.TLabel').pack(side='right')
    footer = ttk.Frame(window, padding=(24, 10))
    footer.pack(side='bottom', fill='x')
    notice = tk.StringVar(value='Host connection changes apply after Restart. Shared settings are managed in Gateway → Settings.')
    ttk.Label(footer, textvariable=notice, style='Muted.TLabel').pack(anchor='w', pady=(0, 10))
    actions = ttk.Frame(footer)
    actions.pack(fill='x')
    tabs = ttk.Notebook(window)
    window.settings_tabs = tabs
    tabs.pack(fill='both', expand=True, padx=20)
    advanced_visible = tk.BooleanVar(value=section == 'Advanced')
    advanced_shell = None
    scroll_surfaces = {}
    def page(name, notebook=tabs):
        shell = ttk.Frame(notebook)
        notebook.add(shell, text=name)
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

    window.bind('<MouseWheel>', scroll)
    sections = {}
    for title in ('General', 'Advanced', 'Add-ons'):
        canvas = page(title)
        if title == 'Advanced':
            advanced_shell = canvas.master
        frame = ttk.Frame(canvas, padding=24)
        attach(canvas, frame)
        frame.columnconfigure(1, weight=1)
        sections[title] = frame
        if title == section:
            tabs.select(canvas.master)
    github = ttk.Frame(tabs)
    tabs.add(github, text='GitHub')
    if section in {'GitHub', 'Repository Validation'}:
        tabs.select(github)
    def toggle_advanced():
        if advanced_visible.get():
            tabs.insert(1, advanced_shell, text='Advanced')
            tabs.select(advanced_shell)
        else:
            tabs.hide(advanced_shell)
    ttk.Checkbutton(header, text='Show advanced settings', variable=advanced_visible,
                    command=toggle_advanced).pack(side='right', padx=18)
    if not advanced_visible.get():
        tabs.hide(advanced_shell)
    values = {}
    shared_fields = {'backend', 'workers', 'active_chats', 'start_at_login', 'update_mode', 'update_interval_hours', 'debug_mode'}

    def title(frame, text, detail):
        ttk.Label(frame, text=text, style='Title.TLabel').grid(row=0, column=0, columnspan=3, sticky='w', pady=(0, 8))
        ttk.Label(frame, text=detail, style='Muted.TLabel', wraplength=600).grid(row=1, column=0, columnspan=3, sticky='w', pady=(0, 22))

    def field(frame, row, name, label, choices=None):
        ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', pady=7, padx=(0, 24))
        variable = tk.StringVar(value=str(cfg[name]))
        values[name] = variable
        widget = ttk.Combobox(frame, textvariable=variable, values=choices, state='readonly') if choices else ttk.Entry(frame, textvariable=variable)
        if name in shared_fields:
            widget.configure(state='disabled')
        widget.grid(row=row, column=1, columnspan=2, sticky='ew', pady=7)
        return widget

    general = sections['General']
    title(general, 'Host connection settings', 'Configure this PC’s listener here. Open Gateway → Settings to edit shared backend, capacity, startup and update preferences with revision checks.')
    field(general, 2, 'backend', 'Model backend origin')
    field(general, 3, 'port', 'Gateway port')
    field(general, 4, 'bind_host', 'Listen address (IP)')
    field(general, 5, 'network_hostnames', 'Additional hostnames')
    field(general, 6, 'update_mode', 'Updates', ['off', 'notify', 'auto'])
    field(general, 7, 'update_interval_hours', 'Check updates every (hours)')
    values['start_at_login'] = tk.BooleanVar(value=cfg['start_at_login'])
    ttk.Checkbutton(general, text='Start Gateway when I sign in', variable=values['start_at_login'], state='disabled').grid(row=8, column=0, columnspan=3, sticky='w', pady=12)
    ttk.Label(general, text='127.0.0.1: this PC only. A PC network IP: one interface. 0.0.0.0: all IPv4 interfaces.\nNetwork access is key-free: reachable devices can use enabled models and tools.\nAuto installs verified compatible updates when idle. Save settings, then Restart to apply.', style='Muted.TLabel', wraplength=600).grid(row=9, column=0, columnspan=3, sticky='w', pady=12)
    network_buttons = ttk.Frame(general)
    network_buttons.grid(row=10, column=0, columnspan=3, sticky='w', pady=8)

    advanced = sections['Advanced']
    title(advanced, 'Fine-tune your PC', 'Control capacity and shutdown behaviour for your local model.')
    field(advanced, 2, 'workers', 'Concurrent model workers')
    field(advanced, 3, 'active_chats', 'Maximum admitted chats')
    field(advanced, 4, 'shutdown_seconds', 'Shutdown grace (seconds)')
    ttk.Label(advanced, text='For one phone, fewer workers can reduce competition for GPU memory.\nWaiting and paused jobs are preserved when an update or restart is refused.', style='Muted.TLabel', wraplength=600).grid(row=5, column=0, columnspan=3, sticky='w', pady=18)
    ttk.Label(advanced, text='Local diagnostics', style='Section.TLabel').grid(row=6, column=0, columnspan=3, sticky='w', pady=(18, 8))
    diagnostics = tk.StringVar(value='Waiting for measured runtime health.')
    ttk.Label(advanced, textvariable=diagnostics, wraplength=600, justify='left', style='Muted.TLabel').grid(row=7, column=0, columnspan=3, sticky='w')

    values['debug_mode'] = tk.BooleanVar(value=cfg['debug_mode'])
    ttk.Checkbutton(advanced, text='Debug mode — retain up to 5,000 diagnostic lines', variable=values['debug_mode'], state='disabled').grid(row=8, column=0, columnspan=3, sticky='w', pady=(14, 4))
    ttk.Label(advanced, text='The full diagnostic bundle includes request timing, backend failures, gateway build details and retained task events. Enable Debug mode on your phone to include its profile, build and network details. Prompts, message bodies and credentials are excluded.', style='Muted.TLabel', wraplength=600).grid(row=9, column=0, columnspan=3, sticky='w', pady=8)
    def export_debug():
        target = filedialog.asksaveasfilename(defaultextension='.zip', initialfile='gateway-debug.zip',
                                            filetypes=[('Full diagnostic bundle', '*.zip'), ('Transport log only', '*.jsonl')])
        if not target:
            return
        def work():
            try:
                if Path(target).suffix.lower() == '.zip':
                    request(root, 'export-debug')
                    import shutil
                    shutil.copyfile(root / 'logs' / 'gateway-debug.zip', target)
                    messages.put((True, 'Exported the diagnostic bundle. Its manifest identifies retained evidence and coverage gaps.'))
                    return
                from v14.debug import DebugLogStore
                source = root / 'logs' / 'gateway-debug.jsonl'
                if not source.is_file():
                    raise ValueError('Enable debug mode, save settings and restart the gateway before exporting.')
                content = DebugLogStore(source).export()
                if not content:
                    raise ValueError('No diagnostic records are available yet.')
                Path(target).write_text(content, encoding='utf-8')
                messages.put((True, 'Exported transport records only. Choose the ZIP bundle to include build details and retained task events.'))
            except (OSError, ValueError, RuntimeError) as error:
                messages.put((False, str(error)))
        threading.Thread(target=work, daemon=True).start()
    ttk.Button(advanced, text='Export debug log…', command=export_debug).grid(row=10, column=0, columnspan=3, sticky='w', pady=8)
    ttk.Label(advanced, text='Execution mode for NEW GitHub jobs', style='Section.TLabel').grid(row=11, column=0, columnspan=3, sticky='w', pady=(18, 8))
    batch_policy_path = root / 'data' / 'execution-policy.json'
    batch_settings = load_execution_policy(batch_policy_path)
    batch_mode = tk.StringVar(value=batch_settings['default_mode'])
    ttk.Combobox(advanced, textvariable=batch_mode, state='disabled',
                 values=['standard', 'smart', 'full']).grid(row=12, column=0, columnspan=3, sticky='ew', pady=5)
    ttk.Label(advanced, text='Standard keeps the existing path. Smart uses safe read batching. Full tracks durable engineering stages and can run host-approved independent local test suites in parallel. Enable Full on this PC and select it in Android GitHub Mode; previous saved jobs retain their original policy.',
              style='Muted.TLabel', wraplength=600).grid(row=13, column=0, columnspan=3, sticky='w', pady=(4, 12))
    ttk.Label(advanced, text='Full Batch local validation workers', style='Section.TLabel').grid(row=14, column=0, columnspan=3, sticky='w', pady=(6, 8))
    batch_workers = tk.StringVar(value=str(batch_settings['local_validation_workers']))
    ttk.Combobox(advanced, textvariable=batch_workers, state='disabled',
                 values=['1', '2', '3', '4']).grid(row=15, column=0, columnspan=3, sticky='ew', pady=5)
    ttk.Label(advanced, text='One runs sequentially. Higher values parallelize only adjacent independent checks that the repository validation policy explicitly groups. Each check stays in its own offline sandbox. GitHub API requests remain serialized; native cloud-provider batching is unavailable.',
              style='Muted.TLabel', wraplength=600).grid(row=16, column=0, columnspan=3, sticky='w', pady=(4, 12))
    ttk.Label(advanced, text='Repository validation is configured in GitHub → Repository Validation. No external policy file is required.',
              style='Muted.TLabel', wraplength=600).grid(row=17, column=0, columnspan=3, sticky='w', pady=(12, 8))
    def benchmark_settings():
        from desktop.benchmark_panel import open_benchmark_settings
        open_benchmark_settings(window, root)
    ttk.Button(advanced, text='Benchmark settings…', command=benchmark_settings).grid(row=22, column=0, columnspan=3, sticky='w', pady=(14, 6))

    addons = sections['Add-ons']
    title(addons, 'Extend your gateway', 'Manage configured PC tools.')
    values['engineering'] = tk.BooleanVar(value=cfg['engineering'])
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
    ttk.Label(addons, textvariable=addon_note, style='Muted.TLabel', wraplength=600).grid(row=6, column=0, columnspan=3, sticky='w', pady=10)

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

    from desktop.github_setup import build_github_settings
    from desktop.repository_validation_panel import RepositoryValidationPanel
    github_tabs = ttk.Notebook(github)
    github_tabs.pack(fill='both', expand=True)
    window.github_tabs = github_tabs
    from desktop.admission_panel import build_panel as build_admission_panel
    connection_canvas = page('Connection', github_tabs)
    connection = ttk.Frame(connection_canvas, padding=14)
    connection.columnconfigure(1, weight=1)
    attach(connection_canvas, connection)
    validation_frame = ttk.Frame(github_tabs)
    github_tabs.add(validation_frame, text='Repository Validation')
    public_maps_saved = public_repository_maps(root)
    public_maps = tk.BooleanVar(value=public_maps_saved)
    copilot_saved = copilot_enabled(root)
    copilot_jobs = tk.BooleanVar(value=copilot_saved)
    github_tabs.add(build_admission_panel(github_tabs, root), text='Job admission')
    refresh_github = build_github_settings(connection, root, cfg, public_maps, values['engineering'], copilot_jobs, managed_maps=True)
    validation_panel = RepositoryValidationPanel(validation_frame, root, cfg)
    if section == 'Repository Validation':
        github_tabs.select(validation_frame)
    window.repository_validation_panel = validation_panel

    scroll_surfaces[validation_panel.viewport] = validation_panel.viewport

    messages = queue.Queue()
    busy = False

    from desktop.phone_panel import show as show_phone_controls
    ttk.Button(general, text='Android controls and pairing…', command=lambda: show_phone_controls(window, root)).grid(row=20, column=0, columnspan=3, sticky='w', pady=8)

    def save():
        nonlocal copilot_saved
        try:
            # Never copy an old shared-settings snapshot back over the
            # supervisor's observed effective state from this host-only utility.
            with settings_write_lock(root):
                updated = load(root)
                for name, variable in values.items():
                    if name not in shared_fields:
                        updated[name] = int(variable.get()) if type(cfg[name]) is int else variable.get()
                updated = validate(updated)
                atomic_json(root / 'settings.json', updated)
                cfg.update(updated)
            if copilot_jobs.get() != copilot_saved:
                save_copilot_enabled(root, copilot_jobs.get())
                copilot_saved = copilot_jobs.get()
            notice.set('Host connection settings saved. Shared settings remain managed in Gateway → Settings. Restart when idle.')
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

    def shared_settings():
        def work():
            from desktop.native_launch import show as show_native
            if show_native(root, view='Settings') is None:
                messages.put((False, 'Open the installed Windows Gateway app and choose Settings to manage shared preferences.'))
        threading.Thread(target=work, daemon=True).start()
    buttons = [('Shared settings', shared_settings), ('Save host settings', save)]
    for label, action in [('Restart', 'restart'), ('Stop', 'stop'), ('Check updates', 'check'), ('Install update', 'install')]:
        buttons.append((label, lambda a=action: operation(a)))
    for index, (label, handler) in enumerate(buttons):
        ttk.Button(actions, text=label, command=handler).grid(row=index // 3, column=index % 3, sticky='ew', padx=3, pady=2)
        actions.columnconfigure(index % 3, weight=1)

    def refresh():
        nonlocal busy, last_open
        try:
            opened = json.loads((root / 'show-settings.json').read_text(encoding='utf-8'))
            if opened['requestedAt'] > last_open:
                last_open = opened['requestedAt']
                target = opened['section']
                if target == 'Advanced' and not advanced_visible.get():
                    advanced_visible.set(True)
                    toggle_advanced()
                outer = 'GitHub' if target == 'Repository Validation' else target
                for tab in tabs.tabs():
                    if tabs.tab(tab, 'text') == outer:
                        tabs.select(tab)
                if outer == 'GitHub':
                    github_tabs.select(validation_frame if target == 'Repository Validation' else connection_canvas.master)
                window.deiconify()
                window.lift()
        except (OSError, ValueError, KeyError, TypeError):
            pass
        refresh_github()
        validation_panel.refresh()
        while not messages.empty():
            success, text = messages.get_nowait()
            busy = False
            notice.set(text)
            if not success:
                messagebox.showerror('Gateway', text)
        try:
            state = json.loads((root / 'status.json').read_text(encoding='utf-8'))
            state_text.set(f"{str(state.get('state', 'unknown')).capitalize()}  ·  v{state.get('version', '?')}")
            diagnostics.set(f"Gateway: {state.get('state', 'unknown')}\nUpdates: {state.get('updateState', 'not checked')}\n{state.get('availableVersion') or ''} {state.get('updateError') or state.get('error') or ''}")
        except (OSError, ValueError):
            state_text.set('Start Gateway to connect')
            state = {}
        callbacks.schedule(2000, refresh)
    def firewall(remove=False):
        def work():
            try:
                from desktop.firewall import apply
                apply(root, remove)
                messages.put((True, 'Firewall rule removed.' if remove else 'Saved port allowed from the local subnet on Private networks. Reapply after changing listener settings.'))
            except Exception as error:
                messages.put((False, str(error)))
        threading.Thread(target=work, daemon=True).start()
    ttk.Button(network_buttons, text='Allow LAN through firewall…', command=firewall).pack(side='left', padx=(0, 6))
    ttk.Button(network_buttons, text='Remove firewall rule…', command=lambda: firewall(True)).pack(side='left')
    refresh()
    from desktop.window_lifecycle import watch
    watch(window, root, window.destroy)
    if parent is not None:
        # Tk can map a transient Toplevel behind its owner on Windows when the
        # owner has not processed geometry yet. Map the owner first, finish the
        # child layout, then raise the settings window before returning it to
        # the dashboard's click handler.
        owner = parent.winfo_toplevel()
        owner.update_idletasks()
        window.update_idletasks()
        window.lift()
        window.focus_set()
    return window


def show(root, parent=None, section='General'):
    if parent is not None:
        return build_window(root, parent, section)
    from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned
    lock = StateDirectoryLock(Path(root) / 'settings-ui' / 'owner')
    try:
        lock.acquire()
    except StateDirectoryAlreadyOwned:
        atomic_json(Path(root) / 'show-settings.json', {'requestedAt': time.time(), 'section': section})
        return
    try:
        build_window(root, section=section).mainloop()
    finally:
        from desktop.repository_validation_panel import settle_setup_workers
        settle_setup_workers(root)
        lock.release()
