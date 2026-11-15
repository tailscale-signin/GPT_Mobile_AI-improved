"""Optional Docker and WSL 2 onboarding, shared by installer and Gateway Settings."""
from __future__ import annotations
import queue
from pathlib import Path
import threading
import tkinter as tk
from tkinter import messagebox, ttk
import webbrowser

from desktop import engineering_setup as setup
from desktop import repository_validation as validation
from desktop.configuration import load
from desktop.validation_policy import load_policy_snapshot

STEPS = [('git', 'Git'), ('wsl', 'WSL 2'), ('docker', 'Docker Linux engine'),
         ('credentials', 'Docker CLI credentials')]


class EngineeringSetupWindow:
    def __init__(self, root, parent=None):
        self.root, self.parent = Path(root), parent
        self.window = tk.Toplevel(parent) if parent else tk.Tk()
        if parent:
            self.window.transient(parent.winfo_toplevel())
        self.window.title('Gateway - Engineering setup')
        self.window.geometry('650x570')
        self.window.minsize(560, 470)
        self.mode = tk.StringVar(master=self.window, value='full')
        self.summary = tk.StringVar(master=self.window, value='Checking your PC does not change system settings.')
        self.results = queue.Queue()
        self.cancel = threading.Event()
        self.worker = None
        self.busy = self.closed = False
        self.window.protocol('WM_DELETE_WINDOW', self.close)
        body = ttk.Frame(self.window, padding=18)
        body.pack(fill='both', expand=True)
        ttk.Label(body, text='Engineering environment setup', font=('Segoe UI', 17, 'bold')).pack(anchor='w')
        ttk.Label(body, text='Standard Gateway does not require Docker. Full Engineering uses temporary Linux validation containers.',
                  wraplength=570).pack(anchor='w', pady=(5, 12))
        for value, label in [('standard', 'Standard Gateway - no Docker needed'),
                             ('full', 'Full GitHub Engineering - Linux validation')]:
            ttk.Radiobutton(body, value=value, text=label, variable=self.mode,
                            command=self.set_mode).pack(anchor='w')
        ttk.Separator(body).pack(fill='x', pady=12)
        self.rows = {}
        for key, label in STEPS:
            row = ttk.Frame(body)
            row.pack(fill='x', pady=4)
            ttk.Label(row, text=label, width=22).pack(side='left')
            variable = tk.StringVar(master=self.window, value='Not checked')
            ttk.Label(row, textvariable=variable, wraplength=330).pack(side='left', fill='x', expand=True)
            self.rows[key] = variable
        ttk.Label(body, textvariable=self.summary, wraplength=575).pack(anchor='w', pady=(14, 10))
        action_bar = ttk.Frame(body)
        action_bar.pack(fill='x')
        self.buttons = {}
        actions = [('check', 'Check PC', self.check),
                   ('wsl', 'Install / update WSL', self.install_wsl),
                   ('docker', 'Install Docker', self.install_docker),
                   ('helper', 'Repair Docker PATH', self.repair_path),
                   ('prepare', 'Prepare & verify', self.prepare),
                   ('advanced', 'Advanced settings', self.open_validation)]
        for index, (key, label, command) in enumerate(actions):
            button = ttk.Button(action_bar, text=label, command=command)
            button.grid(row=index // 2, column=index % 2, sticky='ew', padx=3, pady=4)
            self.buttons[key] = button
        action_bar.columnconfigure(0, weight=1)
        action_bar.columnconfigure(1, weight=1)
        ttk.Button(body, text='Official Docker setup guide',
                   command=lambda: webbrowser.open(setup.DOCKER_DOCS)).pack(anchor='w', pady=7)
        ttk.Button(body, text='Close', command=self.close).pack(anchor='e')
        self.set_mode()
        self.window.after(100, self.poll)
        self.window.after(150, self.check)

    def set_mode(self):
        if self.closed:
            return
        for key, button in self.buttons.items():
            button.configure(state='disabled' if self.busy or
                             (self.mode.get() == 'standard' and key not in {'check', 'advanced'}) else 'normal')
        if self.mode.get() == 'standard':
            self.summary.set('Standard Gateway stays available. Existing validation settings are unchanged.')

    def run(self, title, function):
        if self.busy or self.closed or self.mode.get() != 'full':
            return
        self.busy = True
        self.cancel = threading.Event()
        self.set_mode()
        self.summary.set(title + '...')
        cancel = self.cancel
        def work():
            try:
                self.results.put(('done', title, function(cancel)))
            except Exception as error:
                self.results.put(('error', title, str(error) if isinstance(error, ValueError) else
                                  'The operation could not complete; saved settings are retained.'))
        self.worker = threading.Thread(target=work, name='engineering-setup-worker', daemon=False)
        self.worker.start()

    def check(self):
        self.run('Checking environment', lambda cancel: setup.check_environment(cancel=cancel))

    def install_wsl(self):
        if messagebox.askyesno('Install WSL 2', 'Install or update WSL 2? Windows may ask for administrator approval or a reboot. Existing distributions are preserved.',
                               parent=self.window):
            self.run('Installing WSL 2', lambda cancel: setup.install_prerequisite('wsl', approved=True, cancel=cancel))

    def install_docker(self):
        if messagebox.askyesno('Install Docker Desktop', 'Install the official Docker Desktop package using Windows Package Manager? This may require elevation, license acceptance or a reboot; no Docker data will be reset.',
                               parent=self.window):
            self.run('Installing Docker Desktop', lambda cancel: setup.install_prerequisite('docker', approved=True, cancel=cancel))

    def repair_path(self):
        if messagebox.askyesno('Repair Docker PATH', 'Add the existing official Docker credential-helper folder to your Windows user PATH? Existing settings and credentials will be retained.',
                               parent=self.window):
            self.run('Repairing Docker PATH', lambda cancel: setup.repair_helper_path(approved=True))

    def prepare(self):
        if not messagebox.askyesno('Approve Linux validation', 'Download or refresh the repository mirror and digest-pinned Linux image, save reviewed validation settings, and test a disposable offline container? No repository tests, GitHub writes or merges are authorized.',
                                   parent=self.window):
            return
        def work(cancel):
            report = setup.check_environment(cancel=cancel)
            if not report['ready_for_validation']:
                raise ValueError('Git, WSL 2 and Docker Linux containers are required. Check PC and repair missing prerequisites.')
            saved = load_policy_snapshot(self.root)
            if saved['state'] == 'INVALID':
                raise ValueError('Saved validation policy needs review in Advanced settings.')
            options = validation.options_for(self.root, validation.DEFAULT_REPOSITORY, saved['repositories'])
            token = None
            try:
                from desktop.github_setup import configured_connection
                _, token = configured_connection(self.root, load(self.root))
            except (ValueError, OSError):
                pass  # The public repository can be prepared without credentials.
            self.results.put(('progress', 'Preparing mirror and digest-pinned Linux image...'))
            prepared = validation.setup(self.root, options, saved['revision'], prepare=True,
                                        token=token, enabled=True, cancel=cancel,
                                        progress=lambda message: self.results.put(('progress', message)))
            self.results.put(('progress', 'Checking prepared policy and launching an offline smoke test...'))
            checked = validation.setup(self.root, dict(options, image=prepared['image']),
                                       prepared['snapshot']['revision'], prepare=False, enabled=True,
                                       cancel=cancel)
            return setup.smoke_image(checked['image'], cancel=cancel)
        self.run('Preparing GitHub validation', work)

    def open_validation(self):
        if self.parent and hasattr(self.parent, 'github_tabs'):
            self.parent.settings_tabs.select(3)
            self.parent.github_tabs.select(1)
            self.parent.lift()
        else:
            from desktop.ui import build_window
            settings = build_window(self.root, parent=self.window, section='GitHub')
            settings.github_tabs.select(1)

    def poll(self):
        if self.closed:
            return
        try:
            while True:
                kind, *items = self.results.get_nowait()
                if kind == 'progress':
                    self.summary.set(items[0])
                    continue
                title, result = items
                self.busy = False
                self.set_mode()
                if kind == 'error':
                    self.summary.set(title + ': ' + result)
                elif isinstance(result, dict) and 'checks' in result:
                    for key, value in result['checks'].items():
                        if key in self.rows:
                            self.rows[key].set(value[0] + ' - ' + value[1])
                    self.summary.set('Prerequisites ready. Select Prepare & verify.' if
                                     result['ready_for_validation'] else
                                     'Repair or install missing prerequisites, then Check PC again.')
                    self.buttons['helper'].configure(state='normal' if
                        self.mode.get() == 'full' and result['repairable_helper'] else 'disabled')
                else:
                    self.summary.set(str(result) + ' Restart Gateway when idle if validation was saved. Full Android builds require a separate approved image.')
        except queue.Empty:
            pass
        self.window.after(100, self.poll)

    def close(self):
        self.closed = True
        self.cancel.set()
        self.window.destroy()


def show(root, parent=None):
    wizard = EngineeringSetupWindow(root, parent)
    if parent is None:
        try:
            wizard.window.mainloop()
        finally:
            wizard.cancel.set()
            if wizard.worker is not None:
                wizard.worker.join()
    return wizard
