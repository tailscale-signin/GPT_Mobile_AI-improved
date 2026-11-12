"""A scoped read projection of both durable engines. No execution is initiated."""
from v14.engineering_scope import accepted_scope
import asyncio
import json
import sqlite3
import time

from fastapi import Request
from fastapi.responses import JSONResponse
from v14.conversations import Conversations, ConversationConflict, QueueFull, CursorExpired, cursor_encode, cursor_decode, short_title
from v14.conversation_policy import verify_mutation_identity
from v14.engineering_batch_plan import summary as batch_summary
from v14.execution_binding import ExecutionBindingError


def generation_summary(public, now):
    status = str(public.get('status', 'unknown')).lower()
    if status in {'running', 'resuming'} and public.get('scheduling_state') == 'queued':
        status = 'queued'
    state = ('needs_attention' if status in {'waiting_for_client_tool', 'needs_input', 'cancel_requested'} else
             'queued' if status == 'queued' else 'running' if status in {'running', 'resuming', 'cancelling'} else
             'cancelled' if status == 'canceled' else status)
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


def engineering_summary(job, lease, now):
    request, phase = job['request'], job['state']
    provider_retry_at = (job.get('provider_wait') or {}).get('retry_at')
    state = ('completed' if phase == 'COMPLETED' else 'cancelled' if phase == 'CANCELLED' else 'failed' if phase == 'FAILED' else
             'needs_attention' if phase in {'NEEDS_INPUT', 'MERGE_READY', 'CANCELLING'} else 'running' if lease else 'queued')
    return {'job_id': job['id'], 'job_kind': 'engineering', 'conversation_id': job.get('conversation_id'),
            'title': job.get('title') or short_title(request.get('objective')), 'profile_id': request.get('profile_id'), 'profile_name': request.get('profile_name'),
            'model': request.get('model'), 'repository': request['repository'], 'branch': request['branch'], 'base': request['base'],
            'source_branch': request.get('source_branch', request['branch']), 'source_sha': request.get('source_sha'), 'working_branch': request['branch'],
            'task_scope': accepted_scope(request), 'cancellation_requested': phase == 'CANCELLING', 'state': state, 'phase': phase, 'revision': job['revision'],
            'accepted_at': job.get('accepted_at', job['created_at']), 'queued_at': job.get('queued_at', job['created_at']),
            'started_at': job.get('started_at'), 'finished_at': job.get('finished_at', job['updated_at'] if phase in {'COMPLETED', 'CANCELLED', 'FAILED'} else None),
            'server_now': now, 'provider_retry_at': provider_retry_at,
            'execution_mode': request.get('execution_mode', 'standard'),
            'batch_progress': batch_summary(job.get('data', {}).get('integration_batch')),
            'queue_reason': ('provider_rate_limit' if provider_retry_at is not None else 'ci_wait' if phase == 'CI' else 'engineering_worker') if state == 'queued' else None,
            'queue_position': None, 'result_available': phase == 'COMPLETED', 'transcript_available': bool(job.get('conversation_id')),
            'allowed_actions': ['open'] + (['cancel'] if phase not in {'COMPLETED', 'CANCELLED', 'FAILED', 'CANCELLING'} else []) + (['approve'] if phase == 'MERGE_READY' else [])}


def catalog(conversations, engineering, principal, ownership, cursor=None, limit=50, kind=None, state=None):
    if not 1 <= limit <= 100 or kind not in {None, 'engineering', 'generation'} or state not in {None, 'running', 'queued', 'needs_attention', 'completed', 'failed', 'cancelled'}:
        raise ValueError('Invalid job catalog filter')
    now = conversations.now()
    def read(db):
        gateway_id = conversations.identity(db)
        permissions = [principal.local_admin or 'jobs' in principal.scopes, principal.local_admin or 'engineering' in principal.scopes]
        scope = [gateway_id, principal.id, kind, state, permissions]
        sequence = {(row['job_kind'], row['job_id']): row['sequence'] for row in db.execute('SELECT * FROM gateway_catalog_keys')}
        upper, anchor = max(sequence.values(), default=0), None
        if cursor:
            value = conversations.decode_cursor(db, cursor)
            if value.get('scope') != scope or type(value.get('upper')) is not int or value['upper'] < 0:
                raise ValueError('Catalog cursor scope changed')
            upper, anchor = value['upper'], value.get('anchor')
            if not isinstance(anchor, list) or len(anchor) != 3 or not isinstance(anchor[0], (int, float)) or any(not isinstance(v, str) for v in anchor[1:]):
                raise ValueError('Invalid catalog cursor')
        rows = []
        generation_rows = db.execute('SELECT public_json FROM gateway_jobs').fetchall() if principal.local_admin or 'jobs' in principal.scopes else []
        parsed = [json.loads(row[0]) for row in generation_rows]
        for start in range(0, len(parsed), 200):
            chunk = parsed[start:start + 200]
            allowed = ownership.owned_job_ids([row['job_id'] for row in chunk], principal.id)
            rows.extend(generation_summary(row, now) for row in chunk if row['job_id'] in allowed)
        if (principal.local_admin or 'engineering' in principal.scopes) and db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='engineering_jobs'").fetchone():
            for row in db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE principal=?', (principal.id,)):
                job = dict(json.loads(row[0]), state=row[1], revision=row[2])
                rows.append(engineering_summary(job, row[3], now))
        rows = [row for row in rows if sequence.get((row['job_kind'], row['job_id']), 0) <= upper and (not kind or row['job_kind'] == kind)]
        counts = {'running': 0, 'queued': 0, 'needs_attention': 0, 'recent': 0}
        for row in rows:
            bucket = row['state'] if row['state'] in counts else 'recent'
            counts[bucket] += 1
        rows = [row for row in rows if not state or row['state'] == state]
        order = lambda row: (sequence.get((row['job_kind'], row['job_id']), 0), row['job_kind'], row['job_id'])
        rows.sort(key=order, reverse=True)
        if anchor:
            rows = [row for row in rows if order(row) < tuple(anchor)]
        more = len(rows) > limit
        page = rows[:limit]
        for item in page:
            conversation = db.execute('SELECT * FROM gateway_conversations WHERE id=? AND principal=?', (item.get('conversation_id'), principal.id)).fetchone()
            if conversation:
                item.update(conversations.history_status(db, conversation))
            else:
                item.update(history_complete=False, history_limitation='conversation_retention_disabled' if item.get('conversation_retention') == 'none' else 'legacy_snapshot_only')
        return {'schema_version': 1, 'gateway_id': gateway_id, 'principal_id': principal.id, 'server_now': now,
                'items': page, 'counts': counts, 'counts_complete': True, 'has_more': more, 'complete': not more,
                'next_cursor': conversations.encode_cursor(db, {'scope': scope, 'upper': upper, 'anchor': list(order(page[-1]))}) if more else None}
    return conversations.read(read)


def install_routes(app, conversations, engineering, service, ownership):
    def engineering_allowed(request):
        return request.state.principal.local_admin or 'engineering' in request.state.principal.scopes

    def authorized_snapshot(request, conversation_id):
        value = conversations.snapshot(request.state.principal.id, conversation_id)
        allowed = engineering_allowed(request) if value and value['active_job_kind'] == 'engineering' else request.state.principal.local_admin or 'jobs' in request.state.principal.scopes
        return value if value and allowed else None

    async def execute(action):
        try:
            if not conversations.runtime.GATEWAY_DURABLE_JOBS:
                return JSONResponse({'error': {'code': 'conversation_storage_disabled', 'retryable': False}}, status_code=503)
            value = await asyncio.to_thread(action)
            if value is None:
                return JSONResponse({'error': {'code': 'not_found'}}, status_code=404)
            return value
        except ExecutionBindingError as error:
            return JSONResponse({'error': error.error_payload(),
                                 **({'preflight': error.result} if hasattr(error, 'result') else {})}, status_code=error.status)
        except QueueFull:
            return JSONResponse({'error': {'code': 'queue_full', 'retryable': True}}, status_code=429)
        except CursorExpired:
            return JSONResponse({'error': {'code': 'cursor_expired', 'message': 'Refresh the saved conversation snapshot before continuing this read.'}}, status_code=409)
        except ConversationConflict as error:
            return JSONResponse({'error': {'code': 'conversation_conflict', 'message': str(error), 'retryable': False}}, status_code=409)
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
        return await execute(lambda: {'schema_version': 1, 'protocol_version': 1, 'gateway_id': conversations.gateway_id(),
            'instance_id': service.instance_id, 'principal_id': principal, 'server_now': conversations.now(),
            'server_time_ms': int(conversations.now() * 1000),
            'identity_scope': 'shared-network' if principal == 'network:shared' else 'user' if principal.startswith('tailnet:') else 'local-machine'})

    @app.get('/gateway/job-catalog')
    @app.get('/v1/gateway/job-catalog')
    async def jobs(request: Request):
        q = request.query_params
        return await execute(lambda: catalog(conversations, engineering, request.state.principal, ownership() if callable(ownership) else ownership, q.get('cursor'), int(q.get('limit', '50')), q.get('kind'), q.get('state')))

    @app.get('/gateway/conversations')
    @app.get('/v1/gateway/conversations')
    async def list_conversations(request: Request):
        return await execute(lambda: conversations.list(request.state.principal.id, request.query_params.get('cursor'), int(request.query_params.get('limit', '50')), engineering_allowed(request), request.state.principal.local_admin or 'jobs' in request.state.principal.scopes))

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
        return await execute(read)

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
