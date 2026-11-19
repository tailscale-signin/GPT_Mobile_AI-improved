"""Graphical desktop client for gateway-owned, durable benchmark runs."""
from datetime import datetime
import json
from pathlib import Path
import queue
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import uuid

import requests

from desktop import benchmark, theme
from benchmarks.contracts import TERMINAL
from benchmarks.settings import (load_settings, save_settings, prepare_sandbox,
                                 smoke_sandbox_image, verify_submission)

SUITES = {'Quick check': 'quick_check', 'Batch comparison': 'batch_comparison', 'GitHub coding': 'coding'}
PRESETS = {'Quick': 'quick', 'Standard': 'standard', 'Deep': 'deep'}


def pretty(value, suffix='', digits=1):
    return f'{value:,.{digits}f}' + suffix if type(value) in (int, float) else 'Not measured'


class Bars(tk.Canvas):
    """Responsive exact-value bars; Breakdown exposes the same values as text."""
    def __init__(self, parent):
        super().__init__(parent, bg=theme.PANEL, highlightthickness=0, height=280, takefocus=True)
        self.rows, self.unit = [], ''
        self.bind('<Configure>', lambda _: self.draw())

    def set(self, rows, unit=''):
        self.rows, self.unit = rows, unit
        self.draw()

    def draw(self):
        self.delete('all')
        width, height = max(280, self.winfo_width()), max(160, self.winfo_height())
        if not self.rows:
            self.create_text(24, 32, anchor='nw', text='Run a benchmark to see measured results.', fill=theme.MUTED, font=('Segoe UI', 11))
            return
        rows = self.rows[:12]
        left, right = min(205, width * .34), max(min(205, width * .34) + 30, width - 125)
        maximum = max([value for _, value, _ in rows if type(value) in (int, float)] + [1])
        spacing = min(32, (height - 30) / len(rows))
        for index, (label, value, color) in enumerate(rows):
            y = 16 + index * spacing
            self.create_text(12, y + 8, anchor='w', text=label[:25], fill=theme.TEXT, font=('Segoe UI', 9))
            self.create_rectangle(left, y, right, y + 16, fill=theme.RAISED, outline='')
            if type(value) in (int, float):
                self.create_rectangle(left, y, left + max(0, min(1, value / maximum)) * (right - left), y + 16, fill=color, outline='')
            self.create_text(right + 10, y + 8, anchor='w', text=pretty(value, self.unit), fill=theme.MUTED, font=('Segoe UI', 9))


class BenchmarkPanel(tk.Frame):
    def __init__(self, parent, root):
        super().__init__(parent, bg=theme.BG)
        self.root, self.events = root, queue.Queue(maxsize=64)
        self.results, self.selected = [], None
        self.busy, self.discovered, self.alive = False, False, True
        self.next_cursor, self.pending_start = None, benchmark.load_pending(root)
        self.last_refresh, self.after_id = 0.0, None
        self.bind('<Destroy>', self.destroyed, add='+')
        # Reserve actions before expandable visual content at minimum size.
        footer = tk.Frame(self, bg=theme.BG)
        footer.pack(side='bottom', fill='x', pady=(10, 0))
        controls = tk.Frame(self, bg=theme.BG)
        controls.pack(fill='x', pady=(0, 10))
        controls.columnconfigure(0, weight=1)
        self.model = ttk.Combobox(controls, state='readonly', width=22)
        self.model.grid(row=0, column=0, sticky='ew', padx=(0, 8))
        self.suite = ttk.Combobox(controls, state='readonly', values=list(SUITES), width=18)
        self.suite.set('Quick check')
        self.suite.grid(row=0, column=1, padx=(0, 8))
        self.preset = ttk.Combobox(controls, state='readonly', values=list(PRESETS), width=9)
        self.preset.set('Quick')
        if self.pending_start:
            self.model.set(self.pending_start['model'])
            self.suite.set(next(key for key, value in SUITES.items() if value == self.pending_start['suite']))
            self.preset.set(next(key for key, value in PRESETS.items() if value == self.pending_start.get('preset', 'quick')))
        self.preset.grid(row=0, column=2, padx=(0, 8))
        self.start = ttk.Button(controls, text='Run benchmark', style='Accent.TButton', command=self.run)
        self.start.grid(row=0, column=3)
        ttk.Button(controls, text='Production tasks…', command=self.production_tasks).grid(row=1, column=0, columnspan=2, sticky='w', pady=6)
        ttk.Button(controls, text='Setup coding sandbox…', command=lambda: open_benchmark_settings(self, self.root)).grid(row=1, column=2, columnspan=2, sticky='e', pady=6)
        self.model.bind('<<ComboboxSelected>>', lambda _: self.update_controls())
        self.suite.bind('<<ComboboxSelected>>', lambda _: self.explain())
        self.preset.bind('<<ComboboxSelected>>', lambda _: self.explain())
        self.status = theme.label(self, 'Select a model and a benchmark.', color=theme.MUTED, anchor='w', justify='left', wraplength=700)
        self.status.pack(fill='x', pady=(0, 12))
        cards = tk.Frame(self, bg=theme.BG)
        cards.pack(fill='x', pady=(0, 12))
        self.metrics = {}
        for column, (key, title) in enumerate((('score', 'SCORE'), ('completion', 'TRIALS'), ('latency', 'FIRST RESPONSE'), ('state', 'RUN STATUS'))):
            cards.columnconfigure(column, weight=1)
            card = tk.Frame(cards, bg=theme.PANEL, padx=12, pady=11)
            card.grid(row=0, column=column, sticky='nsew', padx=(0, 8 if column < 3 else 0))
            theme.label(card, title, size=8, color=theme.MUTED, bg=theme.PANEL).pack(anchor='w')
            value = theme.label(card, '—', size=15, color=theme.CYAN, bold=True, bg=theme.PANEL)
            value.pack(anchor='w', pady=(5, 0))
            self.metrics[key] = value
        self.tabs = ttk.Notebook(self)
        self.tabs.pack(fill='both', expand=True)
        self.pages = {}
        for title in ('Overview', 'Breakdown', 'Evidence', 'History'):
            page = tk.Frame(self.tabs, bg=theme.BG)
            self.tabs.add(page, text=title)
            self.pages[title] = page
        self.summary_text = theme.label(self.pages['Overview'], 'Closing the window keeps admitted work running on Gateway.', color=theme.MUTED,
                                        anchor='nw', justify='left', wraplength=700)
        self.summary_text.pack(side='bottom', fill='x', pady=(0, 10))
        self.chart = Bars(self.pages['Overview'])
        self.chart.pack(fill='both', expand=True, pady=(10, 8))
        self.case_table = ttk.Treeview(self.pages['Breakdown'], columns=('case', 'status', 'score', 'first', 'total'), show='headings', height=9)
        self.case_table.pack(side='left', fill='both', expand=True, pady=10)
        scroll = ttk.Scrollbar(self.pages['Breakdown'], orient='vertical', command=self.case_table.yview)
        scroll.pack(side='right', fill='y', pady=10)
        self.case_table.configure(yscrollcommand=scroll.set)
        for key, title, width in [('case', 'TEST / PAIR', 220), ('status', 'OUTCOME', 100), ('score', 'POINTS', 80), ('first', 'FIRST TEXT', 95), ('total', 'DURATION', 95)]:
            self.case_table.heading(key, text=title)
            self.case_table.column(key, width=width, minwidth=70, stretch=key == 'case')
        self.case_table.bind('<<TreeviewSelect>>', self.case_details)
        self.evidence = tk.Text(self.pages['Evidence'], bg=theme.PANEL, fg=theme.TEXT, insertbackground=theme.CYAN,
                                relief='flat', padx=14, pady=14, wrap='word', font=('Consolas', 10), state='disabled')
        self.evidence.pack(fill='both', expand=True, pady=10)
        history_controls = tk.Frame(self.pages['History'], bg=theme.BG)
        history_controls.pack(side='bottom', fill='x', pady=10)
        self.table = ttk.Treeview(self.pages['History'], columns=('model', 'suite', 'state', 'date'), show='headings', selectmode='browse', height=8)
        self.table.pack(fill='both', expand=True, pady=(10, 0))
        for key, title, width in [('model', 'MODEL', 220), ('suite', 'SUITE', 150), ('state', 'STATE', 120), ('date', 'TESTED', 130)]:
            self.table.heading(key, text=title, anchor='w')
            self.table.column(key, width=width, minwidth=70, stretch=key == 'model')
        self.table.bind('<<TreeviewSelect>>', self.select_history)
        self.more = ttk.Button(history_controls, text='Load earlier results', command=lambda: self.refresh_history(more=True))
        self.more.pack(side='left')
        ttk.Button(history_controls, text='Legacy results', command=self.show_legacy).pack(side='left', padx=8)
        self.remove = ttk.Button(history_controls, text='Remove saved result', command=self.remove_selected)
        self.remove.pack(side='right')
        self.reload = ttk.Button(footer, text='Refresh models', command=self.refresh_models)
        self.reload.pack(side='left')
        ttk.Button(footer, text='Refresh history', command=self.refresh_history).pack(side='left', padx=8)
        self.export = ttk.Button(footer, text='Export results', command=self.export_selected)
        self.export.pack(side='right')
        self.stop_button = ttk.Button(footer, text='Stop run', command=self.stop_selected)
        self.stop_button.pack(side='right', padx=8)
        self.bind('<Configure>', self.resize, add='+')
        self.update_controls()
        self.after_id = self.after(200, self.poll)

    def destroyed(self, event):
        if event.widget is self:
            self.alive = False
            if self.after_id:
                try:
                    self.after_cancel(self.after_id)
                except tk.TclError:
                    pass

    def resize(self, event):
        if event.widget is self:
            self.status.configure(wraplength=max(250, event.width - 12))
            self.summary_text.configure(wraplength=max(250, event.width - 24))

    def production_tasks(self):
        if self.busy:
            return
        path = filedialog.askopenfilename(parent=self, title='Open ten-scenario qualification manifest', filetypes=[('JSON', '*.json')])
        if not path:
            return
        def inspect():
            from desktop.conversation_client import Client
            from desktop.configuration import atomic_json
            from benchmarks.engineering_qualification import run_manifest
            source = Path(path)
            if source.stat().st_size > 1024 * 1024:
                raise ValueError('Qualification manifest exceeds 1 MiB')
            manifest = json.loads(source.read_text())
            client = Client(self.root)
            client.identity()
            result = run_manifest(client, manifest)
            atomic_json(source.with_suffix('.results.json'), result)
            return result
        self.launch('production-qualification', inspect, 'Reading exact production-job evidence…')

    def show_qualification(self, report):
        self.selected = None
        self.metrics['score'].configure(text='Qualified' if report['qualified'] else 'Not qualified')
        self.metrics['completion'].configure(text=f"{report['passed']} / {report['total']}")
        self.metrics['latency'].configure(text='Not measured')
        from benchmarks.engineering_qualification import CONTINUOUS_SCHEMA
        from benchmarks import workspace_qualification as workspace
        label = 'Development workspace' if report['schema'] == workspace.SCHEMA else 'Continuous engineering' if report['schema'] == CONTINUOUS_SCHEMA else 'Production tasks'
        self.metrics['state'].configure(text=label)
        measured = [row for row in report['cases'] if row.get('elapsed_seconds') is not None]
        self.chart.set([(row['scenario'].replace('_', ' '), row['elapsed_seconds'], theme.CYAN if row['status'] == 'PASSED' else theme.ERROR) for row in measured], ' s')
        self.summary_text.configure(text=f'{report["total"]} scenario-specific evidence gates. Missing runtime, recovery, device, or mutation observations remain unverified. Fixture scores are separate.')
        self.case_table.delete(*self.case_table.get_children())
        for index, row in enumerate(report['cases']):
            self.case_table.insert('', 'end', iid=str(index), values=(row['scenario'].replace('_', ' '), row['status'],
                'Not measured', 'Not measured', pretty(row.get('elapsed_seconds'), ' s')))
        self.write_evidence(report)
        self.tabs.select(self.pages['Overview'])
        self.status.configure(text='Production evidence refreshed; results saved beside the selected manifest.')

    def activate(self):
        if not self.discovered and not self.busy:
            self.refresh_models()
        elif not self.busy:
            self.refresh_history()

    def explain(self):
        suite, preset = SUITES[self.suite.get()], PRESETS[self.preset.get()]
        descriptions = {
            'quick_check': 'Five short answer fixtures. Scores apply to this check only.',
            'batch_comparison': {'quick': '3', 'standard': '10', 'deep': '30'}[preset] + ' matched pairs: serial versus two concurrent HTTP requests. Native inference batching is not inferred.',
            'coding': {'quick': '12 trials across 12 repair problems.', 'standard': '36 seeded trials across 12 repair problems.', 'deep': '96 seeded repair trials and 4 integration capstones: 16 problems, 100 trials.'}[preset] + ' Requires prepared Python sandbox; choose Setup coding sandbox. No production GitHub writes.'}
        self.status.configure(text=descriptions[suite])

    def update_controls(self):
        frozen = self.busy or self.pending_start is not None
        for control in (self.model, self.suite, self.preset):
            control.configure(state='disabled' if frozen else 'readonly')
        self.start.configure(state='normal' if not self.busy and (self.pending_start or self.model.get()) else 'disabled', text='Check submission' if self.pending_start else 'Run benchmark')
        self.reload.configure(state='disabled' if self.busy else 'normal')
        active = bool(self.selected and self.selected.get('state') not in TERMINAL)
        self.stop_button.configure(state='normal' if active and not self.busy else 'disabled')
        self.export.configure(state='normal' if self.selected and not self.busy else 'disabled')
        self.remove.configure(state='normal' if self.selected and not active and not self.busy else 'disabled')
        self.more.configure(state='normal' if self.next_cursor and not self.busy else 'disabled')

    def launch(self, operation, action, status=None):
        if self.busy or not self.alive:
            return
        self.busy = True
        self.update_controls()
        if status:
            self.status.configure(text=status)
        def work():
            try:
                result = (operation, action())
            except benchmark.BenchmarkRejected as error:
                result = ('rejected' if operation == 'started' else 'error', str(error)[:240])
            except requests.RequestException:
                result = ('error', 'Gateway could not be reached. Saved work remains available when it reconnects.')
            except (OSError, ValueError, KeyError, TypeError) as error:
                result = ('error', str(error)[:240] if isinstance(error, ValueError) else 'Benchmark data could not be read or saved.')
            except Exception:
                result = ('error', 'The benchmark view could not complete the request. Retained evidence is unchanged.')
            if self.alive:
                self.events.put(result)
        threading.Thread(target=work, name='benchmark-view', daemon=True).start()

    def refresh_models(self):
        self.launch('models', lambda: benchmark.discover(self.root), 'Reading available models…')

    def refresh_history(self, more=False):
        cursor = self.next_cursor if more else None
        self.launch('more_history' if more else 'history', lambda: benchmark.history(self.root, cursor))

    def run(self):
        if self.pending_start is not None:
            pending = self.pending_start
            if '_binding' not in pending:
                self.status.configure(text='The first submission was not sent. Restore Gateway access and reopen this view.')
                return
            self.launch('reconciled', lambda: benchmark.reconcile_run(self.root, pending['request_id'], pending['_binding']),
                        'Checking the original submission receipt…')
            return
        if not self.pending_start:
            self.pending_start = {'model': self.model.get(), 'suite': SUITES[self.suite.get()], 'preset': PRESETS[self.preset.get()], 'request_id': 'desktop-' + uuid.uuid4().hex}
        pending = self.pending_start
        def submit():
            # Offline verification happens before any request or idempotency receipt is sent.
            verify_submission(self.root, pending['suite'])
            if '_binding' not in pending:
                pending['_binding'] = benchmark.execution_binding(self.root)
            benchmark.save_pending(self.root, pending)
            request = {key: value for key, value in pending.items() if not key.startswith('_')}
            return benchmark.start_run(self.root, **request, binding=pending['_binding'])
        self.launch('started', submit, 'Submitting to Gateway…')

    def poll(self):
        if not self.alive:
            return
        for _ in range(16):
            try:
                event, value = self.events.get_nowait()
            except queue.Empty:
                break
            self.busy = False
            if event == 'models':
                old = self.model.get()
                self.model.configure(values=value)
                self.model.set(old if old in value else value[0] if value else '')
                self.discovered = bool(value)
                self.explain()
                self.refresh_history()
            elif event in {'history', 'more_history'}:
                self.results = (self.results if event == 'more_history' else []) + value.get('runs', [])
                self.next_cursor = value.get('next')
                self.render_history()
                if self.results and self.selected is None:
                    ident = self.results[0]['id']
                    self.launch('snapshot', lambda ident=ident: benchmark.run_snapshot(self.root, ident))
            elif event in {'started', 'snapshot', 'stopped'}:
                if event == 'started':
                    benchmark.save_pending(self.root, None)
                    self.pending_start = None
                    self.tabs.select(self.pages['Overview'])
                self.show(value)
            elif event == 'reconciled':
                state = value.get('submissionState')
                if state == 'OBSERVED' and isinstance(value.get('run'), dict):
                    benchmark.save_pending(self.root, None)
                    self.pending_start = None
                    self.show(value['run'])
                elif state == 'REMOVED':
                    benchmark.save_pending(self.root, None)
                    self.pending_start = None
                    self.status.configure(text='The original result was removed. Its request identity cannot execute again.')
                else:
                    self.status.configure(text='No original request receipt is visible. No new work was sent. Restore the original connection and check again.')
            elif event == 'production-qualification':
                self.show_qualification(value)
            elif event == 'exported':
                self.status.configure(text='Results exported with retained measurements and provenance.')
            elif event == 'removed':
                self.selected = None
                self.refresh_history()
            elif event == 'rejected':
                if self.pending_start and self.pending_start.get('_uncertain'):
                    self.status.configure(text=value + ' The original submission is still uncertain; restore access to reconcile it.')
                else:
                    benchmark.save_pending(self.root, None)
                    self.pending_start = None
                    self.status.configure(text=value + ' Choose another benchmark or correct the settings.')
            elif event == 'error':
                if self.pending_start:
                    if '_binding' not in self.pending_start:
                        self.pending_start = None
                    else:
                        self.pending_start['_uncertain'] = True
                self.status.configure(text=value + (' Use Check submission to reconcile the same request.' if self.pending_start else ''))
            self.update_controls()
        if self.selected and self.selected.get('state') not in TERMINAL and not self.busy and time.monotonic() - self.last_refresh >= 1:
            self.last_refresh = time.monotonic()
            ident = self.selected['id']
            self.launch('snapshot', lambda: benchmark.run_snapshot(self.root, ident))
        self.after_id = self.after(200, self.poll)

    def render_history(self):
        self.table.delete(*self.table.get_children())
        for index, row in enumerate(self.results):
            request = row.get('request', {})
            self.table.insert('', 'end', iid=str(index), values=(request.get('model', ''), request.get('suite', '').replace('_', ' '), row.get('state'), datetime.fromtimestamp(row['createdAt']).strftime('%b %d, %H:%M')))

    def show_legacy(self):
        self.write_evidence({'scope': 'Legacy quick-check scoreboard; distinct from the new coding and paired suites.',
                             'results': benchmark.scoreboard(self.root)})
        self.tabs.select(self.pages['Evidence'])

    def select_history(self, _event=None):
        selected = self.table.selection()
        if selected and not self.busy:
            ident = self.results[int(selected[0])]['id']
            self.launch('snapshot', lambda: benchmark.run_snapshot(self.root, ident))

    def show(self, run):
        self.selected = run
        summary, cases = run.get('summary') or {}, run.get('cases', [])
        score = summary.get('score')
        self.metrics['score'].configure(text=f'{score:g}/{summary.get("maximum", 100)}' if type(score) in (int, float) else 'Ungraded')
        self.metrics['completion'].configure(text=f'{run.get("completed", 0)} / {run.get("planned", 0)}')
        first = sorted(c.get('firstMs', c.get('timing', {}).get('firstMs')) for c in cases if type(c.get('firstMs', c.get('timing', {}).get('firstMs'))) in (int, float))
        self.metrics['latency'].configure(text=pretty(first[len(first) // 2] if first else None, ' ms', 0))
        self.metrics['state'].configure(text=run['state'].replace('_', ' ').title())
        self.status.configure(text=run.get('progress', ''))
        suite = run['request']['suite']
        if suite == 'coding':
            self.chart.set([(name.title(), item.get('value'), theme.CYAN) for name, item in summary.get('dimensions', {}).items()], ' pts')
            detail = f'{16 if run["request"]["preset"] == "deep" else 12} distinct fixture problems · {run.get("planned", 0)} trials · {summary.get("resolved", 0)} resolved. '
            detail += 'The 1000-point result covers declared isolated fixture criteria. Live GitHub, GPU and general intelligence are not measured.'
            if score is None:
                detail += ' Overall score unavailable pending complete verification.'
                provisional = summary.get('provisionalEarned', summary.get('earned'))
                if type(provisional) in (int, float):
                    detail += f' Provisional evidence: {provisional:.2f} weighted points.'
                if summary.get('dimensions', {}).get('safety', {}).get('unknownWeight', 0) > 0:
                    detail += ' Independent input preservation has not been measured.'
        elif suite == 'batch_comparison':
            values = summary.get('makespanMs', {})
            self.chart.set([(name.title(), values.get(name, {}).get('median'), theme.CYAN if name == 'sequential' else theme.PURPLE) for name in ('sequential', 'concurrent')], ' ms')
            detail = f'{summary.get("pairedCount", 0)} complete matched pairs. '
            if summary.get('observedSpeedup') is not None:
                detail += 'Observed makespan ratio ' + pretty(summary['observedSpeedup'], '×') + '. '
            detail += 'Serial versus concurrent HTTP requests; lower makespan is better. Native inference batching and cache conditions are not verified.'
        else:
            self.chart.set([(c.get('name', c['id']), c.get('totalMs'), theme.CYAN if c.get('status') == 'PASSED' else theme.ERROR) for c in cases], ' ms')
            detail = 'Five tool-free fixtures measure answer accuracy and response timing. Each bar is a complete request duration; lower is faster.'
        self.summary_text.configure(text=detail)
        self.case_table.delete(*self.case_table.get_children())
        for index, case in enumerate(cases):
            timing = case.get('timing', case)
            self.case_table.insert('', 'end', iid=str(index), values=(case.get('title', case.get('name', case['id'])), case.get('status'), pretty(case.get('score'), '', 0), pretty(timing.get('firstMs'), ' ms', 0), pretty(timing.get('totalMs', case.get('validationMs')), ' ms', 0)))
        self.write_evidence({'request': run['request'], 'provenance': run.get('provenance'), 'summary': summary, 'failure': run.get('failure'), 'completionReceipt': run.get('completionReceipt'), 'pendingCaseIds': run.get('pendingCaseIds'), 'detail': 'Select a Breakdown row for case-level receipts.'})
        self.update_controls()

    def write_evidence(self, value):
        self.evidence.configure(state='normal')
        self.evidence.delete('1.0', 'end')
        self.evidence.insert('1.0', json.dumps(value, indent=2, ensure_ascii=False))
        self.evidence.configure(state='disabled')

    def case_details(self, _event=None):
        selected = self.case_table.selection()
        if selected and self.selected:
            self.write_evidence(self.selected.get('cases', [])[int(selected[0])])
            self.tabs.select(self.pages['Evidence'])

    def stop_selected(self):
        if self.selected:
            ident = self.selected['id']
            self.launch('stopped', lambda: benchmark.stop_run(self.root, ident), 'Requesting stop…')

    def export_selected(self):
        if not self.selected:
            return
        path = filedialog.asksaveasfilename(parent=self, title='Export benchmark evidence', defaultextension='.json', initialfile='gateway-benchmark-' + self.selected['id'][6:14] + '.json', filetypes=[('JSON results', '*.json')])
        if path:
            ident = self.selected['id']
            self.launch('exported', lambda: benchmark.export_run(self.root, ident, path))

    def remove_selected(self):
        if self.selected and messagebox.askyesno('Remove saved benchmark', 'Remove this saved result? Export it first if you want to retain a copy.', parent=self):
            ident = self.selected['id']
            self.launch('removed', lambda: benchmark.remove_run(self.root, ident))


def open_benchmark_settings(parent, root):
    """Operator-approved setup; network and Docker work never run on Tk's thread."""
    from benchmarks import sandbox
    window = tk.Toplevel(parent)
    theme.apply(window)
    window.title('Coding benchmark sandbox')
    window.geometry('710x460')
    content = tk.Frame(window, bg=theme.BG, padx=24, pady=20)
    content.pack(fill='both', expand=True)
    theme.label(content, 'Coding benchmark sandbox', size=18, bold=True).pack(anchor='w')
    theme.label(
        content,
        'Prepare the official Python image once. Gateway pins its verified registry digest, '
        'tests offline execution and keeps generated code isolated from your PC.',
        color=theme.MUTED, wraplength=635, justify='left'
    ).pack(anchor='w', pady=(9, 14))
    try:
        settings = load_settings(root)
    except (OSError, ValueError) as error:
        messagebox.showerror('Benchmark settings', str(error), parent=window)
        window.destroy()
        return None

    image = tk.StringVar(master=window, value=settings['sandbox_image'])
    theme.label(content, 'Approved local image @sha256 digest (advanced/manual)').pack(anchor='w')
    image_field = ttk.Entry(content, textvariable=image)
    image_field.pack(fill='x', pady=(4, 12))
    repairs = tk.StringVar(master=window, value=str(settings['repair_attempts']))
    row = tk.Frame(content, bg=theme.BG)
    row.pack(fill='x')
    theme.label(row, 'Repair attempts per coding trial').pack(side='left')
    repairs_field = ttk.Combobox(row, textvariable=repairs, values=('0', '1', '2'), state='readonly', width=4)
    repairs_field.pack(side='right')
    message = tk.StringVar(master=window, value='Not checked. Prepare the sandbox, or verify an existing approved pin.')
    theme.label(content, '', textvariable=message, color=theme.MUTED, wraplength=635,
                justify='left').pack(anchor='w', pady=(18, 4))
    progress = ttk.Progressbar(content, mode='indeterminate')
    progress.pack(fill='x', pady=(5, 12))
    actions = tk.Frame(content, bg=theme.BG)
    actions.pack(fill='x', pady=(12, 4))
    events = queue.Queue()
    cancel_event = threading.Event()
    busy = False

    def save():
        nonlocal settings
        if busy:
            return
        try:
            settings = save_settings(
                root, dict(settings, sandbox_image=image.get().strip(),
                           repair_attempts=int(repairs.get())), expected=settings)
        except (ValueError, OSError, RuntimeError) as error:
            messagebox.showerror('Benchmark settings', str(error), parent=window)
            return
        message.set('Saved. The selected image will be verified before a coding run.')

    def start(kind):
        nonlocal busy
        if busy:
            return
        if kind == 'prepare' and not messagebox.askyesno(
                'Prepare coding benchmark sandbox',
                'Gateway will download the official Python image from Docker Hub, verify its '
                'immutable registry digest, test restricted offline Python and save the pin. '
                'This replaces any manually entered sandbox image. Continue?', parent=window):
            return
        busy = True
        cancel_event.clear()
        for widget in (image_field, repairs_field, prepare_button, check_button, save_button):
            widget.configure(state='disabled')
        stop_button.configure(state='normal')
        progress.start(12)
        message.set('Preparing secure Python sandbox…' if kind == 'prepare' else 'Verifying installed sandbox…')
        snapshot = dict(settings)
        current_image = image.get().strip()

        def worker():
            try:
                if kind == 'prepare':
                    result = prepare_sandbox(root, expected=snapshot, cancel=cancel_event,
                                             progress=lambda value: events.put(('progress', value)))
                else:
                    if current_image != snapshot['sandbox_image']:
                        raise ValueError('Save the edited image digest before checking it.')
                    sandbox.preflight(snapshot)
                    smoke_sandbox_image(snapshot['sandbox_image'], cancel=cancel_event)
                    result = snapshot['sandbox_image']
                events.put(('success', result))
            except (OSError, ValueError, RuntimeError, TimeoutError) as error:
                events.put(('failure', str(error)[:240]))
            except Exception:
                events.put(('failure', 'Sandbox setup failed. Check Docker Desktop and retry.'))
        threading.Thread(target=worker, name='benchmark-sandbox-setup', daemon=True).start()

    def poll():
        nonlocal settings, busy
        while True:
            try:
                kind, value = events.get_nowait()
            except queue.Empty:
                break
            if kind == 'progress':
                message.set(value)
                continue
            busy = False
            progress.stop()
            for widget in (image_field, prepare_button, check_button, save_button):
                widget.configure(state='normal')
            repairs_field.configure(state='readonly')
            stop_button.configure(state='disabled')
            if kind == 'success':
                try:
                    settings = load_settings(root)
                    image.set(settings['sandbox_image'])
                    message.set('Verified: ' + value + ' · Restricted offline Python execution passed.')
                except (OSError, ValueError):
                    message.set('Sandbox check passed, but saved settings could not be reloaded.')
            else:
                message.set('Setup not changed: ' + value)
        if window.winfo_exists():
            window.after(120, poll)

    prepare_button = ttk.Button(actions, text='Prepare sandbox', style='Accent.TButton',
                                command=lambda: start('prepare'))
    prepare_button.pack(side='left')
    check_button = ttk.Button(actions, text='Check setup (offline)', command=lambda: start('check'))
    check_button.pack(side='left', padx=8)
    stop_button = ttk.Button(actions, text='Cancel setup', command=cancel_event.set, state='disabled')
    stop_button.pack(side='left')
    save_button = ttk.Button(actions, text='Save settings', command=save)
    save_button.pack(side='right')
    theme.label(content,
                'Docker Desktop with Linux containers is required for coding benchmarks only. '
                'Quick checks do not need Docker. Setup never changes the GitHub validation policy.',
                color=theme.MUTED, wraplength=635, justify='left').pack(anchor='w', pady=(12, 0))

    def destroyed(event):
        if event.widget is window:
            cancel_event.set()
    window.bind('<Destroy>', destroyed, add='+')
    window.after(120, poll)
    return window
