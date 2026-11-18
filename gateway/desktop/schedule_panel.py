"""Windows operator view of schedules processed by the Gateway service."""
from datetime import datetime
from pathlib import Path
import sqlite3
import tkinter as tk
import webbrowser
from tkinter import ttk

from v14.operator_schedule import OperatorSchedule
from v14.scheduled_publications import KIND, source_url
from v14.scheduled_validation import KIND as TEST_KIND


def show_schedules(parent, root):
    window = tk.Toplevel(parent)
    window.title('Scheduled work')
    store = OperatorSchedule(Path(root) / 'data')
    body = ttk.Frame(window, padding=12)
    body.pack(fill='both', expand=True)
    title, kind, delay, period = (tk.StringVar(window, value=value) for value in ('', 'Reminder', '5', '0'))
    query = tk.StringVar(window)
    for index, (label, value) in enumerate((('Task name', title), ('Action', kind), ('Due in minutes', delay), ('Repeat every hours (0 = once)', period))):
        ttk.Label(body, text=label).grid(row=index, column=0, sticky='w')
        field = ttk.Combobox(body, textvariable=value, state='readonly', values=('Reminder', 'Check saved job storage', KIND, TEST_KIND)) if value is kind else ttk.Entry(body, textvariable=value)
        field.grid(row=index, column=1, sticky='ew')
    query_label = ttk.Label(body, text='Public search words sent to Crossref')
    query_field = ttk.Entry(body, textvariable=query)
    query_label.grid(row=4, column=0, sticky='w')
    query_field.grid(row=4, column=1, sticky='ew')
    def enable_query(*_):
        if kind.get() in (KIND, TEST_KIND):
            query_label.configure(text='Approved repository (tests run on mirror HEAD)' if kind.get() == TEST_KIND else 'Public search words sent to Crossref')
            query_label.grid()
            query_field.grid()
        else:
            query_label.grid_remove()
            query_field.grid_remove()
    kind.trace_add('write', enable_query)
    enable_query()
    table = ttk.Treeview(body, columns=('name', 'due', 'last', 'result'), show='headings', height=8)
    for col, label in zip(table['columns'], ('Task', 'Next occurrence', 'Last occurrence', 'Result')):
        table.heading(col, text=label)
        table.column(col, width=155)
    table.grid(row=6, column=0, columnspan=3, sticky='nsew')
    status = tk.StringVar(window)
    ttk.Label(body, textvariable=status).grid(row=7, column=0, columnspan=3, sticky='w')

    received = {}

    def refresh():
        selected = table.selection()
        try:
            rows = store.rows()
        except (OSError, sqlite3.Error):
            status.set('Schedule storage is unavailable.')
            return
        for item in table.get_children():
            table.delete(item)
        def stamp(value):
            return datetime.fromtimestamp(value).strftime('%Y-%m-%d %H:%M') if value else '—'
        current = {row['id']: (row['last_due'], row['result']) for row in rows}
        for row in rows:
            previous = received.get(row['id'])
            if previous and previous != current[row['id']] and (row['result'] == 'Reminder due' or (row['result'] or '').endswith((' new publication', ' new publications'))):
                window.bell()
            table.insert('', 'end', iid=row['id'], values=(row['title'], stamp(row['due']), stamp(row['last_due']), row['result'] or 'Scheduled'))
        received.clear()
        received.update(current)
        if selected and table.exists(selected[0]):
            table.selection_set(selected[0])

    def add():
        try:
            store.add(title.get(), kind.get(), int(delay.get()), int(period.get()), query.get())
        except (ValueError, TypeError, KeyError, OSError, sqlite3.Error) as error:
            status.set(str(error))
            return
        title.set('')
        query.set('')
        status.set('Scheduled on this Gateway.')
        refresh()

    def remove():
        if table.selection():
            try:
                store.remove(table.selection()[0])
            except (OSError, sqlite3.Error):
                status.set('Could not remove schedule.')
            refresh()

    def view_results():
        selected = table.selection()
        if not selected:
            return
        try:
            records = store.publications(selected[0])
            checks = store.test_history(selected[0])
        except (ValueError, sqlite3.Error):
            status.set('Saved results are unavailable.')
            return
        if checks:
            dialog = tk.Toplevel(window)
            dialog.title('Repository test receipts')
            details = tk.Text(dialog, width=90, height=20, wrap='word')
            details.pack(fill='both', expand=True)
            for due, receipt in checks:
                when = datetime.fromtimestamp(due).strftime('%Y-%m-%d %H:%M')
                details.insert('end', f"{when} · {receipt['status']}\n{receipt['source_sha']}\n")
                for index, command in enumerate(receipt['commands'], 1):
                    details.insert('end', f"Command {index} · exit {command['exit_code']} · {command['elapsed_ms']} ms\nOutput SHA-256  {command['output_sha256']}\n")
                details.insert('end', '\n')
            details.configure(state='disabled')
            return
        if not records:
            status.set('This schedule has no saved sources or test receipts yet.')
            return
        dialog = tk.Toplevel(window)
        dialog.title('Latest Crossref metadata')
        for item in records:
            url = source_url(item['doi'])
            if url:
                ttk.Button(dialog, text=item['title'] + '\n' + item['doi'], command=lambda link=url: webbrowser.open(link)).pack(anchor='w', fill='x')

    def poll():
        refresh()
        window.after(15000, poll)

    ttk.Button(body, text='Schedule', command=add).grid(row=5, column=0)
    ttk.Button(body, text='Remove selected', command=remove).grid(row=5, column=1)
    ttk.Button(body, text='View results…', command=view_results).grid(row=5, column=2)
    body.columnconfigure(1, weight=1)
    body.rowconfigure(6, weight=1)
    poll()
