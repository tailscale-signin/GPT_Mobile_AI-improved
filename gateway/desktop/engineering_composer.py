"""Desktop engineering preparation and explicit acceptance review."""
import re
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk


class EngineeringForm(simpledialog.Dialog):
    def __init__(self, parent, model, conversation):
        self.model, self.conversation = model, conversation
        super().__init__(parent, title='Continue engineering' if conversation else 'Engineering task')

    def body(self, frame):
        frame.columnconfigure(1, weight=1)
        ttk.Label(frame, text='Model · ' + (self.model or 'Select a model first')).grid(row=0, column=0, columnspan=2, sticky='w')
        self.fields = {}
        if not self.conversation:
            fields = [('repository', 'Repository', ''), ('source_branch', 'Source branch', 'main'),
                      ('source_sha', 'Source commit SHA', ''), ('branch', 'Working branch', 'main'),
                      ('base', 'Target branch', 'main'), ('provider', 'Model provider', 'LLAMA')]
            for row, (name, label, value) in enumerate(fields, 1):
                ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', padx=(0, 8), pady=3)
                choice = tk.StringVar(master=frame, value=value)
                self.fields[name] = choice
                field = (ttk.Combobox(frame, textvariable=choice, values=('LLAMA', 'OLLAMA'), state='readonly')
                         if name == 'provider' else ttk.Entry(frame, textvariable=choice, width=50))
                field.grid(row=row, column=1, sticky='ew', pady=3)
        ttk.Label(frame, text='Task scope').grid(row=7, column=0, sticky='w')
        self.scope = tk.StringVar(master=frame, value='implement')
        ttk.Combobox(frame, textvariable=self.scope, values=('implement', 'plan', 'read'), state='readonly').grid(row=7, column=1, sticky='ew')
        ttk.Label(frame, text='Task objective').grid(row=8, column=0, sticky='nw', pady=4)
        self.objective = tk.Text(frame, width=50, height=6, wrap='word')
        self.objective.grid(row=8, column=1, sticky='ew', pady=4)
        return self.objective

    def validate(self):
        prompt = self.objective.get('1.0', 'end-1c').strip()
        values = {key: variable.get().strip() for key, variable in self.fields.items()}
        if not self.model or not 1 <= len(prompt) <= 16000:
            messagebox.showerror('Engineering task', 'Choose a model and enter an objective of 1 to 16,000 characters.', parent=self)
            return False
        if not self.conversation and (not re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+', values['repository'])
                                      or not re.fullmatch(r'[a-f0-9]{40}', values['source_sha'])):
            messagebox.showerror('Engineering task', 'Enter a repository and its exact 40-character source commit SHA.', parent=self)
            return False
        self.result = dict({'provider': None}, **values, objective=prompt, task_scope=self.scope.get())
        return True


class AcceptanceForm(simpledialog.Dialog):
    def __init__(self, parent, proposal):
        self.proposal = proposal
        super().__init__(parent, title='Review acceptance for this task')

    def body(self, frame):
        frame.columnconfigure(1, weight=1)
        ttk.Label(frame, text='Approved tests at ' + self.proposal['source_sha'][:12]).grid(row=0, column=0, columnspan=2, sticky='w')
        self.rows = [(case, requirement) for case in self.proposal['candidates'] for requirement in case['requirement_ids']]
        self.selection = ttk.Combobox(frame, state='readonly', width=54,
                                      values=[row['case_id'] + ' · ' + requirement for row, requirement in self.rows])
        self.selection.grid(row=1, column=0, columnspan=2, sticky='ew', pady=5)
        self.selection.current(0)
        ttk.Label(frame, text='Changed source paths (one per line)').grid(row=2, column=0, columnspan=2, sticky='w')
        self.paths = tk.Text(frame, width=54, height=4, wrap='none')
        self.paths.grid(row=3, column=0, columnspan=2, sticky='ew', pady=4)
        ttk.Label(frame, text='Required check').grid(row=4, column=0, sticky='w')
        self.check = ttk.Combobox(frame, state='readonly', values=self.proposal['allowed_checks'])
        self.check.grid(row=4, column=1, sticky='ew')
        if self.proposal['allowed_checks']:
            self.check.current(0)
        return self.paths

    def validate(self):
        index = self.selection.current()
        paths = list(dict.fromkeys(line.strip() for line in self.paths.get('1.0', 'end-1c').splitlines() if line.strip()))
        if index < 0 or not self.check.get() or not 1 <= len(paths) <= 32:
            messagebox.showerror('Task acceptance', 'Select an approved test and CI check and enter at least one affected source path.', parent=self)
            return False
        case, requirement = self.rows[index]
        self.result = {'id': requirement, 'paths': paths, 'checks': [self.check.get()],
                       'evidence': [{'kind': 'behavior_test', 'name': case['case_id']}]}
        return True
