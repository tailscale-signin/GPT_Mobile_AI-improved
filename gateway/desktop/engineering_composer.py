"""Desktop engineering preparation and explicit acceptance review."""
from contextlib import contextmanager
import re
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk

from desktop import theme
from desktop.conversation_client import ClientError, scope_key


@contextmanager
def modal_scope(panel):
    """Defer conversation refresh while a user reviews one engineering action."""
    previous = getattr(panel, 'engineering_modal', False)
    panel.engineering_modal = True
    try:
        yield
    finally:
        panel.engineering_modal = previous


class TaskPreparation:
    """Retain the exact connection and view that opened the task form."""
    VIEW_FIELDS = ('conversation_id', 'active_job_id', 'active_job_kind', 'revision', 'profile_id', 'model')

    def __init__(self, panel):
        self.client, self.model, self.epoch = panel.client(), panel.model.get(), panel.epoch
        self.view = dict(panel.conversation or {})
        self.origin = dict(self.view) if self.view.get('active_job_kind') == 'engineering' else None

    def current(self, panel):
        valid = (not panel.closed and panel.visible and not panel.busy and not panel.sending and
                 not panel.state.error and not panel.state.value.get('pending') and panel.epoch == self.epoch and
                 panel.model.get() == self.model and
                 scope_key(panel.identity or {}) == scope_key(self.client.expected_identity or {}) and
                 all((panel.conversation or {}).get(key) == self.view.get(key) for key in self.VIEW_FIELDS))
        if valid:
            try:
                self.client.check_scope({})
            except ClientError:
                valid = False
        if not valid and not panel.closed and panel.visible:
            panel.status.configure(text='The connection or task context changed. Open the engineering task again before submitting.')
        return valid


class _ThemedForm(simpledialog.Dialog):
    def buttonbox(self):
        self.configure(background=theme.BG)
        box = ttk.Frame(self)
        ttk.Button(box, text='OK', width=10, command=self.ok, default=tk.ACTIVE,
                   style='Accent.TButton').pack(side=tk.LEFT, padx=5, pady=5)
        ttk.Button(box, text='Cancel', width=10, command=self.cancel).pack(side=tk.LEFT, padx=5, pady=5)
        self.bind('<Return>', self.ok)
        self.bind('<Escape>', self.cancel)
        box.pack()


class EngineeringForm(_ThemedForm):
    def __init__(self, parent, model, conversation):
        self.model, self.conversation = model, conversation
        super().__init__(parent, title='Continue engineering' if conversation else 'Engineering task')

    def body(self, frame):
        frame.configure(background=theme.BG)
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
        self.objective = tk.Text(frame, width=50, height=6, wrap='word', bg=theme.PANEL, fg=theme.TEXT,
                                 insertbackground=theme.CYAN, selectbackground=theme.RAISED,
                                 selectforeground=theme.TEXT, relief='flat', highlightthickness=1,
                                 highlightbackground=theme.LINE, highlightcolor=theme.CYAN)
        self.objective.grid(row=8, column=1, sticky='ew', pady=4)
        if not self.conversation:
            for row, name, label in ((9, 'adopt_pr_number', 'Existing PR (optional)'), (10, 'adopt_base_sha', 'Reviewed PR base SHA')):
                ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', pady=3)
                self.fields[name] = tk.StringVar(master=frame)
                ttk.Entry(frame, textvariable=self.fields[name], width=50).grid(row=row, column=1, sticky='ew')
        from v14.workspace_guidance import PROCEDURES
        ttk.Label(frame, text='Procedure').grid(row=11, column=0, sticky='w', pady=3)
        procedure = ttk.Combobox(frame, values=tuple(PROCEDURES), state='readonly')
        procedure.grid(row=11, column=1, sticky='ew', pady=3)

        def select_procedure(_):
            from v14.workspace_guidance import requested_procedure
            text = self.objective.get('1.0', 'end-1c').strip()
            selected, detail = requested_procedure(text)
            if selected and text.startswith('/'):
                text = detail
            self.objective.delete('1.0', 'end')
            self.objective.insert('1.0', '/' + procedure.get() + ' ' + text)
            self.objective.focus_set()

        procedure.bind('<<ComboboxSelected>>', select_procedure)
        if not self.conversation:
            self.context_choices = {'Task context (recommended)': 'TASK', 'Current context baseline': 'CURRENT',
                                    'Repository reference': 'REFERENCE', 'Task context with local similarity': 'SEMANTIC'}
            self.context_choice = tk.StringVar(master=frame, value='Task context (recommended)')
            ttk.Label(frame, text='Repository context').grid(row=12, column=0, sticky='w', pady=3)
            ttk.Combobox(frame, textvariable=self.context_choice, values=tuple(self.context_choices), state='readonly').grid(row=12, column=1, sticky='ew', pady=3)
            ttk.Label(frame, text='Task context selects source ranges, repository guidance and test candidates.', wraplength=450).grid(row=13, column=0, columnspan=2, sticky='w', pady=3)
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
        number, base_sha = values.pop('adopt_pr_number', ''), values.pop('adopt_base_sha', '')
        if number or base_sha:
            try:
                pr_number = int(number) if number.isdigit() else 0
            except ValueError:
                pr_number = 0
            if (pr_number < 1 or not re.fullmatch(r'[a-f0-9]{40}', base_sha)
                    or values['branch'] != values['source_branch'] or values['branch'] == values['base'] or self.scope.get() != 'implement'):
                messagebox.showerror('Existing PR', 'Enter the PR number and reviewed base SHA. Working and source branches must both be the PR branch.', parent=self)
                return False
            values['adopt_pr'] = {'number': pr_number, 'head_sha': values['source_sha'], 'base_sha': base_sha}
        if not self.conversation:
            values['context_variant'] = self.context_choices[self.context_choice.get()]
        self.result = dict({'provider': None}, **values, objective=prompt, task_scope=self.scope.get())
        return True


class AcceptanceForm(_ThemedForm):
    def __init__(self, parent, proposal):
        self.proposal = proposal
        super().__init__(parent, title='Review acceptance for this task')

    def body(self, frame):
        frame.configure(background=theme.BG)
        frame.columnconfigure(1, weight=1)
        ttk.Label(frame, text='Approved tests at ' + self.proposal['source_sha'][:12]).grid(row=0, column=0, columnspan=2, sticky='w')
        self.rows = [(case, requirement) for case in self.proposal['candidates'] for requirement in case['requirement_ids']]
        self.selection = ttk.Combobox(frame, state='readonly', width=54,
                                      values=[row['case_id'] + ' · ' + requirement for row, requirement in self.rows])
        self.selection.grid(row=1, column=0, columnspan=2, sticky='ew', pady=5)
        self.selection.current(0)
        ttk.Label(frame, text='Changed source paths (one per line)').grid(row=2, column=0, columnspan=2, sticky='w')
        self.paths = tk.Text(frame, width=54, height=4, wrap='none', bg=theme.PANEL, fg=theme.TEXT,
                             insertbackground=theme.CYAN, selectbackground=theme.RAISED,
                             selectforeground=theme.TEXT, relief='flat', highlightthickness=1,
                             highlightbackground=theme.LINE, highlightcolor=theme.CYAN)
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
