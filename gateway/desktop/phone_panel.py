"""Local owner enrollment, grant management and live synchronized settings."""
import io
import ipaddress
import json
from pathlib import Path
import socket
import sqlite3
import time
from contextlib import closing
import tkinter as tk
from tkinter import ttk, messagebox
from types import SimpleNamespace

from desktop import phone_access, phone_settings, theme
from desktop.configuration import load, atomic_json
from desktop.window_lifecycle import WindowCallbacks
from v14.conversations import Conversations
from v14.engineering import EngineeringStore


def show(parent, root):
    root = Path(root)
    window = tk.Toplevel(parent)
    theme.apply(window)
    window.title('Android controls · GPT Mobile Gateway')
    window.geometry('710x650')
    window.minsize(500, 420)
    window.transient(parent.winfo_toplevel())
    outer = ttk.Frame(window, padding=15)
    outer.pack(fill='both', expand=True)
    canvas = tk.Canvas(outer, bg=theme.BG, highlightthickness=0)
    scroll = ttk.Scrollbar(outer, orient='vertical', command=canvas.yview)
    scroll.pack(side='right', fill='y')
    canvas.configure(yscrollcommand=scroll.set)
    canvas.pack(fill='both', expand=True)
    surface = theme.RoundedPanel(canvas)
    content = surface.content
    content.columnconfigure(1, weight=1)
    item = canvas.create_window(0, 0, anchor='nw', window=surface)
    canvas.bind('<Configure>', lambda e: canvas.itemconfigure(item, width=e.width))
    surface.bind('<Configure>', lambda _: canvas.configure(scrollregion=canvas.bbox('all')))
    callbacks = WindowCallbacks(window)
    status = tk.StringVar(value='Pair a device to manage this installation from GPT Mobile.')
    cfg_path = phone_access.directory(root) / 'listener.json'
    cfg = json.loads(cfg_path.read_text()) if cfg_path.exists() else {}
    addresses = sorted({r[4][0] for r in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
                        if ipaddress.ip_address(r[4][0]).is_private and not ipaddress.ip_address(r[4][0]).is_loopback})
    host = tk.StringVar(value=cfg.get('host', addresses[0] if addresses else ''))
    port = tk.StringVar(value=str(cfg.get('port', 8443)))
    role = tk.StringVar(value='owner')
    ttk.Label(content, text='Android controls', style='Title.TLabel').grid(row=0, column=0, columnspan=2, sticky='w', padx=12, pady=10)
    def field(row, title, widget):
        ttk.Label(content, text=title).grid(row=row, column=0, sticky='w', padx=12, pady=4)
        widget.grid(row=row, column=1, sticky='ew', padx=12, pady=4)
    field(1, 'Private interface', ttk.Combobox(content, textvariable=host, values=addresses))
    field(2, 'Paired HTTPS port', ttk.Entry(content, textvariable=port))
    field(3, 'Device permission', ttk.Combobox(content, textvariable=role, values=('owner','viewer'), state='readonly'))
    bar = ttk.Frame(content)
    bar.grid(row=4, column=0, columnspan=2, sticky='w', padx=12)
    devices = ttk.Treeview(content, columns=('name','role','state'), show='headings', height=3)
    for key, heading, width in [('name','Device',130),('role','Role',70),('state','Access',75)]:
        devices.heading(key, text=heading)
        devices.column(key, width=width)
    devices.grid(row=5, column=0, columnspan=2, sticky='ew', padx=12, pady=8)
    detail = tk.StringVar()
    ttk.Label(content, textvariable=detail, wraplength=440).grid(row=6, column=0, columnspan=2, sticky='w', padx=12)
    actions = ttk.Frame(content)
    actions.grid(row=7, column=0, columnspan=2, sticky='w', padx=12, pady=6)
    ttk.Label(content, textvariable=status, wraplength=440).grid(row=8, column=0, columnspan=2, sticky='w', padx=12, pady=8)
    approved = tk.StringVar(value=load(root)['backend'])
    field(9, 'Approve backend origin', ttk.Entry(content, textvariable=approved))
    current = {}
    store = SimpleNamespace(admission_settings=EngineeringStore.admission_settings, conversations=Conversations)

    def local(fn):
        path = root / 'data' / 'jobs.sqlite3'
        with closing(sqlite3.connect(path.resolve().as_uri() + '?mode=rw', uri=True, timeout=.25)) as db:
            with db:
                db.execute('BEGIN IMMEDIATE')
                # Older installations may not have run an engineering job yet.
                db.execute('CREATE TABLE IF NOT EXISTS engineering_admission_settings (principal TEXT PRIMARY KEY, enabled INTEGER NOT NULL, revision INTEGER NOT NULL)')
                return fn(db)

    def refresh():
        selected = next(iter(devices.selection()), None)
        try:
            with phone_access.database(root) as db:
                entries = db.execute('SELECT id,label,role,revoked FROM grants ORDER BY rowid DESC LIMIT 64').fetchall()
            listed = set(devices.get_children())
            for ident, label, grant, revoked in entries:
                values = (label, grant, 'Revoked' if revoked else 'Paired')
                if ident in listed:
                    devices.item(ident, values=values)
                    listed.remove(ident)
                else:
                    devices.insert('', 'end', iid=ident, values=values)
            for ident in listed:
                devices.delete(ident)
            if not selected and entries:
                selected = entries[0][0]
                devices.selection_set(selected)
            if selected and phone_access.authorized(root, selected):
                state = local(lambda db: phone_settings.snapshot(root, db, store, selected, 'owner', {}))
                current.update(state)
                detail.set('Engineering ' + ('on' if state['desired']['engineering'] else 'off') +
                           f"  ·  Workers {state['desired']['workers']}  ·  Updates {state['desired']['update_mode']}")
                last = state.get('last_change') or {}
                if last:
                    detail.set(detail.get() + f"  ·  #{last['sequence']} {last['field']} · {last['source']} · {last['state']}")
                toggle.configure(state='normal')
            else:
                current.clear()
                detail.set('Choose a paired owner to manage engineering jobs.')
                toggle.configure(state='disabled')
        except (OSError, ValueError, sqlite3.Error):
            current.clear()
            toggle.configure(state='disabled')
            detail.set('Gateway settings unavailable. Start the installed runtime and refresh.')

    def tick():
        refresh()
        callbacks.schedule(3000, tick)

    def invitation():
        try:
            port_number = int(port.get())
            if port_number == load(root)['port']:
                raise ValueError('Choose a separate HTTPS port.')
            phone_access.configure(root, host.get().strip(), port_number)
            link = phone_access.invite(root, role.get())
            import segno
            qr = segno.make(link, micro=False)
            buf = io.BytesIO()
            qr.save(buf, kind='png', scale=4, border=4)
            popup = tk.Toplevel(window)
            theme.apply(popup)
            popup.title('Pair your Android phone')
            graphic = tk.PhotoImage(master=popup, data=buf.getvalue())
            image = tk.Label(popup, image=graphic, bg=theme.BG)
            image.image = graphic
            image.pack(padx=12, pady=12)
            ttk.Label(popup, text=f'Scan on the intended {role.get()} device. Invite expires in five minutes.').pack(padx=12)
            pin = json.loads(cfg_path.read_text())['pin']
            ttk.Label(popup, text='PC fingerprint  ' + ' '.join(pin[i:i+8] for i in range(0, 64, 8)), wraplength=410).pack(padx=12)
            entry = ttk.Entry(popup, width=56)
            entry.insert(0, link)
            entry.configure(state='readonly')
            entry.pack(padx=12, pady=8)
            ttk.Button(popup, text='Close', command=popup.destroy).pack(pady=8)
            popup.after(300000, popup.destroy)
            status.set('Start the Gateway and allow the selected HTTPS port on your private network if Windows blocks it.')
        except (ValueError, OSError, sqlite3.Error, ImportError, tk.TclError) as error:
            status.set(str(error))

    def switch():
        try:
            device = current['device']
            if not phone_access.authorized(root, device) or devices.selection() != (device,):
                raise ValueError('Refresh the selected owner.')
            import uuid
            cmd = dict(command_id=uuid.uuid4().hex, device=device, installation=current['installation'], revision=current['revision'],
                       changes={'engineering': not current['desired']['engineering']})
            local(lambda db: phone_settings.change(root, db, store, device, 'owner', {}, cmd, source='Windows'))
            refresh()
        except (ValueError, KeyError, sqlite3.Error, OSError) as error:
            status.set(str(error))

    def revoke():
        selected = devices.selection()
        if selected and messagebox.askyesno('Revoke phone access', 'Remove this device’s Gateway access?', parent=window):
            phone_access.revoke(root, selected[0])
            refresh()

    def approve_backend():
        from desktop.configuration import validate
        try:
            cfg = validate(dict(load(root), backend=approved.get().strip()))
            choices = list(dict.fromkeys([cfg['backend'], *phone_settings.approved_backends(root)]))[:16]
            atomic_json(phone_access.directory(root) / 'backends.json', choices)
            status.set('Backend available for selection on paired owner devices.')
        except (OSError, ValueError) as error:
            status.set(str(error))

    def edit_firewall(remove=False):
        try:
            from desktop.firewall import apply
            apply(root, remove=remove, phone=True)
            status.set('Private network rule removed.' if remove else 'HTTPS access allowed on this interface for the private local subnet.')
        except (ValueError, OSError, RuntimeError) as error:
            status.set(str(error))

    def disable_listener():
        if not messagebox.askyesno('Pause phone access', 'Disconnect all phones until this listener is enabled again?', parent=window):
            return
        with phone_access.database(root) as db:
            db.execute('DELETE FROM invitations')
        cfg_path.unlink(missing_ok=True)
        status.set('Phone access paused. Create an invitation to enable it again.')

    maintenance = ttk.Frame(content)
    maintenance.grid(row=11, column=0, columnspan=2, sticky='w', padx=12, pady=8)
    ttk.Button(maintenance, text='Allow private firewall access', command=edit_firewall).pack(side='left')
    ttk.Button(maintenance, text='Remove firewall rule', command=lambda: edit_firewall(True)).pack(side='left')
    ttk.Button(maintenance, text='Pause phone access', command=disable_listener).pack(side='left')
    def approve_policy():
        from desktop.phone_policies import approve_current
        try:
            approve_current(root)
            status.set('Current validation profile approved for paired owners.')
        except (OSError, ValueError) as error:
            status.set(str(error))

    def review_requests():
        try:
            def get(db):
                phone_settings.schema(db)
                return db.execute("SELECT seq,payload FROM phone_setting_commands WHERE state='waiting_pc' ORDER BY seq LIMIT 1").fetchone()
            row = local(get)
            if not row:
                status.set('No Windows actions requested.')
                return
            action = json.loads(row[1])['changes']['host_request']
            if messagebox.askyesno('Requested from Android', action + '\nMark this request as reviewed?', parent=window):
                local(lambda db: db.execute("UPDATE phone_setting_commands SET state='reviewed' WHERE seq=? AND state='waiting_pc'", (row[0],)))
                status.set('Request reviewed. Run the selected action in Windows settings when ready.')
        except (ValueError, sqlite3.Error, OSError) as error:
            status.set(str(error))

    ttk.Button(content, text='Approve current validation profile', command=approve_policy).grid(row=12, column=0, sticky='w', padx=12)
    ttk.Button(content, text='Review phone requests', command=review_requests).grid(row=12, column=1, sticky='w', padx=12)
    ttk.Button(bar, text='Create pairing invitation', style='Accent.TButton', command=invitation).pack(pady=8)
    toggle = ttk.Button(actions, text='Toggle engineering', command=switch)
    toggle.pack(side='left', padx=3)
    ttk.Button(actions, text='Revoke device', command=revoke).pack(side='left', padx=3)
    ttk.Button(content, text='Approve backend', command=approve_backend).grid(row=10, column=1, sticky='e', padx=12, pady=8)
    devices.bind('<<TreeviewSelect>>', lambda _: refresh())
    tick()
    return window
