"""GitHub Settings / Repository Validation. GUI edits, never an external policy-file prerequisite."""
import copy
import json
import queue
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

from desktop import repository_validation as validation
from desktop.theme import PANEL, TEXT, CYAN

CONTRACT_FIELDS = ('allowed_checks', 'acceptance_map', 'parallel_command_groups',
                   'acceptance_templates', 'draft_repair_requirements')


class RepositoryValidationPanel:
    def __init__(self, frame, root, config):
        self.frame, self.root, self.config = frame, root, config
        self.results = queue.Queue()
        self.cancel = threading.Event()
        self.busy = False
        self.document = {}
        self.revision = None
        self.loaded = None
        self.fields = {}
        self.inputs = []
        self.closed = False
        frame.columnconfigure(1, weight=1)
        ttk.Label(frame, text='Repository validation', style='Section.TLabel').grid(row=0, column=0, columnspan=3, sticky='w', pady=(12, 6))
        ttk.Label(frame, text='Set up this PC once. Gateway creates or reuses the mirror, downloads the approved image and pins its real digest. No external policy file or terminal commands are needed.',
                  style='Muted.TLabel', wraplength=530).grid(row=1, column=0, columnspan=3, sticky='w', pady=(0, 14))
        for row, key, label in [(2, 'repository', 'GitHub repository'), (3, 'mirror', 'Local mirror folder'),
                                (4, 'profile', 'Validation profile'), (5, 'timeout_seconds', 'Timeout per command (seconds)')]:
            ttk.Label(frame, text=label).grid(row=row, column=0, sticky='w', padx=(0, 12), pady=5)
            variable = tk.StringVar(master=frame)
            self.fields[key] = variable
            if key == 'profile':
                entry = ttk.Combobox(frame, textvariable=variable, state='readonly',
                                     values=[validation.SOURCE_PROFILE, validation.CUSTOM_PROFILE])
                entry.bind('<<ComboboxSelected>>', self.profile_changed)
            elif key == 'repository':
                entry = ttk.Combobox(frame, textvariable=variable)
                self.repository_picker = entry
                entry.bind('<<ComboboxSelected>>', lambda _: self.load_repository())
                entry.bind('<Return>', lambda _: self.load_repository())
            else:
                entry = ttk.Entry(frame, textvariable=variable)
            entry.grid(row=row, column=1, sticky='ew', pady=5)
            self.inputs.append(entry)
        browse = ttk.Button(frame, text='Browse…', command=self.browse)
        browse.grid(row=3, column=2, padx=(8, 0))
        choose = ttk.Button(frame, text='Load / add', command=self.load_repository)
        choose.grid(row=2, column=2, padx=(8, 0))
        self.inputs.extend([browse, choose])
        ttk.Label(frame, text='Recommended checks cover Python syntax, diagnostic-schema drift and CI policy. They do not replace Android builds, behavior tests, Windows acceptance or GitHub required checks.',
                  style='Muted.TLabel', wraplength=530).grid(row=9, column=0, columnspan=3, sticky='w', pady=(8, 12))
        self.digest = tk.StringVar(master=frame, value='Image not prepared yet')
        ttk.Label(frame, textvariable=self.digest, style='Muted.TLabel', wraplength=530).grid(row=8, column=0, columnspan=3, sticky='w', pady=6)
        self.status = tk.StringVar(master=frame)
        ttk.Label(frame, textvariable=self.status, wraplength=530).grid(row=7, column=0, columnspan=3, sticky='w', pady=8)
        buttons = ttk.Frame(frame)
        buttons.grid(row=6, column=0, columnspan=3, sticky='w', pady=6)
        for text, callback in [('Prepare & save', lambda: self.start(True)), ('Check setup', lambda: self.start(False)), ('Reload', self.reload)]:
            button = ttk.Button(buttons, text=text, command=callback)
            button.pack(side='left', padx=(0, 6))
            self.inputs.append(button)
        self.cancel_button = ttk.Button(buttons, text='Cancel', command=lambda: self.cancel.set(), state='disabled')
        self.cancel_button.pack(side='left')
        self.advanced_visible = tk.BooleanVar(master=frame, value=False)
        self.advanced_toggle = ttk.Checkbutton(frame, text='Advanced validation settings', variable=self.advanced_visible,
                                               command=self.toggle_advanced)
        self.advanced_toggle.grid(row=10, column=0, columnspan=3, sticky='w', pady=(12, 4))
        self.inputs.append(self.advanced_toggle)
        self.advanced = ttk.Frame(frame)
        self.advanced.grid(row=11, column=0, columnspan=3, sticky='ew')
        self.advanced.columnconfigure(1, weight=1)
        ttk.Label(self.advanced, text='Image to prepare').grid(row=0, column=0, sticky='w', padx=(0, 12))
        self.fields['image_source'] = tk.StringVar(master=frame)
        source = ttk.Entry(self.advanced, textvariable=self.fields['image_source'])
        source.grid(row=0, column=1, sticky='ew', pady=4)
        self.inputs.append(source)
        refresh_image = ttk.Button(self.advanced, text='Refresh image pin', command=self.refresh_image)
        refresh_image.grid(row=1, column=0, columnspan=2, sticky='w', pady=6)
        self.inputs.append(refresh_image)
        ttk.Label(self.advanced, text='Approved commands (argument arrays; no shell expansion)', style='Muted.TLabel', wraplength=520).grid(row=2, column=0, columnspan=2, sticky='w')
        self.commands = tk.Text(self.advanced, height=7, width=40, wrap='word', undo=True, bg=PANEL, fg=TEXT, insertbackground=CYAN)
        self.commands.grid(row=3, column=0, columnspan=2, sticky='ew', pady=5)
        self.inputs.append(self.commands)
        ttk.Label(self.advanced, text='Acceptance mappings and parallel groups from existing settings are preserved. Changing a custom command does not prove its associated behavior cases pass.',
                  style='Muted.TLabel', wraplength=520).grid(row=4, column=0, columnspan=2, sticky='w', pady=6)
        ttk.Label(self.advanced, text='Acceptance and CI mappings (advanced JSON fields)',
                  style='Muted.TLabel', wraplength=520).grid(row=5, column=0, columnspan=2, sticky='w')
        self.contract = tk.Text(self.advanced, height=7, width=40, wrap='word', undo=True,
                                bg=PANEL, fg=TEXT, insertbackground=CYAN)
        self.contract.grid(row=6, column=0, columnspan=2, sticky='ew', pady=5)
        self.inputs.append(self.contract)
        ttk.Label(self.advanced, text='Source defaults do not invent per-task acceptance. Implementation tasks still need real behavior-test mappings and explicit requirements. GitHub merge rules remain independent.',
                  style='Muted.TLabel', wraplength=520).grid(row=7, column=0, columnspan=2, sticky='w', pady=6)
        reset = ttk.Button(self.advanced, text='Disable / reset validation settings…', command=self.reset)
        reset.grid(row=8, column=0, columnspan=2, sticky='w', pady=6)
        self.inputs.append(reset)
        self.toggle_advanced()
        frame.bind('<Destroy>', self.destroyed, add='+')
        self.reload()

    def toggle_advanced(self):
        if self.advanced_visible.get():
            self.advanced.grid()
        else:
            self.advanced.grid_remove()

    def fill(self, options):
        self.loaded = copy.deepcopy(options)
        for key, var in self.fields.items():
            var.set(str(options[key]))
        self.commands.configure(state='normal')
        self.commands.delete('1.0', 'end')
        self.commands.insert('1.0', json.dumps(options['commands'], indent=2))
        self.commands.configure(state='disabled' if options['profile'] == validation.SOURCE_PROFILE else 'normal')
        self.contract.delete('1.0', 'end')
        self.contract.insert('1.0', json.dumps({k: options.get(k, []) for k in CONTRACT_FIELDS}, indent=2))
        self.digest.set('Pinned image: ' + options['image'] if options['image'] else 'Image not prepared yet — Prepare & save resolves the real registry digest.')

    def reload(self):
        if self.busy:
            return
        try:
            self.document, self.revision = validation.read_settings(self.root)
            selected = self.fields['repository'].get() or next(iter(self.document), validation.DEFAULT_REPOSITORY)
            self.repository_picker.configure(values=sorted(self.document))
            self.fill(validation.options_for(self.root, selected, self.document))
            self.status.set('Saved settings loaded. Check setup to verify this PC, or Prepare & save to create / refresh prerequisites.')
        except (OSError, ValueError, KeyError, TypeError):
            self.revision = None
            self.fill(validation.defaults(self.root))
            self.status.set('Saved validation settings need repair. They have not been replaced. Use Advanced → Disable / reset, then prepare again.')

    def load_repository(self):
        if self.busy:
            return
        try:
            self.fill(validation.options_for(self.root, self.fields['repository'].get(), self.document))
            self.status.set('Repository loaded. Prepare & save applies this repository; other entries are retained.')
        except ValueError as error:
            self.status.set(str(error))

    def profile_changed(self, _=None):
        if self.busy:
            return
        if self.fields['profile'].get() == validation.SOURCE_PROFILE:
            if self.loaded.get('acceptance_map') or self.loaded.get('parallel_command_groups'):
                self.fields['profile'].set(validation.CUSTOM_PROFILE)
                self.status.set('This repository has acceptance mappings. Keep the Custom profile to preserve their approved command bindings.')
                return
            self.commands.configure(state='normal')
            self.commands.delete('1.0', 'end')
            self.commands.insert('1.0', json.dumps(validation.SOURCE_COMMANDS, indent=2))
            self.commands.configure(state='disabled')
        else:
            self.commands.configure(state='normal')
            self.advanced_visible.set(True)
            self.toggle_advanced()

    def browse(self):
        chosen = filedialog.askdirectory(parent=self.frame.winfo_toplevel(), title='Select an existing bare mirror or a new mirror destination', mustexist=False)
        if chosen:
            self.fields['mirror'].set(chosen)

    def refresh_image(self):
        if not self.busy:
            self.loaded['image'] = ''
            self.digest.set('A new digest will be approved the next time you choose Prepare & save.')

    def snapshot(self):
        options = copy.deepcopy(self.loaded)
        for key, variable in self.fields.items():
            options[key] = variable.get().strip()
        try:
            options['timeout_seconds'] = int(options['timeout_seconds'])
            options['commands'] = json.loads(self.commands.get('1.0', 'end'))
            contract = json.loads(self.contract.get('1.0', 'end'))
            if not isinstance(contract, dict) or set(contract) != set(CONTRACT_FIELDS):
                raise ValueError('Acceptance and CI fields must retain the five displayed keys.')
            options.update(contract)
        except (ValueError, TypeError):
            raise ValueError('Enter a whole-number timeout, command arrays and valid acceptance / CI fields.') from None
        if options['image_source'] != self.loaded['image_source']:
            options['image'] = ''
        # Editing a new repository name must not carry another repository's mirror/acceptance contract.
        if validation.repository_name(options['repository']) != self.loaded['repository']:
            raise ValueError('Use Load / add after changing repositories, then review its mirror and commands.')
        return validation.validate_options(options)

    def set_busy(self, value):
        self.busy = value
        for widget in self.inputs:
            widget.configure(state='disabled' if value else 'normal')
        if not value:
            for widget in self.inputs:
                if isinstance(widget, ttk.Combobox) and widget is not self.repository_picker:
                    widget.configure(state='readonly')
            if self.fields['profile'].get() == validation.SOURCE_PROFILE:
                self.commands.configure(state='disabled')
        self.cancel_button.configure(state='normal' if value else 'disabled')

    def start(self, prepare):
        if self.busy or self.closed:
            return
        try:
            if self.revision is None:
                raise ValueError('Repair or reset the saved validation settings first.')
            options = self.snapshot()
        except ValueError as error:
            self.status.set(str(error))
            return
        if prepare and not messagebox.askyesno('Prepare repository validation',
                'Gateway will download or refresh this GitHub repository and container image, then save the displayed commands as host-approved validation settings. Source checks do not authorize a merge. Continue?',
                parent=self.frame.winfo_toplevel()):
            return
        self.cancel = threading.Event()
        revision = self.revision
        self.set_busy(True)
        self.status.set('Checking prerequisites…')

        def work():
            try:
                token = None
                if prepare:
                    from desktop.github_setup import configured_connection
                    try:
                        _, token = configured_connection(self.root, self.config)
                    except ValueError:
                        pass  # Public repositories do not require a token.
                result = validation.setup(self.root, options, revision, prepare=prepare, token=token, cancel=self.cancel,
                                          progress=lambda message: self.results.put(('progress', message)))
                self.results.put(('done', result))
            except (ValueError, OSError, RuntimeError) as error:
                # Known setup errors contain only fixed stage text, never subprocess output or credentials.
                self.results.put(('error', str(error) if isinstance(error, ValueError) else 'Setup could not complete. Check folder permissions, Git and Docker Desktop; previous settings remain selected.'))
            except Exception:
                self.results.put(('error', 'Setup returned an unreadable result. Previous settings remain selected.'))
        threading.Thread(target=work, daemon=True, name='repository-validation-setup').start()

    def refresh(self):
        if self.closed:
            return
        while True:
            try:
                kind, payload = self.results.get_nowait()
            except queue.Empty:
                return
            if kind == 'progress':
                self.status.set(payload)
            else:
                self.set_busy(False)
                if kind == 'done':
                    if payload['saved']:
                        self.reload()
                    self.digest.set('Pinned image: ' + payload['image'])
                    self.status.set(('Settings saved. Restart Gateway when current jobs finish. ' if payload['saved'] else 'Prerequisites available. ')
                                    + 'Mirror and image are available; repository tests have not run. Refresh for new commits. Task-specific behavior mappings remain required for implementation.')
                else:
                    self.status.set(payload)

    def reset(self):
        if self.busy:
            return
        if messagebox.askyesno('Disable repository validation', 'Disable all selected repository validation settings? Mirrors, downloaded images and the original policy are not deleted. Implementation jobs will remain blocked until you prepare settings again.', parent=self.frame.winfo_toplevel()):
            try:
                validation.reset_settings(self.root)
                self.document = {}
                self.fields['repository'].set(validation.DEFAULT_REPOSITORY)
                self.reload()
            except (OSError, ValueError, RuntimeError):
                self.status.set('Could not reset validation settings. Finish any setup in another window and check data-folder permissions.')

    def destroyed(self, event):
        if event.widget is self.frame:
            self.closed = True
            self.cancel.set()
