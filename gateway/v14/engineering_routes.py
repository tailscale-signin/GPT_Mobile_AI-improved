"""Authenticated user controls, independent of the model's tool catalogue."""
import asyncio
import hashlib
import hmac
import json
import os
import requests
from fastapi import Request
from fastapi.responses import JSONResponse

from v14.engineering import public_job, validate_request


def install_engineering_routes(app, service):
    def principal(request):
        user = request.state.principal
        if not user.local_admin and 'engineering' not in user.scopes:
            raise PermissionError('Engineering scope is required')
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
            return {'enabled': service.store.runtime.GATEWAY_DURABLE_JOBS and service.backend.available(), 'duration_hours': [3, 6, 12, 0], 'until_complete_limit_hours': 72,
                    'principal_id': owner,
                    'repair_attempts': 5, 'explicit_merge_approval': True, 'approval_ttl_seconds': 1800,
                    'notifications': 'android_background_sync', 'batch_reads': True}
        return await execute(run)

    @app.post('/gateway/engineering/jobs')
    @app.post('/v1/gateway/engineering/jobs')
    async def create(request: Request):
        async def run():
            owner = principal(request)
            if not service.store.runtime.GATEWAY_DURABLE_JOBS or not service.backend.available():
                return JSONResponse({'error': {'code': 'engineering_connection_required', 'message': 'Enable durable storage and configure the gateway host GitHub connection before starting an autonomous job.'}}, status_code=503)
            payload = validate_request(await body(request))
            job = await asyncio.to_thread(service.store.create, owner, payload)
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
            raw = await request.body()
            if not secret or len(raw) > 1024 * 1024:
                raise PermissionError('Webhook relay is not configured')
            expected = 'sha256=' + hmac.new(secret.encode(), raw, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, request.headers.get('X-Hub-Signature-256', '')):
                raise PermissionError('Invalid webhook signature')
            # A signed webhook is only a wake-up hint, never authoritative CI
            # evidence or approval. Repeated deliveries cannot repeat a mutation.
            await asyncio.to_thread(service.store.wake_checks, owner)
            service.wake.set()
            return {'accepted': True, 'reconciliation': 'fresh GitHub reads'}
        return await execute(run)
