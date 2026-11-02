"""Native, local-only Windows dashboard. No network secret is given to this UI."""
from collections import deque
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox

from desktop import theme
from desktop.configuration import atomic_json, load
from desktop.dashboard_model import age, counters, read_snapshot, rows
from desktop.manager import request
from v14 import VERSION
from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned

PAGES = ['Overview', 'Devices', 'Recent activity', 'Search jobs', 'GitHub jobs']


class Dashboard:
    def __init__(self, root, window=None):
        self.root = Path(root)
        self.window = window or tk.Tk()
        self.window.title('GPT Mobile Gateway')
        width = min(1180, max(900, self.window.winfo_screenwidth() - 32))
        height = min(790, max(600, self.window.winfo_screenheight() - 100))
        self.compact = width < 1100 or height < 740
        self.window.geometry(f'{width}x{height}+16+16')
        self.window.minsize(900, 600)
        theme.apply(self.window)
        self.page = 'Overview'
        self.snapshot = {}
        self.displayed = []
        self.plot = deque(maxlen=45)
        self.last_sample = None
        self.busy = False
        self.closed = False
        self.window.protocol('WM_DELETE_WINDOW', self.close)
        self.build()
        self.refresh()
        from desktop.window_lifecycle import watch
        watch(self.window, self.root, self.close)

    def build(self):
        sidebar = tk.Frame(self.window, bg='#061C24', width=175 if self.compact else 210)
        sidebar.pack(side='left', fill='y')
        sidebar.pack_propagate(False)
        if getattr(self.window, '_brand_icon', None):
            self.logo = self.window._brand_icon.subsample(2)
            tk.Label(sidebar, image=self.logo, background='#061C24').pack(anchor='w', padx=23, pady=(26, 12))
        theme.label(sidebar, 'GATEWAY', 20, bold=True, bg='#061C24').pack(anchor='w', padx=23)
        theme.label(sidebar, f'GPT Mobile  /  {VERSION}', 10, color=theme.MUTED, bg='#061C24').pack(anchor='w', padx=23, pady=(3, 28))
        self.nav = {}
        for index, page in enumerate(PAGES):
            button = tk.Button(sidebar, text=f'{index + 1:02d}   {page}', anchor='w', padx=15, pady=12,
                font=('Segoe UI', 11), foreground=theme.MUTED, background='#061C24', activebackground=theme.RAISED,
                activeforeground=theme.CYAN, relief='flat', borderwidth=0, highlightthickness=0, cursor='hand2',
                command=lambda value=page: self.navigate(value))
            button.pack(fill='x', padx=12, pady=3)
            self.nav[page] = button
        tk.Frame(sidebar, bg=theme.LINE, height=1).pack(fill='x', padx=25, pady=25)
        ttk.Button(sidebar, text='Connection & settings', command=self.settings).pack(fill='x', padx=17)
        theme.label(sidebar, 'Runs on your PC\nNo cloud dashboard', 10, color=theme.MUTED, bg='#061C24', justify='left').pack(side='bottom', anchor='w', padx=25, pady=24)
        self.body = tk.Frame(self.window, bg=theme.BG)
        self.body.pack(side='left', fill='both', expand=True, padx=20 if self.compact else 27, pady=20)
        header = tk.Frame(self.body, bg=theme.BG)
        header.pack(fill='x')
        self.title = theme.label(header, 'Your gateway, at a glance', 18 if self.compact else 23, bold=True)
        self.title.pack(side='left')
        self.badge = theme.label(header, 'Connecting', 10, color=theme.CYAN, bg=theme.RAISED, padx=13, pady=8)
        self.badge.pack(side='right', pady=3)
        self.subtitle = theme.label(self.body, 'Live statistics and the work happening on your gateway.', 10, color=theme.MUTED, anchor='w')
        self.subtitle.pack(fill='x', pady=(8, 21))
        cards = tk.Frame(self.body, bg=theme.BG)
        cards.pack(fill='x', pady=(0, 18))
        self.cards = []
        for i, (name, note, accent) in enumerate([
                ('CONNECTED CLIENTS', 'Observed within 60 seconds', theme.CYAN),
                ('OPEN JOBS', 'Chat and engineering', theme.CYAN),
                ('SEARCH ACTIVITY', 'Retained search operations', theme.PURPLE),
                ('GITHUB JOBS', 'Latest saved engineering jobs', theme.PURPLE)]):
            cards.columnconfigure(i, weight=1, uniform='card')
            card = tk.Frame(cards, bg=theme.PANEL, highlightbackground=theme.LINE, highlightthickness=1)
            card.grid(row=0, column=i, sticky='nsew', padx=(0 if i == 0 else 6, 0 if i == 3 else 6))
            theme.label(card, name, 8 if self.compact else 9, color=theme.MUTED, bold=True, bg=theme.PANEL).pack(anchor='w', padx=10, pady=(13, 4))
            value = theme.label(card, '—', 29, color=accent, bold=True, bg=theme.PANEL)
            value.pack(anchor='w', padx=14)
            theme.label(card, note, 8, color=theme.MUTED, bg=theme.PANEL, wraplength=155 if self.compact else 185, justify='left').pack(anchor='w', padx=10, pady=(0, 13))
            self.cards.append(value)
        self.chart_frame = tk.Frame(self.body, bg=theme.PANEL, highlightbackground=theme.LINE, highlightthickness=1)
        self.chart_frame.pack(fill='x', pady=(0, 18))
        chart_head = tk.Frame(self.chart_frame, bg=theme.PANEL)
        chart_head.pack(fill='x', padx=16, pady=(12, 0))
        theme.label(chart_head, 'TRAFFIC / REQUESTS PER SECOND', 9, color=theme.CYAN, bold=True, bg=theme.PANEL).pack(side='left')
        self.metrics = theme.label(chart_head, 'Waiting for measured traffic', 9, color=theme.MUTED, bg=theme.PANEL)
        self.metrics.pack(side='right')
        self.chart = tk.Canvas(self.chart_frame, height=72 if self.compact else 100, bg=theme.PANEL, highlightthickness=0)
        self.chart.pack(fill='x', padx=12, pady=8)
        self.chart.bind('<Configure>', lambda event: self.draw_plot())
        toolbar = tk.Frame(self.body, bg=theme.BG)
        toolbar.pack(fill='x', pady=(0, 10))
        self.section = theme.label(toolbar, 'Recent activity', 14, bold=True)
        self.section.pack(side='left')
        self.query = tk.StringVar()
        self.search = ttk.Entry(toolbar, textvariable=self.query, width=32)
        self.search.pack(side='right')
        self.search.insert(0, '')
        theme.label(toolbar, 'Filter  ', 10, color=theme.MUTED).pack(side='right')
        self.query.trace_add('write', lambda *_: self.render_rows())
        table = tk.Frame(self.body, bg=theme.PANEL)
        table.pack(fill='both', expand=True)
        self.table = ttk.Treeview(table, columns=('kind', 'title', 'state', 'updated'), show='headings', selectmode='browse', height=5)
        self.table.pack(side='left', fill='both', expand=True)
        scroll = ttk.Scrollbar(table, orient='vertical', command=self.table.yview)
        scroll.pack(side='right', fill='y')
        self.table.configure(yscrollcommand=scroll.set)
        for column, label, width in [('kind', 'TYPE', 100), ('title', 'ACTIVITY', 380), ('state', 'STATE', 160), ('updated', 'UPDATED', 105)]:
            self.table.heading(column, text=label, anchor='w')
            self.table.column(column, width=width, minwidth=75, stretch=column == 'title')
        for tag, color in [('running', theme.CYAN), ('failed', theme.ERROR), ('completed', theme.TEXT), ('idle', theme.MUTED)]:
            self.table.tag_configure(tag, foreground=color)
        self.table.bind('<<TreeviewSelect>>', self.details)
        self.detail = theme.label(self.body, 'Select a row to inspect its details.', 10, color=theme.MUTED, anchor='w', justify='left', wraplength=820)
        self.detail.pack(fill='x', pady=(12, 9))
        footer = tk.Frame(self.body, bg=theme.BG)
        footer.pack(fill='x')
        self.note = theme.label(footer, 'Local observations only', 9, color=theme.MUTED, anchor='w')
        self.note.pack(side='left', fill='x', expand=True)
        self.buttons = []
        for name, action in [('Start gateway', 'start'), ('Restart', 'restart'), ('Stop', 'stop')]:
            button = ttk.Button(footer, text=name, command=lambda value=action: self.operation(value))
            button.pack(side='left', padx=(7, 0))
            self.buttons.append(button)
        # Reserve controls/details before giving the remaining height to history.
        table.pack_forget()
        self.detail.pack_forget()
        footer.pack(side='bottom', fill='x')
        self.detail.pack(side='bottom', fill='x', pady=(12, 9))
        table.pack(fill='both', expand=True)
        self.navigate('Overview')

    def navigate(self, page):
        self.page = page
        for name, button in self.nav.items():
            button.configure(background=theme.RAISED if name == page else '#061C24', foreground=theme.CYAN if name == page else theme.MUTED)
        self.title.configure(text='Your gateway, at a glance' if page == 'Overview' else page)
        self.section.configure(text='Recent activity' if page == 'Overview' else page)
        if page == 'Overview':
            if not self.chart_frame.winfo_manager():
                self.chart_frame.pack(fill='x', pady=(0, 18), before=self.section.master)
        else:
            self.chart_frame.pack_forget()
        self.query.set('')
        self.render_rows()

    def render_rows(self):
        selected = self.table.selection()
        selected_id = self.displayed[int(selected[0])].get('id') if selected and int(selected[0]) < len(self.displayed) else None
        self.displayed = rows(self.snapshot, self.page, self.query.get())
        self.table.delete(*self.table.get_children())
        device_page = self.page == 'Devices'
        for col, title in [('kind', 'CLIENT' if device_page else 'TYPE'), ('title', 'ADDRESS / AGENT' if device_page else 'ACTIVITY'), ('state', 'CONNECTION' if device_page else 'STATE'), ('updated', 'LAST SEEN' if device_page else 'UPDATED')]:
            self.table.heading(col, text=title)
        for index, item in enumerate(self.displayed):
            status = item.get('state', '') if device_page else item.get('status', '')
            if device_page:
                status = f'{item.get("activeRequests", 0)} active' if item.get('activeRequests') and self.snapshot.get('fresh') else 'Seen recently' if status == 'recent' and self.snapshot.get('fresh') else 'Idle / last seen'
                values = ('DEVICE', f'{item.get("address", "Unknown")}  ·  {item.get("agent") or "Client type unavailable"}', status, age(item.get('lastSeen')))
            else:
                values = (str(item.get('kind', 'job')).upper(), item.get('title') or 'Gateway job', str(status).replace('_', ' ').title(), age(item.get('updated')))
            tag = 'failed' if str(status).lower() in {'failed', 'interrupted'} else 'running' if str(status).lower() in {'running', 'ci', 'implement', 'read', 'plan'} else 'completed'
            self.table.insert('', 'end', iid=str(index), values=values, tags=(tag,))
            if item.get('id') and item.get('id') == selected_id:
                self.table.selection_set(str(index))
        if not self.displayed:
            self.detail.configure(text='No matching activity yet. Items appear when connected clients use the gateway.' if self.query.get() else 'No activity recorded yet. Connect the Android app and send a task to begin.')
        elif not self.table.selection():
            self.detail.configure(text='Select a row to inspect its details.')

    def details(self, _event=None):
        selected = self.table.selection()
        if not selected or int(selected[0]) >= len(self.displayed):
            return
        item = self.displayed[int(selected[0])]
        if self.page == 'Devices':
            content = f'Observed address: {item.get("address")}  ·  Requests this session: {item.get("requests", 0)}\nKey-free connections are grouped by socket address; this is not a verified device identity.'
        else:
            content = '  ·  '.join(str(item[key]) for key in ('id', 'detail', 'branch', 'peer') if item.get(key))
            duration = item.get('duration')
            if isinstance(duration, (int, float)):
                content += f'  ·  {duration:.1f}s'
        self.detail.configure(text=content[:420] or 'No additional metadata recorded.')

    def draw_plot(self):
        self.chart.delete('all')
        width, height = max(40, self.chart.winfo_width()), max(40, self.chart.winfo_height())
        for y in (height * .18, height * .46, height * .74):
            self.chart.create_line(8, y, width - 8, y, fill=theme.LINE)
        if len(self.plot) < 2:
            self.chart.create_text(20, height / 2, text='Traffic appears here as requests arrive', fill=theme.MUTED, anchor='w', font=('Segoe UI', 10))
            return
        peak = max(1, max(self.plot))
        points = []
        for i, value in enumerate(self.plot):
            points.extend((10 + i * (width - 22) / (len(self.plot) - 1), height * .82 - value / peak * height * .66))
        self.chart.create_polygon([10, height * .84, *points, width - 12, height * .84], fill='#0C3F44', outline='')
        self.chart.create_line(*points, fill=theme.CYAN, width=2, smooth=True)
        self.chart.create_oval(points[-2] - 3, points[-1] - 3, points[-2] + 3, points[-1] + 3, fill=theme.CYAN, outline='')

    def refresh(self):
        if self.closed:
            return
        show_request = self.root / 'show-dashboard.json'
        if show_request.exists():
            try:
                show_request.unlink()
                self.window.deiconify()
                self.window.lift()
                self.window.focus_force()
            except OSError:
                pass
        self.snapshot = read_snapshot(self.root)
        status = self.snapshot.get('manager', {})
        fresh = self.snapshot.get('fresh')
        self.badge.configure(text='●  LIVE' if fresh else '●  ' + str(status.get('state', 'offline')).upper(), fg=theme.CYAN if fresh else theme.MUTED)
        try:
            cfg = load(self.root)
            endpoint = f'{cfg["bind_host"]}:{cfg["port"]}'
            mode = 'Key-free network' if cfg['access'] == 'network' else 'This PC only'
            self.subtitle.configure(text=f'{endpoint}  /  {mode}  /  Model backend: {status.get("backend", "not measured")}')
        except (OSError, ValueError):
            self.subtitle.configure(text='Connection settings unavailable. Open settings to recover the configuration.')
        for card, value in zip(self.cards, counters(self.snapshot)):
            card.configure(text='—' if value is None else str(value))
        total = self.snapshot.get('requests', 0)
        stamp = self.snapshot.get('capturedAt')
        started = self.snapshot.get('startedAt')
        if fresh and isinstance(stamp, (int, float)):
            if self.last_sample and self.last_sample[2] == started and stamp > self.last_sample[0]:
                self.plot.append(max(0, total - self.last_sample[1]) / (stamp - self.last_sample[0]))
            elif not self.last_sample or self.last_sample[2] != started:
                self.plot.clear()
            self.last_sample = (stamp, total, started)
        self.metrics.configure(text=f'{total:,} requests this session  ·  {self.snapshot.get("errors", 0):,} failed  ·  {self.snapshot.get("sentBytes", 0) / 1024:.0f} KiB sent')
        self.note.configure(text=self.snapshot.get('historyError') or ('Refreshes every 2s · latest 100 saved jobs / 300 activities' if fresh else 'Offline or refreshing · retained history shown'))
        self.render_rows()
        self.draw_plot()
        self.window.after(2000, self.refresh)

    def settings(self):
        from desktop.ui import show
        show(self.root, parent=self.window)

    def operation(self, action):
        if self.busy:
            return
        self.busy = True
        for button in self.buttons:
            button.configure(state='disabled')
        def work():
            error = None
            try:
                if action == 'start':
                    if os.name == 'nt':
                        command = [str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe'),
                                   '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass',
                                   '-File', str(self.root / 'Start-Gateway.ps1')]
                    else:
                        command = [sys.executable, str(Path(__file__).with_name('entry.py')), 'supervise', '--root', str(self.root)]
                    subprocess.Popen(command, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                else:
                    request(self.root, action)
            except Exception as exc:
                error = str(exc)
            def finished():
                self.busy = False
                for button in self.buttons:
                    button.configure(state='normal')
                if error:
                    messagebox.showerror('Gateway', error, parent=self.window)
            if not self.closed:
                self.window.after(0, finished)
        threading.Thread(target=work, daemon=True).start()

    def close(self):
        self.closed = True
        self.window.destroy()  # The supervisor/tray owns gateway lifetime.


def show(root, smoke=False):
    # Separate lock directory: opening a dashboard must not claim the engine lock.
    lock = StateDirectoryLock(Path(root) / 'dashboard-ui' / 'owner')
    try:
        lock.acquire()
    except StateDirectoryAlreadyOwned:
        atomic_json(Path(root) / 'show-dashboard.json', {'requestedAt': time.time()})
        return
    try:
        _show(root, smoke)
    finally:
        lock.release()


def _show(root, smoke):
    dashboard = Dashboard(root)
    if smoke:
        def inspect():
            try:
                for page in PAGES:
                    dashboard.navigate(page)
                    dashboard.window.update_idletasks()
                dashboard.navigate('Overview')
                dashboard.window.update()
                if os.name == 'nt':
                    # Capture the rendered window for Windows CI visual review.
                    x, y = dashboard.window.winfo_rootx(), dashboard.window.winfo_rooty()
                    width, height = dashboard.window.winfo_width(), dashboard.window.winfo_height()
                    script = ('Add-Type -AssemblyName System.Drawing; '
                              f'$b=New-Object System.Drawing.Bitmap({width},{height}); '
                              '$g=[System.Drawing.Graphics]::FromImage($b); '
                              f'$g.CopyFromScreen({x},{y},0,0,$b.Size); '
                              '$b.Save($env:GATEWAY_UI_CAPTURE,[System.Drawing.Imaging.ImageFormat]::Png); '
                              '$g.Dispose(); $b.Dispose()')
                    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
                                   env=dict(os.environ, GATEWAY_UI_CAPTURE=str(Path(root) / 'dashboard-preview.png')),
                                   check=True, timeout=30, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
                from desktop.configuration import atomic_json
                atomic_json(Path(root) / 'dashboard-ui-smoke.json', {'pages': PAGES, 'width': dashboard.window.winfo_width(), 'height': dashboard.window.winfo_height()})
            finally:
                dashboard.close()
        dashboard.window.after(800, inspect)
    dashboard.window.mainloop()
