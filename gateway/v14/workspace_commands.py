"""Revision-bound workspace commands consumed by the existing engineering owner.

The durable command identity is admitted before dispatch. Its outcome commits in
the same SQLite transaction as the leased job checkpoint. A lost executing owner
produces UNKNOWN; it never silently repeats an external operation.
"""
import hashlib
import json
import re

from v14.execution_binding import canonical
from v14.workspace_board import MAINTENANCE_ACTIONS, available_actions

TERMINAL = {'COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'UNKNOWN'}
IDENTIFIER = re.compile(r'[A-Za-z0-9_-]{1,128}')


class WorkspaceCommands:
    def __init__(self, service):
        self.service = service
        self.store = service.store

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS engineering_workspace_commands ('
                   'sequence INTEGER PRIMARY KEY AUTOINCREMENT,job_id TEXT NOT NULL,principal TEXT NOT NULL,'
                   'command_id TEXT NOT NULL,source_sha TEXT NOT NULL,revision INTEGER NOT NULL,'
                   'digest TEXT NOT NULL,action TEXT NOT NULL,payload_ref TEXT NOT NULL,status TEXT NOT NULL,'
                   'result_ref TEXT,reason TEXT,created_at REAL NOT NULL,updated_at REAL NOT NULL,'
                   'UNIQUE(job_id,command_id))')
        db.execute('CREATE INDEX IF NOT EXISTS workspace_commands_owner ON '
                   'engineering_workspace_commands(job_id,principal,status,sequence)')
        db.execute('CREATE TABLE IF NOT EXISTS engineering_review_drafts ('
                   'job_id TEXT NOT NULL,principal TEXT NOT NULL,source_sha TEXT NOT NULL,draft_id TEXT NOT NULL,'
                   'reference TEXT NOT NULL,updated_at REAL NOT NULL,PRIMARY KEY(job_id,source_sha,draft_id))')

    @staticmethod
    def _projection(row):
        return {'schema_version': 1, 'job_id': row[0], 'command_id': row[1], 'head_sha': row[2],
                'revision': row[3], 'action': row[4], 'status': row[5],
                'result_reference': json.loads(row[6]) if row[6] else None,
                'reason': row[7], 'created_at': row[8], 'updated_at': row[9]}

    @staticmethod
    def maintenance_pending(db, job_id):
        WorkspaceCommands.schema(db)
        placeholders = ','.join('?' for _ in MAINTENANCE_ACTIONS)
        return db.execute("SELECT 1 FROM engineering_workspace_commands WHERE job_id=? AND status IN ('QUEUED','RUNNING') "
                          'AND action IN (' + placeholders + ') LIMIT 1', (job_id, *sorted(MAINTENANCE_ACTIONS))).fetchone() is not None

    def _read(self, db, job, command_id):
        self.schema(db)
        row = db.execute('SELECT job_id,command_id,source_sha,revision,action,status,result_ref,reason,created_at,updated_at '
                         'FROM engineering_workspace_commands WHERE job_id=? AND principal=? AND command_id=?',
                         (job['id'], job['principal'], command_id)).fetchone()
        return self._projection(row) if row else None

    def list(self, job):
        def read(db):
            self.schema(db)
            return [self._projection(row) for row in db.execute(
                'SELECT job_id,command_id,source_sha,revision,action,status,result_ref,reason,created_at,updated_at '
                'FROM engineering_workspace_commands WHERE job_id=? AND principal=? ORDER BY sequence DESC LIMIT 32',
                (job['id'], job['principal']))]
        return self.store.tx(read)

    def read(self, principal, job_id, command_id):
        if not isinstance(command_id, str) or not IDENTIFIER.fullmatch(command_id):
            raise ValueError('Invalid workspace command identity')
        job = self.service.evidence_access(principal, job_id)
        if job['state'] in {'CANCELLED', 'CANCELLING', 'FAILED', 'COMPLETED'}:
            def retire(db):
                self.schema(db)
                db.execute("UPDATE engineering_workspace_commands SET status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'UNKNOWN' END,"
                           "reason='PARENT_STOPPED',updated_at=? WHERE job_id=? AND principal=? AND status IN ('QUEUED','RUNNING')",
                           (self.store.now(), job_id, principal))
            self.store.tx(retire)
        result = self.store.tx(lambda db: self._read(db, job, command_id))
        if result is None:
            return {'schema_version': 1, 'job_id': job_id, 'command_id': command_id,
                    'status': 'NOT_FOUND', 'confirmed_absent': True, 'head_sha': job['data'].get('head_sha'),
                    'revision': job['revision']}
        reference = result['result_reference']
        if reference and reference.get('byte_length', 98305) <= 98304:
            result['result'] = self.service.artifacts.json_value(job, reference)
        else:
            result['result'] = None
        self.service.evidence_access(principal, job_id)
        return result

    def enqueue(self, principal, job_id, payload):
        if (not isinstance(payload, dict) or set(payload) != {'command_id', 'revision', 'head_sha', 'action', 'arguments'} or
                not isinstance(payload.get('command_id'), str) or not IDENTIFIER.fullmatch(payload['command_id']) or
                type(payload.get('revision')) is not int or not isinstance(payload.get('arguments'), dict)):
            raise ValueError('Use a command identity, exact revision, source SHA, action and argument object')
        raw = canonical(payload)
        if len(raw.encode()) > 65536:
            raise ValueError('Workspace command exceeds 64 KiB')
        if set(payload['arguments']) & {'action', 'head_sha', 'principal', 'principal_id', 'job_id', 'revision'}:
            raise ValueError('Workspace arguments cannot override their admitted identity')
        if payload['arguments'].get('request_id', payload['command_id']) != payload['command_id']:
            raise ValueError('The request identity must match its durable workspace command')
        job = self.service.evidence_access(principal, job_id)
        digest = hashlib.sha256(raw.encode()).hexdigest()
        # Artifact reads acquire their own store transaction. Hydrate before the
        # writer lock, then compare the exact retained tree representation in the
        # admission transaction so a concurrent checkpoint cannot substitute it.
        draft_tree, draft_tree_digest = None, None
        if payload['action'] == 'review_draft' and 'path' in payload['arguments']:
            retained_tree = job['data'].get('tree') or {}
            draft_tree_digest = hashlib.sha256(canonical(retained_tree).encode()).hexdigest()
            draft_tree = (self.service.artifacts.json_value(job, retained_tree)
                          if isinstance(retained_tree, dict) and 'result_ref' in retained_tree else retained_tree)
            if not isinstance(draft_tree, dict):
                raise ValueError('Review draft requires the retained source tree')
        def save(db):
            self.schema(db)
            existing = db.execute('SELECT digest FROM engineering_workspace_commands WHERE job_id=? AND command_id=?',
                                  (job_id, payload['command_id'])).fetchone()
            if existing:
                if existing[0] != digest:
                    raise ValueError('Workspace command identity was reused with different arguments')
                return self._read(db, job, payload['command_id'])
            row = db.execute('SELECT data,state,revision FROM engineering_jobs WHERE id=? AND principal=?', (job_id, principal)).fetchone()
            current = self.store.decode(row)
            if (current is None or current['revision'] != payload['revision'] or
                    current['data'].get('head_sha') != payload['head_sha'] or
                    payload['head_sha'] != job['data'].get('head_sha')):
                raise ValueError('Workspace revision or source changed; refresh before applying this command')
            if payload['action'] not in available_actions(current):
                raise ValueError('This workspace action is not available at the current job checkpoint')
            total, pending = db.execute("SELECT COUNT(*),COALESCE(SUM(status IN ('QUEUED','RUNNING')),0) "
                                        'FROM engineering_workspace_commands WHERE job_id=?', (job_id,)).fetchone()
            if total >= 256 or pending >= 8:
                raise ValueError('The workspace command allowance is full; inspect retained work')
            reference = self.service.artifacts.put_json(current, payload, 'workspace-command', transaction=db)
            now = self.store.now()
            status, result = 'QUEUED', None
            if payload['action'] == 'review_draft':
                if (draft_tree_digest is not None and draft_tree_digest !=
                        hashlib.sha256(canonical(current['data'].get('tree') or {}).encode()).hexdigest()):
                    raise ValueError('Review source tree changed; refresh before saving this draft')
                result = self._save_draft(db, current, payload['arguments'], draft_tree)
                status = 'COMPLETED'
            db.execute('INSERT INTO engineering_workspace_commands '
                       '(job_id,principal,command_id,source_sha,revision,digest,action,payload_ref,status,result_ref,created_at,updated_at) '
                       'VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                       (job_id, principal, payload['command_id'], payload['head_sha'], payload['revision'], digest,
                        payload['action'], canonical(reference), status, canonical(result) if result else None, now, now))
            if status == 'QUEUED' and payload['action'] in MAINTENANCE_ACTIONS:
                db.execute("UPDATE engineering_jobs SET next_at=MIN(next_at,?) WHERE id=? AND state IN ('NEEDS_INPUT','MERGE_READY')",
                           (now, job_id))
            return self._read(db, current, payload['command_id'])
        result = self.store.tx(save)
        self.service.wake.set()
        return result

    def _save_draft(self, db, job, args, tree=None):
        if (set(args) - {'draft_id', 'body', 'check_id', 'path', 'line'} or
                not isinstance(args.get('draft_id'), str) or not re.fullmatch('[A-Za-z0-9_-]{1,64}', args['draft_id']) or
                not isinstance(args.get('body'), str) or not 1 <= len(args['body'].strip()) <= 4000):
            raise ValueError('Use a named review draft and 1–4000 characters of review text')
        check = args.get('check_id')
        if check is not None and (not isinstance(check, str) or check not in job['data'].get('workspace_checks', {}) or
                                 job['data']['workspace_checks'][check].get('source_sha') != job['data']['head_sha']):
            raise ValueError('Review draft requires a candidate at this exact source revision')
        if 'line' in args and (type(args['line']) is not int or args['line'] < 1 or 'path' not in args):
            raise ValueError('A review line needs an observed path and positive line number')
        if 'path' in args:
            from v14.engineering_backend import safe_path
            path = safe_path(args['path'])
            if not isinstance(tree, dict) or path not in tree:
                raise ValueError('Review path is not present in the retained source tree')
        count = db.execute('SELECT COUNT(*) FROM engineering_review_drafts WHERE job_id=?', (job['id'],)).fetchone()[0]
        exists = db.execute('SELECT 1 FROM engineering_review_drafts WHERE job_id=? AND source_sha=? AND draft_id=?',
                            (job['id'], job['data']['head_sha'], args['draft_id'])).fetchone()
        if count >= 64 and not exists:
            raise ValueError('Retain at most 64 review drafts per job')
        value = dict(args, head_sha=job['data']['head_sha'], job_id=job['id'], posted=False,
                     status='DRAFT', updated_at=self.store.now())
        reference = self.service.artifacts.put_json(job, value, 'review-draft', transaction=db)
        db.execute('INSERT INTO engineering_review_drafts VALUES(?,?,?,?,?,?) ON CONFLICT(job_id,source_sha,draft_id) '
                   'DO UPDATE SET reference=excluded.reference,updated_at=excluded.updated_at',
                   (job['id'], job['principal'], job['data']['head_sha'], args['draft_id'], canonical(reference), self.store.now()))
        return reference

    def drafts(self, job):
        def read(db):
            self.schema(db)
            return [json.loads(row[0]) for row in db.execute('SELECT reference FROM engineering_review_drafts '
                    'WHERE job_id=? AND principal=? AND source_sha=? ORDER BY updated_at DESC LIMIT 64',
                    (job['id'], job['principal'], job['data'].get('head_sha')))]
        return [self.service.artifacts.json_value(job, reference) for reference in self.store.tx(read)]

    def process(self, job, *, maintenance=False):
        """Consume at most one command inside the parent's existing execution lease."""
        self.store.assert_claim(job)
        def begin(db):
            self.schema(db)
            row = db.execute("SELECT command_id,source_sha,payload_ref,status FROM engineering_workspace_commands "
                             "WHERE job_id=? AND principal=? AND status IN ('QUEUED','RUNNING') ORDER BY sequence LIMIT 1",
                             (job['id'], job['principal'])).fetchone()
            if row is None:
                return None
            owner = db.execute('SELECT revision,lease FROM engineering_jobs WHERE id=? AND principal=?',
                               (job['id'], job['principal'])).fetchone()
            if owner is None or tuple(owner) != (job['revision'], job['_execution_lease']):
                raise PermissionError('Workspace execution owner changed')
            db.execute("UPDATE engineering_workspace_commands SET status='RUNNING',updated_at=? WHERE job_id=? AND command_id=?",
                       (self.store.now(), job['id'], row[0]))
            return row
        row = self.store.tx(begin)
        if row is None:
            return None
        command, source, reference, previous = row
        status, reason, result = 'COMPLETED', None, None
        if previous == 'RUNNING':
            status, reason = 'UNKNOWN', 'INTERRUPTED_COMMAND_REQUIRES_RECONCILIATION'
        elif source != job['data'].get('head_sha'):
            status, reason = 'REJECTED', 'STALE_SOURCE'
        else:
            payload = self.service.artifacts.json_value(job, json.loads(reference))
            if payload['action'] not in available_actions(job) or maintenance and payload['action'] not in MAINTENANCE_ACTIONS:
                status, reason = 'REJECTED', 'CHECKPOINT_CHANGED'
            else:
                try:
                    if maintenance:
                        # Readiness/status/cancel do not invoke the parent's model
                        # or resume a paused task. Refresh its existing evidence authority.
                        self.service.evidence_access(job['principal'], job['id'])
                    else:
                        self.service.validate_execution(job)
                    from v14.engineering_workspace import act
                    action = dict(payload['arguments'], action=payload['action'], head_sha=source)
                    # The queue owns the durable external invocation identity.
                    action['request_id'] = command
                    # This provenance exists only while executing an authenticated
                    # user command. Model-generated JSON cannot grant delegation.
                    job['_workspace_user_command_id'] = command
                    try:
                        result = act(self.service.backend, job, action)
                    finally:
                        job.pop('_workspace_user_command_id', None)
                    job['data'].setdefault('workspace_receipts', []).append(result)
                    del job['data']['workspace_receipts'][:-32]
                except (ValueError, TypeError, KeyError, PermissionError) as error:
                    status, reason = 'REJECTED', getattr(error, 'code', 'WORKSPACE_INPUT_REJECTED')
                except Exception as error:
                    # Do not leak provider credentials or assume a timed-out write failed.
                    undispatched = getattr(error, 'dispatched', None) is False
                    status = 'REJECTED' if undispatched else 'UNKNOWN'
                    reason = getattr(error, 'code', 'WORKSPACE_INPUT_REJECTED' if undispatched else 'WORKSPACE_OUTCOME_UNCONFIRMED')
        result_ref = self.service.artifacts.put_json(job, result, 'workspace-command-result') if result is not None else None
        completion = {'command_id': command, 'status': status, 'reason': reason, 'result_reference': result_ref}
        job['_workspace_command_completion'] = completion
        return dict(completion, requires_attention=status == 'UNKNOWN')

    @staticmethod
    def complete_transaction(db, job, now):
        completion = job.get('_workspace_command_completion')
        if completion is None:
            return
        WorkspaceCommands.schema(db)
        db.execute("UPDATE engineering_workspace_commands SET status=?,result_ref=?,reason=?,updated_at=? "
                   "WHERE job_id=? AND principal=? AND command_id=? AND status='RUNNING'",
                   (completion['status'], canonical(completion['result_reference']) if completion['result_reference'] else None,
                    completion['reason'], now, job['id'], job['principal'], completion['command_id']))

    @staticmethod
    def retain_pending_at_boundary(db, job, phase):
        """Late commands may not disappear when a model stage reaches publication."""
        WorkspaceCommands.schema(db)
        if phase not in {'COMMIT', 'MERGE_READY', 'MERGE', 'COMPLETED'}:
            return False
        settling = (job.get('_workspace_command_completion') or {}).get('command_id', '')
        return db.execute("SELECT 1 FROM engineering_workspace_commands WHERE job_id=? AND command_id!=? "
                          "AND status IN ('QUEUED','RUNNING') LIMIT 1", (job['id'], settling)).fetchone() is not None
