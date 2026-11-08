"""Transactional import of the superseded continuity-v1 journal.

The old physical/logical journal is retained as an archive. No second live
conversation owner is installed, and absent or damaged history is not invented.
"""
import hashlib
import json

from v14.engineering_scope import accepted_scope


def columns(db, table):
    return {row[1] for row in db.execute('PRAGMA table_info(' + table + ')')}


def prepare(db):
    conversation = columns(db, 'gateway_conversations')
    command = columns(db, 'gateway_commands')
    old = bool(conversation and 'client_key' not in conversation)
    if old and not {'message_sequence', 'latest_job_id', 'history_complete'} <= conversation:
        raise ValueError('Unrecognized saved conversation schema; the journal was not modified')
    if command and 'id' not in command and not {'command_id', 'digest', 'accepted_at'} <= command:
        raise ValueError('Unrecognized command schema; the journal was not modified')
    if old and command and 'id' in command or not old and command and 'command_id' in command:
        raise ValueError('Mixed conversation schemas require explicit reconciliation')
    if not old:
        return
    for source, target in (
        ('gateway_conversations', 'gateway_continuity_v1_conversations'),
        ('gateway_commands', 'gateway_continuity_v1_commands'),
        ('gateway_conversation_messages', 'gateway_continuity_v1_messages'),
        ('gateway_logical_messages', 'gateway_continuity_v1_logical_messages'),
        ('gateway_continuity_jobs', 'gateway_continuity_v1_jobs'),
    ):
        if columns(db, source):
            if columns(db, target):
                raise ValueError('Continuity archive already exists; explicit reconciliation is required')
            db.execute('ALTER TABLE ' + source + ' RENAME TO ' + target)


def migrate(db, gateway_id):
    if not columns(db, 'gateway_continuity_v1_conversations'):
        return
    if db.execute("SELECT 1 FROM gateway_installation WHERE key='continuity_v1_imported'").fetchone():
        return
    jobs = {}
    if columns(db, 'gateway_continuity_v1_jobs'):
        for row in db.execute('SELECT * FROM gateway_continuity_v1_jobs ORDER BY catalog_sequence'):
            item = json.loads(row['public_json'])
            kind = 'engineering' if row['kind'] == 'engineering' else 'generation'
            jobs[(row['conversation_id'], row['job_id'])] = (kind, item, row['principal'])
            db.execute('INSERT OR IGNORE INTO gateway_catalog_keys(job_kind,job_id) VALUES(?,?)', (kind, row['job_id']))
    commands = {}
    if columns(db, 'gateway_continuity_v1_commands'):
        commands = {(row['principal'], row['job_id']): dict(row) for row in db.execute('SELECT * FROM gateway_continuity_v1_commands')}
    for old in db.execute('SELECT * FROM gateway_continuity_v1_conversations ORDER BY created_at,id').fetchall():
        cid, principal, latest = old['id'], old['principal'], old['latest_job_id']
        kind, item, owner = jobs.get((cid, latest), ('engineering' if str(latest).startswith('eng_') else 'generation', {}, principal))
        if owner != principal:
            raise ValueError('Continuity ownership mismatch; automatic import stopped')
        binding = json.loads(old['binding'])
        binding.update(source_branch=binding.get('source_branch') or binding.get('branch'),
                       working_branch=item.get('working_branch') or binding.get('branch'), base=item.get('base'))
        if kind == 'engineering' and columns(db, 'engineering_jobs'):
            previous = db.execute('SELECT data FROM engineering_jobs WHERE id=? AND principal=?', (latest, principal)).fetchone()
            if previous:
                request = json.loads(previous[0])['request']
                binding.update(base=request['base'], working_branch=request['branch'])
        binding['branch'] = binding['working_branch']
        complete, limitation = bool(old['history_complete']), old['history_limitation']
        coverage = None
        if columns(db, 'gateway_continuity_v1_messages'):
            coverage = db.execute('SELECT COUNT(*),MIN(sequence),MAX(sequence) FROM gateway_continuity_v1_messages WHERE conversation_id=?', (cid,)).fetchone()
        if coverage is None or coverage[0] != old['message_sequence'] or coverage[0] and (coverage[1] != 1 or coverage[2] != old['message_sequence']):
            complete, limitation = False, 'retention_gap'
        db.execute('INSERT OR IGNORE INTO gateway_conversations(id,principal,client_key,binding,title,revision,created_at,updated_at,active_kind,active_job,history_complete,history_limitation) VALUES(?,?,?,?,?,0,?,?,?,?,?,?)',
                   (cid, principal, 'legacy:' + cid, json.dumps(binding), old['title'], old['created_at'], old['updated_at'], kind, latest, int(complete), limitation))
        db.execute('INSERT OR IGNORE INTO gateway_conversation_catalog(conversation_id) VALUES(?)', (cid,))
        logical = []
        if columns(db, 'gateway_continuity_v1_logical_messages'):
            logical = db.execute('SELECT * FROM gateway_continuity_v1_logical_messages WHERE conversation_id=? ORDER BY created_at,rowid', (cid,)).fetchall()
        if not logical:
            # Older unchunked records are actual retained text. Missing parts
            # remain a limitation; they are never filled with guessed content.
            physical = db.execute('SELECT * FROM gateway_continuity_v1_messages WHERE conversation_id=? ORDER BY sequence', (cid,)).fetchall() if coverage else []
            logical = [dict(value, logical_message_id=value.get('logical_message_id') or value['message_id']) for value in map(dict, physical) if value.get('part_count', 1) == 1]
        retained_ids = {value['logical_message_id'] for value in logical}
        if coverage:
            physical_columns = columns(db, 'gateway_continuity_v1_messages')
            logical_id = 'COALESCE(logical_message_id,message_id)' if 'logical_message_id' in physical_columns else 'message_id'
            for physical in db.execute('SELECT ' + logical_id + ',role,kind FROM gateway_continuity_v1_messages WHERE conversation_id=?', (cid,)):
                if physical[1] in {'user', 'assistant'} and physical[2] not in {'tool_request', 'tool_result'} and physical[0] not in retained_ids:
                    # Physical coverage alone does not prove that every actual
                    # visible logical message survived. Keep the archive, but
                    # never label a partial import as complete history.
                    complete, limitation = False, 'retention_gap'
        revision = 0
        for raw in logical:
            value = dict(raw)
            if value['role'] not in {'user', 'assistant'} or value['kind'] in {'tool_request', 'tool_result'}:
                continue
            content = value['content']
            checksum = value.get('content_sha256')
            if checksum and hashlib.sha256(content.encode('utf-8')).hexdigest() != checksum:
                complete, limitation = False, 'content_integrity_failed'
                continue
            if value['kind'] == 'final':
                try:
                    report = json.loads(content)
                except (ValueError, TypeError):
                    report = None
                if isinstance(report, dict) and isinstance(report.get('summary'), str):
                    content = report['summary']
            command = commands.get((principal, value['job_id']))
            turn = command['command_id'] if command else 'legacy:' + value['job_id']
            publication = cid + ':' + turn + (':assistant' if value['kind'] == 'final' else ':user' if value['kind'] == 'user' and value['logical_message_id'].endswith('user:' + value['job_id']) else ':history:' + value['logical_message_id'])
            revision += 1
            db.execute('INSERT OR IGNORE INTO gateway_messages(id,conversation_id,turn_id,role,content,status,revision,created_at,publication_id) VALUES(?,?,?,?,?,?,?,?,?)',
                       (value['logical_message_id'], cid, turn, value['role'], content, 'sent' if value['role'] == 'user' else 'completed', revision, value['created_at'], publication))
        db.execute('UPDATE gateway_conversations SET revision=?,history_complete=?,history_limitation=? WHERE id=?',
                   (max(revision, old['revision']), int(complete), limitation, cid))
    for (principal, job_id), old in commands.items():
        cid = old['conversation_id']
        conversation = db.execute('SELECT binding FROM gateway_conversations WHERE id=? AND principal=?', (cid, principal)).fetchone()
        if not conversation:
            raise ValueError('Retained command has no scoped conversation')
        receipt = dict(json.loads(conversation[0]), schema_version=1, gateway_id=gateway_id, principal_id=principal,
                       conversation_id=cid, command_id=old['command_id'], job_id=job_id, job_kind='engineering',
                       accepted_at=old['accepted_at'], request_fingerprint=old['digest'], detach_safe=True)
        db.execute('INSERT OR IGNORE INTO gateway_commands VALUES(?,?,?,?,?,?,?,?)',
                   (principal, old['command_id'], old['digest'], cid, 'engineering', job_id, json.dumps(receipt), old['accepted_at']))
    if columns(db, 'engineering_jobs'):
        for row in db.execute('SELECT id,principal,data FROM engineering_jobs').fetchall():
            job = json.loads(row['data'])
            request = job['request']
            if 'scope' in request:
                request['task_scope'] = accepted_scope(request)
                request.pop('scope')
            from v14.engineering import validate_request, digest
            request = validate_request(request)
            job['request'] = request
            command = commands.get((row['principal'], row['id']))
            if command:
                job.update(conversation_id=command['conversation_id'], command_id=command['command_id'], gateway_id=gateway_id,
                           principal_id=row['principal'], accepted_at=command['accepted_at'], detach_safe=True)
            if isinstance(job.get('data', {}).get('scope_result'), dict):
                job['data'].setdefault('answer', job['data']['scope_result'].get('answer', ''))
            db.execute('UPDATE engineering_jobs SET data=?,request_digest=? WHERE id=?', (json.dumps(job), digest(request), row['id']))
    if columns(db, 'gateway_jobs'):
        for (cid, job_id), (kind, item, principal) in jobs.items():
            if kind != 'generation':
                continue
            row = db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
            if not row:
                continue
            public = json.loads(row[0])
            if public.get('principal_id') and public['principal_id'] != principal:
                raise ValueError('Retained ordinary job ownership changed')
            public.update(conversation_id=cid, principal_id=principal, gateway_id=gateway_id,
                          profile_id=item.get('profile_id'), profile_name=item.get('profile_name'),
                          command_id='legacy:' + job_id, detach_safe=False)
            db.execute('UPDATE gateway_jobs SET public_json=? WHERE job_id=?', (json.dumps(public), job_id))
    db.execute("INSERT INTO gateway_installation VALUES('continuity_v1_imported','1')")
