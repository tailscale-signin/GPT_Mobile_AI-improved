"""GitHub Settings / Repository Validation. GUI edits, never an external policy-file prerequisite."""
import copy
import json
import queue
from pathlib import Path
import threading
import weakref
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

from desktop import repository_validation as validation
from desktop.theme import BG, PANEL, TEXT, CYAN, LINE
from desktop.validation_policy import PolicyConflictError, load_policy_snapshot, save_policy_snapshot

CONTRACT_FIELDS = ('allowed_checks', 'acceptance_map', 'parallel_command_groups',
                   'acceptance_templates', 'draft_repair_requirements')


_PANELS = weakref.WeakSet()


def settle_setup_workers(root):
    """Keep a closing desktop process owned until its bounded cleanup settles.

    Owner finally blocks call this after Tk closes. Other live windows and
    installations are untouched; there are no Tk calls from the wait path.
    """
    root = Path(root).resolve()
    panels = [panel for panel in tuple(_PANELS) if panel.closed and Path(panel.root).resolve() == root]
    for panel in panels:
        panel.cancel.set()
    for panel in panels:
        if panel.worker is not None and panel.worker is not threading.current_thread():
            panel.worker.join()


class RepositoryValidationPanel:
    def __init__(self, frame, root, config):
        self.frame, self.root, self.config = frame, root, config
        self.results = queue.Queue()
        self.cancel = threading.Event()
        self.busy = self.closed = self.loading = self.dirty = self.editor_dirty = self.conflicted = False
        self.document, self.drafts = {}, {}
        self.revision = self.loaded = self.saved_snapshot = self.worker = None
        self.fields, self.inputs = {}, []
        frame.configure(padding=(14, 10))
        frame.columnconfigure(0, weight=1)
        frame.rowconfigure(5, weight=1)
        self.enabled = tk.BooleanVar(master=frame, value=False)
        self.enable_button = ttk.Checkbutton(frame, text='Enable repository validation', variable=self.enabled,
                                            command=self.enabled_changed)
        self.enable_button.grid(row=0, column=0, sticky='w')
        self.inputs.append(self.enable_button)
        basic = ttk.Frame(frame)
        basic.grid(row=1, column=0, sticky='ew')
        basic.columnconfigure(1, weight=1)
        for row, key, label in [(0, 'repository', 'Repository'), (1, 'profile', 'Validation profile')]:
            ttk.Label(basic, text=label).grid(row=row, column=0, sticky='w', padx=(0, 10), pady=3)
            var = tk.StringVar(master=frame)
            self.fields[key] = var
            control = ttk.Combobox(basic, textvariable=var)
            if key == 'profile':
                control.configure(state='readonly', values=[validation.SOURCE_PROFILE, validation.CUSTOM_PROFILE])
                control.bind('<<ComboboxSelected>>', self.profile_changed)
                self.profile_box = control
                var.trace_add('write', lambda *_: self.edited())
            else:
                self.repository_picker = control
                control.bind('<<ComboboxSelected>>', lambda _: self.load_repository())
                control.bind('<Return>', lambda _: self.load_repository())
            control.grid(row=row, column=1, sticky='ew', pady=3)
            self.inputs.append(control)
        choose = ttk.Button(basic, text='Load / add', command=self.load_repository)
        choose.grid(row=0, column=2, padx=(6, 0))
        self.inputs.append(choose)
        actions = ttk.Frame(basic)
        actions.grid(row=2, column=0, columnspan=3, sticky='w', pady=5)
        self.discovery_button = ttk.Button(actions, text='Choose task tests…', command=self.discover_behavior_checks)
        self.discovery_button.pack(side='left', padx=(0, 10))
        self.inputs.append(self.discovery_button)
        self.release_button = ttk.Button(actions, text='Release verification…', command=self.configure_release_checks)
        self.release_button.pack(side='left')
        self.inputs.append(self.release_button)
        self.status = tk.StringVar(master=frame)
        self.status_label = ttk.Label(frame, textvariable=self.status, style='Muted.TLabel', wraplength=530, justify='left')
        self.status_label.grid(row=2, column=0, sticky='ew', pady=3)
        self.advanced_visible = tk.BooleanVar(master=frame, value=False)
        self.advanced_toggle = ttk.Checkbutton(frame, text='Advanced validation settings', variable=self.advanced_visible,
                                               command=self.toggle_advanced)
        self.advanced_toggle.grid(row=0, column=1, sticky='e')
        self.inputs.append(self.advanced_toggle)
        self.advanced = ttk.Frame(frame)
        self.advanced.grid(row=5, column=0, sticky='nsew', pady=4)
        self.editor_canvas = tk.Canvas(self.advanced, bg=BG, highlightthickness=0, height=80)
        scroll = ttk.Scrollbar(self.advanced, orient='vertical', command=self.editor_canvas.yview)
        self.editor_canvas.configure(yscrollcommand=scroll.set)
        scroll.pack(side='right', fill='y')
        self.editor_canvas.pack(side='left', fill='both', expand=True)
        editor = ttk.Frame(self.editor_canvas, padding=(0, 2, 8, 4))
        editor.columnconfigure(1, weight=1)
        item = self.editor_canvas.create_window((0, 0), window=editor, anchor='nw')
        editor.bind('<Configure>', lambda _: self.editor_canvas.configure(scrollregion=self.editor_canvas.bbox('all')))
        self.editor_canvas.bind('<Configure>', lambda e: self.editor_canvas.itemconfigure(item, width=e.width))
        self.editor = editor
        for row, key, label in [(0, 'mirror', 'Local mirror folder'), (1, 'timeout_seconds', 'Timeout (seconds)'),
                                (2, 'image_source', 'Image to prepare')]:
            ttk.Label(editor, text=label).grid(row=row, column=0, sticky='w', padx=(0, 10), pady=4)
            var = tk.StringVar(master=frame)
            self.fields[key] = var
            var.trace_add('write', lambda *_: self.edited())
            control = ttk.Entry(editor, textvariable=var)
            control.grid(row=row, column=1, sticky='ew', pady=4)
            control.bind('<FocusIn>', lambda _, w=control: self.reveal(w))
            self.inputs.append(control)
        browse = ttk.Button(editor, text='Browse…', command=self.browse)
        browse.grid(row=0, column=2, padx=(6, 0))
        self.inputs.append(browse)
        refresh_image = ttk.Button(editor, text='Refresh image pin', command=self.refresh_image)
        refresh_image.grid(row=3, column=0, columnspan=3, sticky='w', pady=4)
        self.inputs.append(refresh_image)
        self.digest = tk.StringVar(master=frame, value='Image not prepared yet')
        ttk.Label(editor, textvariable=self.digest, style='Muted.TLabel', wraplength=480).grid(row=4, column=0, columnspan=3, sticky='ew', pady=4)
        ttk.Label(editor, text='Approved commands (JSON argument arrays)', style='Muted.TLabel').grid(row=5, column=0, columnspan=3, sticky='w')
        self.commands = tk.Text(editor, height=3, width=1, wrap='word', undo=True, bg=PANEL, fg=TEXT,
                                insertbackground=CYAN, highlightthickness=1, highlightbackground=LINE, highlightcolor=CYAN)
        self.commands.grid(row=6, column=0, columnspan=3, sticky='ew', pady=4)
        ttk.Label(editor, text='CI checks, behavior mappings, templates and parallel groups (JSON)', style='Muted.TLabel',
                  wraplength=480).grid(row=7, column=0, columnspan=3, sticky='w')
        self.contract = tk.Text(editor, height=3, width=1, wrap='word', undo=True, bg=PANEL, fg=TEXT,
                                insertbackground=CYAN, highlightthickness=1, highlightbackground=LINE, highlightcolor=CYAN)
        self.contract.grid(row=8, column=0, columnspan=3, sticky='ew', pady=4)
        for control in (self.commands, self.contract):
            control.bind('<<Modified>>', lambda _, w=control: self.text_edited(w))
            control.bind('<Tab>', lambda _, w=control: self.focus_next(w))
            control.bind('<Shift-Tab>', lambda _, w=control: self.focus_next(w, backwards=True))
            control.bind('<ISO_Left_Tab>', lambda _, w=control: self.focus_next(w, backwards=True))
            control.bind('<FocusIn>', lambda _, w=control: self.reveal(w))
            self.inputs.append(control)
        self.behavior_button = ttk.Button(editor, text='Add task behavior check…', command=self.add_behavior_check)
        self.behavior_button.grid(row=9, column=0, columnspan=3, sticky='w', pady=5)
        self.inputs.append(self.behavior_button)
        ttk.Label(editor, text='Source checks are not behavior-test evidence. Preserve exact command/case mappings; each implementation job still verifies its own requirements.',
                  style='Muted.TLabel', wraplength=480).grid(row=10, column=0, columnspan=3, sticky='ew', pady=5)
        self.reset_button = ttk.Button(editor, text='Disable validation…', command=self.reset)
        self.reset_button.grid(row=11, column=0, columnspan=3, sticky='w', pady=5)
        self.inputs.append(self.reset_button)
        buttons = ttk.Frame(frame)
        # Keep the primary actions in the fixed portion of the page. On a
        # minimum-height Windows window, placing them below the expandable
        # editor can put their hit targets underneath Settings' footer.
        buttons.grid(row=3, column=0, sticky='ew', pady=(4, 0))
        for attr, label, command in [('prepare_button', 'Prepare & save', lambda: self.start(True)),
                                     ('check_button', 'Check setup', lambda: self.start(False)),
                                     ('save_button', 'Save policy', self.save), ('reload_button', 'Reload', self.reload)]:
            button = ttk.Button(buttons, text=label, command=command, padding=(8, 8))
            button.pack(side='left', padx=(0, 4))
            setattr(self, attr, button)
            self.inputs.append(button)
        self.cancel_button = ttk.Button(buttons, text='Cancel', command=lambda: self.cancel.set(), state='disabled', padding=(8, 8))
        self.cancel_button.pack(side='left')
        self.toggle_advanced()
        frame.bind('<Configure>', lambda e: self.status_label.configure(wraplength=max(200, e.width - 28)) if e.widget is frame else None, add='+')
        frame.bind('<Destroy>', self.destroyed, add='+')
        self.reload(confirm=False)
        _PANELS.add(self)

    def notice(self, message):
        self.status.set(message if len(message) <= 190 else message[:187] + '…')

    def edited(self):
        if not self.loading:
            self.dirty = self.editor_dirty = True

    def text_edited(self, control):
        if control.edit_modified():
            self.edited()
            control.edit_modified(False)

    def enabled_changed(self):
        self.dirty = True
        self.notice('Save policy to apply this choice. Disabling retains saved policies; read and plan remain available.')

    @staticmethod
    def focus_next(control, backwards=False):
        target = control.tk_focusPrev() if backwards else control.tk_focusNext()
        if target is not None:
            target.focus_set()
        return 'break'

    def reveal(self, control):
        if self.advanced_visible.get():
            self.editor.update_idletasks()
            margin = (self.editor_canvas.winfo_height() - control.winfo_height()) // 2
            self.editor_canvas.yview_moveto(max(0, control.winfo_y() - margin) / max(1, self.editor.winfo_height()))

    def discover_behavior_checks(self):
        from desktop.acceptance_discovery_dialog import open_dialog
        return open_dialog(self)

    def configure_release_checks(self):
        from desktop.release_checks_dialog import open_dialog
        return open_dialog(self)

    def add_behavior_check(self):
        if self.busy or self.closed or self.conflicted or self.revision is None:
            return
        from desktop.acceptance_builder import add_behavior_check
        try:
            opened_options, opened_revision = self.snapshot(), self.revision
        except (ValueError, TypeError, KeyError) as error:
            self.status.set(str(error))
            return
        dialog = tk.Toplevel(self.frame, background=BG)
        dialog.title('Task behavior check')
        dialog.columnconfigure(1, weight=1)
        ttk.Label(dialog, text='Map a task to a real test case. The selected approved command must create a fresh JUnit XML report; saving this mapping does not mark the task passed.',
                  wraplength=500).grid(row=0, column=0, columnspan=2, padx=12, pady=12)
        fields = {}
        labels = [('objective', 'Exact task objective'), ('requirement_id', 'Requirement ID'), ('source_path', 'Source file path'),
                  ('case_id', 'JUnit class.case name'), ('report_path', 'JUnit report path'),
                  ('command_index', 'Approved command number (1–10)'), ('ci_check', 'GitHub check name')]
        for row, (key, label) in enumerate(labels, 1):
            ttk.Label(dialog, text=label).grid(row=row, column=0, sticky='w', padx=12, pady=4)
            fields[key] = tk.StringVar(master=dialog, value='1' if key == 'command_index' else '')
            ttk.Entry(dialog, textvariable=fields[key], width=44).grid(row=row, column=1, sticky='ew', padx=12, pady=4)
        status = tk.StringVar(master=dialog)
        ttk.Label(dialog, textvariable=status, wraplength=500).grid(row=8, column=0, columnspan=2, padx=12)
        def save():
            try:
                if self.closed or self.busy or self.conflicted or self.revision is None or self.revision != opened_revision or self.snapshot() != opened_options:
                    raise ValueError('Settings changed while this dialog was open. Close it and open a new behavior check.')
                values = {key: value.get().strip() for key, value in fields.items()}
                values['command_index'] = int(values['command_index']) - 1
                self.fill(add_behavior_check(opened_options, **values))
                if callable(getattr(self, 'edited', None)):
                    self.edited()
                self.notice('Behavior mapping added. Save policy persists it; execution must still verify the task.')
                dialog.destroy()
            except (ValueError, KeyError, TypeError) as error:
                status.set(str(error))
        ttk.Button(dialog, text='Add mapping', command=save).grid(row=9, column=1, sticky='e', padx=12, pady=12)

    def toggle_advanced(self):
        if self.advanced_visible.get():
            self.advanced.grid()
        else:
            self.advanced.grid_remove()

    def fill(self, options):
        self.loading = True
        try:
            self.loaded = copy.deepcopy(options)
            for key, var in self.fields.items():
                var.set(str(options[key]))
            self.commands.configure(state='normal')
            self.commands.delete('1.0', 'end')
            self.commands.insert('1.0', json.dumps(options['commands'], indent=2))
            self.commands.edit_modified(False)
            self.commands.configure(state='disabled' if options['profile'] == validation.SOURCE_PROFILE else 'normal')
            self.contract.configure(state='normal')
            self.contract.delete('1.0', 'end')
            self.contract.insert('1.0', json.dumps({key: options.get(key, []) for key in CONTRACT_FIELDS}, indent=2))
            self.contract.edit_modified(False)
            self.digest.set('Pinned image: ' + options['image'] if options['image'] else 'Image not prepared yet — Prepare & save resolves the real registry digest.')
            self.editor_dirty = False
        finally:
            self.loading = False

    def apply_snapshot(self, snapshot):
        self.saved_snapshot = copy.deepcopy(snapshot)
        self.document = copy.deepcopy(snapshot['repositories'])
        self.revision = snapshot['revision']
        self.enabled.set(snapshot['enabled'])
        self.repository_picker.configure(values=sorted(set(self.document) | set(self.drafts)))

    def show_repair_notice(self, options):
        if not options.get('repair_notice'):
            return False
        self.advanced_visible.set(True)
        self.toggle_advanced()
        self.dirty = self.editor_dirty = True
        self.notice(options['repair_notice'] + ' Original files remain unchanged until Save.')
        return True

    def reload(self, confirm=True):
        if self.busy or self.closed:
            return
        if confirm and (self.dirty or self.drafts or self.conflicted) and not messagebox.askyesno(
                'Reload saved validation policy',
                "Discard this window's unsaved edits and load the latest saved policies? Other windows' saved changes will be retained.",
                parent=self.frame.winfo_toplevel()):
            return
        try:
            snapshot = load_policy_snapshot(self.root)
            selected = self.loaded['repository'] if self.loaded else next(iter(snapshot['repositories']), validation.DEFAULT_REPOSITORY)
            try:
                options = validation.options_for(self.root, selected, snapshot['repositories'])
            except (ValueError, KeyError, TypeError, RecursionError):
                # Keep the repair token and original document. Replacing one
                # invalid entry still requires an explicit reviewed Save.
                options = validation.defaults(self.root, selected)
            self.drafts = {}
            self.apply_snapshot(snapshot)
            self.fill(options)
            self.conflicted = self.dirty = False
            self.set_busy(False)
            if self.show_repair_notice(options):
                pass
            elif snapshot['state'] == 'INVALID':
                self.notice('Saved policy needs repair. Review Advanced settings and save, or disable validation. Original files stay unchanged until you save.')
            else:
                self.notice(('Enabled policy loaded. ' if snapshot['enabled'] else 'Validation is disabled. ')
                            + 'Check setup verifies local prerequisites; Prepare & save downloads them. No repository tests run here.')
        except (OSError, ValueError, KeyError, TypeError, RecursionError):
            self.revision = None
            if self.loaded is None:
                self.fill(validation.defaults(self.root))
            self.set_busy(False)
            self.notice('Saved settings could not be read. Check data-folder access and Reload; existing settings and unsaved edits are preserved.')

    def load_repository(self):
        if self.busy or self.closed:
            return
        previous = self.loaded['repository']
        try:
            selected = validation.repository_name(self.fields['repository'].get())
            if self.editor_dirty:
                self.drafts[previous] = self.snapshot(current=True, validate=False)
            options = self.drafts.get(selected)
            if options is None:
                options = validation.options_for(self.root, selected, self.document)
            self.fill(options)
            self.repository_picker.configure(values=sorted(set(self.document) | set(self.drafts) | {selected}))
            if not self.show_repair_notice(options):
                self.notice('Repository loaded. Unsaved edits to other repositories are retained. Save policy applies all valid staged edits; Prepare & save applies this repository.')
        except (ValueError, KeyError, TypeError, RecursionError) as error:
            self.fields['repository'].set(previous)
            self.notice(str(error) if isinstance(error, ValueError) else 'This repository has invalid saved fields. Repair its policy before switching.')

    def profile_changed(self, _=None):
        if self.busy:
            return
        if self.fields['profile'].get() == validation.SOURCE_PROFILE:
            try:
                contract = self.read_json(self.contract)
                mapped = not isinstance(contract, dict) or contract.get('acceptance_map') or contract.get('parallel_command_groups')
            except ValueError:
                mapped = True
            if mapped:
                self.fields['profile'].set(validation.CUSTOM_PROFILE)
                self.notice('Keep the Custom profile until acceptance mappings and independent command groups are reviewed. Their approved command bindings are preserved.')
                return
            self.commands.configure(state='normal')
            self.commands.delete('1.0', 'end')
            self.commands.insert('1.0', json.dumps(validation.SOURCE_COMMANDS, indent=2))
            self.commands.configure(state='disabled')
        else:
            self.commands.configure(state='normal')
            self.advanced_visible.set(True)
            self.toggle_advanced()
        self.edited()

    def browse(self):
        chosen = filedialog.askdirectory(parent=self.frame.winfo_toplevel(), title='Select an existing bare mirror or a new mirror destination', mustexist=False)
        if chosen:
            self.fields['mirror'].set(chosen)

    def refresh_image(self):
        if not self.busy:
            self.loaded['image'] = ''
            self.edited()
            self.digest.set('A new digest will be approved the next time you choose Prepare & save.')

    @staticmethod
    def read_json(control):
        raw = control.get('1.0', 'end-1c')
        try:
            if len(raw) > validation.MAX_POLICY_BYTES or len(raw.encode('utf-8')) > validation.MAX_POLICY_BYTES:
                raise ValueError('Policy JSON exceeds 64 KiB.')
            value = json.loads(raw)
            pending = [(value, 0)]
            while pending:
                node, depth = pending.pop()
                if depth > 64:
                    raise ValueError('Policy JSON exceeds the supported depth.')
                if isinstance(node, dict):
                    pending.extend((child, depth + 1) for child in node.values())
                elif isinstance(node, list):
                    pending.extend((child, depth + 1) for child in node)
            return value
        except (ValueError, TypeError, RecursionError):
            raise ValueError('Policy JSON must be valid, bounded to 64 KiB and not deeply nested.') from None

    def snapshot(self, *, current=False, validate=True):
        options = copy.deepcopy(self.loaded)
        for key, variable in self.fields.items():
            options[key] = variable.get().strip()
        if current:
            options['repository'] = self.loaded['repository']
        # Editing a name must not carry another repository's mirror/contract.
        if validation.repository_name(options['repository']) != self.loaded['repository']:
            raise ValueError('Use Load / add after changing repositories, then review its mirror and commands.')
        try:
            options['timeout_seconds'] = int(options['timeout_seconds'])
            options['commands'] = self.read_json(self.commands)
            contract = self.read_json(self.contract)
            if not isinstance(contract, dict) or set(contract) != set(CONTRACT_FIELDS):
                raise ValueError('Acceptance and CI fields must retain the five displayed keys.')
            options.update(contract)
        except (ValueError, TypeError, RecursionError):
            raise ValueError('Enter a whole-number timeout and valid command / contract JSON bounded to 64 KiB, retaining all five contract keys.') from None
        if options['image_source'] != self.loaded['image_source']:
            options['image'] = ''
        try:
            return validation.validate_options(options) if validate else options
        except (TypeError, RecursionError, OverflowError):
            raise ValueError('Policy fields must use the supported bounded JSON forms.') from None

    def conflict(self):
        self.conflicted = True
        self.set_busy(False)
        self.notice('Settings changed in another window or on disk. Your edits are retained. Reload the saved policy before saving again; newer changes were preserved.')

    def save(self):
        if self.busy or self.closed or self.conflicted or self.revision is None:
            return
        enabled = self.enabled.get()
        staged = copy.deepcopy(self.drafts)
        unparsed = False
        try:
            staged[self.loaded['repository']] = self.snapshot(validate=False)
        except ValueError as error:
            if enabled:
                self.notice(str(error))
                return
            unparsed = True
        # Keep every staged entry until the backend confirms that the complete
        # draft was saved. A successful disable alone does not save bad edits.
        self.drafts = staged
        document = copy.deepcopy(self.document)
        try:
            if unparsed:
                document = {}  # Explicitly disable without changing the saved draft.
            else:
                for name, options in staged.items():
                    document[name] = validation.entry_from_options(options, self.document.get(name))
        except (ValueError, TypeError, KeyError, RecursionError, OverflowError) as error:
            if enabled:
                self.notice(str(error) if isinstance(error, ValueError) else 'Policy fields must use the supported bounded JSON forms.')
                return
            document = {}  # The entire invalid draft remains local, never partly saved.
        try:
            saved = save_policy_snapshot(self.root, document, enabled=enabled, expected_revision=self.revision)
        except PolicyConflictError:
            self.conflict()
            return
        except (OSError, ValueError) as error:
            self.notice(str(error) if isinstance(error, ValueError) else 'Could not save policy. Check data-folder access; unsaved edits are retained.')
            return
        self.apply_snapshot(saved)
        if saved['draft_saved']:
            self.drafts = {}
            self.fill(validation.options_for(self.root, self.loaded['repository'], self.document))
            self.dirty = False
            self.repository_picker.configure(values=sorted(self.document))
            self.notice(('Policy saved and enabled. Restart Gateway when idle. ' if saved['enabled'] else 'Policy saved; validation remains disabled. ')
                        + 'Check setup verifies local prerequisites. Repository tests and behavior requirements are still unverified.')
        else:
            self.dirty = True
            self.notice('Validation is disabled. Invalid policy edits were not saved; all staged edits remain in this window. Repair them before saving, or Reload to discard them.')

    def set_busy(self, value):
        self.busy = value
        for widget in self.inputs:
            widget.configure(state='disabled' if value else 'normal')
        if not value:
            self.profile_box.configure(state='readonly')
            if self.fields['profile'].get() == validation.SOURCE_PROFILE:
                self.commands.configure(state='disabled')
            if self.conflicted or self.revision is None:
                for control in (self.prepare_button, self.check_button, self.save_button, self.reset_button, self.behavior_button, self.discovery_button, self.release_button):
                    control.configure(state='disabled')
        self.cancel_button.configure(state='normal' if value else 'disabled')

    def start(self, prepare):
        if self.busy or self.closed or self.conflicted:
            return
        try:
            if self.revision is None:
                raise ValueError('Reload the saved validation settings first.')
            options = self.snapshot()
        except ValueError as error:
            self.notice(str(error))
            return
        if prepare and not messagebox.askyesno('Prepare repository validation',
                'Gateway will download or refresh this GitHub repository and container image, then save the displayed commands as host-approved validation settings. The Enable choice is retained. Source checks do not authorize a merge. Continue?',
                parent=self.frame.winfo_toplevel()):
            return
        self.cancel = cancel = threading.Event()
        revision, enabled = self.revision, self.enabled.get()
        self.set_busy(True)
        self.notice('Checking prerequisites…')

        def work():
            try:
                token = None
                if prepare:
                    from desktop.github_setup import configured_connection
                    try:
                        _, token = configured_connection(self.root, self.config)
                    except ValueError:
                        pass  # Public repositories do not require a token.
                result = validation.setup(self.root, options, revision, prepare=prepare, enabled=enabled, token=token, cancel=cancel,
                                          progress=lambda message: self.results.put(('progress', message)))
                self.results.put(('done', result))
            except PolicyConflictError:
                self.results.put(('conflict', None))
            except (ValueError, OSError, RuntimeError) as error:
                # Known failures are fixed stage text, never subprocess output or credentials.
                self.results.put(('error', str(error) if isinstance(error, ValueError) else 'Setup could not complete. Check folder permissions, Git and Docker Desktop; previous settings remain selected.'))
            except Exception:
                self.results.put(('error', 'Setup returned an unreadable result. Previous settings remain selected.'))
        self.worker = threading.Thread(target=work, daemon=False, name='repository-validation-setup')
        self.worker.start()

    def refresh(self):
        if self.closed:
            return
        while True:
            try:
                kind, payload = self.results.get_nowait()
            except queue.Empty:
                return
            if kind == 'progress':
                self.notice(payload)
            elif kind == 'conflict':
                self.conflict()
            else:
                self.set_busy(False)
                if kind == 'done':
                    if payload['saved']:
                        snapshot = payload['snapshot']
                        self.apply_snapshot(snapshot)
                        prepared = validation.options_for(self.root, payload['repository'], {payload['repository']: payload['entry']})
                        if snapshot['draft_saved']:
                            self.drafts.pop(payload['repository'], None)
                        else:
                            self.drafts[payload['repository']] = prepared
                        self.fill(prepared)
                        self.dirty = bool(self.drafts)
                        self.repository_picker.configure(values=sorted(set(self.document) | set(self.drafts)))
                        if not snapshot['draft_saved']:
                            prefix = 'Validation disabled; prepared policy edits remain unsaved. '
                        elif snapshot['enabled']:
                            prefix = 'Prepared and enabled. Restart Gateway when idle. '
                        else:
                            prefix = 'Prepared and saved; validation remains disabled. '
                    else:
                        prefix = 'Local prerequisites available. '
                    self.digest.set('Pinned image: ' + payload['image'])
                    self.notice(prefix + 'Mirror and pinned image are available. No repository tests ran; task-specific behavior mappings are still required.')
                else:
                    self.notice(payload)

    def reset(self):
        if self.busy or self.closed or self.conflicted or self.revision is None:
            return
        if messagebox.askyesno('Disable repository validation',
                'Disable all selected repository validation settings? Saved policies, mirrors and downloaded images are retained. Unsaved edits remain in this window.',
                parent=self.frame.winfo_toplevel()):
            try:
                saved = validation.reset_settings(self.root, expected_revision=self.revision)
                self.apply_snapshot(saved)
                self.dirty = bool(self.drafts) or self.editor_dirty
                self.notice('Validation is disabled. Saved policies and unsaved edits are retained. Enable and Save policy to activate a reviewed valid policy again.')
            except PolicyConflictError:
                self.conflict()
            except (OSError, ValueError, RuntimeError):
                self.notice('Could not disable validation. Existing settings and edits are preserved. Check data-folder access and Reload.')

    def destroyed(self, event):
        if event.widget is self.frame:
            self.closed = True
            self.cancel.set()
