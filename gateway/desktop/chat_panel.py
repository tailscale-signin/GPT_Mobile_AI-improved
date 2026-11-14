"""Native daily chat and Saved views over the existing gateway-owned history."""
import hashlib
import queue
import threading
import tkinter as tk
from tkinter import ttk, simpledialog
import uuid

from desktop import theme
from desktop.chat_state import ChatState
from desktop.conversation_client import Client, ClientError, privacy_text, scope_key
from desktop.dashboard_model import age


TERMINAL = {'COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED', 'INTERRUPTED'}


class ChatPanel(tk.Frame):
    def __init__(self, parent, root):
        super().__init__(parent, bg=theme.BG)
        self.root = root
        self.state = ChatState(root)
        self.draft_version = self.state.draft_version
        self.events = queue.Queue(maxsize=256)
        self.identity = None
        self.conversation = None
        self.transcript = []
        self.rows = []
        self.selected_message = None
        self.cursor = None
        self.busy = False
        self.sending = False
        self.closed = False
        self.visible = False
        self.epoch = 0
        self.stream_text = ''
        self.draft_timer = None
        self.poll_timer = None
        self.event_timer = None
        self.build()
        self.bind('<Destroy>', self.destroyed, add='+')
        self.event_timer = self.after(100, self.poll)

    def build(self):
        head = tk.Frame(self, bg=theme.BG)
        head.pack(fill='x', pady=(0, 6))
        self.heading = theme.label(head, 'Chat', 23, bold=True)
        self.heading.pack(side='left')
        ttk.Button(head, text='New prompt', command=self.new_chat, style='Accent.TButton').pack(side='right')
        self.privacy = theme.label(self, 'Connect to see where your conversations are saved.', color=theme.MUTED,
                                   justify='left', anchor='w', wraplength=720)
        self.privacy.pack(fill='x', pady=(0, 10))
        tools = tk.Frame(self, bg=theme.BG)
        tools.pack(fill='x', pady=(0, 10))
        tools.columnconfigure(2, weight=1)
        self.filter = tk.StringVar(value='Conversations')
        self.filter_box = ttk.Combobox(tools, textvariable=self.filter, values=('Conversations', 'Saved messages', 'Favorites', 'Archived'),
                                       state='readonly', width=18)
        self.filter_box.grid(row=0, column=0, sticky='ew')
        self.filter_box.bind('<<ComboboxSelected>>', lambda _: self.refresh_list())
        self.query = tk.StringVar()
        ttk.Label(tools, text='  Search ').grid(row=0, column=1)
        search = ttk.Entry(tools, textvariable=self.query)
        search.grid(row=0, column=2, sticky='ew')
        search.bind('<Return>', lambda _: self.refresh_list())
        ttk.Button(tools, text='Find', command=self.refresh_list).grid(row=0, column=3, padx=6)
        self.refresh_button = ttk.Button(tools, text='Refresh', command=self.connect)
        self.refresh_button.grid(row=0, column=4)

        body = ttk.Panedwindow(self, orient='horizontal')
        body.pack(fill='both', expand=True)
        listing = tk.Frame(body, bg=theme.PANEL)
        body.add(listing, weight=1)
        list_shell = tk.Frame(listing, bg=theme.PANEL)
        list_shell.pack(side='top', fill='both', expand=True)
        self.list = ttk.Treeview(list_shell, columns=('title', 'state'), show='headings', selectmode='browse', height=7)
        self.list.heading('title', text='Saved conversation')
        self.list.heading('state', text='Updated')
        self.list.column('title', width=175, minwidth=100)
        self.list.column('state', width=80, minwidth=65, stretch=False)
        self.list.pack(side='left', fill='both', expand=True)
        list_vertical = ttk.Scrollbar(list_shell, orient='vertical', command=self.list.yview)
        list_vertical.pack(side='right', fill='y')
        self.list.configure(yscrollcommand=list_vertical.set)
        self.list.bind('<<TreeviewSelect>>', self.open_selected)
        self.list.bind('<Return>', self.open_selected)
        list_scroll = ttk.Scrollbar(listing, orient='horizontal', command=self.list.xview)
        list_scroll.pack(fill='x')
        self.list.configure(xscrollcommand=list_scroll.set)
        self.more_button = ttk.Button(listing, text='Load more', command=lambda: self.refresh_list(more=True), state='disabled')
        self.more_button.pack(fill='x', padx=6, pady=6)
        self.remove_saved_button = ttk.Button(listing, text='Remove saved message', command=self.remove_saved, state='disabled')
        self.remove_saved_button.pack(fill='x', padx=6, pady=(0, 6))
        list_shell.pack_forget()
        list_scroll.pack_forget()
        self.more_button.pack_forget()
        self.remove_saved_button.pack_forget()
        self.remove_saved_button.pack(side='bottom', fill='x', padx=6, pady=(0, 6))
        self.more_button.pack(side='bottom', fill='x', padx=6, pady=6)
        list_scroll.pack(side='bottom', fill='x')
        list_shell.pack(fill='both', expand=True)

        reading = tk.Frame(body, bg=theme.BG)
        body.add(reading, weight=3)
        self.title = theme.label(reading, 'Start a conversation', 15, bold=True, anchor='w', wraplength=600)
        self.title.pack(fill='x', padx=(12, 0), pady=(0, 4))
        self.binding = theme.label(reading, 'Choose a model below, then send a prompt.', color=theme.MUTED,
                                   anchor='w', justify='left', wraplength=600)
        self.binding.pack(fill='x', padx=(12, 0), pady=(0, 6))
        actions = tk.Frame(reading, bg=theme.BG)
        actions.pack(fill='x', padx=(12, 0), pady=(0, 6))
        self.rename_button = ttk.Button(actions, text='Rename', command=self.rename, state='disabled')
        self.rename_button.pack(side='left')
        self.archive_button = ttk.Button(actions, text='Archive', command=self.archive, state='disabled')
        self.archive_button.pack(side='left', padx=5)
        self.favorite_button = ttk.Button(actions, text='Favorite', command=self.favorite, state='disabled')
        self.favorite_button.pack(side='left')
        text_shell = tk.Frame(reading, bg=theme.PANEL)
        text_shell.pack(fill='both', expand=True, padx=(12, 0))
        self.text = tk.Text(text_shell, bg=theme.PANEL, fg=theme.TEXT, insertbackground=theme.CYAN,
                            selectbackground=theme.RAISED, selectforeground=theme.TEXT, relief='flat', wrap='word',
                            padx=16, pady=14, font=(theme.FONT, 11), state='disabled', height=8, takefocus=True)
        self.text.pack(side='left', fill='both', expand=True)
        scroll = ttk.Scrollbar(text_shell, orient='vertical', command=self.text.yview)
        scroll.pack(side='right', fill='y')
        self.text.configure(yscrollcommand=scroll.set)
        self.text.tag_configure('role', foreground=theme.CYAN, font=(theme.FONT, 10, 'bold'), spacing1=12, spacing3=4)
        self.text.tag_configure('status', foreground=theme.MUTED, font=(theme.FONT, 9))
        self.text.bind('<ButtonRelease-1>', self.select_message)
        self.text.bind('<KeyRelease>', self.select_message)
        message_actions = tk.Frame(reading, bg=theme.BG)
        message_actions.pack(fill='x', padx=(12, 0), pady=5)
        self.save_message_button = ttk.Button(message_actions, text='Save message', command=self.bookmark, state='disabled')
        self.save_message_button.pack(side='left')
        ttk.Button(message_actions, text='Copy', width=5, command=self.copy_selection).pack(side='left', padx=5)
        self.reconcile_button = ttk.Button(message_actions, text='Check send', command=self.reconcile, state='disabled')
        self.reconcile_button.pack(side='right')
        text_shell.pack_forget()
        message_actions.pack_configure(side='bottom')
        text_shell.pack(fill='both', expand=True, padx=(12, 0))

        compose = tk.Frame(self, bg=theme.PANEL, padx=12, pady=10)
        compose.pack(fill='x', pady=(10, 0))
        model_row = tk.Frame(compose, bg=theme.PANEL)
        model_row.pack(fill='x', pady=(0, 8))
        theme.label(model_row, 'AI model', bg=theme.PANEL).pack(side='left', padx=(0, 10))
        self.model = tk.StringVar(value=self.state.value['model'])
        self.model_box = ttk.Combobox(model_row, textvariable=self.model, state='readonly', width=32)
        self.model_box.pack(side='left', fill='x', expand=True)
        self.model_box.bind('<<ComboboxSelected>>', self.model_changed)
        self.stop_button = ttk.Button(model_row, text='Stop reply', command=self.stop, state='disabled')
        self.stop_button.pack(side='right', padx=(8, 0))
        self.send_button = ttk.Button(model_row, text='Send prompt', command=self.send, style='Accent.TButton', state='disabled')
        self.send_button.pack(side='right', padx=(8, 0))
        self.model_box.pack_forget()
        self.model_box.pack(side='left', fill='x', expand=True)
        self.composer = tk.Text(compose, bg=theme.BG, fg=theme.TEXT, insertbackground=theme.CYAN,
                                selectbackground=theme.RAISED, relief='flat', wrap='word', height=3,
                                font=(theme.FONT, 11), padx=10, pady=8, undo=True)
        self.composer.pack(fill='x')
        self.composer.insert('1.0', self.state.value['draft'])
        self.composer.bind('<<Modified>>', self.draft_changed)
        self.composer.bind('<Control-Return>', lambda _: (self.send(), 'break')[-1])
        self.composer.edit_modified(False)
        self.status = theme.label(self, self.state.error or 'Ctrl+Enter sends. Saved chats stay available when this window closes.',
                                  color=theme.MUTED, anchor='w', justify='left', wraplength=750)
        self.status.pack(fill='x', pady=(6, 0))
        self.recovery_button = ttk.Button(self, text='Recover draft storage', command=self.recover_storage)
        # Reserve the composer and recovery controls before the elastic history
        # pane. A long transcript must never push Send below the window edge.
        body.pack_forget()
        compose.pack_forget()
        self.status.pack_forget()
        self.status.pack(side='bottom', fill='x', pady=(6, 0))
        if self.state.error:
            self.recovery_button.pack(side='bottom', anchor='w', pady=(6, 0))
        compose.pack(side='bottom', fill='x', pady=(10, 0))
        body.pack(fill='both', expand=True)
        self.bind('<Configure>', self.resize)

    def resize(self, event):
        if event.widget == self:
            for widget in (self.privacy, self.status):
                widget.configure(wraplength=max(300, event.width - 24))
            self.binding.configure(wraplength=max(220, int(event.width * .62)))
            self.title.configure(wraplength=max(220, int(event.width * .62)))

    def post(self, name, value, epoch=None):
        while not self.closed:
            try:
                self.events.put((name, value, self.epoch if epoch is None else epoch), timeout=.2)
                return
            except queue.Full:
                pass

    def client(self):
        return Client(self.root, self.identity)

    def launch(self, name, action, *, epoch=None, quiet=False):
        if self.busy and not quiet:
            return False
        if not quiet:
            self.busy = True
            self.controls()
        current = self.epoch if epoch is None else epoch
        def work():
            try:
                self.post(name, action(), current)
            except (OSError, ValueError, RuntimeError) as error:
                self.post('error', (name, error, quiet), current)
            except Exception:
                self.post('error', (name, ClientError('This action could not complete. Refresh Gateway and try again.'), quiet), current)
        threading.Thread(target=work, name='gateway-desktop-' + name, daemon=True).start()
        return True

    def activate(self, saved=False, smoke=False):
        self.visible = True
        self.heading.configure(text='Saved' if saved else 'Chat')
        if saved:
            self.filter.set('Saved messages')
        elif self.filter.get() == 'Saved messages':
            self.filter.set('Conversations')
        if not smoke:
            if self.identity is None:
                self.connect()
            else:
                self.refresh_list()

    def deactivate(self):
        self.visible = False

    def connect(self):
        if self.sending:
            return
        previous_identity = self.identity or {}
        conversation_id = (self.conversation or {}).get('conversation_id')
        def work():
            client = Client(self.root)
            identity = client.identity()
            models = client.models()
            opened = client.read(conversation_id) if conversation_id and scope_key(previous_identity) == scope_key(identity) else None
            return identity, models, opened
        self.status.configure(text='Connecting to Gateway and reading the live model list…')
        self.launch('connected', work)

    def refresh_list(self, more=False):
        if not self.identity:
            self.connect()
            return
        query, mode, cursor = self.query.get().strip(), self.filter.get(), self.cursor if more else None
        def work():
            client = self.client()
            result = client.saved(query=query, cursor=cursor) if mode == 'Saved messages' else client.conversations(
                query=query, archived=mode == 'Archived', favorite=mode == 'Favorites', cursor=cursor)
            return result, more, mode, query
        self.launch('listed', work)

    def open_selected(self, _event=None):
        selected = self.list.selection()
        if not selected or self.sending:
            return
        index = int(selected[0])
        if index >= len(self.rows):
            return
        row = self.rows[index]
        self.open_message_id = row.get('message_id') if self.filter.get() == 'Saved messages' else None
        ident = row.get('conversation_id')
        if not isinstance(ident, str):
            return
        self.status.configure(text='Loading saved conversation…')
        self.launch('opened', lambda: self.client().read(ident))

    def new_chat(self):
        if self.sending or self.busy:
            self.status.configure(text='Wait for the current action, or stop the reply before starting another chat.')
            return
        self.epoch += 1
        self.conversation, self.transcript, self.selected_message = None, [], None
        self.active_job_id, self.open_message_id = None, None
        if self.poll_timer:
            self.after_cancel(self.poll_timer)
            self.poll_timer = None
        self.stream_text = ''
        self.title.configure(text='Start a conversation')
        self.binding.configure(text='Choose a model below, then send a prompt.')
        self.render_text()
        self.controls()
        self.composer.focus_set()

    def draft_changed(self, _event=None):
        if not self.composer.edit_modified():
            return
        self.composer.edit_modified(False)
        self.draft_version = self.state.reserve_draft()
        if self.draft_timer:
            self.after_cancel(self.draft_timer)
        if not self.sending:
            self.draft_timer = self.after(600, self.save_draft)

    def model_changed(self, _event=None):
        self.draft_version = self.state.reserve_draft()
        if self.draft_timer:
            self.after_cancel(self.draft_timer)
        self.draft_timer = self.after(600, self.save_draft)

    def save_draft(self):
        self.draft_timer = None
        draft, model = self.composer.get('1.0', 'end-1c'), self.model.get()
        version = self.draft_version
        self.launch('draft', lambda: self.state.save(draft=draft, model=model, draft_version=version), quiet=True)

    def recover_storage(self):
        self.launch('storage-recovered', self.state.recover)

    def send(self):
        if self.busy or self.sending or not self.identity:
            return
        if self.state.error:
            self.status.configure(text='Recover draft storage before sending a new prompt. The original file will be preserved.')
            return
        if self.state.value.get('pending'):
            self.status.configure(text='A previous send needs reconciliation. Choose Check send before sending another prompt.')
            return
        prompt, model = self.composer.get('1.0', 'end-1c'), self.model.get()
        if not prompt.strip() or not model:
            self.status.configure(text='Choose a model and enter a prompt.')
            return
        if self.draft_timer:
            self.after_cancel(self.draft_timer)
            self.draft_timer = None
        command = uuid.uuid4().hex
        version = self.draft_version = self.state.reserve_draft()
        conversation, transcript, identity = self.conversation, list(self.transcript), dict(self.identity)
        epoch = self.epoch
        self.sending, self.stream_text = True, ''
        self.status.configure(text='Sending your prompt…')
        self.controls()
        def work():
            self.state.save(draft=prompt, model=model, draft_version=version,
                            pending={'command_id': command, 'identity': identity,
                            'draft_sha256': hashlib.sha256(prompt.encode('utf-8')).hexdigest(),
                            'conversation_id': (conversation or {}).get('conversation_id')})
            client = Client(self.root, identity)
            return client.send(model, prompt, command, conversation=conversation, history=transcript,
                               on_event=lambda name, value: self.post(name, value, epoch))
        self.launch('sent', work, epoch=epoch)

    def reconcile(self):
        pending = self.state.value.get('pending')
        if not pending or not self.identity or self.sending:
            return
        if scope_key(pending.get('identity', {})) != scope_key(self.identity):
            self.status.configure(text='The pending send belongs to another Gateway identity. Restore that connection to check it.')
            return
        self.status.configure(text='Checking the original command without sending it again…')
        self.launch('reconciled', lambda: self.client().command(pending['command_id']))

    def settle_send(self, value):
        """Reconcile one command without discarding edits made after its send."""
        self.sending = False
        pending = self.state.value.get('pending') or {}
        if not pending or value.get('command_id') != pending.get('command_id'):
            self.status.configure(text='The reply does not identify the pending command. Choose Check send; it will not be sent again.')
            return
        if value.get('admission_state') == 'rejected':
            self.launch('rejected-cleared', lambda: self.state.save(pending=None), quiet=True)
            self.status.configure(text='The original command was rejected before dispatch. Your draft is kept; correct its prerequisite before sending again.')
        elif value.get('conversation_id'):
            ident = value['conversation_id']
            draft, model = self.composer.get('1.0', 'end-1c'), self.model.get()
            if pending.get('draft_sha256') == hashlib.sha256(draft.encode('utf-8')).hexdigest():
                self.composer.configure(state='normal')
                self.composer.delete('1.0', 'end')
                self.composer.edit_modified(False)
                if self.draft_timer:
                    self.after_cancel(self.draft_timer)
                    self.draft_timer = None
                draft = ''
            version = self.draft_version = self.state.reserve_draft()
            client = self.client()
            def complete():
                self.state.save(draft=draft, model=model, pending=None, draft_version=version)
                return client.read(ident)
            self.launch('opened', complete)
        else:
            self.status.configure(text='The reply needs a saved receipt. Choose Check send; your prompt will not be sent again.')

    def rename(self):
        if not self.conversation:
            return
        value = simpledialog.askstring('Rename conversation', 'Conversation title', initialvalue=self.conversation.get('title', ''), parent=self)
        if value and value.strip():
            self.update_metadata(title=value.strip()[:120])

    def archive(self):
        if self.conversation:
            self.update_metadata(archived=not self.conversation.get('archived', False))

    def favorite(self):
        if self.conversation:
            self.update_metadata(favorite=not self.conversation.get('favorite', False))

    def update_metadata(self, **values):
        row = self.conversation
        self.launch('metadata', lambda: self.client().metadata(row['conversation_id'], row.get('metadata_revision', 0), **values))

    def select_message(self, _event=None):
        tags = self.text.tag_names('insert')
        self.selected_message = next((tag[8:] for tag in tags if tag.startswith('message:')), None)
        self.controls()

    def bookmark(self):
        if self.conversation and self.selected_message:
            conversation_id, message_id = self.conversation['conversation_id'], self.selected_message
            self.launch('bookmarked', lambda: self.client().bookmark(conversation_id, message_id))

    def remove_saved(self):
        selected = self.list.selection()
        if self.filter.get() != 'Saved messages' or not selected:
            return
        row = self.rows[int(selected[0])]
        if row.get('conversation_id') and row.get('message_id'):
            self.launch('unbookmarked', lambda: self.client().bookmark(row['conversation_id'], row['message_id'], False))

    def copy_selection(self):
        try:
            selected = self.text.get('sel.first', 'sel.last')
        except tk.TclError:
            self.status.configure(text='Select message text to copy.')
            return
        self.clipboard_clear()
        self.clipboard_append(selected)
        self.status.configure(text='Selected text copied.')

    def stop(self):
        ident = (self.conversation or {}).get('active_job_id') or getattr(self, 'active_job_id', None)
        if ident:
            self.launch('stopped', lambda: self.client().cancel(ident), quiet=True)

    def controls(self):
        pending = self.state.value.get('pending')
        original = self.conversation
        read_only = original and (original.get('active_job_kind') == 'engineering' or not original.get('history_complete'))
        active = original and str(original.get('job_state', '')).upper() not in TERMINAL and original.get('active_job_id')
        self.send_button.configure(state='normal' if self.identity and not self.state.error and not self.busy and not self.sending and not pending and not read_only and not active else 'disabled')
        self.model_box.configure(state='disabled' if original or self.sending else 'readonly')
        self.composer.configure(state='disabled' if self.sending or read_only else 'normal')
        self.reconcile_button.configure(state='normal' if pending and self.identity and not self.busy and not self.sending else 'disabled')
        self.stop_button.configure(state='normal' if (self.sending or active) and (getattr(self, 'active_job_id', None) or active) and not (original and original.get('active_job_kind') == 'engineering') else 'disabled')
        for button in (self.rename_button, self.archive_button, self.favorite_button):
            button.configure(state='normal' if original and not self.busy else 'disabled')
        self.archive_button.configure(text='Unarchive' if original and original.get('archived') else 'Archive')
        self.favorite_button.configure(text='Unfavorite' if original and original.get('favorite') else 'Favorite')
        self.save_message_button.configure(state='normal' if original and self.selected_message and not self.busy else 'disabled')
        self.remove_saved_button.configure(state='normal' if self.filter.get() == 'Saved messages' and self.list.selection() and not self.busy else 'disabled')

    def render_text(self):
        follow = self.text.yview()[1] >= .98
        view = self.text.yview()[0]
        self.text.configure(state='normal')
        self.text.delete('1.0', 'end')
        for item in self.transcript:
            tag = 'message:' + item['message_id']
            self.text.insert('end', 'You\n' if item.get('role') == 'user' else 'Assistant\n', ('role', tag))
            self.text.insert('end', item.get('content') or '(Awaiting reply)', (tag,))
            status = str(item.get('status', ''))
            if status not in {'sent', 'completed'}:
                self.text.insert('end', '\n' + status.replace('_', ' ').title(), ('status', tag))
            self.text.insert('end', '\n\n')
        if self.stream_text:
            self.text.insert('end', 'Assistant · responding\n', 'role')
            self.text.insert('end', self.stream_text)
        if not self.transcript and not self.stream_text:
            self.text.insert('end', 'Your conversation will appear here.\n\nSaved messages can be opened from the Saved menu.', 'status')
        self.text.configure(state='disabled')
        if follow:
            self.text.see('end')
        else:
            self.text.yview_moveto(view)

    def show_opened(self, value):
        self.conversation, self.transcript = value['conversation'], value['messages']
        self.selected_message, self.stream_text = None, ''
        self.title.configure(text=self.conversation.get('title') or 'Gateway conversation')
        self.model.set(self.conversation.get('model') or '')
        self.active_job_id = self.conversation.get('active_job_id')
        mode = 'GitHub job' if self.conversation.get('active_job_kind') == 'engineering' else 'Chat'
        self.binding.configure(text=f'{mode} · {self.conversation.get("model") or "Model not available"} · {str(self.conversation.get("job_state") or "Saved").replace("_", " ").title()}')
        self.render_text()
        opened_message = getattr(self, 'open_message_id', None)
        if opened_message:
            positions = self.text.tag_ranges('message:' + opened_message)
            if positions:
                self.text.see(positions[0])
                self.text.mark_set('insert', positions[0])
                self.selected_message = opened_message
            self.open_message_id = None
        self.status.configure(text='Saved conversation loaded. Click inside a message to save it.' if mode == 'Chat' else 'Saved GitHub transcript. Continue engineering work through its original job.')
        self.controls()
        if self.poll_timer:
            self.after_cancel(self.poll_timer)
        if str(self.conversation.get('job_state', '')).upper() not in TERMINAL:
            self.poll_timer = self.after(2000, self.refresh_conversation)

    def refresh_conversation(self):
        self.poll_timer = None
        if self.closed or not self.visible or not self.conversation or self.sending:
            return
        if self.busy:
            self.poll_timer = self.after(2000, self.refresh_conversation)
            return
        ident = self.conversation['conversation_id']
        self.launch('opened', lambda: self.client().read(ident))

    def poll(self):
        self.event_timer = None
        if self.closed:
            return
        redraw = False
        for _ in range(128):
            try:
                name, value, epoch = self.events.get_nowait()
            except queue.Empty:
                break
            if epoch != self.epoch and name not in {'draft', 'error'}:
                continue
            if name not in {'delta', 'accepted', 'draft', 'error', 'stopped', 'rejected-cleared'}:
                self.busy = False
            if name == 'connected':
                identity, models, opened = value
                if self.identity and scope_key(identity) != scope_key(self.identity):
                    self.conversation, self.transcript, self.rows = None, [], []
                    self.active_job_id, self.selected_message, self.open_message_id = None, None, None
                    self.title.configure(text='Start a conversation')
                    self.binding.configure(text='Choose a model below, then send a prompt.')
                    self.list.delete(*self.list.get_children())
                    self.render_text()
                self.identity = identity
                self.privacy.configure(text=privacy_text(identity))
                self.model_box.configure(values=models)
                if not self.conversation and self.model.get() not in models:
                    self.model.set(models[0] if models else '')
                self.status.configure(text='Connected. Choose a conversation or send a prompt.' if models else 'The backend advertised no models. Open Settings to check the connection.')
                if opened:
                    self.show_opened(opened)
                self.refresh_list()
            elif name == 'listed':
                page, more, mode, query = value
                if mode != self.filter.get() or query != self.query.get().strip():
                    self.refresh_list()
                    continue
                items = page.get('items', page.get('conversations', []))
                if not isinstance(items, list):
                    self.status.configure(text='Gateway returned an invalid saved list.')
                    continue
                self.rows = (self.rows if more else []) + [row for row in items if isinstance(row, dict)]
                self.cursor = page.get('next_cursor')
                self.list.delete(*self.list.get_children())
                self.list.heading('title', text='Saved message' if mode == 'Saved messages' else 'Conversation')
                for index, row in enumerate(self.rows):
                    label = ((str(row.get('title') or 'Saved message') + ' · ' + str(row.get('content') or '(Empty message)'))
                             if mode == 'Saved messages' else row.get('title') or 'Saved conversation')
                    self.list.insert('', 'end', iid=str(index), values=(' '.join(str(label).split())[:100], age(row.get('updated_at', row.get('saved_at', row.get('created_at'))))))
                self.more_button.configure(state='normal' if self.cursor and page.get('has_more', True) else 'disabled')
                self.status.configure(text=f'{len(self.rows)} saved items shown.' if self.rows else 'Nothing here yet. Send a prompt or save a message from a conversation.')
            elif name == 'opened':
                self.show_opened(value)
            elif name == 'accepted':
                self.active_job_id = value.get('job_id')
                self.status.configure(text='Gateway is responding. Closing this window keeps admitted work running.' if value.get('detach_safe') else 'Gateway is responding. Keep this window open until the reply is saved.')
            elif name == 'delta':
                self.stream_text += value
                redraw = True
            elif name in {'sent', 'reconciled'}:
                self.settle_send(value)
            elif name == 'metadata':
                if self.conversation:
                    self.conversation = dict(self.conversation, **value)
                    self.title.configure(text=self.conversation.get('title', 'Conversation'))
                self.status.configure(text='Conversation updated.')
                self.refresh_list()
            elif name == 'bookmarked':
                self.status.configure(text='Message saved. Open Saved to find it later.')
            elif name == 'unbookmarked':
                self.status.configure(text='Message removed from Saved. Its conversation is unchanged.')
                self.refresh_list()
            elif name == 'stopped':
                self.status.configure(text='Stop requested. The final saved status will confirm the outcome.')
                self.refresh_conversation()
            elif name == 'storage-recovered':
                self.recovery_button.pack_forget()
                self.status.configure(text='Draft storage recovered. The original file is preserved. Check Saved for earlier work; any readable pending send still requires Check send.')
            elif name == 'error':
                operation, error, quiet = value
                if not quiet:
                    self.busy = False
                if operation == 'sent':
                    self.sending = False
                    if getattr(error, 'not_dispatched', False):
                        self.launch('rejected-cleared', lambda: self.state.save(pending=None), quiet=True)
                self.status.configure(text=str(error))
            self.controls()
        if redraw:
            self.render_text()
        self.event_timer = self.after(100, self.poll)

    def destroyed(self, event):
        if event.widget is self:
            self.close()

    def close(self):
        if self.closed:
            return
        self.closed = True
        for name in ('draft_timer', 'poll_timer', 'event_timer'):
            timer = getattr(self, name, None)
            if timer:
                try:
                    self.after_cancel(timer)
                except tk.TclError:
                    pass
                setattr(self, name, None)
        if not self.sending:
            try:
                version = self.state.reserve_draft()
                self.state.save(draft=self.composer.get('1.0', 'end-1c'), model=self.model.get(), draft_version=version)
            except (OSError, ValueError, tk.TclError):
                pass
