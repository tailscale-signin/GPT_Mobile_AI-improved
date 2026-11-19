"""Run the operator's analysis against bounded data inside the configured offline container."""
import csv
import io
import json
import os
from pathlib import Path
import queue
import threading
import tempfile
import tkinter as tk
from tkinter import filedialog, ttk

from benchmarks.sandbox import execute
from benchmarks.settings import load_settings
from desktop.analysis_export import FILE_TYPES, export_result


def analysis_input(code, source):
    if not isinstance(code, str) or not isinstance(source, str) or not 1 <= len(code.encode('utf-8')) <= 32768 or len(source.encode('utf-8')) > 131072:
        raise ValueError('Use up to 32 KiB of Python and 128 KiB of JSON input.')
    return json.loads(source)


def analyze(root, code, source, cancel):
    data = analysis_input(code, source)
    result = execute({'service.py': code}, [data], load_settings(root), cancel)
    rows = result.get('outputs', [])
    if result.get('status') != 'COMPLETED' or len(rows) != 1 or 'output' not in rows[0] or 'error' in rows[0]:
        raise ValueError('Analysis did not produce a JSON result.')
    return json.dumps(rows[0]['output'], ensure_ascii=False, allow_nan=False, indent=2)


def write_analysis(path, code, source):
    analysis_input(code, source)
    snapshot = {'format': 'gptmobile.analysis.v1', 'python': code, 'input': source}
    target = Path(path)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stream:
        temp = Path(stream.name)
        try:
            stream.write(json.dumps(snapshot, ensure_ascii=False).encode('utf-8'))
            stream.close()
            os.replace(temp, target)
        finally:
            stream.close()
            temp.unlink(missing_ok=True)


def read_analysis(path):
    with Path(path).open('rb') as stream:
        raw = stream.read(1_048_577)
    if len(raw) > 1_048_576:
        raise ValueError('Analysis file exceeds 1 MiB.')
    saved = json.loads(raw)
    if not isinstance(saved, dict) or saved.get('format') != 'gptmobile.analysis.v1':
        raise ValueError('Choose a saved analysis file.')
    code, source = saved.get('python'), saved.get('input')
    analysis_input(code, source)
    return code, source


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


def show_analysis(parent, root, initial_code, append_result, selected_python=None):
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
    def inputs():
        return code.get('1.0', 'end-1c'), source.get('1.0', 'end-1c')
    completed_inputs = None
    def current_result():
        if completed_inputs is not None and inputs() == completed_inputs:
            return True
        status.set('Run the edited analysis to get its result.')
        return False
    def clear_result():
        nonlocal completed_inputs
        completed_inputs = None
        output.configure(state='normal')
        output.delete('1.0', 'end')
        output.configure(state='disabled')
        for button in (save_button, chat_button):
            button.configure(state='disabled')
    def edited(event):
        if event.widget.edit_modified():
            event.widget.edit_modified(False)
            if completed_inputs is not None and inputs() != completed_inputs:
                clear_result()
                status.set('Analysis edited. Run again to update the result.')
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
        if not current_result():
            return
        path = filedialog.asksaveasfilename(parent=window, defaultextension='.json', filetypes=FILE_TYPES)
        if path:
            try:
                target = Path(path)
                data = export_result(output.get('1.0', 'end-1c'), target.suffix.lower())
                target.write_bytes(data)
                status.set('Saved ' + target.name)
            except (OSError, ValueError, RecursionError) as error:
                show_status(error)
    def workspace(load_file):
        picker = filedialog.askopenfilename if load_file else filedialog.asksaveasfilename
        path = picker(parent=window, defaultextension='.gptanalysis', filetypes=[('Editable analysis', '*.gptanalysis')])
        if not path:
            return
        try:
            if load_file:
                script, data = read_analysis(path)
                for widget, value in ((code, script), (source, data)):
                    widget.delete('1.0', 'end')
                    widget.insert('1.0', value)
                clear_result()
                status.set('Analysis opened. Review the Python and input before running.')
            else:
                write_analysis(path, code.get('1.0', 'end-1c'), source.get('1.0', 'end-1c'))
                status.set('Saved editable Python and input.')
        except (ValueError, OSError, RecursionError) as error:
            show_status(error)
    def poll():
        nonlocal completed_inputs
        try:
            args, ok, value = results.get_nowait()
        except queue.Empty:
            window.after(100, poll)
            return
        run_button.configure(state='normal')
        open_button.configure(state='normal')
        if args != inputs():
            clear_result()
            status.set('Inputs changed while running. Run again to update the result.')
            return
        if not ok:
            show_status(value)
            return
        output.configure(state='normal')
        output.delete('1.0', 'end')
        completed_inputs = args
        output.insert('1.0', value)
        output.configure(state='disabled')
        for button in (save_button, chat_button):
            button.configure(state='normal')
        status.set('Analysis complete.')
    def run():
        args = inputs()
        clear_result()
        for button in (run_button, open_button, chat_button, save_button):
            button.configure(state='disabled')
        status.set('Running in the offline container…')
        def work():
            try:
                results.put((args, True, analyze(root, *args, cancel)))
            except Exception as error:
                results.put((args, False, error))
        threading.Thread(target=work, daemon=True).start()
        window.after(100, poll)
    ttk.Button(toolbar, text='Open data…', command=load).pack(side='left')
    run_button = ttk.Button(toolbar, text='Run analysis', command=run)
    run_button.pack(side='left')
    save_button = ttk.Button(toolbar, text='Export result…', command=save, state='disabled')
    save_button.pack(side='left')
    def use_result():
        if not current_result():
            return
        value = output.get('1.0', 'end-1c')
        if len(value) > 32000:
            status.set('The result exceeds one chat prompt. Save it or narrow the analysis.')
        else:
            status.set('Added to the chat draft.' if append_result(value) else 'Return to the original editable conversation with enough draft space.')
    chat_button = ttk.Button(toolbar, text='Add result to draft', command=use_result, state='disabled')
    chat_button.pack(side='left')
    files = ttk.Frame(body)
    files.pack(fill='x')
    open_button = ttk.Button(files, text='Open analysis…', command=lambda: workspace(True))
    open_button.pack(side='left')
    ttk.Button(files, text='Save editable analysis…', command=lambda: workspace(False)).pack(side='left')
    def request_revision():
        script = code.get('1.0', 'end-1c')
        if not script.strip() or not append_result('Revise this analysis function:\n\n' + script):
            status.set('Return to the original editable conversation with enough draft space.')
        else:
            status.set('Python added to the draft. Describe the change and send when ready.')
    def use_revision():
        revised = selected_python()
        if not revised:
            status.set('Select the revised Python in the original chat.')
            return
        code.delete('1.0', 'end')
        code.insert('1.0', revised)
        status.set('Revised Python loaded. Review it before running with the current input.')
    if selected_python:
        ttk.Button(files, text='Request revision in chat', command=request_revision).pack(side='left')
        ttk.Button(files, text='Use selected revision', command=use_revision).pack(side='left')
    for widget in (code, source):
        widget.edit_modified(False)
        widget.bind('<<Modified>>', edited, add='+')
    window.bind('<Destroy>', lambda event: cancel.set() if event.widget is window else None, add='+')
