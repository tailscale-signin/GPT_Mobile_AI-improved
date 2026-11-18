"""Run the operator's analysis against bounded data inside the configured offline container."""
import csv
import io
import json
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import filedialog, ttk

from benchmarks.sandbox import execute
from benchmarks.settings import load_settings
from desktop.analysis_export import FILE_TYPES, export_result


def analyze(root, code, source, cancel):
    if not 1 <= len(code.encode('utf-8')) <= 32768 or len(source.encode('utf-8')) > 131072:
        raise ValueError('Use up to 32 KiB of Python and 128 KiB of JSON input.')
    data = json.loads(source)
    result = execute({'service.py': code}, [data], load_settings(root), cancel)
    rows = result.get('outputs', [])
    if result.get('status') != 'COMPLETED' or len(rows) != 1 or 'output' not in rows[0] or 'error' in rows[0]:
        raise ValueError('Analysis did not produce a JSON result.')
    return json.dumps(rows[0]['output'], ensure_ascii=False, allow_nan=False, indent=2)


def import_data(path):
    with Path(path).open('rb') as stream:
        data = stream.read(131073)
    if len(data) > 131072:
        raise ValueError('Input exceeds 128 KiB.')
    text = data.decode('utf-8-sig')
    if Path(path).suffix.lower() == '.csv':
        text = json.dumps(list(csv.reader(io.StringIO(text))), ensure_ascii=False)
    json.loads(text)
    if len(text.encode('utf-8')) > 131072:
        raise ValueError('Converted input exceeds 128 KiB.')
    return text


def show_analysis(parent, root, initial_code, append_result):
    window = tk.Toplevel(parent)
    window.title('Analysis workspace')
    body = ttk.Frame(window, padding=12)
    body.pack(fill='both', expand=True)
    def editor(label, height):
        ttk.Label(body, text=label).pack(anchor='w')
        value = tk.Text(body, height=height, width=72, wrap='word')
        value.pack(fill='both', expand=True)
        return value
    code = editor('Python · define handle(data)', 8)
    code.insert('1.0', initial_code or 'def handle(data):\n    return data\n')
    source = editor('Input data · JSON', 4)
    source.insert('1.0', '[]')
    output = editor('Result', 6)
    output.configure(state='disabled')
    status = tk.StringVar(window)
    toolbar = ttk.Frame(body)
    toolbar.pack(fill='x')
    ttk.Label(body, textvariable=status).pack(anchor='w')
    cancel = threading.Event()
    results = queue.Queue(maxsize=1)
    def show_status(error):
        status.set(str(error)[:240])
    def load():
        path = filedialog.askopenfilename(parent=window, filetypes=[('CSV and JSON', '*.csv *.json')])
        if path:
            try:
                text = import_data(path)
                source.delete('1.0', 'end')
                source.insert('1.0', text)
            except (ValueError, OSError, csv.Error, RecursionError) as error:
                show_status(error)
    def save():
        path = filedialog.asksaveasfilename(parent=window, defaultextension='.json', filetypes=FILE_TYPES)
        if path:
            try:
                target = Path(path)
                data = export_result(output.get('1.0', 'end-1c'), target.suffix.lower())
                target.write_bytes(data)
                status.set('Saved ' + target.name)
            except (OSError, ValueError, RecursionError) as error:
                show_status(error)
    def poll():
        try:
            ok, value = results.get_nowait()
        except queue.Empty:
            window.after(100, poll)
            return
        run_button.configure(state='normal')
        if not ok:
            show_status(value)
            return
        output.configure(state='normal')
        output.delete('1.0', 'end')
        output.insert('1.0', value)
        output.configure(state='disabled')
        for button in (save_button, chat_button):
            button.configure(state='normal')
        status.set('Analysis complete.')
    def run():
        args = code.get('1.0', 'end-1c'), source.get('1.0', 'end-1c')
        for button in (run_button, chat_button, save_button):
            button.configure(state='disabled')
        status.set('Running in the offline container…')
        def work():
            try:
                results.put((True, analyze(root, *args, cancel)))
            except Exception as error:
                results.put((False, error))
        threading.Thread(target=work, daemon=True).start()
        window.after(100, poll)
    ttk.Button(toolbar, text='Open data…', command=load).pack(side='left')
    run_button = ttk.Button(toolbar, text='Run analysis', command=run)
    run_button.pack(side='left')
    save_button = ttk.Button(toolbar, text='Export result…', command=save, state='disabled')
    save_button.pack(side='left')
    def use_result():
        value = output.get('1.0', 'end-1c')
        if len(value) > 32000:
            status.set('The result exceeds one chat prompt. Save it or narrow the analysis.')
        else:
            status.set('Added to the chat draft.' if append_result(value) else 'Open an editable chat with enough space first.')
    chat_button = ttk.Button(toolbar, text='Add result to draft', command=use_result, state='disabled')
    chat_button.pack(side='left')
    window.bind('<Destroy>', lambda event: cancel.set() if event.widget is window else None, add='+')
