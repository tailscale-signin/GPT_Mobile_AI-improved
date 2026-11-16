"""Native patch/test timeline; restoration never rewrites remote Git history."""
import tkinter as tk
import queue
import threading
from tkinter import ttk, simpledialog, messagebox
from desktop import theme


class CheckpointDialog(simpledialog.Dialog):
    def __init__(self, parent, history, read_candidate):
        self.history, self.read_candidate = history, read_candidate
        self.results, self.selection_epoch = queue.Queue(), 0
        super().__init__(parent, 'Engineering checkpoints')

    def body(self, frame):
        frame.configure(bg=theme.BG)
        ttk.Label(frame, text='Source ' + (self.history.get('head_sha') or 'unavailable')[:12] +
                  ' · ' + self.history['state']).pack(anchor='w', pady=8)
        self.table = ttk.Treeview(frame, columns=('check', 'status', 'patch'), show='headings', height=8)
        for name, title in (('check', 'Attempt / check'), ('status', 'Result'), ('patch', 'Candidate')):
            self.table.heading(name, text=title)
            self.table.column(name, width=180)
        for row in self.history['candidates']:
            self.table.insert('', 'end', iid=row['check_id'], values=(row.get('attempt_id') or str(row['check_index']),
                row['status'], row['patch_identity'][:16]))
        self.table.pack(fill='both', expand=True)
        self.table.bind('<<TreeviewSelect>>', self.inspect)
        text = tk.Text(frame, height=9, width=80, bg=theme.PANEL, fg=theme.TEXT, wrap='word')
        text.pack(fill='both', expand=True, pady=8)
        text.insert('end', '\n'.join(str(row.get('revision', '')) + ' · ' + row.get('state', '') + ' · ' + row.get('message', '')
                                  for row in self.history.get('updates', [])[-50:]))
        text.configure(state='disabled')
        self.preview = text
        self.after(100, self.poll)
        ttk.Label(frame, text='Restore selects an unpublished candidate and reruns validation. Published history remains unchanged.').pack(anchor='w')
        return self.table

    def buttonbox(self):
        box = ttk.Frame(self)
        box.pack(fill='x', pady=8)
        allowed = self.history['state'] == 'NEEDS_INPUT' and not self.history['published']
        ttk.Button(box, text='Restore selected candidate', command=self.ok, state='normal' if allowed else 'disabled').pack(side='left', padx=8)
        ttk.Button(box, text='Close', command=self.cancel).pack(side='right', padx=8)

    def inspect(self, _=None):
        selected = self.table.selection()
        if not selected:
            return
        self.selection_epoch += 1
        epoch = self.selection_epoch
        def work():
            try:
                value = self.read_candidate(selected[0])
                text = value['diff'] + ('\n[Diff truncated]' if value['truncated'] else '') + '\n\nTest output:\n' + value['output']
            except Exception:
                text = 'Candidate inspection is unavailable. Refresh the original job and connection.'
            self.results.put((epoch, text))
        threading.Thread(target=work, daemon=True, name='checkpoint-preview').start()

    def poll(self):
        if not self.winfo_exists():
            return
        try:
            while True:
                epoch, text = self.results.get_nowait()
                if epoch == self.selection_epoch:
                    self.preview.configure(state='normal')
                    self.preview.delete('1.0', 'end')
                    self.preview.insert('end', text)
                    self.preview.configure(state='disabled')
        except queue.Empty:
            pass
        self.after(100, self.poll)

    def validate(self):
        selected = self.table.selection()
        if not selected:
            return False
        if not messagebox.askyesno('Restore local candidate?', 'Restore this candidate and rerun complete validation and review?', parent=self):
            return False
        self.result = selected[0]
        return True
