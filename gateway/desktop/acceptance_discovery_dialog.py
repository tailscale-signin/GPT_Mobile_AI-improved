"""Normal settings flow for discovering and approving existing behavior checks."""
import queue
import threading
import tkinter as tk
from tkinter import ttk

from desktop.acceptance_discovery import add_discovered, discover, preset


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
    dialog.rowconfigure(3, weight=1)
    fields = {key: tk.StringVar(master=dialog) for key in ('query', 'objective', 'requirement_id', 'source_path', 'ci_check')}
    status = tk.StringVar(master=dialog, value='Search the prepared mirror for existing Python unittest or JUnit tests.')
    ttk.Label(dialog, text='Find existing tests, choose the behavior relevant to your task, then save the policy.',
              wraplength=680).grid(row=0, column=0, columnspan=3, padx=12, pady=10, sticky='w')
    ttk.Label(dialog, text='Test filename / path').grid(row=1, column=0, padx=12, sticky='w')
    ttk.Entry(dialog, textvariable=fields['query']).grid(row=1, column=1, sticky='ew')
    search = ttk.Button(dialog, text='Find tests')
    search.grid(row=1, column=2, padx=12)
    ttk.Label(dialog, textvariable=status, wraplength=680).grid(row=2, column=0, columnspan=3, padx=12, pady=8, sticky='w')
    listing = tk.Listbox(dialog, exportselection=False)
    listing.grid(row=3, column=0, columnspan=3, padx=12, sticky='nsew')
    for row, (key, label) in enumerate((('objective', 'Task objective'), ('requirement_id', 'Requirement ID'),
                                      ('source_path', 'File this requirement covers'), ('ci_check', 'Required GitHub check')), 4):
        ttk.Label(dialog, text=label).grid(row=row, column=0, padx=12, pady=4, sticky='w')
        if key == 'ci_check':
            control = ttk.Combobox(dialog, textvariable=fields[key], values=options['allowed_checks'])
            if options['allowed_checks']:
                fields[key].set(options['allowed_checks'][0])
        else:
            control = ttk.Entry(dialog, textvariable=fields[key])
        control.grid(row=row, column=1, columnspan=2, padx=(0, 12), pady=4, sticky='ew')
    preview = tk.Text(dialog, height=4, wrap='word')
    preview.grid(row=8, column=0, columnspan=3, padx=12, pady=6, sticky='ew')
    preview.insert('1.0', 'Select a test to review its proposed command. It runs only in the configured validation container. '
                   'JUnit/Gradle tests require a prepared image with that build environment. No test has run yet.')
    preview.configure(state='disabled')
    results, candidates = queue.Queue(), []
    state = {'running': False, 'closing': False}

    def unchanged():
        if (panel.closed or panel.conflicted or panel.revision != revision or panel.snapshot() != options):
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

    def receive():
        if panel.closed:
            panel.cancel.set()
            return
        try:
            success, value = results.get_nowait()
        except queue.Empty:
            panel.frame.after(75, receive)
            return
        state['running'] = False
        panel.set_busy(False)
        if state['closing']:
            dialog.destroy()
            return
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

    def find():
        if state['running']:
            return
        try:
            unchanged()
        except (ValueError, TypeError, KeyError) as error:
            status.set(str(error))
            return
        query = fields['query'].get().strip()
        panel.cancel.clear()
        panel.set_busy(True)
        state['running'] = True
        search.configure(state='disabled')
        status.set('Inspecting the pinned repository files…')
        def work():
            try:
                results.put((True, discover(options, query, panel.cancel)))
            except (OSError, ValueError, TypeError, KeyError):
                results.put((False, 'Test discovery was cancelled or unavailable. Prepare the mirror, then try a narrower filename search.'))
        panel.worker = threading.Thread(target=work, name='acceptance-discovery', daemon=False)
        panel.worker.start()
        panel.frame.after(75, receive)

    def apply():
        if state['running']:
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
        if state['running']:
            state['closing'] = True
            panel.cancel.set()
            status.set('Cancelling discovery…')
        else:
            dialog.destroy()

    search.configure(command=find)
    listing.bind('<<ListboxSelect>>', select)
    ttk.Button(dialog, text='Cancel', command=close).grid(row=9, column=0, padx=12, pady=10, sticky='w')
    ttk.Button(dialog, text='Add selected task check', command=apply).grid(row=9, column=1, columnspan=2, padx=12, pady=10, sticky='e')
    dialog.protocol('WM_DELETE_WINDOW', close)
    return dialog
