"""Tk view for selecting a live model and inspecting saved benchmark scores."""
from datetime import datetime
import queue
import threading
import tkinter as tk
from tkinter import ttk

import requests

from desktop import benchmark, theme


class BenchmarkPanel(tk.Frame):
    def __init__(self, parent, root):
        super().__init__(parent, bg=theme.BG)
        self.root = root
        self.events = queue.Queue()
        self.results = []
        self.busy = False
        self.discovered = False

        controls = tk.Frame(self, bg=theme.BG)
        controls.pack(fill='x', pady=(0, 12))
        self.model = ttk.Combobox(controls, state='readonly', width=30)
        self.model.pack(side='left', fill='x', expand=True)
        self.model.bind('<<ComboboxSelected>>', lambda _: self.update_controls())
        self.reload = ttk.Button(controls, text='Refresh models', command=self.refresh_models)
        self.reload.pack(side='left', padx=8)
        self.start = ttk.Button(controls, text='Run benchmark', command=self.run, style='Accent.TButton')
        self.start.pack(side='left')
        self.status = theme.label(self, 'Start Gateway and select a model.', color=theme.MUTED, anchor='w')
        self.status.pack(fill='x', pady=(0, 10))

        self.table = ttk.Treeview(self, columns=('model', 'score', 'first', 'gateway', 'date'), show='headings', selectmode='browse', height=5)
        self.table.pack(fill='both', expand=True)
        for key, title, width in [('model', 'MODEL', 220), ('score', 'SCORE', 80), ('first', 'FIRST TEXT', 95),
                                  ('gateway', 'GATEWAY RTT', 105), ('date', 'TESTED', 130)]:
            self.table.heading(key, text=title, anchor='w')
            self.table.column(key, width=width, minwidth=60, stretch=key == 'model')
        self.table.bind('<<TreeviewSelect>>', self.details)
        self.detail = theme.label(self, '', color=theme.MUTED, justify='left', anchor='w')
        self.detail.pack(fill='x', pady=(10, 0))
        self.bind('<Configure>', lambda event: self.detail.configure(wraplength=max(250, event.width - 10)))
        self.render()
        self.after(150, self.poll)

    def activate(self):
        if not self.discovered:
            self.refresh_models()

    def update_controls(self):
        self.reload.configure(state='disabled' if self.busy else 'normal')
        self.start.configure(state='normal' if not self.busy and self.model.get() else 'disabled')
        self.model.configure(state='disabled' if self.busy else 'readonly')

    def launch(self, operation, action, status):
        if self.busy:
            return
        self.busy = True
        self.update_controls()
        self.status.configure(text=status)

        def work():
            try:
                self.events.put((operation, action()))
            except requests.RequestException:
                self.events.put(('error', 'Could not reach Gateway. Check the dashboard connection.'))
            except (OSError, ValueError) as error:
                self.events.put(('error', str(error) if isinstance(error, ValueError) else 'Could not read or save Gateway data.'))

        threading.Thread(target=work, daemon=True).start()

    def refresh_models(self):
        self.launch('models', lambda: benchmark.discover(self.root), 'Reading the live model list…')

    def run(self):
        selected = self.model.get()
        if selected:
            self.launch('result', lambda: benchmark.run(self.root, selected, lambda text: self.events.put(('progress', text))),
                        'Checking Gateway…')

    def poll(self):
        while not self.events.empty():
            event, value = self.events.get_nowait()
            if event == 'progress':
                self.status.configure(text=value)
                continue
            self.busy = False
            if event == 'models':
                previous = self.model.get()
                self.model.configure(values=value)
                self.model.set(previous if previous in value else value[0] if value else '')
                self.discovered = bool(value)
                self.status.configure(text='Five fixtures test answer accuracy and record response timing.' if value else 'The model backend advertised no models.')
            elif event == 'result':
                self.status.configure(text='Benchmark saved.' if value['score'] is not None else 'Incomplete benchmark saved. Select the model to see failed requests.')
                self.render(value['model'], value['backend'])
            else:
                self.status.configure(text=value[:180])
            self.update_controls()
        self.after(150, self.poll)

    def render(self, model=None, backend=None):
        def rank(row):
            return row['score'] if row.get('score') is not None else -1

        self.results = sorted((row for row in benchmark.scoreboard(self.root)
                               if isinstance(row, dict) and isinstance(row.get('model'), str)
                               and type(row.get('at')) in (int, float) and 0 < row['at'] < 4102444800),
                              key=rank, reverse=True)
        self.table.delete(*self.table.get_children())
        self.detail.configure(text='Select a result for individual test outcomes. Each model and backend shows its latest run.')
        for index, row in enumerate(self.results):
            score = f"{row['score']}/100" if row.get('score') is not None else 'Incomplete'
            first = f"{row['firstMs']:g} ms" if row.get('firstMs') is not None else '—'
            self.table.insert('', 'end', iid=str(index), values=(row['model'], score, first,
                              f"{row.get('gatewayMs', 0):g} ms", datetime.fromtimestamp(row['at']).strftime('%b %d, %H:%M')))
            if (row['model'], row.get('backend')) == (model, backend):
                self.table.selection_set(str(index))
                self.table.see(str(index))

    def details(self, _event=None):
        selected = self.table.selection()
        if not selected:
            return
        row = self.results[int(selected[0])]
        lines = [f"{row.get('backend', '')}  ·  Gateway {row.get('gateway', '')}  ·  Suite {benchmark.SUITE}"]
        for case in row.get('cases', []):
            status = case.get('error') or ('Pass' if case['passed'] else 'Incorrect')
            timing = f"  ·  first {case['firstMs']:g} ms  ·  total {case['totalMs']:g} ms  ·  max gap {case['gapMs']:g} ms" if 'firstMs' in case else ''
            if case.get('tokensPerSecond') is not None:
                timing += f"  ·  {case['tokensPerSecond']:g} tok/s"
            lines.append(f"{case['name']}  ·  {status}{timing}")
        self.detail.configure(text='\n'.join(lines))
