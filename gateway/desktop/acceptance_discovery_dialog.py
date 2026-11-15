"""Normal settings flow for discovering and approving existing behavior checks."""
import queue
import threading
import tkinter as tk
from tkinter import ttk

from desktop.acceptance_discovery import add_discovered, discover, preset


class DiscoveryOperation:
    """Keep one worker owned until cleanup exits, even after its dialog closes."""
    def __init__(self, panel, callbacks, receive):
        self.panel, self.callbacks, self.receive = panel, callbacks, receive
        self.closed = self.running = False
        self.cancel = self.worker = self.pending = None

    def start(self, options, query, *, source_sha=None):
        if (self.closed or self.running or self.panel.closed or self.panel.busy
                or self.panel.conflicted or self.panel.revision is None):
            return False
        results = queue.Queue()
        self.cancel = cancel = threading.Event()
        self.panel.cancel = cancel
        self.panel.set_busy(True)
        self.running = True

        def work():
            result = (False, 'Test discovery was cancelled or unavailable. Prepare the mirror, then try a narrower filename search.')
            try:
                result = (True, discover(options, query, cancel, source_sha=source_sha))
            except Exception:
                pass  # Never display subprocess output or leave a failed worker busy.
            finally:
                results.put(result)

        self.results = results
        previous_worker = self.panel.worker
        self.worker = self.panel.worker = threading.Thread(target=work, name='acceptance-discovery', daemon=False)
        try:
            self.worker.start()
        except RuntimeError:
            self.running = False
            self.panel.worker, self.worker = previous_worker, None
            self.panel.set_busy(False)
            raise ValueError('Test discovery could not start. Try again.') from None
        self.pending = self.callbacks.schedule(75, self.poll)
        return True

    def poll(self):
        self.pending = None
        if self.panel.closed or self.callbacks.closed:
            self.close()
            return
        if self.worker.is_alive():
            self.pending = self.callbacks.schedule(75, self.poll)
            return
        self.running = False
        if self.panel.worker is not self.worker:
            return
        self.panel.set_busy(False)
        if not self.closed:
            self.receive(*self.results.get_nowait())

    def close(self):
        self.closed = True
        if self.cancel is not None:
            self.cancel.set()
        self.callbacks.cancel(self.pending)
        self.pending = None
        if self.running and not self.panel.closed and not self.callbacks.closed:
            # Only the still-live parent is touched while cancellation settles.
            # The desktop owner also joins panel.worker when Settings closes.
            self.pending = self.callbacks.schedule(75, self.poll)


def open_dialog(panel):
    if panel.busy or panel.closed or panel.conflicted or panel.revision is None:
        return
    try:
        options, revision = panel.snapshot(), panel.revision
    except (ValueError, TypeError, KeyError) as error:
        panel.notice(str(error))
        return
    dialog = tk.Toplevel(panel.frame)
    dialog.transient(panel.frame.winfo_toplevel())
    dialog.title('Choose task tests')
    dialog.geometry('740x620')
    dialog.columnconfigure(1, weight=1)
    dialog.rowconfigure(4, weight=1)
    fields = {key: tk.StringVar(master=dialog) for key in ('query', 'source_sha', 'objective', 'requirement_id', 'source_path', 'ci_check')}
    status = tk.StringVar(master=dialog, value='Search the prepared mirror for existing Python unittest or JUnit tests.')
    ttk.Label(dialog, text='Find existing tests, choose the behavior relevant to your task, then save the policy.',
              wraplength=680).grid(row=0, column=0, columnspan=3, padx=12, pady=10, sticky='w')
    ttk.Label(dialog, text='Test filename / path').grid(row=1, column=0, padx=12, sticky='w')
    ttk.Entry(dialog, textvariable=fields['query']).grid(row=1, column=1, sticky='ew')
    search = ttk.Button(dialog, text='Find tests')
    search.grid(row=1, column=2, padx=12)
    ttk.Label(dialog, text='Source SHA (optional)').grid(row=2, column=0, padx=12, sticky='w')
    ttk.Entry(dialog, textvariable=fields['source_sha']).grid(row=2, column=1, sticky='ew')
    ttk.Label(dialog, textvariable=status, wraplength=680).grid(row=3, column=0, columnspan=3, padx=12, pady=8, sticky='w')
    listing = tk.Listbox(dialog, exportselection=False)
    listing.grid(row=4, column=0, columnspan=3, padx=12, sticky='nsew')
    for row, (key, label) in enumerate((('objective', 'Task objective'), ('requirement_id', 'Requirement ID'),
                                      ('source_path', 'File this requirement covers'), ('ci_check', 'Required GitHub check')), 5):
        ttk.Label(dialog, text=label).grid(row=row, column=0, padx=12, pady=4, sticky='w')
        if key == 'ci_check':
            control = ttk.Combobox(dialog, textvariable=fields[key], values=options['allowed_checks'])
            if options['allowed_checks']:
                fields[key].set(options['allowed_checks'][0])
        else:
            control = ttk.Entry(dialog, textvariable=fields[key])
        control.grid(row=row, column=1, columnspan=2, padx=(0, 12), pady=4, sticky='ew')
    preview = tk.Text(dialog, height=4, wrap='word')
    preview.grid(row=9, column=0, columnspan=3, padx=12, pady=6, sticky='ew')
    preview.insert('1.0', 'Select a test to review its proposed command. It runs only in the configured validation container. '
                   'JUnit/Gradle tests require a prepared image with that build environment. No test has run yet.')
    preview.configure(state='disabled')
    candidates = []

    def unchanged():
        if (panel.closed or panel.busy or panel.conflicted or panel.revision != revision or panel.snapshot() != options):
            raise ValueError('Settings changed. Close this dialog and reopen it before adding a check.')

    def select(_=None):
        if not listing.curselection():
            return
        candidate = candidates[listing.curselection()[0]]
        try:
            command, report = preset(candidate)
            text = 'Command: ' + repr(command) + '\nReport: ' + report
        except ValueError as error:
            text = str(error)
        preview.configure(state='normal')
        preview.delete('1.0', 'end')
        preview.insert('1.0', text)
        preview.configure(state='disabled')
        fields['requirement_id'].set('behavior-' + str(listing.curselection()[0] + 1))

    def receive(success, value):
        search.configure(state='normal')
        if not success:
            status.set(value)
            return
        try:
            unchanged()
        except (ValueError, TypeError, KeyError) as error:
            status.set(str(error))
            return
        candidates[:] = value['candidates']
        listing.delete(0, 'end')
        for item in candidates:
            listing.insert('end', item['case_id'] + ' — ' + item['path'])
        status.set(f"Found {len(candidates)} test cases at {value['head_sha'][:12]}. No tests executed. "
                   + ('Narrow the filename search for more results.' if value['truncated'] else 'Select a relevant test below.'))

    from desktop.ui import WindowCallbacks
    parent = panel.frame.winfo_toplevel()
    callbacks = getattr(parent, 'settings_callbacks', None)
    if callbacks is None:
        callbacks = WindowCallbacks(parent)
    operation = DiscoveryOperation(panel, callbacks, receive)

    def find():
        if operation.running or operation.closed:
            return
        try:
            unchanged()
            if not operation.start(options, fields['query'].get().strip(),
                                   source_sha=fields['source_sha'].get().strip() or None):
                return
        except (ValueError, TypeError, KeyError) as error:
            status.set(str(error))
            return
        search.configure(state='disabled')
        status.set('Inspecting the pinned repository files…')

    def apply():
        if operation.running or operation.closed:
            return
        try:
            unchanged()
            if not listing.curselection():
                raise ValueError('Select a discovered test first.')
            candidate = candidates[listing.curselection()[0]]
            result = add_discovered(options, candidate, **{key: fields[key].get().strip()
                for key in ('objective', 'requirement_id', 'source_path', 'ci_check')})
            panel.fill(result)
            panel.edited()
            panel.notice('Task check added. Review the command and Save policy to approve it. Tests remain unexecuted.')
            dialog.destroy()
        except (ValueError, TypeError, KeyError) as error:
            status.set(str(error))

    def close():
        operation.close()
        dialog.destroy()

    search.configure(command=find)
    listing.bind('<<ListboxSelect>>', select)
    ttk.Button(dialog, text='Cancel', command=close).grid(row=10, column=0, padx=12, pady=10, sticky='w')
    ttk.Button(dialog, text='Add selected task check', command=apply).grid(row=10, column=1, columnspan=2, padx=12, pady=10, sticky='e')
    dialog.protocol('WM_DELETE_WINDOW', close)
    dialog.bind('<Destroy>', lambda event: operation.close() if event.widget is dialog else None, add='+')
    return dialog
