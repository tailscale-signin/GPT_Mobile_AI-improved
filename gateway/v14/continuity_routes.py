"""Read-only recovery surfaces and explicit, idempotent user follow-ups."""
import asyncio
import json
import sqlite3

from fastapi import Request
from fastapi.responses import JSONResponse

from v14.continuity import page_limit, verify_mutation_identity
from v14.execution_binding import ExecutionBindingError
from v14.jobs import DurableCommitError


def install_continuity_routes(app, engineering, service, config):
    continuity = engineering.store.continuity

    def kinds(principal):
        result = []
        if principal.local_admin or 'jobs' in principal.scopes:
            result.append('chat')
        if principal.local_admin or 'engineering' in principal.scopes:
            result.append('engineering')
        return result

    async def execute(action):
        try:
            if not engineering.store.runtime.GATEWAY_DURABLE_JOBS:
                return JSONResponse({'error': {'code': 'CONTINUITY_STORAGE_DISABLED', 'retryable': False}}, status_code=503)
            return await action()
        except ExecutionBindingError as error:
            return JSONResponse({'error': {'code': error.code, 'message': str(error), 'retryable': error.retryable}}, status_code=error.status)
        except (ValueError, KeyError, TypeError, json.JSONDecodeError):
            return JSONResponse({'error': {'code': 'CONTINUITY_INVALID_REQUEST', 'retryable': False}}, status_code=400)
        except (sqlite3.Error, DurableCommitError):
            return JSONResponse({'error': {'code': 'CONTINUITY_STORAGE_UNAVAILABLE', 'retryable': True}}, status_code=503)

    async def snapshot(request, conversation_id):
        principal = request.state.principal
        value = await asyncio.to_thread(continuity.conversation, principal.id, conversation_id)
        if value.get('kind') not in kinds(principal):
            raise ExecutionBindingError('CONTINUITY_SCOPE_REQUIRED', 'This conversation requires its original job read permission.', 403)
        return value

    @app.get('/gateway/identity')
    @app.get('/v1/gateway/identity')
    async def identity(request: Request):
        return await execute(lambda: asyncio.to_thread(continuity.identity, request.state.principal.id, service.instance_id,
            'shared-network' if config.mode == 'open_network' else 'user' if config.mode == 'trusted_proxy' else 'local-machine'))

    @app.get('/gateway/job-catalog')
    @app.get('/v1/gateway/job-catalog')
    async def catalog(request: Request):
        return await execute(lambda: asyncio.to_thread(continuity.catalog, request.state.principal.id,
            request.query_params.get('cursor'), page_limit(request.query_params.get('limit')), kinds(request.state.principal)))

    @app.get('/gateway/conversations/{conversation_id}')
    @app.get('/v1/gateway/conversations/{conversation_id}')
    async def conversation(conversation_id: str, request: Request):
        return await execute(lambda: snapshot(request, conversation_id))

    @app.get('/gateway/conversations/{conversation_id}/messages')
    @app.get('/v1/gateway/conversations/{conversation_id}/messages')
    async def messages(conversation_id: str, request: Request):
        async def read():
            await snapshot(request, conversation_id)
            return await asyncio.to_thread(continuity.messages, request.state.principal.id, conversation_id,
                request.query_params.get('cursor'), page_limit(request.query_params.get('limit'), 100))
        return await execute(read)

    @app.get('/gateway/commands/{command_id}')
    @app.get('/v1/gateway/commands/{command_id}')
    async def command(command_id: str, request: Request):
        async def read():
            if 'engineering' not in kinds(request.state.principal):
                raise ExecutionBindingError('CONTINUITY_SCOPE_REQUIRED', 'Engineering permission is required for command reconciliation.', 403)
            return await asyncio.to_thread(continuity.command, request.state.principal.id, command_id)
        return await execute(read)

    @app.post('/gateway/conversations/{conversation_id}/messages')
    @app.post('/v1/gateway/conversations/{conversation_id}/messages')
    async def followup(conversation_id: str, request: Request):
        async def submit():
            principal = request.state.principal
            if 'engineering' not in kinds(principal):
                raise ExecutionBindingError('CONTINUITY_SCOPE_REQUIRED', 'Engineering permission is required for a follow-up.', 403)
            verify_mutation_identity(request.headers, principal.id, engineering.bindings.installation_id)
            raw = await request.body()
            if len(raw) > 98304:
                raise ValueError('Follow-up is too large')
            command = json.loads(raw)
            source, existing, command_hash = await asyncio.to_thread(continuity.followup_source, principal.id, conversation_id, command)
            if existing is None:
                await asyncio.to_thread(engineering.validate_submission, principal.id, source)
                await asyncio.to_thread(engineering.store.create, principal.id, source, followup=command, command_hash=command_hash)
                engineering.wake.set()
            receipt = await asyncio.to_thread(continuity.command, principal.id, command['command_id'])
            return JSONResponse(receipt, status_code=200 if existing else 202)
        return await execute(submit)
