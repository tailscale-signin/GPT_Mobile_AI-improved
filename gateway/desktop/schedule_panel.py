"""Windows operator view of schedules processed by the Gateway service."""
from datetime import datetime
from pathlib import Path
import sqlite3
import tkinter as tk
from tkinter import ttk

from v14.operator_schedule import OperatorSchedule


def show_schedules(parent, root):
    window = tk.Toplevel(parent)
    window.title('Scheduled work')
    store = OperatorSchedule(Path(root) / 'data')
    body = ttk.Frame(window, padding=12)
    body.pack(fill='both', expand=True)
    title, kind, delay, period = (tk.StringVar(window, value=value) for value in ('', 'Reminder', '5', '0'))
    for index, (label, value) in enumerate((('Task name', title), ('Action', kind), ('Due in minutes', delay), ('Repeat every hours (0 = once)', period))):
        ttk.Label(body, text=label).grid(row=index, column=0, sticky='w')
        field = ttk.Combobox(body, textvariable=value, state='readonly', values=('Reminder', 'Check saved job storage')) if value is kind else ttk.Entry(body, textvariable=value)
        field.grid(row=index, column=1, sticky='ew')
    table = ttk.Treeview(body, columns=('name', 'due', 'last', 'result'), show='headings', height=8)
    for col, label in zip(table['columns'], ('Task', 'Next occurrence', 'Last occurrence', 'Result')):
        table.heading(col, text=label)
        table.column(col, width=155)
    table.grid(row=5, column=0, columnspan=3, sticky='nsew')
    status = tk.StringVar(window)
    ttk.Label(body, textvariable=status).grid(row=6, column=0, columnspan=3, sticky='w')

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
        for row in rows:
            table.insert('', 'end', iid=row['id'], values=(row['title'], stamp(row['due']), stamp(row['last_due']), row['result'] or 'Scheduled'))
        if selected and table.exists(selected[0]):
            table.selection_set(selected[0])

    def add():
        try:
            store.add(title.get(), kind.get(), int(delay.get()), int(period.get()))
        except (ValueError, OSError, sqlite3.Error) as error:
            status.set(str(error))
            return
        title.set('')
        status.set('Scheduled on this Gateway.')
        refresh()

    def remove():
        if table.selection():
            try:
                store.remove(table.selection()[0])
            except (OSError, sqlite3.Error):
                status.set('Could not remove schedule.')
            refresh()

    def poll():
        refresh()
        window.after(15000, poll)

    ttk.Button(body, text='Schedule', command=add).grid(row=4, column=0)
    ttk.Button(body, text='Remove selected', command=remove).grid(row=4, column=1)
    body.columnconfigure(1, weight=1)
    body.rowconfigure(5, weight=1)
    poll()
