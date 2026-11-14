"""Personal gateway control centre. All network/control work stays off the UI thread."""
import json
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from desktop.configuration import atomic_json, load, public_repository_maps, save_public_repository_maps, validate
from desktop.validation_policy import check_policy_file, save_policy_path, selected_policy_path
from v14.engineering_batch_policy import load_policy as load_execution_policy, save_policy as save_execution_policy
from desktop.manager import request, startup_registration
from desktop.addons import load_addons, save_addons
from desktop import theme
from desktop.theme import BG as BACKGROUND, PANEL as SURFACE, TEXT, MUTED, CYAN as ACCENT


def build_window(root, parent=None, section='General'):
    root = Path(root)
    cfg = load(root)
    window = tk.Toplevel(parent) if parent else tk.Tk()
    theme.apply(window)
    window.title('GPT Mobile Gateway')
    width = min(900, max(660, window.winfo_screenwidth() - 32))
    height = min(820, max(500, window.winfo_screenheight() - 100))
    window.geometry(f'{width}x{height}+16+16')
    window.minsize(660, 500)
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
    advanced_visible = tk.BooleanVar(value=section == 'Advanced')
    advanced_shell = None
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

    window.bind('<MouseWheel>', scroll)
    sections = {}
    for title in ('General', 'Advanced', 'Add-ons', 'GitHub'):
        canvas = page(title)
        if title == 'Advanced':
            advanced_shell = canvas.master
        frame = ttk.Frame(canvas, padding=24)
        attach(canvas, frame)
        frame.columnconfigure(1, weight=1)
        sections[title] = frame
        if title == section:
            tabs.select(canvas.master)
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

    def title(frame, text, detail):
        ttk.Label(frame, text=text, style='Title.TLabel').grid(row=0, column=0, columnspan=3, sticky='w', pady=(0, 8))
        ttk.Label(frame, text=detail, style='Muted.TLabel', wraplength=600).grid(row=1, column=0, columnspan=3, sticky='w', pady=(0, 22))

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
    field(general, 4, 'bind_host', 'Listen address (IP)')
    field(general, 5, 'network_hostnames', 'Additional hostnames')
    field(general, 6, 'update_mode', 'Updates', ['off', 'notify', 'auto'])
    field(general, 7, 'update_interval_hours', 'Check updates every (hours)')
    values['start_at_login'] = tk.BooleanVar(value=cfg['start_at_login'])
    ttk.Checkbutton(general, text='Start Gateway when I sign in', variable=values['start_at_login']).grid(row=8, column=0, columnspan=3, sticky='w', pady=12)
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
    ttk.Checkbutton(advanced, text='Debug mode — retain up to 5,000 diagnostic lines', variable=values['debug_mode']).grid(row=8, column=0, columnspan=3, sticky='w', pady=(14, 4))
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
    ttk.Combobox(advanced, textvariable=batch_mode, state='readonly',
                 values=['standard', 'smart', 'full']).grid(row=12, column=0, columnspan=3, sticky='ew', pady=5)
    ttk.Label(advanced, text='Standard keeps the existing path. Smart uses safe read batching. Full tracks durable engineering stages and can run host-approved independent local test suites in parallel. Enable Full on this PC and select it in Android GitHub Mode; previous saved jobs retain their original policy.',
              style='Muted.TLabel', wraplength=600).grid(row=13, column=0, columnspan=3, sticky='w', pady=(4, 12))
    ttk.Label(advanced, text='Full Batch local validation workers', style='Section.TLabel').grid(row=14, column=0, columnspan=3, sticky='w', pady=(6, 8))
    batch_workers = tk.StringVar(value=str(batch_settings['local_validation_workers']))
    ttk.Combobox(advanced, textvariable=batch_workers, state='readonly',
                 values=['1', '2', '3', '4']).grid(row=15, column=0, columnspan=3, sticky='ew', pady=5)
    ttk.Label(advanced, text='One runs sequentially. Higher values parallelize only adjacent independent checks that the repository validation policy explicitly groups. Each check stays in its own offline sandbox. GitHub API requests remain serialized; native cloud-provider batching is unavailable.',
              style='Muted.TLabel', wraplength=600).grid(row=16, column=0, columnspan=3, sticky='w', pady=(4, 12))
    ttk.Label(advanced, text='Repository validation policy', style='Section.TLabel').grid(row=17, column=0, columnspan=3, sticky='w', pady=(12, 8))
    validation_policy_invalid = False
    validation_policy_error = ''
    try:
        validation_policy_saved = selected_policy_path(root)
    except (OSError, ValueError) as error:
        validation_policy_saved = ''
        validation_policy_invalid = True
        validation_policy_error = f'Validation policy selection needs repair: {error}. Choose a policy or leave the field empty, then save settings.'
    validation_policy = tk.StringVar(value=validation_policy_saved)
    ttk.Entry(advanced, textvariable=validation_policy).grid(row=18, column=0, columnspan=3, sticky='ew', pady=5)
    policy_buttons = ttk.Frame(advanced)
    policy_buttons.grid(row=19, column=0, columnspan=3, sticky='w', pady=5)
    policy_note = tk.StringVar(value=validation_policy_error or 'Choose the policy approved on this PC for repository builds and behavior tests. Save settings and restart to apply.')
    ttk.Label(advanced, textvariable=policy_note, style='Muted.TLabel', wraplength=600).grid(row=20, column=0, columnspan=3, sticky='w', pady=(4, 12))

    def browse_policy():
        selected = filedialog.askopenfilename(parent=window, title='Choose repository validation policy', filetypes=[('JSON policy', '*.json'), ('All files', '*.*')])
        if selected:
            validation_policy.set(selected)

    def inspect_policy():
        try:
            checked = check_policy_file(validation_policy.get())
            policy_note.set(f"Policy file validated for {checked['repositories']} repositories. Runtime and source checks run when a job starts."
                            if checked['state'] == 'VALIDATED' else 'No policy selected. Implementation jobs require a host-approved policy.')
        except ValueError as error:
            policy_note.set(str(error))

    ttk.Button(policy_buttons, text='Choose policy…', command=browse_policy).pack(side='left', padx=(0, 8))
    ttk.Button(policy_buttons, text='Check policy', command=inspect_policy).pack(side='left')
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
    github = sections['GitHub']
    title(github, 'GitHub', 'Map public repositories or connect GitHub for private maps and saved jobs.')
    public_maps_saved = public_repository_maps(root)
    public_maps = tk.BooleanVar(value=public_maps_saved)
    refresh_github = build_github_settings(github, root, cfg, public_maps, values['engineering'])

    footer = ttk.Frame(window, padding=(24, 16))
    footer.pack(fill='x')
    notice = tk.StringVar(value='Settings apply after Restart. Active work is preserved.')
    ttk.Label(footer, textvariable=notice, style='Muted.TLabel').pack(anchor='w', pady=(0, 10))
    actions = ttk.Frame(footer)
    actions.pack(fill='x')
    messages = queue.Queue()
    busy = False

    def save():
        nonlocal public_maps_saved, validation_policy_saved, validation_policy_invalid
        try:
            updated = dict(cfg)
            for name, variable in values.items():
                updated[name] = int(variable.get()) if type(cfg[name]) is int else variable.get()
            updated = validate(updated)
            selected_batch_mode = batch_mode.get()
            staged_batch = dict(batch_settings, default_mode=selected_batch_mode,
                                full_enabled=(selected_batch_mode == 'full'),
                                local_validation_workers=int(batch_workers.get()))
            # Validate separately so the older Windows schema-1 settings reader
            # never receives the new policy fields.
            from v14.engineering_batch_policy import validate_policy as validate_execution_policy
            staged_batch = validate_execution_policy(staged_batch)
            policy_changed = validation_policy_invalid or validation_policy.get() != validation_policy_saved or not (root / 'data' / 'validation-settings.json').exists()
            if policy_changed:
                check_policy_file(validation_policy.get())
            startup_registration(root, updated['start_at_login'])
            atomic_json(root / 'settings.json', updated)
            cfg.update(updated)
            save_execution_policy(batch_policy_path, staged_batch)
            batch_settings.update(staged_batch)
            if policy_changed:
                checked_policy = save_policy_path(root, validation_policy.get())
                validation_policy_saved = validation_policy.get()
                validation_policy_invalid = False
                policy_note.set('Validation policy selection saved. Restart to apply.' if checked_policy['state'] == 'VALIDATED'
                                else 'No policy selected. Implementation jobs remain blocked until a host-approved policy is selected.')
            if public_maps.get() != public_maps_saved:
                save_public_repository_maps(root, public_maps.get())
                public_maps_saved = public_maps.get()
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
        refresh_github()
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
        window.after(2000, refresh)
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
    return window


def show(root, parent=None, section='General'):
    if parent is not None:
        return build_window(root, parent, section)
    from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned
    lock = StateDirectoryLock(Path(root) / 'settings-ui' / 'owner')
    try:
        lock.acquire()
    except StateDirectoryAlreadyOwned:
        return
    try:
        build_window(root, section=section).mainloop()
    finally:
        lock.release()
