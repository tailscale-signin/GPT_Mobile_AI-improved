"""Durable, principal-scoped display transcripts and admission receipts.

This is a projection of the existing execution engines, never a third executor.
Message publication and engine checkpoints share their SQLite transaction. Only
visible content is retained here: provider reasoning and raw tool arguments are
not a conversation transcript.
"""
import base64
import contextvars
import hashlib
import hmac
import json
import re
import sqlite3
import secrets
import time
import uuid

admission_context = contextvars.ContextVar('gateway_conversation_admission', default=None)
TERMINAL = {'completed', 'failed', 'cancelled', 'canceled', 'interrupted', 'COMPLETED', 'CANCELLED', 'FAILED'}
FEATURES = {'job_catalog_v1': True, 'conversation_sync_v1': True, 'conversation_message_parts_v1': True, 'durable_detach_v1': True, 'command_outcomes_v1': True}
SCHEMA_VERSION = '3'
MESSAGE_PAGE_BYTES = 1_900_000
MESSAGE_PART_BYTES = 256 * 1024


class ConversationConflict(ValueError):
    pass


class CommandIdentityConflict(ConversationConflict):
    """A reused ID cannot establish the outcome of the altered payload."""


class QueueFull(ValueError):
    pass


class CursorExpired(ValueError):
    pass


def short_title(value):
    return ' '.join(str(value or 'Gateway conversation').split())[:120]


def text_content(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return '\n'.join(str(part.get('text', '')) for part in value if isinstance(part, dict) and part.get('type') in {'text', 'input_text', 'output_text'})
    return ''


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


def cursor_encode(value):
    return base64.urlsafe_b64encode(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).decode().rstrip('=')


def cursor_decode(value):
    if not isinstance(value, str) or len(value) > 4096:
        raise ValueError('Invalid cursor')
    try:
        result = json.loads(base64.urlsafe_b64decode(value + '=' * (-len(value) % 4)))
    except (ValueError, TypeError, UnicodeError) as error:
        raise ValueError('Invalid cursor') from error
    if not isinstance(result, dict):
        raise ValueError('Invalid cursor')
    return result


def save_message_parts(db, message_id, revision, content):
    raw = content.encode('utf-8')
    checksum = hashlib.sha256(raw).hexdigest()
    bounds, start = [], 0
    while start < len(raw):
        end = min(len(raw), start + MESSAGE_PART_BYTES)
        while end < len(raw) and raw[end] & 0xc0 == 0x80:
            end -= 1
        bounds.append((start, end))
        start = end
    bounds = bounds or [(0, 0)]
    db.execute('DELETE FROM gateway_message_parts WHERE message_id=?', (message_id,))
    db.executemany('INSERT INTO gateway_message_parts VALUES(?,?,?,?,?,?)',
        ((message_id, revision, index, len(bounds), raw[start:end].decode('utf-8'), checksum) for index, (start, end) in enumerate(bounds)))


class Conversations:
    def __init__(self, runtime, now=time.time):
        self.runtime, self.now = runtime, now

    @staticmethod
    def schema(db):
        db.row_factory = sqlite3.Row
        if not db.in_transaction:
            db.execute('BEGIN IMMEDIATE')
        from v14.conversation_migration import prepare, migrate, columns
        from v14.execution_binding import ExecutionBindings
        prepare(db)
        ExecutionBindings.schema(db)
        statements = (
            'CREATE TABLE IF NOT EXISTS gateway_installation (key TEXT PRIMARY KEY,value TEXT NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_conversations (id TEXT PRIMARY KEY,principal TEXT NOT NULL,client_key TEXT NOT NULL,binding TEXT NOT NULL,title TEXT NOT NULL,revision INTEGER NOT NULL,created_at REAL NOT NULL,updated_at REAL NOT NULL,active_kind TEXT,active_job TEXT,history_complete INTEGER NOT NULL DEFAULT 1,history_limitation TEXT,UNIQUE(principal,client_key))',
            'CREATE TABLE IF NOT EXISTS gateway_messages (id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL,turn_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,created_at REAL NOT NULL,publication_id TEXT NOT NULL UNIQUE)',
            'CREATE INDEX IF NOT EXISTS gateway_messages_cursor ON gateway_messages(conversation_id,revision)',
            'CREATE TABLE IF NOT EXISTS gateway_message_publications (conversation_id TEXT NOT NULL,message_id TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(conversation_id,revision))',
            'CREATE TABLE IF NOT EXISTS gateway_message_parts (message_id TEXT NOT NULL,revision INTEGER NOT NULL,part_index INTEGER NOT NULL,part_count INTEGER NOT NULL,content TEXT NOT NULL,content_sha256 TEXT NOT NULL,PRIMARY KEY(message_id,revision,part_index))',
            'CREATE TABLE IF NOT EXISTS gateway_catalog_keys (sequence INTEGER PRIMARY KEY AUTOINCREMENT,job_kind TEXT NOT NULL,job_id TEXT NOT NULL,UNIQUE(job_kind,job_id))',
            'CREATE TABLE IF NOT EXISTS gateway_conversation_catalog (sequence INTEGER PRIMARY KEY AUTOINCREMENT,conversation_id TEXT NOT NULL UNIQUE)',
            'CREATE TABLE IF NOT EXISTS gateway_commands (principal TEXT NOT NULL,id TEXT NOT NULL,fingerprint TEXT NOT NULL,conversation_id TEXT NOT NULL,job_kind TEXT NOT NULL,job_id TEXT NOT NULL,receipt TEXT NOT NULL,created_at REAL NOT NULL,PRIMARY KEY(principal,id))',
            'CREATE TABLE IF NOT EXISTS gateway_command_rejections (principal TEXT NOT NULL,id TEXT NOT NULL,fingerprint TEXT NOT NULL,receipt TEXT NOT NULL,created_at REAL NOT NULL,PRIMARY KEY(principal,id))',
            'CREATE TABLE IF NOT EXISTS gateway_generation_admissions (job_id TEXT PRIMARY KEY,principal TEXT NOT NULL,payload_json TEXT,dispatch_state TEXT NOT NULL,accepted_at REAL NOT NULL,deadline REAL NOT NULL,binding_fingerprint TEXT NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_pending_instructions (command_id TEXT NOT NULL,conversation_id TEXT NOT NULL,content TEXT NOT NULL,status TEXT NOT NULL,created_at REAL NOT NULL,PRIMARY KEY(conversation_id,command_id))',
            'CREATE TABLE IF NOT EXISTS gateway_history_tombstones (principal TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,expired_at REAL NOT NULL,PRIMARY KEY(principal,kind,id))',
        )
        for statement in statements:
            db.execute(statement)
        if 'binding_fingerprint' not in {row[1] for row in db.execute('PRAGMA table_info(gateway_generation_admissions)')}:
            db.execute("ALTER TABLE gateway_generation_admissions ADD COLUMN binding_fingerprint TEXT NOT NULL DEFAULT ''")
        for name, definition in (('history_complete', 'INTEGER NOT NULL DEFAULT 1'), ('history_limitation', 'TEXT')):
            if name not in columns(db, 'gateway_conversations'):
                db.execute('ALTER TABLE gateway_conversations ADD COLUMN ' + name + ' ' + definition)
        installation = db.execute("SELECT value FROM engineering_identity WHERE name='installation'").fetchone()[0]
        db.execute("INSERT OR IGNORE INTO gateway_installation VALUES('gateway_id',?)", (installation,))
        db.execute("INSERT OR IGNORE INTO gateway_installation VALUES('cursor_key',?)", (secrets.token_hex(32),))
        migrate(db, Conversations.identity(db))
        version = db.execute("SELECT value FROM gateway_installation WHERE key='conversation_schema'").fetchone()
        if version is None or version[0] != SCHEMA_VERSION:
            db.execute('INSERT OR IGNORE INTO gateway_conversation_catalog(conversation_id) SELECT id FROM gateway_conversations ORDER BY created_at,id')
            db.execute('INSERT OR IGNORE INTO gateway_message_publications SELECT conversation_id,id,revision FROM gateway_messages')
            for row in db.execute('SELECT id,revision,content FROM gateway_messages'):
                save_message_parts(db, row['id'], row['revision'], row['content'])
            if columns(db, 'gateway_jobs') and 'job_id' in columns(db, 'gateway_jobs'):
                db.execute("INSERT OR IGNORE INTO gateway_catalog_keys(job_kind,job_id) SELECT 'generation',job_id FROM gateway_jobs ORDER BY created_at,job_id")
            if columns(db, 'engineering_jobs'):
                db.execute("INSERT OR IGNORE INTO gateway_catalog_keys(job_kind,job_id) SELECT 'engineering',id FROM engineering_jobs ORDER BY next_at,id")
                for row in db.execute("SELECT c.id,c.binding,j.data FROM gateway_conversations c JOIN engineering_jobs j ON c.active_job=j.id WHERE c.active_kind='engineering'").fetchall():
                    binding, job = json.loads(row['binding']), json.loads(row['data'])
                    request = job['request']
                    for key, value in {'source_branch': request.get('source_branch', request['branch']), 'working_branch': request['branch'],
                                       'source_sha': request.get('source_sha'), 'base': request['base'], 'execution_binding': job.get('execution_binding')}.items():
                        binding.setdefault(key, value)
                    db.execute('UPDATE gateway_conversations SET binding=? WHERE id=?', (json.dumps(binding), row['id']))
        db.execute("INSERT INTO gateway_installation VALUES('conversation_schema',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value WHERE value!=excluded.value", (SCHEMA_VERSION,))

    def tx(self, operation):
        if not self.runtime.GATEWAY_DURABLE_JOBS:
            raise ValueError('Conversation recovery requires durable storage')
        if not self.runtime.durable_job_initialized:
            self.runtime.init_durable_job_store()
        with self.runtime.durable_job_lock:
            db = self.runtime._durable_connect()
            try:
                with db:
                    db.execute('BEGIN IMMEDIATE')
                    self.schema(db)
                    return operation(db)
            finally:
                db.close()

    def read(self, operation):
        """Read retained history without requiring a writable journal.

        Schema creation/migration happens once when it is actually missing.
        Ordinary polling then uses a deferred snapshot and issues no DDL/DML;
        failed new admissions must not make already saved answers unreadable.
        """
        if not self.runtime.GATEWAY_DURABLE_JOBS:
            raise ValueError('Conversation recovery requires durable storage')
        if not self.runtime.durable_job_initialized:
            self.runtime.init_durable_job_store()
        for attempt in range(2):
            with self.runtime.durable_job_lock:
                db = self.runtime._durable_connect()
                try:
                    db.row_factory = sqlite3.Row
                    with db:
                        db.execute('BEGIN')
                        exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_installation'").fetchone()
                        installation = dict(db.execute('SELECT key,value FROM gateway_installation')) if exists else {}
                        if installation.get('gateway_id') and installation.get('conversation_schema') == SCHEMA_VERSION:
                            return operation(db)
                finally:
                    db.close()
            if attempt == 0:
                self.tx(lambda db: None)
        raise sqlite3.DatabaseError('Conversation schema initialization failed')

    @staticmethod
    def identity(db):
        return db.execute("SELECT value FROM gateway_installation WHERE key='gateway_id'").fetchone()[0]

    def gateway_id(self):
        return self.read(self.identity)

    @staticmethod
    def encode_cursor(db, value):
        payload = json.dumps(dict(value, version=2), sort_keys=True, separators=(',', ':')).encode('utf-8')
        key = bytes.fromhex(db.execute("SELECT value FROM gateway_installation WHERE key='cursor_key'").fetchone()[0])
        return base64.urlsafe_b64encode(payload + hmac.new(key, payload, hashlib.sha256).digest()).decode().rstrip('=')

    @staticmethod
    def decode_cursor(db, value):
        try:
            if not isinstance(value, str) or len(value) > 4096:
                raise ValueError('Invalid cursor')
            raw = base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True)
            payload, signature = raw[:-32], raw[-32:]
            key = bytes.fromhex(db.execute("SELECT value FROM gateway_installation WHERE key='cursor_key'").fetchone()[0])
            if not hmac.compare_digest(signature, hmac.new(key, payload, hashlib.sha256).digest()):
                raise ValueError('Cursor signature changed')
            result = json.loads(payload)
            if not isinstance(result, dict) or result.get('version') != 2:
                raise ValueError('Unsupported cursor')
            return result
        except (ValueError, TypeError, KeyError, UnicodeError) as error:
            raise CursorExpired('The cursor cannot be verified; hydrate the retained conversation snapshot') from error

    @staticmethod
    def active(db, kind, job_id):
        if not job_id:
            return False
        table, key = ('engineering_jobs', 'id') if kind == 'engineering' else ('gateway_jobs', 'job_id')
        if not db.execute('SELECT 1 FROM sqlite_master WHERE type=? AND name=?', ('table', table)).fetchone():
            return False
        column = 'state' if kind == 'engineering' else 'status'
        row = db.execute(f'SELECT {column} FROM {table} WHERE {key}=?', (job_id,)).fetchone()
        return bool(row and row[0] not in TERMINAL)

    @staticmethod
    def bounded_id(value, name):
        if not isinstance(value, str) or not 1 <= len(value) <= 256 or any(ord(c) < 32 for c in value):
            raise ValueError('Invalid ' + name)
        return value

    def admission(self, db, principal, command_id, kind, job_id, request, binding, content, *, now=None, replace_paused=None):
        """Called inside the engine's creation commit, before any dispatch."""
        self.schema(db)
        self.bounded_id(command_id, 'command identity')
        now = self.now() if now is None else now
        request_hash = fingerprint(request)
        old = db.execute('SELECT fingerprint,receipt FROM gateway_commands WHERE principal=? AND id=?', (principal, command_id)).fetchone()
        if old:
            if old[0] != request_hash:
                raise ConversationConflict('Command identity was reused with different content or binding')
            receipt = json.loads(old[1])
            if receipt['job_id'] != job_id:
                raise ConversationConflict('Command already belongs to a different admitted job')
            return receipt
        if db.execute('SELECT 1 FROM gateway_command_rejections WHERE principal=? AND id=?', (principal, command_id)).fetchone():
            raise ConversationConflict('This command was definitively rejected. Repair its prerequisite and submit a new command identity.')
        remote_id = request.get('conversation_id')
        client_key = request.get('client_conversation_id') or kind + ':' + job_id
        self.bounded_id(client_key, 'client conversation identity')
        local_key = 'scope1:' + fingerprint([client_key, binding.get('profile_id'), kind])
        if remote_id:
            row = db.execute('SELECT * FROM gateway_conversations WHERE id=? AND principal=?', (remote_id, principal)).fetchone()
            if not row:
                raise ConversationConflict('Conversation does not exist for this principal')
        else:
            row = db.execute('SELECT * FROM gateway_conversations WHERE principal=? AND client_key=?', (principal, local_key)).fetchone()
            if row is None:
                legacy = db.execute('SELECT * FROM gateway_conversations WHERE principal=? AND client_key=?', (principal, client_key)).fetchone()
                if legacy and legacy['active_kind'] == kind and json.loads(legacy['binding']).get('profile_id') == binding.get('profile_id'):
                    db.execute('UPDATE gateway_conversations SET client_key=? WHERE id=?', (local_key, legacy['id']))
                    row = legacy
        new_conversation = row is None
        if row:
            row = dict(row)
            saved_binding = json.loads(row['binding'])
            if any(saved_binding.get(key) != binding.get(key) for key in ('profile_id', 'model', 'repository', 'branch', 'base', 'source_branch', 'source_sha', 'working_branch')):
                raise ConversationConflict('The original conversation profile, model or repository binding changed')
            if saved_binding.get('execution_binding') is not None and saved_binding['execution_binding'] != binding.get('execution_binding'):
                raise ConversationConflict('The original selected execution binding changed')
            if kind == 'engineering' and saved_binding.get('execution_mode', 'standard') != binding.get('execution_mode', 'standard'):
                raise ConversationConflict('The original Gateway execution mode changed')
            expected = request.get('expected_revision')
            if expected is not None and (type(expected) is not int or expected != row['revision']):
                raise ConversationConflict('Conversation revision changed; refresh before sending')
            if self.active(db, row['active_kind'], row['active_job']):
                if kind != 'engineering' or row['active_kind'] != 'engineering' or replace_paused is None or expected is None:
                    raise ConversationConflict('A job is active in this conversation; submit a queued instruction')
                replace_paused(db, row['active_job'])
            conversation_id = row['id']
        else:
            if request.get('expected_revision') not in (None, 0):
                raise ConversationConflict('Conversation revision changed')
            conversation_id = 'conv_' + uuid.uuid4().hex
            db.execute('INSERT INTO gateway_conversations(id,principal,client_key,binding,title,revision,created_at,updated_at,active_kind,active_job) VALUES(?,?,?,?,?,0,?,?,?,?)',
                       (conversation_id, principal, local_key, json.dumps(binding), short_title(content), now, now, kind, job_id))
        db.execute('INSERT OR IGNORE INTO gateway_conversation_catalog(conversation_id) VALUES(?)', (conversation_id,))
        db.execute('INSERT OR IGNORE INTO gateway_catalog_keys(job_kind,job_id) VALUES(?,?)', (kind, job_id))
        db.execute('UPDATE gateway_conversations SET active_kind=?,active_job=?,updated_at=? WHERE id=?', (kind, job_id, now, conversation_id))
        if new_conversation and kind == 'generation':
            visible = [item for item in request.get('messages', []) if isinstance(item, dict) and item.get('role') in {'user', 'assistant'}]
            # The final user submission belongs to this command. Historical
            # visible turns are imported once; system/tool/reasoning are excluded.
            last_user = next((index for index in range(len(visible) - 1, -1, -1) if visible[index].get('role') == 'user'), len(visible))
            visible = visible[:last_user]
            for index, item in enumerate(visible):
                historical = text_content(item.get('content'))
                if historical:
                    publication = command_id + ':history:' + str(index)
                    self.message(db, conversation_id, publication, item['role'], historical, 'completed', publication, now)
        self.message(db, conversation_id, command_id, 'user', text_content(content), 'sent', command_id + ':user', now)
        self.message(db, conversation_id, command_id, 'assistant', '', 'queued', command_id + ':assistant', now)
        receipt = {'schema_version': 1, 'gateway_id': self.identity(db), 'principal_id': principal,
                   'command_id': command_id, 'conversation_id': conversation_id, 'job_kind': kind,
                   'job_id': job_id, 'request_fingerprint': request_hash, 'accepted_at': now,
                   'server_now': now, 'detach_safe': True, 'previous_job_id': row['active_job'] if row else None,
                   'instance_id': getattr(getattr(self.runtime, 'gateway_service', None), 'instance_id', None), **binding}
        db.execute('INSERT INTO gateway_commands VALUES(?,?,?,?,?,?,?,?)', (principal, command_id, request_hash, conversation_id, kind, job_id, json.dumps(receipt), now))
        return receipt

    def message(self, db, conversation_id, turn, role, content, status, publication_id, now=None):
        now = self.now() if now is None else now
        publication_id = conversation_id + ':' + publication_id
        old = db.execute('SELECT id,content,status FROM gateway_messages WHERE publication_id=? AND conversation_id=?', (publication_id, conversation_id)).fetchone()
        if old and old[1] == content and old[2] == status:
            return old[0]
        db.execute('UPDATE gateway_conversations SET revision=revision+1,updated_at=? WHERE id=?', (now, conversation_id))
        revision = db.execute('SELECT revision FROM gateway_conversations WHERE id=?', (conversation_id,)).fetchone()[0]
        message_id = old[0] if old else 'msg_' + uuid.uuid4().hex
        db.execute('INSERT INTO gateway_messages VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET content=excluded.content,status=excluded.status,revision=excluded.revision',
                   (message_id, conversation_id, turn, role, content, status, revision, now, publication_id))
        db.execute('INSERT INTO gateway_message_publications VALUES(?,?,?)', (conversation_id, message_id, revision))
        save_message_parts(db, message_id, revision, content)
        return message_id

    def generation_projection(self, db, job, public, result):
        self.schema(db)
        db.execute("INSERT OR IGNORE INTO gateway_catalog_keys(job_kind,job_id) VALUES('generation',?)", (public['job_id'],))
        context = admission_context.get()
        if not public.get('conversation_id') and context and context.get('retention', 'full') == 'full':
            request = context['payload']
            metadata = dict(request.get('gateway') or {})
            command = metadata.get('command_id') or context['command_id']
            presentation = context.get('profile') or {}
            binding = {'profile_id': metadata.get('profile_id') or presentation.get('profile_id'), 'profile_name': presentation.get('profile_name'),
                       'model': public.get('model'), 'repository': None, 'branch': None, 'base': None}
            visible = self.runtime.get_effective_user_text(request.get('messages', []))
            receipt = self.admission(db, context['principal'], command, 'generation', public['job_id'],
                                     dict(request, **{k: metadata[k] for k in ('conversation_id', 'client_conversation_id', 'expected_revision') if k in metadata}), binding, visible,
                                     now=public.get('created_at') or self.now())
            if db.execute("SELECT COUNT(*) FROM gateway_generation_admissions WHERE dispatch_state='queued'").fetchone()[0] >= getattr(self.runtime, 'gateway_max_queued', 64):
                raise QueueFull('Gateway pending queue is full')
            seconds = ((metadata.get('budget') or {}).get('totalDeadlineMs', 900000)) / 1000
            route_fingerprint = fingerprint([getattr(self.runtime, 'LLAMA_BASE', None), public.get('model')])
            db.execute('INSERT INTO gateway_generation_admissions VALUES(?,?,?,?,?,?,?)', (public['job_id'], context['principal'], json.dumps(request), 'queued', receipt['accepted_at'], receipt['accepted_at'] + seconds, route_fingerprint))
            fields = {k: receipt[k] for k in ('conversation_id', 'command_id', 'gateway_id', 'principal_id', 'accepted_at', 'detach_safe', 'profile_id', 'profile_name')}
            fields.update(status='queued', stage='queued', queued_at=receipt['accepted_at'], started_at=None, title=short_title(visible), queue_reason='worker_capacity')
            job.update(fields)
            public.update(fields)
        if context and job.pop('_admission_pending', False) and public.get('conversation_id'):
            request = context['payload']
            metadata = request.get('gateway') or {}
            if context['principal'] != public.get('principal_id') or str(request.get('model')) != public.get('model'):
                raise ConversationConflict('Continuation identity changed')
            header_profile = (context.get('profile') or {}).get('profile_id')
            if header_profile and header_profile != public.get('profile_id'):
                raise ConversationConflict('Continuation profile identity changed')
            for key in ('profile_id', 'conversation_id', 'client_conversation_id'):
                if key == 'client_conversation_id':
                    continue
                if key in metadata and metadata[key] != public.get(key):
                    raise ConversationConflict('Continuation binding changed')
            pending = db.execute('SELECT dispatch_state FROM gateway_generation_admissions WHERE job_id=?', (public['job_id'],)).fetchone()
            if not pending:
                raise ConversationConflict('The continuation lost its durable admission')
            if pending[0] != 'queued' and db.execute("SELECT COUNT(*) FROM gateway_generation_admissions WHERE dispatch_state='queued'").fetchone()[0] >= getattr(self.runtime, 'gateway_max_queued', 64):
                raise QueueFull('Gateway pending queue is full')
            now = self.now()
            segment_command = metadata.get('command_id') or context['command_id']
            if segment_command != public['command_id']:
                request_hash = fingerprint(request)
                previous = db.execute('SELECT fingerprint,job_id FROM gateway_commands WHERE principal=? AND id=?', (context['principal'], segment_command)).fetchone()
                if previous and (previous[0] != request_hash or previous[1] != public['job_id']):
                    raise ConversationConflict('Continuation command identity was reused')
                if not previous:
                    base = db.execute('SELECT receipt FROM gateway_commands WHERE principal=? AND id=?', (context['principal'], public['command_id'])).fetchone()
                    receipt = dict(json.loads(base[0]), command_id=segment_command, request_fingerprint=request_hash, command_accepted_at=now, server_now=now, turn_id=public['command_id'])
                    db.execute('INSERT INTO gateway_commands VALUES(?,?,?,?,?,?,?,?)', (context['principal'], segment_command, request_hash, public['conversation_id'], 'generation', public['job_id'], json.dumps(receipt), now))
            deadline = now + (metadata.get('budget') or {}).get('totalDeadlineMs', 900000) / 1000
            db.execute('UPDATE gateway_generation_admissions SET payload_json=?,dispatch_state=?,deadline=? WHERE job_id=?', (json.dumps(request), 'queued', deadline, public['job_id']))
            fields = dict(status='queued', stage='queued', queued_at=now, queue_reason='worker_capacity')
            public.update(fields)
            job.update(fields)
        if job.pop('_claim_dispatch', False):
            changed = db.execute("UPDATE gateway_generation_admissions SET dispatch_state='dispatched',payload_json=NULL WHERE job_id=? AND dispatch_state='queued'", (public['job_id'],))
            if changed.rowcount != 1:
                raise ConversationConflict('Generation already dispatched')
        command = public.get('command_id')
        if not command:
            return
        status = str(public.get('status', 'running'))
        content = None
        if isinstance(result, dict):
            choices = result.get('choices')
            if isinstance(choices, list) and choices and isinstance(choices[0], dict):
                content = text_content((choices[0].get('message') or {}).get('content'))
        if status in {'failed', 'cancelled', 'canceled'} and not content:
            content = str(public.get('message') or 'The gateway job stopped before completing an answer.')
        old = db.execute('SELECT content FROM gateway_messages WHERE publication_id=?', (public['conversation_id'] + ':' + command + ':assistant',)).fetchone()
        display_status = 'waiting_for_client_tool' if status == 'waiting_for_client_tool' else status
        self.message(db, public['conversation_id'], command, 'assistant', content if content is not None else (old[0] if old else ''), display_status, command + ':assistant')
        if status in TERMINAL:
            db.execute('UPDATE gateway_generation_admissions SET payload_json=NULL,dispatch_state=? WHERE job_id=?', ('finished', public['job_id']))

    def engineering_projection(self, db, job, state):
        if not job.get('conversation_id'):
            return
        data, command = job.get('data', {}), job['command_id']
        content = data.get('answer') or data.get('final_report') or data.get('summary') or ''
        if isinstance(content, dict):
            content = content.get('summary') or json.dumps(content, ensure_ascii=False)
        if not isinstance(content, str):
            content = ''
        status = 'completed' if state == 'COMPLETED' else 'cancelled' if state == 'CANCELLED' else 'failed' if state == 'FAILED' else 'cancelling' if state == 'CANCELLING' else 'needs_attention' if state in {'NEEDS_INPUT', 'MERGE_READY'} else 'running'
        if state in {'NEEDS_INPUT', 'MERGE_READY'} and not content:
            content = job.get('message', '')
        self.message(db, job['conversation_id'], command, 'assistant', text_content(content), status, command + ':assistant')

    def pending_generation(self, job_id=None):
        def read(db):
            if job_id:
                row = db.execute('SELECT * FROM gateway_generation_admissions WHERE job_id=?', (job_id,)).fetchone()
                return dict(row) if row else None
            return [dict(row) for row in db.execute("SELECT * FROM gateway_generation_admissions WHERE dispatch_state='queued' AND payload_json IS NOT NULL ORDER BY accepted_at,job_id")]
        return self.read(read)

    def mark_expired(self, db, job_id):
        self.schema(db)
        row = db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
        public = json.loads(row[0]) if row else {}
        principal = public.get('principal_id')
        if principal:
            db.execute('INSERT OR REPLACE INTO gateway_history_tombstones VALUES(?,?,?,?)', (principal, 'generation', job_id, self.now()))
            db.execute('DELETE FROM gateway_generation_admissions WHERE job_id=?', (job_id,))

    def is_expired(self, principal, kind, ident):
        return self.read(lambda db: bool(db.execute('SELECT 1 FROM gateway_history_tombstones WHERE principal=? AND kind=? AND id=?', (principal, kind, ident)).fetchone()))

    def reject_command(self, principal, command_id, request, code, status, *, kind='engineering'):
        """Fence a definite pre-admission rejection; a committed acceptance always wins.

        Called only after a validated admission fails before dispatch, never for a
        timeout/cancellation or an uncertain upstream result. The same transaction
        fence is checked by admission(), so a late duplicate cannot execute after
        a rejection was published. No prompt, credentials or error message is kept.
        """
        self.bounded_id(command_id, 'command identity')
        if kind not in {'engineering', 'generation'} or not isinstance(code, str) or not re.fullmatch('[A-Za-z][A-Za-z0-9_]{0,95}', code) or type(status) is not int or status not in {400, 403, 409, 412, 422, 429, 503}:
            raise ValueError('Invalid command rejection')
        if kind == 'engineering':
            from v14.engineering import validate_request, digest
            from v14.engineering_defaults import new_request_defaults, retained_request_defaults
            request_hash = fingerprint(validate_request(new_request_defaults(request)))
        else:
            request_hash = fingerprint(request)
        def save(db):
            accepted = db.execute('SELECT receipt,fingerprint FROM gateway_commands WHERE principal=? AND id=?', (principal, command_id)).fetchone()
            if accepted:
                receipt = json.loads(accepted[0])
                matches = accepted[1] == request_hash
                if kind == receipt['job_kind'] == 'engineering':
                    # Accepted jobs can contain host-expanded acceptance. Compare
                    # the frozen original request digest, including its defaults.
                    job = db.execute('SELECT data,request_digest FROM engineering_jobs WHERE principal=? AND id=?', (principal, receipt['job_id'])).fetchone()
                    matches = job is not None and job[1] == digest(validate_request(retained_request_defaults(request, json.loads(job[0])['request'])))
                if not matches or receipt['job_kind'] != kind:
                    raise CommandIdentityConflict('Command identity was reused with different content or binding')
                return dict(receipt, server_now=self.now())
            previous = db.execute('SELECT receipt,fingerprint FROM gateway_command_rejections WHERE principal=? AND id=?', (principal, command_id)).fetchone()
            if previous:
                if previous[1] != request_hash or json.loads(previous[0])['job_kind'] != kind:
                    raise CommandIdentityConflict('Rejected command identity was reused with different content or binding')
                return dict(json.loads(previous[0]), server_now=self.now())
            # Fail closed if the bounded rejection ledger is full; never evict a
            # fence and accidentally make an earlier command safe to dispatch.
            if db.execute('SELECT COUNT(*) FROM gateway_command_rejections').fetchone()[0] >= 16384:
                raise QueueFull('Command outcome history is full')
            now = self.now()
            receipt = {'schema_version': 1, 'gateway_id': self.identity(db), 'principal_id': principal,
                       'command_id': command_id, 'job_kind': kind, 'admission_state': 'rejected',
                       'error_code': code, 'http_status': status, 'failed_stage': 'provider_admission',
                       'dispatch_state': 'not_dispatched', 'inference_attempted': False,
                       'server_now': now, 'recorded_at': now}
            db.execute('INSERT INTO gateway_command_rejections VALUES(?,?,?,?,?)',
                       (principal, command_id, request_hash, json.dumps(receipt), now))
            return receipt
        return self.tx(save)

    def command(self, principal, command_id, *, include_outcome=False):
        def read(db):
            row = db.execute('SELECT receipt FROM gateway_commands WHERE principal=? AND id=?', (principal, command_id)).fetchone()
            if not row and include_outcome and db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_command_rejections'").fetchone():
                row = db.execute('SELECT receipt FROM gateway_command_rejections WHERE principal=? AND id=?', (principal, command_id)).fetchone()
            return dict(json.loads(row[0]), server_now=self.now()) if row else None
        return self.read(read)

    @staticmethod
    def head_sha(db, conversation):
        if conversation['active_kind'] != 'engineering':
            return None
        row = db.execute('SELECT data FROM engineering_jobs WHERE id=? AND principal=?', (conversation['active_job'], conversation['principal'])).fetchone()
        return (json.loads(row[0]).get('data') or {}).get('head_sha') if row else None

    @staticmethod
    def job_state(db, conversation):
        # The retained job ID is a recovery reference, not proof of active execution.
        if not conversation['active_job']:
            return None
        engineering = conversation['active_kind'] == 'engineering'
        table = 'engineering_jobs' if engineering else 'gateway_jobs'
        if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
            return None
        if engineering:
            row = db.execute('SELECT state FROM engineering_jobs WHERE id=? AND principal=?',
                             (conversation['active_job'], conversation['principal'])).fetchone()
        else:
            row = db.execute('SELECT status,public_json FROM gateway_jobs WHERE job_id=?', (conversation['active_job'],)).fetchone()
            if row and json.loads(row['public_json']).get('principal_id') != conversation['principal']:
                return None
        return str(row[0]).upper() if row and row[0] else None

    @staticmethod
    def history_status(db, conversation):
        missing = [row[0] for row in db.execute('SELECT p.revision FROM gateway_message_publications p LEFT JOIN gateway_messages m ON m.id=p.message_id AND m.conversation_id=p.conversation_id WHERE p.conversation_id=? AND m.id IS NULL ORDER BY p.revision', (conversation['id'],))]
        incomplete = [row[0] for row in db.execute('SELECT m.revision FROM gateway_messages m LEFT JOIN gateway_message_parts p ON p.message_id=m.id AND p.revision=m.revision WHERE m.conversation_id=? GROUP BY m.id,m.revision HAVING COUNT(p.part_index)=0 OR COUNT(p.part_index)!=MAX(p.part_count) OR MIN(p.part_index)!=0 OR MAX(p.part_index)!=MAX(p.part_count)-1 OR COUNT(DISTINCT p.content_sha256)!=1', (conversation['id'],))]
        missing = sorted(set(missing + incomplete))
        ranges = []
        for revision in missing:
            if ranges and ranges[-1]['last_revision'] + 1 == revision:
                ranges[-1]['last_revision'] = revision
            else:
                ranges.append({'first_revision': revision, 'last_revision': revision})
        return {'history_complete': bool(conversation['history_complete']) and not missing,
                'history_limitation': 'retention_gap' if missing else conversation['history_limitation'],
                'gap': bool(missing), 'gap_details': {'missing_ranges': ranges[:100], 'has_more': len(ranges) > 100, 'missing_revision_count': len(missing)} if ranges else None}

    def snapshot(self, principal, conversation_id):
        def read(db):
            row = db.execute('SELECT * FROM gateway_conversations WHERE principal=? AND id=?', (principal, conversation_id)).fetchone()
            if not row:
                return None
            row = dict(row)
            return dict(json.loads(row['binding']), schema_version=1, gateway_id=self.identity(db), principal_id=principal,
                        conversation_id=row['id'], title=row['title'], revision=row['revision'], transcript_available=True,
                        active_job_id=row['active_job'], active_job_kind=row['active_kind'], job_state=self.job_state(db, row), created_at=row['created_at'], updated_at=row['updated_at'], head_sha=self.head_sha(db, row), server_now=self.now(), **self.history_status(db, row))
        return self.read(read)

    def list(self, principal, cursor=None, limit=50, engineering_allowed=True, generation_allowed=True):
        if not 1 <= limit <= 100:
            raise ValueError('Page size must be between 1 and 100')
        def read(db):
            gateway_id = self.identity(db)
            scope = [gateway_id, principal, 'conversations', engineering_allowed, generation_allowed]
            upper = db.execute('SELECT COALESCE(MAX(sequence),0) FROM gateway_conversation_catalog').fetchone()[0]
            before = upper + 1
            if cursor:
                value = self.decode_cursor(db, cursor)
                if value.get('scope') != scope:
                    raise ValueError('Conversation list cursor scope changed')
                upper, before = value.get('upper'), value.get('before')
                if type(upper) is not int or type(before) is not int or not 0 <= before <= upper + 1:
                    raise ValueError('Invalid conversation list cursor')
            rows = db.execute("SELECT c.*,k.sequence FROM gateway_conversations c JOIN gateway_conversation_catalog k ON k.conversation_id=c.id WHERE principal=? AND k.sequence<=? AND k.sequence<? AND (? OR active_kind!='engineering') AND (? OR active_kind!='generation') AND c.history_complete=1 AND EXISTS(SELECT 1 FROM gateway_messages m WHERE m.conversation_id=c.id AND m.role=?) AND NOT EXISTS(SELECT 1 FROM gateway_message_publications p LEFT JOIN gateway_messages m ON m.id=p.message_id AND m.conversation_id=p.conversation_id WHERE p.conversation_id=c.id AND m.id IS NULL) ORDER BY k.sequence DESC LIMIT ?", (principal, upper, before, int(engineering_allowed), int(generation_allowed), 'user', limit + 1)).fetchall()
            items = [dict(json.loads(row['binding']), conversation_id=row['id'], title=row['title'], revision=row['revision'], created_at=row['created_at'], updated_at=row['updated_at'], job_id=row['active_job'], job_kind=row['active_kind'], active_job_id=row['active_job'], active_job_kind=row['active_kind'], job_state=self.job_state(db, row), head_sha=self.head_sha(db, row), transcript_available=True, gateway_id=gateway_id, principal_id=principal, **self.history_status(db, row)) for row in rows[:limit]]
            items = [item for item in items if item['history_complete']]
            more = len(rows) > limit
            return {'schema_version': 1, 'gateway_id': gateway_id, 'principal_id': principal, 'server_now': self.now(), 'items': items, 'has_more': more, 'complete': not more, 'next_cursor': self.encode_cursor(db, {'scope': scope, 'upper': upper, 'before': rows[limit - 1]['sequence']}) if more else None}
        return self.read(read)

    def messages(self, principal, conversation_id, after=None, limit=100):
        if not 1 <= limit <= 200:
            raise ValueError('Page size must be between 1 and 200')
        def read(db):
            row = db.execute('SELECT * FROM gateway_conversations WHERE principal=? AND id=?', (principal, conversation_id)).fetchone()
            if not row:
                return None
            gateway_id = self.identity(db)
            start, watermark, part = 0, row['revision'], None
            if after:
                cursor = self.decode_cursor(db, after)
                if cursor.get('scope') != [gateway_id, principal, conversation_id] or type(cursor.get('after')) is not int or cursor['after'] < 0 :
                    raise ValueError('Conversation cursor scope changed')
                if cursor['after'] > row['revision']:
                    raise CursorExpired('The retained transcript watermark changed; hydrate the current snapshot')
                start = cursor['after']
                watermark = cursor.get('watermark') if cursor.get('watermark') is not None else row['revision']
                if type(watermark) is not int or watermark < start:
                    raise ValueError('Invalid conversation watermark')
                if watermark > row['revision']:
                    raise CursorExpired('The retained transcript watermark changed; hydrate the current snapshot')
                part = cursor.get('part')
                if part is not None:
                    if type(part) is not int or part < 1:
                        raise ValueError('Invalid message part cursor')
                    current = db.execute('SELECT revision FROM gateway_messages WHERE id=? AND conversation_id=?', (cursor.get('message_id'), conversation_id)).fetchone()
                    if not current or current[0] != start:
                        raise CursorExpired('The message changed while its parts were loading; hydrate the saved snapshot')
            def page_cursor(after_revision, has_more, next_part=None, message_id=None):
                return self.encode_cursor(db, {'scope': [gateway_id, principal, conversation_id], 'after': after_revision,
                    'part': next_part, 'message_id': message_id, 'watermark': watermark if has_more else None})
            result = {'schema_version': 1, 'gateway_id': gateway_id, 'principal_id': principal, 'conversation_id': conversation_id,
                      'messages': [], 'next_cursor': None, 'has_more': False, 'revision': watermark, 'server_now': self.now(), **self.history_status(db, row)}
            # Reserve the signed cursor and envelope, then count encoded JSON.
            # UTF-8 parts are <=256 KiB, including worst-case JSON escaping.
            encoded_size = lambda value: len(json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8'))
            messages, page_bytes, more = [], encoded_size(result) + 8192, False
            records = db.execute('SELECT m.id,m.turn_id,m.role,m.status,m.revision,m.created_at,m.publication_id,p.content,p.part_index,p.part_count,p.content_sha256 FROM gateway_messages m JOIN gateway_message_parts p ON p.message_id=m.id AND p.revision=m.revision WHERE m.conversation_id=? AND (m.revision>? OR (m.revision=? AND ? IS NOT NULL AND p.part_index>=?)) AND m.revision<=? ORDER BY m.revision,m.id,p.part_index LIMIT ?',
                                 (conversation_id, start, start, part, part, watermark, limit + 1))
            for message in records:
                physical_id = message['id'] if message['part_count'] == 1 else message['id'] + ':v' + str(message['revision']) + ':part:' + str(message['part_index'])
                item = {'message_id': physical_id, 'logical_message_id': message['id'], 'part_index': message['part_index'],
                        'part_count': message['part_count'], 'content_sha256': message['content_sha256'],
                        'turn_id': message['turn_id'], 'role': message['role'], 'content': message['content'],
                        'status': message['status'], 'revision': message['revision'], 'created_at': message['created_at'],
                        'publication_id': message['publication_id'].removeprefix(conversation_id + ':'),
                        'command_id': message['turn_id'] if ':history:' not in message['turn_id'] else None}
                size = encoded_size(item) + bool(messages)
                if len(messages) >= limit or messages and page_bytes + size > MESSAGE_PAGE_BYTES:
                    more = True
                    break
                if size + page_bytes > MESSAGE_PAGE_BYTES and not messages:
                    raise ValueError('The retained message part exceeds its encoded page budget')
                messages.append(item)
                page_bytes += size
            next_part = messages[-1]['part_index'] + 1 if more and messages[-1]['part_index'] + 1 < messages[-1]['part_count'] else None
            result.update(messages=messages, next_cursor=page_cursor(messages[-1]['revision'] if more else watermark, more, next_part,
                          messages[-1]['logical_message_id'] if next_part is not None else None), has_more=more)
            return result
        return self.read(read)

    def instruction(self, principal, conversation_id, payload):
        if set(payload) - {'command_id', 'content', 'expected_revision', 'kind'} or payload.get('kind') != 'instruction':
            raise ValueError('Expected an instruction command')
        command = self.bounded_id(payload.get('command_id'), 'command identity')
        content = payload.get('content')
        if not isinstance(content, str) or not content.strip() or len(content) > 16000:
            raise ValueError('Instruction must contain 1–16000 characters')
        def save(db):
            old = db.execute('SELECT fingerprint,receipt FROM gateway_commands WHERE principal=? AND id=?', (principal, command)).fetchone()
            request_hash = fingerprint([conversation_id, payload])
            if old:
                if old[0] != request_hash:
                    raise ConversationConflict('Command identity was reused')
                return json.loads(old[1])
            if db.execute('SELECT 1 FROM gateway_command_rejections WHERE principal=? AND id=?', (principal, command)).fetchone():
                raise ConversationConflict('This command was definitively rejected. Submit a new command identity.')
            row = db.execute('SELECT * FROM gateway_conversations WHERE id=? AND principal=?', (conversation_id, principal)).fetchone()
            if not row:
                raise KeyError('Unknown conversation')
            if type(payload.get('expected_revision')) is not int or payload['expected_revision'] != row['revision']:
                raise ConversationConflict('Conversation revision changed')
            if row['active_kind'] != 'engineering' or not self.active(db, row['active_kind'], row['active_job']):
                raise ConversationConflict('Instructions require an active engineering job')
            state = db.execute('SELECT state FROM engineering_jobs WHERE id=?', (row['active_job'],)).fetchone()
            if state is None or state[0] in {'CANCELLING', 'NEEDS_INPUT', 'MERGE_READY'}:
                raise ConversationConflict('The job is paused or cancelling; resolve or stop it before starting another turn')
            if db.execute("SELECT COUNT(*) FROM gateway_pending_instructions WHERE conversation_id=? AND status='pending'", (conversation_id,)).fetchone()[0] >= 32:
                raise QueueFull('Conversation instruction queue is full')
            now = self.now()
            db.execute('INSERT INTO gateway_pending_instructions VALUES(?,?,?,?,?)', (command, conversation_id, content, 'pending', now))
            self.message(db, conversation_id, command, 'user', content, 'pending', command + ':user', now)
            receipt = {'schema_version': 1, 'gateway_id': self.identity(db), 'principal_id': principal, 'conversation_id': conversation_id, 'command_id': command, 'job_id': row['active_job'], 'job_kind': 'engineering', 'status': 'queued_instruction', 'accepted_at': now, 'server_now': now, 'detach_safe': True}
            db.execute('INSERT INTO gateway_commands VALUES(?,?,?,?,?,?,?,?)', (principal, command, request_hash, conversation_id, 'engineering', row['active_job'], json.dumps(receipt), now))
            return receipt
        return self.tx(save)

    def consume_instructions(self, db, job):
        if not job.get('conversation_id'):
            return
        rows = db.execute("SELECT command_id,content FROM gateway_pending_instructions WHERE conversation_id=? AND status='pending' ORDER BY created_at,command_id", (job['conversation_id'],)).fetchall()
        if not rows:
            return
        if job['state'] not in {'PLAN', 'READ', 'IMPLEMENT', 'REVIEW'}:
            job['data']['instruction_requires_review'] = True
            job['approval'] = None
        values = job['data'].setdefault('user_instructions', [])
        for row in rows:
            values.append({'command_id': row[0], 'content': row[1]})
            self.message(db, job['conversation_id'], row[0], 'user', row[1], 'delivered', row[0] + ':user')
        db.execute("UPDATE gateway_pending_instructions SET status='delivered' WHERE conversation_id=? AND status='pending'", (job['conversation_id'],))
