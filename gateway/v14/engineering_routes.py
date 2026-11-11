"""Authenticated user controls, independent of the model's tool catalogue."""
import asyncio
import hashlib
import hmac
import json
import os
import requests
from pathlib import Path
from v14 import VERSION
from fastapi import Request
from fastapi.responses import JSONResponse, StreamingResponse

from v14.engineering import public_job, validate_request
from v14.engineering_preflight import EngineeringPreflightError
from v14.engineering_defaults import DEFAULT_DURATION_HOURS, DEFAULT_EXECUTION_BUDGET, EXECUTION_BUDGET_LIMITS
from v14.engineering_diagnostics import diagnostic_job
from v14.execution_binding import ExecutionBindingError
from v14.conversations import FEATURES, QueueFull, ConversationConflict
from v14.conversation_policy import verify_mutation_identity, MUTATION_IDENTITY_HEADERS
from v14.repository_lifecycle import RepositoryLifecycle, RepositoryLifecycleError


def install_engineering_routes(app, service):
    repository_lifecycle = getattr(service, 'repository_lifecycle', None) or RepositoryLifecycle(service)
    service.repository_lifecycle = repository_lifecycle
    server_build = "sha256:" + hashlib.sha256((Path(__file__).resolve().parents[1] / "manifest-v14.json").read_bytes()).hexdigest()
    def public_job(job):
        return service.job_response(job)
    def principal(request):
        user = request.state.principal
        if not user.local_admin and 'engineering' not in user.scopes:
            raise PermissionError('Engineering scope is required')
        if request.method not in {'GET', 'HEAD', 'OPTIONS'}:
            verify_mutation_identity(request.headers, user.id, service.store.conversations.gateway_id)
        return user.id

    async def body(request):
        raw = await request.body()
        if len(raw) > 98304:
            raise ValueError('Engineering request exceeds 96 KiB')
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError('Expected a JSON object')
        return value

    async def execute(action):
        try:
            return await action()
        except EngineeringPreflightError as error:
            return JSONResponse({'error': {'code': error.code, 'message': str(error), 'retryable': False},
                                 'preflight': error.result}, status_code=error.status)
        except ExecutionBindingError as error:
            return JSONResponse({'error': {'code': error.code, 'message': str(error),
                                           'retryable': error.retryable, 'routeSubstituted': False}}, status_code=error.status)
        except RepositoryLifecycleError as error:
            return JSONResponse({'error': {'code': error.code, 'message': str(error), 'retryable': False}}, status_code=error.status)
        except QueueFull:
            return JSONResponse({'error': {'code': 'queue_full', 'retryable': True}}, status_code=429)
        except ConversationConflict as error:
            return JSONResponse({'error': {'code': 'conversation_conflict', 'message': str(error), 'retryable': False}}, status_code=409)
        except PermissionError:
            return JSONResponse({'error': {'code': 'engineering_scope_required'}}, status_code=403)
        except (ValueError, KeyError, TypeError):
            return JSONResponse({'error': {'code': 'engineering_state_or_input_changed',
                                           'message': 'Refresh the job and verify its settings, connection and exact commit.'}}, status_code=409)
        except (requests.RequestException, TimeoutError):
            return JSONResponse({'error': {'code': 'engineering_connection_unavailable'}}, status_code=503)

    @app.get('/gateway/engineering/capabilities')
    @app.get('/v1/gateway/engineering/capabilities')
    async def capabilities(request: Request):
        async def run():
            owner = principal(request)
            readiness = await asyncio.to_thread(service.readiness)
            repository_operations = await asyncio.to_thread(repository_lifecycle.capabilities)
            return {'service': 'engineering-gateway', 'protocol_version': 1, 'diagnostic_protocol': 1, 'gateway_version': VERSION, 'server_build': server_build, 'enabled': readiness['ready'], 'readiness': readiness, 'duration_hours': [3, 6, 12, 24, 0], 'until_complete_limit_hours': 72,
                    'default_duration_hours': DEFAULT_DURATION_HOURS, 'execution_budget_defaults': dict(DEFAULT_EXECUTION_BUDGET), 'execution_budget_limits': dict(EXECUTION_BUDGET_LIMITS),
                    'principal_id': owner, 'gateway_id': await asyncio.to_thread(service.store.conversations.gateway_id) if service.store.runtime.GATEWAY_DURABLE_JOBS else None,
                    'instance_id': getattr(getattr(service.store.runtime, 'gateway_service', None), 'instance_id', None),
                    'task_scope_protocol': 1, 'task_scopes': ['read', 'plan', 'implement'], 'features': dict({key: bool(service.store.runtime.GATEWAY_DURABLE_JOBS) for key in FEATURES}, mutation_identity_preconditions_v1=True, repository_lifecycle_v1=repository_operations['enabled']), **{key: bool(service.store.runtime.GATEWAY_DURABLE_JOBS) for key in FEATURES},
                    'repository_operations': repository_operations,
                    'mutationIdentityPreconditions': MUTATION_IDENTITY_HEADERS,
                    'repair_attempts': 5, 'explicit_merge_approval': True, 'approval_ttl_seconds': 1800,
                    'notifications': 'android_background_sync', 'batch_reads': True,
                    'execution_binding_protocol': 1, 'routing_policy': 'SELECTED_ONLY',
                    'execution_owner': 'GATEWAY', 'retry_owner': 'GATEWAY', 'admission_preflight_protocol': 1, 'sampling_policy_protocol': 1,
                    'code_graph': {'schema_version': 1, 'supported': True,
                                   'configured': readiness.get('checks', {}).get('graph_host_github_connection') == 'configured',
                                   'enabled': readiness['graph_ready'], 'incremental': True, 'source_level_only': True}}
        return await execute(run)

    @app.post('/gateway/engineering/repository-operations')
    @app.post('/v1/gateway/engineering/repository-operations')
    async def repository_operation_create(request: Request):
        async def run():
            owner = principal(request)
            if any(request.headers.get(name) is None for name in MUTATION_IDENTITY_HEADERS):
                raise ExecutionBindingError('CONTINUITY_IDENTITY_REQUIRED', 'Refresh the gateway identity and supply both installation and account preconditions.', 400)
            result = await asyncio.to_thread(repository_lifecycle.submit, owner, await body(request))
            return JSONResponse(result, status_code=202 if result['status'] in {'PENDING', 'RUNNING'} else 200)
        return await execute(run)

    @app.get('/gateway/engineering/repository-operations/{operation_id}')
    @app.get('/v1/gateway/engineering/repository-operations/{operation_id}')
    async def repository_operation_status(operation_id: str, request: Request):
        async def run():
            return await asyncio.to_thread(repository_lifecycle.status, principal(request), operation_id)
        return await execute(run)

    @app.post('/gateway/engineering/bindings')
    @app.post('/v1/gateway/engineering/bindings')
    async def prepare_binding(request: Request):
        async def run():
            owner = principal(request)
            payload = await body(request)
            if set(payload) != {'selection'}:
                raise ExecutionBindingError('EXECUTION_SELECTION_INVALID', 'A selected profile snapshot is required.', 400)
            return await asyncio.to_thread(service.prepare_binding, owner, payload['selection'],
                                           gateway_endpoint=str(request.base_url).rstrip('/'))
        return await execute(run)

    @app.get('/gateway/engineering/bindings/{binding_id}/capabilities')
    @app.get('/v1/gateway/engineering/bindings/{binding_id}/capabilities')
    async def bound_capabilities(binding_id: str, request: Request):
        async def run():
            return await asyncio.to_thread(service.binding_capabilities, principal(request), binding_id)
        return await execute(run)

    @app.post('/gateway/engineering/diagnostics/jobs')
    @app.post('/v1/gateway/engineering/diagnostics/jobs')
    async def diagnostic_create(request: Request):
        async def run():
            owner = principal(request)
            payload = await body(request)
            if set(payload) != {'request_id', 'kind'} or payload['kind'] != 'transport_probe':
                raise ValueError('Only a harmless transport probe is supported')
            return await asyncio.to_thread(diagnostic_job, service.store, owner, payload['request_id'])
        return await execute(run)

    @app.get('/gateway/engineering/diagnostics/jobs/{job_id}/events')
    @app.get('/v1/gateway/engineering/diagnostics/jobs/{job_id}/events')
    async def diagnostic_events(job_id: str, request: Request):
        async def run():
            owner = principal(request)
            result = await asyncio.to_thread(diagnostic_job, service.store, owner, job_id=job_id,
                                             after=int(request.query_params.get('after', '0')))
            if not result:
                return JSONResponse({'error': {'code': 'unknown_diagnostic_job'}}, status_code=404)
            async def events():
                for event in result['events']:
                    yield 'data: ' + json.dumps(dict(event, job_id=job_id)) + '\n\n'
                yield 'data: [DONE]\n\n'
            return StreamingResponse(events(), media_type='text/event-stream', headers={'Cache-Control': 'no-store'})
        return await execute(run)

    @app.get('/gateway/engineering/diagnostics/jobs/{job_id}')
    @app.get('/v1/gateway/engineering/diagnostics/jobs/{job_id}')
    async def diagnostic_resume(job_id: str, request: Request):
        async def run():
            owner = principal(request)
            result = await asyncio.to_thread(diagnostic_job, service.store, owner, job_id=job_id,
                                             after=int(request.query_params.get('after', '0')),
                                             limit=int(request.query_params.get('limit', '10')))
            return result if result else JSONResponse({'error': {'code': 'unknown_diagnostic_job'}}, status_code=404)
        return await execute(run)

    @app.post('/gateway/engineering/graph')
    @app.post('/v1/gateway/engineering/graph')
    async def graph(request: Request):
        async def run():
            owner = principal(request)
            readiness = await asyncio.to_thread(service.readiness)
            if not readiness['graph_ready']:
                missing_connection = readiness.get('checks', {}).get('graph_host_github_connection') == 'missing'
                return JSONResponse({'error': {'code': 'graph_connection_required', 'retryable': not missing_connection,
                                               'reason': 'HOST_GITHUB_CONNECTION_REQUIRED' if missing_connection else 'GRAPH_STORAGE_OR_SERVICE_UNAVAILABLE'},
                                     'supported': True, 'configured': not missing_connection, 'readiness': readiness},
                                    status_code=412 if missing_connection else 503)
            payload = await body(request)
            return await asyncio.to_thread(service.graph.execute, owner, payload)
        return await execute(run)

    @app.post('/gateway/engineering/preflight')
    @app.post('/v1/gateway/engineering/preflight')
    async def preflight(request: Request):
        async def run():
            return await asyncio.to_thread(service.preflight_submission, principal(request), await body(request))
        return await execute(run)

    @app.post('/gateway/engineering/jobs')
    @app.post('/v1/gateway/engineering/jobs')
    async def create(request: Request):
        async def run():
            owner = principal(request)
            payload = await body(request)
            validate_request(payload)
            readiness = await asyncio.to_thread(service.readiness)
            if readiness['saved_results']:
                existing = await asyncio.to_thread(service.store.existing_request, owner, payload)
                if existing:
                    return JSONResponse(public_job(existing), status_code=200)
            if not readiness['ready']:
                return JSONResponse({'error': {'code': 'engineering_connection_required', 'message': 'Check durable storage, the gateway host GitHub connection and the engineering worker before starting an autonomous job.'}, 'readiness': readiness}, status_code=503)
            job = await asyncio.to_thread(service.admit, owner, payload)
            service.wake.set()
            return JSONResponse(public_job(job), status_code=202)
        return await execute(run)

    @app.get('/gateway/engineering/jobs')
    @app.get('/v1/gateway/engineering/jobs')
    async def jobs(request: Request):
        async def run():
            rows = await asyncio.to_thread(service.store.list, principal(request))
            return {'jobs': [public_job(row) for row in rows]}
        return await execute(run)

    @app.get('/gateway/engineering/jobs/{job_id}')
    @app.get('/v1/gateway/engineering/jobs/{job_id}')
    async def job(job_id: str, request: Request):
        async def run():
            row = await asyncio.to_thread(service.store.get, job_id, principal(request))
            if not row:
                return JSONResponse({'error': {'code': 'unknown_job'}}, status_code=404)
            after = int(request.query_params.get('after_revision', '-1'))
            if after < -1 or after > row['revision']:
                raise ValueError('Invalid engineering update cursor')
            updates = await asyncio.to_thread(service.store.updates, job_id, after, row['revision'])
            more = len(updates) > 200
            page = updates[:200]
            return dict(public_job(row), updates=page, has_more_updates=more,
                        next_revision=page[-1]['revision'] if more else row['revision'])
        return await execute(run)

    @app.get('/gateway/engineering/jobs/{job_id}/artifacts/{artifact_id}')
    @app.get('/v1/gateway/engineering/jobs/{job_id}/artifacts/{artifact_id}')
    async def artifact(job_id: str, artifact_id: str, request: Request):
        async def run():
            offset = int(request.query_params.get('offset', '0'))
            length = int(request.query_params.get('length', '65536'))
            return await asyncio.to_thread(service.artifact_page, principal(request), job_id, artifact_id, offset, length)
        return await execute(run)

    @app.get('/gateway/engineering/jobs/{job_id}/operations')
    @app.get('/v1/gateway/engineering/jobs/{job_id}/operations')
    async def operations(job_id: str, request: Request):
        async def run():
            return await asyncio.to_thread(service.operation_page, principal(request), job_id, int(request.query_params.get('after', '0')))
        return await execute(run)

    @app.post('/gateway/engineering/jobs/{job_id}/{action}')
    @app.post('/v1/gateway/engineering/jobs/{job_id}/{action}')
    async def control(job_id: str, action: str, request: Request):
        async def run():
            owner = principal(request)
            payload = await body(request)
            revision = payload.get('revision')
            if type(revision) is not int or revision < 0:
                raise ValueError('Missing exact job revision')
            if action == 'approve':
                # No AI tool is registered for this endpoint. The Android UI
                # supplies this marker only from its explicit confirmation action.
                if request.headers.get('X-Gateway-User-Action') != 'approve-and-merge':
                    raise PermissionError('Explicit merge action required')
                result = await asyncio.to_thread(service.approve, job_id, owner, revision, payload['pr'], payload['head_sha'])
            else:
                result = await asyncio.to_thread(service.store.control, job_id, owner, action, revision)
            return public_job(result)
        return await execute(run)

    @app.get('/gateway/engineering/jobs/{job_id}/evidence')
    @app.get('/v1/gateway/engineering/jobs/{job_id}/evidence')
    async def evidence(job_id: str, request: Request):
        async def run():
            row = await asyncio.to_thread(service.store.get, job_id, principal(request))
            if not row:
                return JSONResponse({'error': {'code': 'unknown_job'}}, status_code=404)
            return await asyncio.to_thread(service.evidence.page, job_id, int(request.query_params.get('after', '-1')))
        return await execute(run)

    @app.get('/gateway/engineering/jobs/{job_id}/evidence/{receipt_id}')
    @app.get('/v1/gateway/engineering/jobs/{job_id}/evidence/{receipt_id}')
    async def evidence_receipt(job_id: str, receipt_id: str, request: Request):
        async def run():
            row = await asyncio.to_thread(service.store.get, job_id, principal(request))
            if not row:
                return JSONResponse({'error': {'code': 'unknown_job'}}, status_code=404)
            result = await asyncio.to_thread(service.evidence.read, job_id, receipt_id)
            if result and request.query_params.get('preview') == 'true':
                text = json.dumps(result['payload'], ensure_ascii=False, indent=2)
                return dict(result, payload=None, preview=text[:16000], truncated=len(text) > 16000)
            return result if result else JSONResponse({'error': {'code': 'unknown_receipt'}}, status_code=404)
        return await execute(run)

    @app.post('/gateway/engineering/webhook')
    @app.post('/v1/gateway/engineering/webhook')
    async def webhook(request: Request):
        async def run():
            owner = principal(request)
            secret = os.getenv('GATEWAY_ENGINEERING_WEBHOOK_SECRET', '')
            if not secret:
                raise PermissionError('Webhook relay is not configured')
            raw = bytearray()
            async for block in request.stream():
                if len(raw) + len(block) > 1024 * 1024:
                    raise ValueError('Webhook exceeds the streaming byte limit')
                raw.extend(block)
            expected = 'sha256=' + hmac.new(secret.encode(), raw, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, request.headers.get('X-Hub-Signature-256', '')):
                raise PermissionError('Invalid webhook signature')
            # A signed webhook is only a wake-up hint, never authoritative CI
            # evidence or approval. Repeated deliveries cannot repeat a mutation.
            body_digest = hashlib.sha256(raw).hexdigest()
            delivery = request.headers.get('X-GitHub-Delivery', body_digest)
            if not delivery or len(delivery) > 128 or any(not (ch.isalnum() or ch in '-_') for ch in delivery):
                raise ValueError('Invalid webhook delivery identifier')
            accepted = await asyncio.to_thread(service.store.accept_webhook, owner, delivery, body_digest)
            if not accepted:
                return {'accepted': True, 'duplicate': True, 'reconciliation': 'polling fallback retained'}
            await asyncio.to_thread(service.store.wake_checks, owner)
            service.wake.set()
            return {'accepted': True, 'reconciliation': 'fresh GitHub reads'}
        return await execute(run)
