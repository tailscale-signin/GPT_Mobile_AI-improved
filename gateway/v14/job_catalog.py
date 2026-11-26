"""A scoped read projection of both durable engines. No execution is initiated."""
from v14.engineering_recovery import continuous_progress, retry_summary
from v14.engineering_scope import accepted_scope
import asyncio
from itertools import islice
import json
import sqlite3
import time

from fastapi import Request
from fastapi.responses import JSONResponse
from v14.conversations import Conversations, ConversationConflict, QueueFull, CursorExpired, cursor_encode, cursor_decode, short_title
from v14.conversation_policy import verify_mutation_identity
from v14.engineering_batch_plan import summary as batch_summary
from v14.execution_binding import ExecutionBindingError


def generation_state(status, scheduling_state):
    status = str(status or 'unknown').lower()
    if status in {'running', 'resuming'} and scheduling_state == 'queued':
        status = 'queued'
    return ('needs_attention' if status in {'waiting_for_client_tool', 'needs_input', 'cancel_requested'} else
             'queued' if status == 'queued' else 'running' if status in {'running', 'resuming', 'cancelling'} else
             'cancelled' if status == 'canceled' else status)


def generation_summary(public, now):
    status = str(public.get('status', 'unknown')).lower()
    state = generation_state(status, public.get('scheduling_state'))
    return {'job_id': public['job_id'], 'job_kind': 'generation', 'conversation_id': public.get('conversation_id'),
            'title': public.get('title') or short_title(public.get('task_preview') or public.get('model')),
            'profile_id': public.get('profile_id'), 'profile_name': public.get('profile_name'), 'model': public.get('model'), 'repository': None, 'branch': None, 'base': None,
            'state': state, 'phase': public.get('stage', status), 'revision': public.get('last_sequence', 0),
            'generation': public.get('generation'), 'accepted_at': public.get('accepted_at', public.get('created_at')),
            'queued_at': public.get('queued_at'), 'started_at': public.get('started_at'), 'finished_at': public.get('finished_at'),
            'server_now': now, 'queue_reason': public.get('queue_reason') if state == 'queued' else None,
            'queue_position': None, 'result_available': bool(public.get('result_available')),
            'allowed_actions': ['open'] + (['cancel'] if state in {'running', 'queued', 'needs_attention'} else []),
            'transcript_available': bool(public.get('conversation_id')), 'cancellation_requested': bool(public.get('cancellation_requested')),
            'conversation_retention': public.get('conversation_retention'),
            'original_profile_known': bool(public.get('profile_id')), 'original_profile_limitation': None if public.get('profile_id') else 'unknown_original_profile'}


def engineering_state(phase, lease, provider_retry_at, now):
    awaiting_provider = type(provider_retry_at) in (float, int) and provider_retry_at > now
    return ('completed' if phase == 'COMPLETED' else 'cancelled' if phase == 'CANCELLED' else 'failed' if phase == 'FAILED' else
             'needs_attention' if phase in {'NEEDS_INPUT', 'MERGE_READY', 'CANCELLING'} else 'running' if lease and not awaiting_provider else 'queued')


def engineering_summary(job, lease, now):
    request, phase = job['request'], job['state']
    data = job.get('data', {})
    recovery = data.get('post_merge') or {}
    provider_retry_at = (job.get('provider_wait') or {}).get('retry_at')
    state = engineering_state(phase, lease, provider_retry_at, now)
    return {'job_id': job['id'], 'job_kind': 'engineering', 'conversation_id': job.get('conversation_id'),
            'title': job.get('title') or short_title(request.get('objective')), 'profile_id': request.get('profile_id'), 'profile_name': request.get('profile_name'),
            'model': request.get('model'), 'repository': request['repository'], 'branch': request['branch'], 'base': request['base'],
            'source_branch': request.get('source_branch', request['branch']), 'source_sha': request.get('source_sha'), 'working_branch': request['branch'],
            'task_scope': accepted_scope(request), 'cancellation_requested': phase == 'CANCELLING', 'state': state, 'phase': phase, 'revision': job['revision'],
            'accepted_at': job.get('accepted_at', job['created_at']), 'queued_at': job.get('queued_at', job['created_at']),
            'started_at': job.get('started_at'), 'finished_at': job.get('finished_at', job['updated_at'] if phase in {'COMPLETED', 'CANCELLED', 'FAILED'} else None),
            'server_now': now, 'provider_retry_at': provider_retry_at,
            'retry': retry_summary(job), 'continuous_progress': continuous_progress(job),
            'execution_mode': request.get('execution_mode', 'standard'),
            'batch_progress': batch_summary(data.get('integration_batch')),
            'recovery_of': job.get('recovery_of'), 'recovery_job_id': data.get('post_merge_recovery_job_id'),
            'recovery_state': recovery.get('recovery_state'), 'recovery_pr': recovery.get('recovery_pr'),
            'recovery_action': recovery.get('recovery_action') if recovery.get('recovery_job_id') else None,
            'qualified_revision': recovery.get('qualified_revision'),
            'queue_reason': ('automatic_retry' if provider_retry_at is not None else 'ci_wait' if phase == 'CI' else 'engineering_worker') if state == 'queued' else None,
            'queue_position': None, 'result_available': phase == 'COMPLETED', 'transcript_available': bool(job.get('conversation_id')),
            'allowed_actions': ['open'] + (['cancel'] if phase not in {'COMPLETED', 'CANCELLED', 'FAILED', 'CANCELLING'} else []) + (['approve'] if phase == 'MERGE_READY' else [])}


def copilot_state(phase):
    return {'ACCEPTED': 'queued', 'QUEUED': 'queued', 'IN_PROGRESS': 'running', 'DISPATCHING': 'running',
              'COMPLETED': 'completed', 'CANCELLED': 'cancelled', 'FAILED': 'failed', 'TIMED_OUT': 'failed',
              'REJECTED': 'failed'}.get(phase, 'needs_attention')


def copilot_summary(task, now):
    source, phase = task['request'], task['state']
    bucket = copilot_state(phase)
    return {'job_id': task['id'], 'job_kind': 'copilot', 'title': 'Copilot · ' + source['repository'],
                 'repository': source['repository'], 'branch': source.get('head_ref') or source['base_ref'],
                 'state': bucket, 'phase': phase, 'model': source.get('model'), 'revision': task['revision'],
                 'accepted_at': task['accepted_at'], 'finished_at': task['updated_at'] if bucket in {'completed','failed','cancelled'} else None,
                 'server_now': now, 'allowed_actions': ['open'], 'transcript_available': False}


def catalog_identities(db, principal, permissions, kind, include_copilot, upper):
    # Store small state projections in covering indexes during migration. Legacy
    # snapshots without a catalog key keep their original (0, kind, id) order.
    arms, args = [], []
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    sources = (
        ('generation', 'gateway_jobs', 'job_id', "CASE WHEN json_valid(public_json) THEN json_extract(public_json,'$.status') ELSE '_INVALID_SNAPSHOT' END,CASE WHEN json_valid(public_json) THEN json_extract(public_json,'$.scheduling_state') ELSE NULL END,NULL", permissions[0]),
        ('engineering', 'engineering_jobs', 'id', "state,lease,json_extract(data,'$.provider_wait.retry_at')", permissions[1]),
        ('copilot', 'engineering_copilot_tasks', 'id', 'state,NULL,NULL', permissions[1] and (include_copilot or kind == 'copilot')),
    )
    indexes = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='index'")}
    for family, table, key, fields, allowed in sources:
        if not allowed or table not in tables or kind not in (None, family):
            continue
        index = 'gateway_catalog_' + family + '_state'
        indexed = ' INDEXED BY ' + index if index in indexes else ''
        arm = (f"SELECT COALESCE(k.sequence,0) AS seq,'{family}' AS family,j.{key} AS jid,{fields} "
               f"FROM {table} j{indexed} LEFT JOIN gateway_catalog_keys k ON k.job_kind=? AND k.job_id=j.{key} "
               "WHERE COALESCE(k.sequence,0)<=?")
        args.extend((family, upper))
        if family != 'generation':
            arm += ' AND j.principal=?'
            args.append(principal)
        arms.append(arm)
    if arms:
        yield from db.execute(' UNION ALL '.join(arms) + ' ORDER BY seq DESC,family DESC,jid DESC', args)


def catalog_row(db, family, job_id, principal, now):
    if family == 'generation':
        raw = db.execute('SELECT public_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
        try:
            public = json.loads(raw[0])
        except (ValueError, TypeError) as error:
            raise sqlite3.DatabaseError('Saved job snapshot cannot be read') from error
        if not isinstance(public, dict) or public.setdefault('job_id', job_id) != job_id:
            raise sqlite3.DatabaseError('Saved job identity does not match the catalog')
        return generation_summary(public, now)
    if family == 'engineering':
        data, phase, revision, lease = db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (job_id, principal)).fetchone()
        return engineering_summary(dict(json.loads(data), state=phase, revision=revision), lease, now)
    data = db.execute('SELECT data FROM engineering_copilot_tasks WHERE id=? AND principal=?', (job_id, principal)).fetchone()[0]
    return copilot_summary(json.loads(data), now)


def catalog(conversations, engineering, principal, ownership, cursor=None, limit=50, kind=None, state=None, include_copilot=False):
    if not 1 <= limit <= 100 or kind not in {None, 'engineering', 'generation', 'copilot'} or state not in {None, 'running', 'queued', 'needs_attention', 'completed', 'failed', 'cancelled'}:
        raise ValueError('Invalid job catalog filter')
    now = conversations.now()
    def read(db):
        gateway_id = conversations.identity(db)
        permissions = [principal.local_admin or 'jobs' in principal.scopes, principal.local_admin or 'engineering' in principal.scopes]
        scope = [gateway_id, principal.id, kind, state, permissions]
        if include_copilot or kind == 'copilot':
            scope.append('copilot')
        anchor = None
        if cursor:
            value = conversations.decode_cursor(db, cursor)
            if value.get('scope') != scope or type(value.get('upper')) is not int or value['upper'] < 0:
                raise ValueError('Catalog cursor scope changed')
            upper, anchor = value['upper'], value.get('anchor')
            if not isinstance(anchor, list) or len(anchor) != 3 or not isinstance(anchor[0], (int, float)) or any(not isinstance(v, str) for v in anchor[1:]):
                raise ValueError('Invalid catalog cursor')
        else:
            upper = db.execute('SELECT COALESCE(MAX(sequence),0) FROM gateway_catalog_keys').fetchone()[0]
        counts = {'running': 0, 'queued': 0, 'needs_attention': 0, 'recent': 0}
        state_counts = {key: 0 for key in ('running', 'queued', 'needs_attention', 'completed', 'failed', 'cancelled', 'other')}
        identities = catalog_identities(db, principal.id, permissions, kind, include_copilot, upper)
        page_keys = []
        while batch := list(islice(identities, 200)):
            generation_ids = [r[2] for r in batch if r[1] == 'generation']
            owned = ownership.owned_job_ids(generation_ids, principal.id) if generation_ids else set()
            for seq, family, jid, phase, detail, retry in batch:
                if family == 'generation':
                    if jid not in owned:
                        continue
                    if phase == '_INVALID_SNAPSHOT':
                        raise sqlite3.DatabaseError('Saved job snapshot cannot be read')
                    bucket = generation_state(phase, detail)
                elif family == 'engineering':
                    bucket = engineering_state(phase, detail, retry, now)
                else:
                    bucket = copilot_state(phase)
                counts[bucket if bucket in counts else 'recent'] += 1
                state_counts[bucket if bucket in state_counts else 'other'] += 1
                key = (seq, family, jid)
                if len(page_keys) <= limit and (state is None or state == bucket) and (anchor is None or key < tuple(anchor)):
                    page_keys.append(key)
        more = len(page_keys) > limit
        page = [catalog_row(db, family, jid, principal.id, now) for _, family, jid in page_keys[:limit]]
        for item in page:
            conversation = db.execute('SELECT * FROM gateway_conversations WHERE id=? AND principal=?', (item.get('conversation_id'), principal.id)).fetchone()
            if conversation:
                item.update(conversations.history_status(db, conversation))
            else:
                item.update(history_complete=False, history_limitation='conversation_retention_disabled' if item.get('conversation_retention') == 'none' else 'legacy_snapshot_only')
        pressure = None
        if permissions[0] and db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_queue_count'").fetchone():
            occupied = db.execute("SELECT occupied FROM gateway_queue_count WHERE dimension='pending_generations'").fetchone()
            if occupied:
                capacity = getattr(conversations.runtime, 'gateway_max_queued', 64)
                pressure = {'dimension': 'pending_generations', 'occupied': occupied[0], 'capacity': capacity,
                            'saturated': occupied[0] >= capacity, 'scope': 'shared_host'}
        return {'schema_version': 1, 'gateway_id': gateway_id, 'principal_id': principal.id, 'server_now': now,
                'items': page, 'queue_pressure': pressure, 'counts': counts, 'state_counts': state_counts, 'counts_complete': True, 'has_more': more, 'complete': not more,
                'next_cursor': conversations.encode_cursor(db, {'scope': scope, 'upper': upper, 'anchor': list(page_keys[limit - 1])}) if more else None}
    return conversations.read(read)


def install_routes(app, conversations, engineering, service, ownership):
    def engineering_allowed(request):
        return request.state.principal.local_admin or 'engineering' in request.state.principal.scopes

    def authorized_snapshot(request, conversation_id):
        value = conversations.snapshot(request.state.principal.id, conversation_id)
        allowed = engineering_allowed(request) if value and value['active_job_kind'] == 'engineering' else request.state.principal.local_admin or 'jobs' in request.state.principal.scopes
        return value if value and allowed else None

    async def execute(action, missing_code='not_found'):
        try:
            if not conversations.runtime.GATEWAY_DURABLE_JOBS:
                return JSONResponse({'error': {'code': 'conversation_storage_disabled', 'retryable': False}}, status_code=503)
            value = await asyncio.to_thread(action)
            if value is None:
                return JSONResponse({'error': {'code': missing_code}}, status_code=404)
            return value
        except ExecutionBindingError as error:
            return JSONResponse({'error': error.error_payload(),
                                 **({'preflight': error.result} if hasattr(error, 'result') else {})}, status_code=error.status)
        except QueueFull as error:
            return JSONResponse({'error': error.payload()}, status_code=429)
        except CursorExpired:
            return JSONResponse({'error': {'code': 'cursor_expired', 'message': 'Refresh the saved conversation snapshot before continuing this read.'}}, status_code=409)
        except ConversationConflict as error:
            return JSONResponse({'error': error.error_payload()}, status_code=409)
        except KeyError:
            return JSONResponse({'error': {'code': 'not_found'}}, status_code=404)
        except (ValueError, TypeError):
            return JSONResponse({'error': {'code': 'invalid_conversation_request'}}, status_code=400)
        except (sqlite3.Error, OSError):
            return JSONResponse({'error': {'code': 'conversation_storage_unavailable', 'retryable': True}}, status_code=503)

    @app.get('/gateway/identity')
    @app.get('/v1/gateway/identity')
    async def identity(request: Request):
        principal = request.state.principal.id
        def describe():
            result = {'schema_version': 1, 'protocol_version': 1, 'gateway_id': conversations.gateway_id(),
            'instance_id': service.instance_id, 'principal_id': principal, 'server_now': conversations.now(),
            'server_time_ms': int(conversations.now() * 1000),
            'identity_scope': 'shared-network' if principal == 'network:shared' else 'paired-device' if principal.startswith('paired:') else 'user' if principal.startswith('tailnet:') else 'local-machine'}
            root = getattr(app.state, 'connected_settings_root', None)
            if root and principal == 'network:shared' and request.client and not any(
                    k.startswith(('x-forwarded-', 'tailscale-', 'sec-fetch-')) or k in {'origin', 'forwarded', 'cookie'} for k in request.headers):
                host = (request.scope.get('server') or ('', 0))[0]
                try:
                    from desktop.configuration import shared_settings_overview
                    if overview := shared_settings_overview(root, host, request.client.host):
                        result['network_settings'] = overview
                except (OSError, TypeError, ValueError):
                    pass
                if request.query_params.get('connect') == '1':
                    from desktop.account_address import offer
                    port = (request.scope.get('server') or ('', 0))[1]
                    if descriptor := offer(root, request.client.host, host, port, result['gateway_id']):
                        result['account_connection'] = descriptor
                try:
                    window = json.loads((root / 'data' / 'phone-connect-window.json').read_text(encoding='utf-8'))
                    if window['host'] == host and window['peer'] == request.client.host and window['expires'] > conversations.now():
                        result['phone_connection'] = window['link']
                except (OSError, ValueError, KeyError, TypeError):
                    pass
            return result
        return await execute(describe)

    @app.get('/gateway/job-catalog')
    @app.get('/v1/gateway/job-catalog')
    async def jobs(request: Request):
        q = request.query_params
        return await execute(lambda: catalog(conversations, engineering, request.state.principal, ownership() if callable(ownership) else ownership, q.get('cursor'), int(q.get('limit', '50')), q.get('kind'), q.get('state'), q.get('include_copilot') == 'true'))

    @app.get('/gateway/conversations')
    @app.get('/v1/gateway/conversations')
    async def list_conversations(request: Request):
        def read():
            q = request.query_params
            def flag(name):
                value = q.get(name)
                if value not in {None, 'true', 'false'}:
                    raise ValueError('Invalid conversation filter')
                return None if value is None else value == 'true'
            return conversations.list(request.state.principal.id, q.get('cursor'), int(q.get('limit', '50')),
                engineering_allowed(request), request.state.principal.local_admin or 'jobs' in request.state.principal.scopes,
                query=q.get('query', ''), archived=flag('archived'), favorite=flag('favorite'))
        return await execute(read)

    @app.get('/gateway/saved-messages')
    @app.get('/v1/gateway/saved-messages')
    async def saved_messages(request: Request):
        from v14.conversation_metadata import saved_messages as saved
        q = request.query_params
        return await execute(lambda: saved(conversations, request.state.principal.id, q.get('query', ''), q.get('cursor'),
            int(q.get('limit', '50')), engineering_allowed(request), request.state.principal.local_admin or 'jobs' in request.state.principal.scopes))

    async def metadata_payload(request):
        raw = await request.body()
        if len(raw) > 8192:
            raise ValueError('Metadata is too large')
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise ValueError('Expected metadata')
        return payload

    @app.patch('/gateway/conversations/{conversation_id}/metadata')
    @app.patch('/v1/gateway/conversations/{conversation_id}/metadata')
    async def update_metadata(conversation_id: str, request: Request):
        from v14.conversation_metadata import update
        try:
            payload = await metadata_payload(request)
        except (ValueError, TypeError):
            return JSONResponse({'error': {'code': 'invalid_conversation_request'}}, status_code=400)
        def save():
            verify_mutation_identity(request.headers, request.state.principal.id, conversations.gateway_id)
            if not authorized_snapshot(request, conversation_id):
                return None
            return update(conversations, request.state.principal.id, conversation_id, payload)
        return await execute(save)

    @app.put('/gateway/conversations/{conversation_id}/bookmarks/{message_id}')
    @app.put('/v1/gateway/conversations/{conversation_id}/bookmarks/{message_id}')
    async def bookmark_message(conversation_id: str, message_id: str, request: Request):
        from v14.conversation_metadata import bookmark
        try:
            payload = await metadata_payload(request)
            if set(payload) != {'saved'}:
                raise ValueError('Expected saved state')
        except (ValueError, TypeError):
            return JSONResponse({'error': {'code': 'invalid_conversation_request'}}, status_code=400)
        def save():
            verify_mutation_identity(request.headers, request.state.principal.id, conversations.gateway_id)
            if not authorized_snapshot(request, conversation_id):
                return None
            return bookmark(conversations, request.state.principal.id, conversation_id, message_id, payload['saved'])
        return await execute(save)

    @app.get('/gateway/conversations/{conversation_id}')
    @app.get('/v1/gateway/conversations/{conversation_id}')
    async def conversation(conversation_id: str, request: Request):
        return await execute(lambda: authorized_snapshot(request, conversation_id))

    @app.get('/gateway/conversations/{conversation_id}/messages')
    @app.get('/v1/gateway/conversations/{conversation_id}/messages')
    async def messages(conversation_id: str, request: Request):
        return await execute(lambda: conversations.messages(request.state.principal.id, conversation_id, request.query_params.get('after_cursor'), int(request.query_params.get('limit', '100'))) if authorized_snapshot(request, conversation_id) else None)

    @app.get('/gateway/commands/{command_id}')
    @app.get('/v1/gateway/commands/{command_id}')
    async def command(command_id: str, request: Request):
        def read():
            receipt = conversations.command(request.state.principal.id, command_id, include_outcome=request.query_params.get('include_outcome') == 'true')
            allowed = engineering_allowed(request) if receipt and receipt['job_kind'] == 'engineering' else request.state.principal.local_admin or 'jobs' in request.state.principal.scopes
            return receipt if receipt and allowed else None
        return await execute(read, 'command_receipt_unavailable')

    @app.post('/gateway/conversations/{conversation_id}/messages')
    @app.post('/v1/gateway/conversations/{conversation_id}/messages')
    async def send(conversation_id: str, request: Request):
        if not request.state.principal.local_admin and 'engineering' not in request.state.principal.scopes:
            return JSONResponse({'error': {'code': 'engineering_scope_required'}}, status_code=403)
        try:
            raw = await request.body()
            if len(raw) > 98304:
                raise ValueError('Request too large')
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError('Expected a command')
        except (ValueError, TypeError):
            return JSONResponse({'error': {'code': 'invalid_conversation_request'}}, status_code=400)
        def save():
            verify_mutation_identity(request.headers, request.state.principal.id, conversations.gateway_id)
            if payload.get('kind') == 'instruction':
                result = conversations.instruction(request.state.principal.id, conversation_id, payload)
                engineering.wake.set()
                return result
            if set(payload) != {'command_id', 'content', 'expected_revision', 'kind', 'engineering_request'} or payload['kind'] != 'message':
                raise ValueError('Expected a conversation command')
            submission = dict(payload['engineering_request'], conversation_id=conversation_id, command_id=payload['command_id'], request_id=payload['command_id'], objective=payload['content'], expected_revision=payload['expected_revision'])
            from v14.engineering import validate_request, public_job
            validate_request(submission)
            old = engineering.store.existing_request(request.state.principal.id, submission)
            if old:
                return public_job(old)
            if not engineering.readiness()['ready']:
                raise ConversationConflict('Engineering service is unavailable')
            job = engineering.admit(request.state.principal.id, submission)
            engineering.wake.set()
            return public_job(job)
        return await execute(save)
