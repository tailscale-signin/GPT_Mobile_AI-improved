"""Ordinary fields for release artifact and installation/smoke requirements."""
from copy import deepcopy
import tkinter as tk
from tkinter import ttk


def configure(options, artifacts, installation, smoke):
    from desktop.repository_validation import validate_options
    result = deepcopy(options)
    result['post_merge'] = {'artifacts': deepcopy(artifacts), 'installation_checks': list(installation), 'smoke_checks': list(smoke)}
    return validate_options(result)


def open_dialog(panel):
    if panel.busy or panel.closed or panel.conflicted or panel.revision is None:
        return
    try:
        options, revision = panel.snapshot(), panel.revision
    except (ValueError, TypeError, KeyError) as error:
        panel.notice(str(error))
        return
    previous = options.get('post_merge', {'artifacts': [], 'installation_checks': [], 'smoke_checks': []})
    artifacts = deepcopy(previous['artifacts'])
    window = tk.Toplevel(panel.frame)
    window.transient(panel.frame.winfo_toplevel())
    window.title('Release verification')
    window.columnconfigure(1, weight=1)
    ttk.Label(window, text='Require these artifacts and checks on the actual merged revision. Downloads are digest-verified; '
              'approved checks run in the configured container. This does not verify a physical phone or Windows installation.',
              wraplength=620).grid(row=0, column=0, columnspan=3, padx=12, pady=10)
    fields = {key: tk.StringVar(master=window, value=value) for key, value in
              (('workflow', '.github/workflows/build.yml'), ('name', ''), ('maximum', '64'))}
    for row, (key, label) in enumerate((('workflow', 'Workflow path'), ('name', 'Exact artifact name'), ('maximum', 'Maximum download (MiB)')), 1):
        ttk.Label(window, text=label).grid(row=row, column=0, padx=12, pady=4, sticky='w')
        ttk.Entry(window, textvariable=fields[key], width=48).grid(row=row, column=1, columnspan=2, padx=12, sticky='ew')
    listing = tk.Listbox(window, height=4, exportselection=False)
    listing.grid(row=4, column=0, columnspan=3, padx=12, pady=6, sticky='ew')
    status = tk.StringVar(master=window)
    def render():
        listing.delete(0, 'end')
        for row in artifacts:
            listing.insert('end', row['name'] + ' — ' + row['workflow'])
    def add():
        try:
            row = {'workflow': fields['workflow'].get().strip(), 'name': fields['name'].get().strip(),
                   'maximum_bytes': int(fields['maximum'].get()) * 1024 * 1024}
            configure(options, artifacts + [row], previous['installation_checks'], previous['smoke_checks'])
            artifacts.append(row)
            render()
        except (ValueError, TypeError, KeyError) as error:
            status.set(str(error))
    def remove():
        if listing.curselection():
            del artifacts[listing.curselection()[0]]
            render()
    ttk.Button(window, text='Add artifact', command=add).grid(row=5, column=1, padx=12, sticky='e')
    ttk.Button(window, text='Remove selected', command=remove).grid(row=5, column=2, padx=12)
    selectors = {}
    for column, key in enumerate(('installation_checks', 'smoke_checks')):
        frame = ttk.Frame(window)
        frame.grid(row=6, column=column, padx=12, pady=8, sticky='nsew')
        ttk.Label(frame, text='Installation checks' if column == 0 else 'Smoke checks').pack(anchor='w')
        box = tk.Listbox(frame, selectmode='multiple', exportselection=False, height=min(10, len(options['commands'])), width=35)
        box.pack(fill='both', expand=True)
        for index, command in enumerate(options['commands']):
            box.insert('end', str(index + 1) + ': ' + ' '.join(command)[:100])
            if index in previous[key]:
                box.selection_set(index)
        selectors[key] = box
    ttk.Label(window, text='Downloaded ZIPs are available to checks at .gateway-artifacts/<artifact-name>.zip.', wraplength=620).grid(
        row=7, column=0, columnspan=3, padx=12, pady=6)
    ttk.Label(window, textvariable=status, wraplength=620).grid(row=8, column=0, columnspan=3, padx=12)
    def apply():
        try:
            if panel.closed or panel.busy or panel.conflicted or panel.revision != revision or panel.snapshot() != options:
                raise ValueError('Settings changed. Reopen release verification before applying it.')
            result = configure(options, artifacts, selectors['installation_checks'].curselection(), selectors['smoke_checks'].curselection())
            panel.fill(result)
            panel.edited()
            panel.notice('Release requirements added. Save policy to approve them; no checks have run yet.')
            window.destroy()
        except (ValueError, TypeError, KeyError) as error:
            status.set(str(error))
    ttk.Button(window, text='Apply requirements', command=apply).grid(row=9, column=1, columnspan=2, sticky='e', padx=12, pady=12)
    render()
    return window
