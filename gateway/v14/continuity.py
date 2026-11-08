"""Principal-scoped conversation projection over the existing durable job journals.

This module owns neither model execution nor retries. Its writes share the job
transaction; a lost HTTP response cannot separate a command from its admitted job.
"""
import base64
import hashlib
import hmac
import json
import re
import secrets
import time
import uuid
import unicodedata

from v14.execution_binding import ExecutionBindingError, ExecutionBindings, canonical, fingerprint

ENGINEERING_PAUSED = {'MERGE_READY', 'NEEDS_INPUT', 'COMPLETED', 'CANCELLED'}
ORDINARY_TERMINAL = {'completed', 'failed', 'cancelled', 'canceled', 'interrupted'}
MESSAGE_PART_BYTES = 256 * 1024
MESSAGE_PAGE_BYTES = 1_800_000


def millis(value):
    return int(value * 1000) if type(value) in {int, float} else None


def identifier(value, name):
    if not isinstance(value, str) or not 1 <= len(value) <= 128 or any(ord(c) < 33 for c in value):
        raise ExecutionBindingError('CONTINUITY_INVALID_' + name.upper(), 'A bounded ' + name + ' is required.', 400)
    return value


def page_limit(value, default=50):
    if value is None:
        return default
    try:
        result = int(value)
    except (ValueError, TypeError):
        result = 0
    if not 1 <= result <= 100:
        raise ExecutionBindingError('CONTINUITY_INVALID_LIMIT', 'Page size must be between 1 and 100.', 400)
    return result


def profile_metadata(headers):
    """Presentation identity only. The authenticated principal never comes here."""
    profile_id = headers.get('x-gateway-profile-id', '')
    if profile_id and (not re.fullmatch(r'[A-Za-z0-9_-]{1,160}', profile_id)):
        raise ValueError('Invalid original profile identity')
    encoded = headers.get('x-gateway-profile-name-base64', '')
    name = ''
    if encoded:
        if len(encoded) > 1366 or not re.fullmatch(r'[A-Za-z0-9_-]+', encoded):
            raise ValueError('Original profile name is too large')
        try:
            raw = base64.b64decode(encoded + '=' * (-len(encoded) % 4), altchars=b'-_', validate=True)
            if not 1 <= len(raw) <= 1024:
                raise ValueError('Original profile name is too large')
            name = raw.decode('utf-8')
        except (ValueError, UnicodeDecodeError) as error:
            raise ValueError('Invalid original profile name encoding') from error
        if any(unicodedata.category(character) == 'Cc' for character in name):
            raise ValueError('Original profile name contains control characters')
    return {'profile_id': profile_id, 'profile_name': name}


def conversation_retention(headers):
    value = headers.get('x-gateway-conversation-retention', 'none')
    if value not in {'full', 'none'}:
        raise ValueError('Unsupported conversation retention policy')
    return value


def verify_mutation_identity(headers, principal, installation):
    """Optional client preconditions, checked against server-owned authority."""
    expected_gateway = headers.get('x-gateway-installation-id')
    expected_principal = headers.get('x-gateway-principal-id')
    if expected_gateway is None and expected_principal is None:
        return
    if any(not isinstance(value, str) or not 1 <= len(value) <= 512 or
           any(ord(character) < 33 for character in value)
           for value in (expected_gateway, expected_principal)):
        raise ExecutionBindingError('CONTINUITY_IDENTITY_INVALID', 'Supply both bounded gateway and account preconditions.', 400)
    if expected_principal != principal or expected_gateway != installation():
        raise ExecutionBindingError('CONTINUITY_IDENTITY_MISMATCH',
            'The gateway installation or account changed. Refresh its identity before submitting this command.', 409)


class ContinuityStore:
    def __init__(self, store):
        self.store = store

    @staticmethod
    def schema(db):
        ExecutionBindings.schema(db)
        db.execute('INSERT OR IGNORE INTO engineering_identity VALUES(?,?)', ('continuity_cursor_key', secrets.token_hex(32)))
        db.execute('CREATE TABLE IF NOT EXISTS gateway_conversations ('
                   'id TEXT PRIMARY KEY, principal TEXT NOT NULL, binding TEXT NOT NULL, title TEXT NOT NULL, '
                   'revision INTEGER NOT NULL DEFAULT 0, message_sequence INTEGER NOT NULL DEFAULT 0, '
                   'created_at REAL NOT NULL, updated_at REAL NOT NULL, latest_job_id TEXT, '
                   'history_complete INTEGER NOT NULL, history_limitation TEXT)')
        db.execute('CREATE INDEX IF NOT EXISTS gateway_conversation_owner ON gateway_conversations(principal,updated_at)')
        db.execute('CREATE TABLE IF NOT EXISTS gateway_conversation_messages ('
                   'conversation_id TEXT NOT NULL, sequence INTEGER NOT NULL, message_id TEXT NOT NULL UNIQUE, '
                   'job_id TEXT NOT NULL, role TEXT NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, '
                   'created_at REAL NOT NULL, logical_message_id TEXT, part_index INTEGER NOT NULL DEFAULT 0, '
                   'part_count INTEGER NOT NULL DEFAULT 1, content_sha256 TEXT, PRIMARY KEY(conversation_id,sequence))')
        message_columns = {row[1] for row in db.execute('PRAGMA table_info(gateway_conversation_messages)')}
        for name, definition in [('logical_message_id', 'TEXT'), ('part_index', 'INTEGER NOT NULL DEFAULT 0'),
                                 ('part_count', 'INTEGER NOT NULL DEFAULT 1'), ('content_sha256', 'TEXT')]:
            if name not in message_columns:
                db.execute('ALTER TABLE gateway_conversation_messages ADD COLUMN ' + name + ' ' + definition)
        db.execute('CREATE TABLE IF NOT EXISTS gateway_logical_messages ('
                   'logical_message_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, job_id TEXT NOT NULL, '
                   'role TEXT NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, content_sha256 TEXT NOT NULL, '
                   'part_count INTEGER NOT NULL, created_at REAL NOT NULL)')
        db.execute('CREATE TABLE IF NOT EXISTS gateway_continuity_jobs ('
                   'catalog_sequence INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, job_id TEXT NOT NULL, '
                   'principal TEXT NOT NULL, conversation_id TEXT NOT NULL, public_json TEXT NOT NULL, '
                   'UNIQUE(kind,job_id))')
        db.execute('CREATE INDEX IF NOT EXISTS gateway_catalog_owner ON gateway_continuity_jobs(principal,catalog_sequence)')
        db.execute('CREATE TABLE IF NOT EXISTS gateway_commands ('
                   'principal TEXT NOT NULL, command_id TEXT NOT NULL, digest TEXT NOT NULL, '
                   'conversation_id TEXT NOT NULL, job_id TEXT NOT NULL, accepted_at REAL NOT NULL, '
                   'accepted_revision INTEGER NOT NULL DEFAULT 0, '
                   'PRIMARY KEY(principal,command_id))')
        if 'accepted_revision' not in {row[1] for row in db.execute('PRAGMA table_info(gateway_commands)')}:
            db.execute('ALTER TABLE gateway_commands ADD COLUMN accepted_revision INTEGER NOT NULL DEFAULT 0')

    @staticmethod
    def installation(db):
        return db.execute("SELECT value FROM engineering_identity WHERE name='installation'").fetchone()[0]

    @staticmethod
    def binding(job):
        request = job['request']
        source_branch = request.get('source_branch', request['branch'])
        return {'profile_id': request.get('profile_id') or '', 'profile_name': request.get('profile_name') or '',
                'model': request['model'], 'repository': request['repository'], 'branch': source_branch,
                'source_branch': source_branch, 'source_sha': request.get('source_sha'),
                'execution_binding': job.get('execution_binding')}

    def identity(self, principal, instance_id, identity_scope):
        return self.store.tx(lambda db: {'protocol_version': 1, 'gateway_id': self.installation(db),
            'instance_id': instance_id, 'principal_id': principal, 'identity_scope': identity_scope,
            'server_time_ms': millis(self.store.now())})

    def _cursor(self, db, principal, kind, **values):
        payload = canonical(dict(values, version=1, gateway_id=self.installation(db),
                                 principal=fingerprint(principal), kind=kind)).encode()
        key = bytes.fromhex(db.execute("SELECT value FROM engineering_identity WHERE name='continuity_cursor_key'").fetchone()[0])
        signature = hmac.new(key, payload, hashlib.sha256).digest()
        return base64.urlsafe_b64encode(payload + signature).decode().rstrip('=')

    def _read_cursor(self, db, principal, kind, value):
        try:
            if not isinstance(value, str) or len(value) > 2048:
                raise ValueError()
            raw = base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True)
            payload, signature = raw[:-32], raw[-32:]
            key = bytes.fromhex(db.execute("SELECT value FROM engineering_identity WHERE name='continuity_cursor_key'").fetchone()[0])
            if not hmac.compare_digest(signature, hmac.new(key, payload, hashlib.sha256).digest()):
                raise ValueError()
            result = json.loads(payload)
            if result.get('version') != 1 or result.get('gateway_id') != self.installation(db) or result.get('principal') != fingerprint(principal) or result.get('kind') != kind:
                raise ValueError()
            return result
        except (ValueError, TypeError, KeyError, json.JSONDecodeError):
            raise ExecutionBindingError('CONTINUITY_CURSOR_INVALID', 'The cursor does not belong to this gateway, account and resource.', 409) from None

    @staticmethod
    def _conversation(db, principal, conversation_id):
        row = db.execute('SELECT binding,title,revision,message_sequence,created_at,updated_at,latest_job_id,history_complete,history_limitation '
                         'FROM gateway_conversations WHERE id=? AND principal=?', (conversation_id, principal)).fetchone()
        if row is None:
            raise ExecutionBindingError('CONVERSATION_NOT_FOUND', 'Conversation not found for this account.', 404)
        return row

    @staticmethod
    def append(db, conversation_id, job_id, message_id, role, kind, content, now, *, input_message=False):
        # Stable keys make callback retries and result replay exactly-once at the
        # projection boundary. They do not imply exactly-once external effects.
        message_id = conversation_id + ':' + message_id
        raw = content if isinstance(content, str) else canonical(content)
        existing = db.execute('SELECT job_id,role,kind,content FROM gateway_logical_messages WHERE logical_message_id=?', (message_id,)).fetchone()
        if existing is None:
            existing = db.execute('SELECT job_id,role,kind,content FROM gateway_conversation_messages WHERE message_id=?', (message_id,)).fetchone()
        if existing:
            if tuple(existing) != (job_id, role, kind, raw):
                raise ValueError('A retained message identity cannot change its content')
            return False
        row = db.execute('SELECT message_sequence FROM gateway_conversations WHERE id=?', (conversation_id,)).fetchone()
        if row is None:
            raise ValueError('Unknown conversation')
        encoded = raw.encode('utf-8')
        content_hash = hashlib.sha256(encoded).hexdigest()
        parts, start = [], 0
        while start < len(encoded):
            end = min(start + MESSAGE_PART_BYTES, len(encoded))
            while end < len(encoded) and encoded[end] & 0xc0 == 0x80:
                end -= 1
            parts.append(encoded[start:end].decode('utf-8'))
            start = end
        parts = parts or ['']
        db.execute('INSERT INTO gateway_logical_messages VALUES(?,?,?,?,?,?,?,?,?)',
                   (message_id, conversation_id, job_id, role, kind, raw, content_hash, len(parts), now))
        sequence = row[0]
        for index, part in enumerate(parts):
            sequence += 1
            physical_id = message_id if len(parts) == 1 else message_id + ':part:' + str(index)
            db.execute('INSERT INTO gateway_conversation_messages '
                       '(conversation_id,sequence,message_id,job_id,role,kind,content,created_at,logical_message_id,part_index,part_count,content_sha256) '
                       'VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                       (conversation_id, sequence, physical_id, job_id, role, kind, part, now, message_id, index, len(parts), content_hash))
        db.execute('UPDATE gateway_conversations SET message_sequence=?,revision=revision+1,updated_at=? WHERE id=?',
                   (sequence, now, conversation_id))
        return True

    def admit_engineering(self, db, job, request_hash, *, legacy=False, command_hash=None):
        request, principal = job['request'], job['principal']
        binding = self.binding(job)
        conversation_id = job.get('conversation_id') or request.get('conversation_id')
        if conversation_id:
            row = self._conversation(db, principal, conversation_id)
            if json.loads(row[0]) != binding:
                raise ExecutionBindingError('CONVERSATION_BINDING_CHANGED', 'Use the original conversation profile, model, repository and branch.', 409)
        else:
            conversation_id = ('conv_' + fingerprint([principal, 'engineering', job['id']])[:32]) if legacy else 'conv_' + uuid.uuid4().hex
            db.execute('INSERT OR IGNORE INTO gateway_conversations(id,principal,binding,title,created_at,updated_at,history_complete,history_limitation) VALUES(?,?,?,?,?,?,?,?)',
                       (conversation_id, principal, canonical(binding), request['objective'][:200], job['created_at'], job['created_at'],
                        int(not legacy), 'legacy_snapshot_only' if legacy else None))
        job.update(conversation_id=conversation_id, gateway_id=self.installation(db), command_id=request['request_id'],
                   accepted_at_ms=millis(job['created_at']))
        self.append(db, conversation_id, job['id'], 'user:' + job['id'], 'user', 'user', request['objective'], job['created_at'])
        admitted_revision = db.execute('SELECT revision FROM gateway_conversations WHERE id=?', (conversation_id,)).fetchone()[0]
        db.execute('INSERT OR IGNORE INTO gateway_commands VALUES(?,?,?,?,?,?,?)',
                   (principal, request['request_id'], command_hash or request_hash, conversation_id, job['id'], job['created_at'], admitted_revision))
        old = db.execute('SELECT job_id FROM gateway_commands WHERE principal=? AND command_id=?', (principal, request['request_id'])).fetchone()
        if old[0] != job['id']:
            raise ExecutionBindingError('COMMAND_ID_CONFLICT', 'The command identity already belongs to another admitted request.', 409)
        db.execute('UPDATE gateway_conversations SET latest_job_id=?,updated_at=? WHERE id=?', (job['id'], job['created_at'], conversation_id))
        previous = db.execute('SELECT sequence,role,content FROM gateway_conversation_messages WHERE conversation_id=? AND job_id<>? '
                              'ORDER BY sequence DESC LIMIT 12', (conversation_id, job['id'])).fetchall()
        if previous:
            job['conversation_context'] = {'messages': [{'sequence': row[0], 'role': row[1], 'content': row[2][:8192]} for row in reversed(previous)],
                                           'bounded_history': True, 'history_complete': False}
        return conversation_id

    @staticmethod
    def engineering_actions(state, scope, binding_available=True):
        actions = ['open']
        if state not in {'COMPLETED', 'CANCELLED'}:
            actions.append('cancel')
        if state == 'MERGE_READY' and scope == 'implement':
            actions.extend(['approve', 'request_changes'])
        if state in ENGINEERING_PAUSED and binding_available:
            actions.append('follow_up')
        return actions

    def record_engineering(self, db, job, state, revision, lease=None, *, legacy=False):
        request, principal = job['request'], job['principal']
        conversation_id = job.get('conversation_id')
        if not conversation_id:
            existing = db.execute("SELECT conversation_id FROM gateway_continuity_jobs WHERE kind='engineering' AND job_id=?", (job['id'],)).fetchone()
            if existing:
                conversation_id = existing[0]
            else:
                conversation_id = self.admit_engineering(db, job, fingerprint(request), legacy=True)
        scheduling = ('completed' if state == 'COMPLETED' else 'cancelled' if state == 'CANCELLED'
                      else 'running' if lease else 'waiting' if state in {'MERGE_READY', 'NEEDS_INPUT', 'CI'} else 'queued')
        scope = request.get('scope', 'implement')
        item = dict(self.binding(job), job_id=job['id'], kind='engineering', conversation_id=conversation_id,
                    revision=revision, state=state, phase=state, scheduling_state=scheduling, scope=scope,
                    title=request['objective'][:200], message=job['message'], accepted_at_ms=millis(job['created_at']),
                    working_branch=request['branch'], base=request['base'],
                    started_at_ms=millis(job.get('started_at')), updated_at_ms=millis(job['updated_at']),
                    finished_at_ms=millis(job['updated_at']) if state in {'COMPLETED', 'CANCELLED'} else None,
                    queue_position=None, permitted_actions=self.engineering_actions(state, scope, bool(job.get('execution_binding'))),
                    source_sha=request.get('source_sha'), command_id=request['request_id'],
                    blocked_reason=job.get('data', {}).get('blocked_reason'))
        self._save_item(db, principal, item)
        if revision > 0:
            terminal = state in {'COMPLETED', 'CANCELLED'}
            report = job.get('data', {}).get('final_report') if state in ENGINEERING_PAUSED else None
            self.append(db, conversation_id, job['id'], ('final:' + job['id']) if terminal else 'engineering:' + job['id'] + ':' + str(revision),
                        'assistant', 'final' if terminal else 'status', report or job['message'], job['updated_at'])

    @staticmethod
    def _save_item(db, principal, item):
        db.execute('INSERT INTO gateway_continuity_jobs(kind,job_id,principal,conversation_id,public_json) VALUES(?,?,?,?,?) '
                   'ON CONFLICT(kind,job_id) DO UPDATE SET public_json=excluded.public_json',
                   (item['kind'], item['job_id'], principal, item['conversation_id'], canonical(item)))

    def record_ordinary(self, db, public, result=None, event=None, *, principal=None, legacy=False, transcript=None):
        self.schema(db)
        job_id = public['job_id']
        existing = db.execute("SELECT principal,conversation_id FROM gateway_continuity_jobs WHERE kind='chat' AND job_id=?", (job_id,)).fetchone()
        principal = existing[0] if existing else principal or public.get('principal_id')
        if not principal:
            return  # Unknown legacy ownership must never be assigned to the reader.
        conversation_id = existing[1] if existing else 'conv_' + fingerprint([principal, 'chat', job_id])[:32]
        now = public.get('updated_at') or self.store.now()
        created = public.get('created_at') or now
        retain_text = public.get('conversation_retention') == 'full'
        complete = retain_text and transcript is not None and not legacy
        title = str(public.get('task_text') or 'Gateway conversation')[:200] if retain_text else 'Gateway conversation'
        limitation = 'legacy_snapshot_only' if legacy else None if complete else (
            'ordinary_request_history_not_retained' if retain_text else 'conversation_retention_disabled')
        binding = {'profile_id': public.get('profile_id') or '', 'profile_name': public.get('profile_name') or '',
                   'model': public.get('model') or '', 'repository': public.get('repository') or '', 'branch': public.get('branch') or '',
                   'source_branch': public.get('branch') or '', 'source_sha': public.get('source_sha'),
                   'execution_binding': None}
        db.execute('INSERT OR IGNORE INTO gateway_conversations(id,principal,binding,title,created_at,updated_at,latest_job_id,history_complete,history_limitation) VALUES(?,?,?,?,?,?,?,?,?)',
                   (conversation_id, principal, canonical(binding), title,
                    created, now, job_id, int(complete), limitation))
        if retain_text and transcript is not None:
            for index, message in enumerate(transcript):
                if not isinstance(message, dict) or message.get('role') not in {'user', 'assistant', 'tool'}:
                    continue
                role = message['role']
                key = 'input:' + job_id + ':' + str(index) + ':' + fingerprint(message)
                kind = 'user' if role == 'user' else 'tool_result' if role == 'tool' else 'assistant'
                if role == 'assistant' and message.get('tool_calls'):
                    key, kind = 'handoff:' + job_id + ':' + fingerprint(message), 'tool_request'
                content = message.get('content')
                if not isinstance(content, str) or message.get('tool_calls'):
                    content = canonical(message)
                self.append(db, conversation_id, job_id, key, role, kind, content, now, input_message=True)
        elif retain_text and public.get('task_text') and not existing:
            self.append(db, conversation_id, job_id, 'user:' + job_id, 'user', 'user', public['task_text'], created)
        status = str(public.get('status') or 'unknown')
        scheduling = public.get('scheduling_state') or ('waiting' if status == 'waiting_for_client_tool' else status)
        item = dict(binding, job_id=job_id, kind='chat', conversation_id=conversation_id,
                    revision=int(public.get('last_sequence') or 0), state=status, phase=public.get('phase') or public.get('stage') or status,
                    scheduling_state=scheduling, scope='chat', title=title,
                    message=public.get('message') if retain_text else None, accepted_at_ms=millis(created), started_at_ms=millis(public.get('started_at')),
                    updated_at_ms=millis(now), finished_at_ms=millis(public.get('finished_at')), queue_position=None,
                    permitted_actions=['open'] + ([] if status in ORDINARY_TERMINAL else ['cancel']),
                    working_branch=public.get('branch') or '', original_profile_known=bool(binding['profile_id']),
                    original_profile_limitation=None if binding['profile_id'] else 'unknown_original_profile')
        self._save_item(db, principal, item)
        if not retain_text:
            # Keep the existing ordinary result journal policy. Do not create a
            # second transcript (including task titles, progress or tool text)
            # when the caller did not explicitly opt in to conversation history.
            return
        if isinstance(event, dict) and type(event.get('sequence')) is int and event.get('message'):
            self.append(db, conversation_id, job_id, 'chat:' + job_id + ':' + str(event['sequence']),
                        'assistant', 'status', event['message'], now)
        if status in ORDINARY_TERMINAL:
            content = None
            if isinstance(result, dict):
                choices = result.get('choices') or []
                if choices and isinstance(choices[0], dict):
                    content = (choices[0].get('message') or {}).get('content')
            self.append(db, conversation_id, job_id, 'final:' + job_id, 'assistant', 'final', content or public.get('message') or status, now)
        elif status == 'waiting_for_client_tool' and isinstance(result, dict):
            choices = result.get('choices') or []
            message = choices[0].get('message') if choices and isinstance(choices[0], dict) else None
            if isinstance(message, dict):
                self.append(db, conversation_id, job_id, 'handoff:' + job_id + ':' + fingerprint(message),
                            'assistant', 'tool_request', message, now, input_message=True)

    def enrich_job(self, job):
        def read(db):
            row = db.execute("SELECT public_json FROM gateway_continuity_jobs WHERE kind='engineering' AND job_id=? AND principal=?",
                             (job['id'], job['principal'])).fetchone()
            return self._item(db, job['principal'], row[0]) if row else None
        return self.store.tx(read)

    def _item(self, db, principal, raw):
        item = json.loads(raw)
        row = self._conversation(db, principal, item['conversation_id'])
        item.update(gateway_id=self.installation(db), principal_id=principal, server_time_ms=millis(self.store.now()),
                    conversation_revision=row[2], latest_message_sequence=row[3], history_complete=bool(row[7]), history_limitation=row[8])
        return item

    def catalog(self, principal, cursor=None, limit=50, kinds=('chat', 'engineering')):
        limit = page_limit(limit)
        kinds = tuple(sorted(set(kinds) & {'chat', 'engineering'}))
        if not kinds:
            raise ExecutionBindingError('CONTINUITY_SCOPE_REQUIRED', 'Jobs or engineering read access is required.', 403)
        placeholders = ','.join('?' for _ in kinds)
        def read(db):
            maximum = db.execute('SELECT COALESCE(MAX(catalog_sequence),0) FROM gateway_continuity_jobs WHERE principal=? AND kind IN (' + placeholders + ')', (principal, *kinds)).fetchone()[0]
            position = self._read_cursor(db, principal, 'catalog', cursor) if cursor else {'through': maximum, 'before': maximum + 1, 'kinds': list(kinds)}
            if position.get('kinds') != list(kinds):
                raise ExecutionBindingError('CONTINUITY_CURSOR_SCOPE_CHANGED', 'Refresh the catalog after account permissions change.', 409)
            if any(type(position.get(key)) is not int or position[key] < 0 for key in ('through', 'before')):
                raise ExecutionBindingError('CONTINUITY_CURSOR_INVALID', 'Invalid catalog cursor.', 409)
            rows = db.execute('SELECT catalog_sequence,public_json FROM gateway_continuity_jobs WHERE principal=? AND kind IN (' + placeholders + ') AND catalog_sequence<=? AND catalog_sequence<? '
                              'ORDER BY catalog_sequence DESC LIMIT ?', (principal, *kinds, position['through'], position['before'], limit + 1)).fetchall()
            more = len(rows) > limit
            page = rows[:limit]
            next_cursor = self._cursor(db, principal, 'catalog', through=position['through'], before=page[-1][0], kinds=list(kinds)) if more else None
            return {'gateway_id': self.installation(db), 'principal_id': principal, 'server_time_ms': millis(self.store.now()),
                    'items': [self._item(db, principal, row[1]) for row in page], 'next_cursor': next_cursor, 'has_more': more}
        return self.store.tx(read)

    def conversation(self, principal, conversation_id):
        def read(db):
            row = self._conversation(db, principal, conversation_id)
            item = db.execute('SELECT public_json FROM gateway_continuity_jobs WHERE principal=? AND conversation_id=? AND job_id=?',
                              (principal, conversation_id, row[6])).fetchone()
            latest = self._item(db, principal, item[0]) if item else {}
            actions = latest.get('permitted_actions', ['open'])
            return dict(json.loads(row[0]), gateway_id=self.installation(db), principal_id=principal,
                        conversation_id=conversation_id, revision=row[2], title=row[1], latest_job_id=row[6],
                        message_watermark=row[3], replay_cursor=self._cursor(db, principal, 'messages', conversation_id=conversation_id, after=0),
                        history_complete=bool(row[7]), history_limitation=row[8], server_time_ms=millis(self.store.now()),
                        permitted_actions=actions, kind=latest.get('kind'), latest_job=latest or None,
                        working_branch=latest.get('working_branch'), base=latest.get('base'),
                        original_profile_known=bool(json.loads(row[0]).get('profile_id')),
                        original_profile_limitation=None if json.loads(row[0]).get('profile_id') else 'unknown_original_profile')
        return self.store.tx(read)

    def messages(self, principal, conversation_id, cursor=None, limit=100):
        limit = page_limit(limit, 100)
        def read(db):
            row = self._conversation(db, principal, conversation_id)
            position = self._read_cursor(db, principal, 'messages', cursor) if cursor else {'conversation_id': conversation_id, 'after': 0}
            after = position.get('after')
            if position.get('conversation_id') != conversation_id or type(after) is not int or after < 0 or after > row[3]:
                raise ExecutionBindingError('CONTINUITY_CURSOR_INVALID', 'Invalid message cursor.', 409)
            entries = db.execute('SELECT sequence,message_id,job_id,role,kind,content,created_at,logical_message_id,part_index,part_count,content_sha256 '
                                 'FROM gateway_conversation_messages WHERE conversation_id=? AND sequence>? ORDER BY sequence LIMIT ?',
                                 (conversation_id, after, limit + 1))
            page, items, page_bytes, more = [], [], 0, False
            for entry in entries:
                item = dict(sequence=entry[0], message_id=entry[1], conversation_id=conversation_id, job_id=entry[2],
                            role=entry[3], kind=entry[4], content=entry[5], created_at_ms=millis(entry[6]),
                            logical_message_id=entry[7] or entry[1], part_index=entry[8], part_count=entry[9],
                            content_sha256=entry[10] or hashlib.sha256(entry[5].encode('utf-8')).hexdigest())
                size = len(json.dumps(item, ensure_ascii=False, separators=(',', ':')).encode('utf-8')) + 1
                if len(page) == limit or page and page_bytes + size > MESSAGE_PAGE_BYTES:
                    more = True
                    break
                if size > MESSAGE_PAGE_BYTES:
                    raise ExecutionBindingError('CONTINUITY_LEGACY_MESSAGE_TOO_LARGE', 'This legacy message requires an explicit storage upgrade before bounded replay.', 409)
                page.append(entry)
                items.append(item)
                page_bytes += size
            earliest = db.execute('SELECT MIN(sequence) FROM gateway_conversation_messages WHERE conversation_id=?', (conversation_id,)).fetchone()[0]
            expected = after + 1
            missing = []
            for entry in page:
                if entry[0] != expected:
                    missing.append({'first_sequence': expected, 'last_sequence': entry[0] - 1})
                expected = entry[0] + 1
            if not more and expected <= row[3]:
                missing.append({'first_sequence': expected, 'last_sequence': row[3]})
            gap = {'missing_ranges': missing} if missing else None
            if gap:
                db.execute("UPDATE gateway_conversations SET history_complete=0,history_limitation='retention_gap' WHERE id=?",
                           (conversation_id,))
            retained_after = row[3] if gap and not more else page[-1][0] if page else after
            return {'gateway_id': self.installation(db), 'principal_id': principal, 'conversation_id': conversation_id,
                    'revision': row[2], 'items': items,
                    'next_cursor': self._cursor(db, principal, 'messages', conversation_id=conversation_id, after=retained_after),
                    'has_more': more, 'earliest_sequence': earliest or 0, 'latest_sequence': row[3],
                    'history_complete': bool(row[7]) and gap is None, 'history_limitation': 'retention_gap' if gap else row[8], 'gap': gap is not None,
                    'gap_details': gap,
                    'server_time_ms': millis(self.store.now())}
        return self.store.tx(read)

    def command(self, principal, command_id):
        identifier(command_id, 'command_id')
        def read(db):
            row = db.execute('SELECT conversation_id,job_id,accepted_at,accepted_revision FROM gateway_commands WHERE principal=? AND command_id=?', (principal, command_id)).fetchone()
            if row is None:
                raise ExecutionBindingError('COMMAND_NOT_FOUND', 'Command not found for this account.', 404)
            conversation = self._conversation(db, principal, row[0])
            job_row = db.execute('SELECT data,state,revision FROM engineering_jobs WHERE id=? AND principal=?', (row[1], principal)).fetchone()
            job_projection = db.execute("SELECT public_json FROM gateway_continuity_jobs WHERE kind='engineering' AND job_id=? AND principal=?", (row[1], principal)).fetchone()
            job = None
            if job_row is not None:
                from v14.engineering import public_job
                job = public_job(self.store.decode(job_row))
                job['server_time_ms'] = millis(self.store.now())
                if job_projection:
                    for key, value in self._item(db, principal, job_projection[0]).items():
                        if key not in job or job[key] is None:
                            job[key] = value
            return {'command_id': command_id, 'status': 'accepted', 'gateway_id': self.installation(db), 'principal_id': principal,
                    'conversation_id': row[0], 'job_id': row[1], 'revision': row[3], 'conversation_revision': conversation[2], 'accepted_at_ms': millis(row[2]),
                    'server_time_ms': millis(self.store.now()), 'job': job}
        return self.store.tx(read)

    def followup_source(self, principal, conversation_id, command):
        if not isinstance(command, dict) or set(command) != {'command_id', 'expected_revision', 'content', 'scope'}:
            raise ExecutionBindingError('COMMAND_INVALID', 'Supply command_id, expected_revision, content and explicit scope.', 400)
        identifier(command['command_id'], 'command_id')
        if type(command['expected_revision']) is not int or command['expected_revision'] < 0 or command['scope'] not in {'read', 'plan', 'implement'}:
            raise ExecutionBindingError('COMMAND_INVALID', 'Supply an exact revision and supported explicit scope.', 400)
        if not isinstance(command['content'], str) or not 1 <= len(command['content'].strip()) <= 16000:
            raise ExecutionBindingError('COMMAND_INVALID', 'Follow-up content must be nonempty and bounded.', 400)
        command_hash = fingerprint({'conversation_id': conversation_id, **command})
        def read(db):
            existing = db.execute('SELECT digest,job_id FROM gateway_commands WHERE principal=? AND command_id=?', (principal, command['command_id'])).fetchone()
            if existing:
                if existing[0] != command_hash:
                    raise ExecutionBindingError('COMMAND_ID_CONFLICT', 'This command identity was used with different content or scope.', 409)
                return None, existing[1], command_hash
            row = self._conversation(db, principal, conversation_id)
            if row[2] != command['expected_revision']:
                raise ExecutionBindingError('CONVERSATION_REVISION_CHANGED', 'Refresh the conversation before submitting this follow-up.', 409)
            item = db.execute("SELECT public_json FROM gateway_continuity_jobs WHERE kind='engineering' AND job_id=? AND principal=?", (row[6], principal)).fetchone()
            if item is None or 'follow_up' not in json.loads(item[0]).get('permitted_actions', []):
                raise ExecutionBindingError('FOLLOW_UP_NOT_AVAILABLE', 'This job must reach an input or terminal checkpoint before a follow-up can be admitted.', 409)
            previous = db.execute('SELECT data FROM engineering_jobs WHERE id=? AND principal=?', (row[6], principal)).fetchone()
            if previous is None:
                raise ExecutionBindingError('FOLLOW_UP_NOT_AVAILABLE', 'The original execution binding is unavailable.', 409)
            request = dict(json.loads(previous[0])['request'], request_id=command['command_id'], objective=command['content'],
                           scope=command['scope'], conversation_id=conversation_id)
            return request, None, command_hash
        return self.store.tx(read)

    def backfill(self):
        """Project retained owned history without inventing a complete transcript."""
        def engineering(db):
            rows = db.execute('SELECT data,state,revision,lease FROM engineering_jobs')
            for raw, state, revision, lease in rows:
                job = json.loads(raw)
                self.record_engineering(db, job, state, revision, lease, legacy=True)
        self.store.tx(engineering)
        runtime = self.store.runtime
        devices = getattr(runtime, 'device_store', None)
        if devices is None:
            return
        # Ownership lives in the existing auth journal, never in supplied headers.
        with devices.connect() as auth:
            cursor = auth.execute('SELECT job_id,device_id FROM ownership ORDER BY job_id')
            while True:
                batch = cursor.fetchmany(100)
                if not batch:
                    break
                def ordinary(db):
                    for job_id, owner in batch:
                        row = db.execute('SELECT public_json,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
                        if row:
                            self.record_ordinary(db, json.loads(row[0]), json.loads(row[1]) if row[1] else None, principal=owner, legacy=True)
                self.store.tx(ordinary)
