"""Local-only runtime activation for settings owned by the Windows supervisor."""
import asyncio
import json
import os
from pathlib import Path
import re
import sqlite3
import time

import requests
from fastapi import Request
from fastapi.responses import JSONResponse

from v14.maintenance import prepare, pending_work


def blocking_jobs(runtime, engineering):
    if not runtime.GATEWAY_DURABLE_JOBS:
        return []
    def read(db):
        result = [row[0] for row in db.execute("SELECT job_id FROM gateway_jobs WHERE status IS NULL OR status NOT IN ('completed','failed','cancelled','canceled','interrupted') LIMIT 32")]
        result += [row[0] for row in db.execute("SELECT id FROM engineering_jobs WHERE state NOT IN ('COMPLETED','CANCELLED','FAILED') LIMIT 32")]
        from v14.copilot_tasks import TERMINAL
        for table, predicate, args in [('code_graph_index_jobs', "state IN ('QUEUED','RUNNING','INTERRUPTED','PAUSED')", ()),
                                         ('engineering_copilot_tasks', 'state NOT IN (' + ','.join('?' for _ in TERMINAL) + ')', tuple(sorted(TERMINAL)))]:
            if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
                # Older journal revisions use different primary-key names. Keep
                # the blocking count even when an identifier cannot be projected.
                columns = {row[1] for row in db.execute('PRAGMA table_info(' + table + ')')}
                column = 'id' if 'id' in columns else 'job_id'
                if column in columns:
                    result += [row[0] for row in db.execute('SELECT ' + column + ' FROM ' + table + ' WHERE ' + predicate + ' LIMIT 32', args)]
        return list(dict.fromkeys(result))[:64]
    return engineering.store.tx(read)


def probe_backend(origin, model):
    deadline = time.monotonic() + 8
    with requests.Session() as client:
        client.trust_env = False
        with client.get(origin.rstrip('/') + '/v1/models', timeout=(3, 1), stream=True, allow_redirects=False) as response:
            response.raise_for_status()
            body = bytearray()
            while chunk := response.raw.read1(8192, decode_content=True):
                body.extend(chunk)
                if time.monotonic() > deadline or len(body) > 1024 * 1024:
                    raise ValueError('Model inventory exceeds its supported bound')
        catalog = json.loads(body)
        rows = catalog.get('data') if isinstance(catalog, dict) else None
        from v14.backend_observation import model_state
        if not isinstance(rows, list) or not rows or model and not any(
                isinstance(row, dict) and row.get('id') == model and model_state(row) not in {'UNLOADED', 'LOADING', 'SLEEPING'} for row in rows):
            raise ValueError('The selected model is not reported by this backend')


def connected_settings_root(config):
    directory = os.environ.get('GATEWAY_DESKTOP_ROOT')
    if not directory or os.environ.get('GATEWAY_DESKTOP_MANAGED') != '1' or not config.control_journal:
        return None
    root = Path(directory).resolve()
    return root if Path(config.control_journal).resolve() == root / 'data' / 'jobs.sqlite3' else None


def install_connected_settings(app, root, config):
    app.state.connected_settings = True
    app.state.connected_settings_root = root
    from desktop.control_store import ControlError, ControlStore, ID
    from v14.account_connection import PREFIX
    from v14.identity import account_authorized

    def account(request):
        principal = request.state.principal.id
        if (any(k.startswith(('x-forwarded-', 'tailscale-', 'sec-fetch-')) or k in {'origin', 'cookie', 'forwarded', 'x-gateway-control-principal'} for k in request.headers)
                or not (config.mode == 'loopback' and principal == 'local:single-user'
                        or principal.startswith(PREFIX) and account_authorized(config, principal))):
            raise ControlError('connected_account_required', 403)
        return principal

    async def dispatch(request, command=None, updates=False, connection=False):
        try:
            raw = await request.body()
            if len(raw) > 32768:
                raise ControlError('control_request_too_large', 413)
            def handle():
                principal = account(request)
                store = ControlStore(root, connected_account=principal)
                # Bind reads, writes and receipt lookups to the installation originally selected by Android.
                if request.headers.get('x-gateway-principal-id') != principal:
                    raise ControlError('connected_account_changed', 409)
                with store.tx() as db:
                    if request.headers.get('x-gateway-installation-id') != store.installation(db):
                        raise ControlError('installation_changed', 409)
                if connection:
                    from desktop.workspace_actions import phone_connection_settings
                    return phone_connection_settings(store, principal, json.loads(raw) if request.method == 'PATCH' else None)
                if updates:
                    from desktop.manager import ManagerRequestError, request as send
                    body = json.loads(raw)
                    if not isinstance(body, dict) or 'principal' in body:
                        raise ControlError('invalid_update_request')
                    try:
                        send(root, 'phone-update', payload=dict(body, principal=principal))
                    except ManagerRequestError as exc:
                        raise ControlError(exc.code or 'update_not_accepted', exc.status or 409) from None
                    return {'accepted': True}
                if command is not None:
                    if not ID.fullmatch(command):
                        raise ControlError('invalid_settings_command')
                    return store.receipt(principal, command)
                if request.method == 'PATCH':
                    return store.patch(principal, json.loads(raw))
                result = store.snapshot(principal)
                try:
                    status = json.loads((root / 'status.json').read_text(encoding='utf-8'))
                    if status.get('version'):
                        result['updates'] = {'installed': status['version'], 'state': status.get('updateState', 'unchecked'),
                                             'available': status.get('availableVersion')}
                except (OSError, ValueError):
                    pass
                return result
            return await asyncio.to_thread(handle)
        except ControlError as exc:
            return JSONResponse({'error': {'code': exc.code}}, status_code=exc.status)
        except (ValueError, TypeError, UnicodeError):
            return JSONResponse({'error': {'code': 'invalid_settings_command'}}, status_code=400)

    async def settings(request: Request):
        return await dispatch(request)

    async def receipt(command: str, request: Request):
        return await dispatch(request, command=command)

    async def connection(request: Request):
        return await dispatch(request, connection=True)

    async def update(request: Request):
        return await dispatch(request, updates=True)

    for prefix in ('/gateway/engineering/connected', '/v1/gateway/engineering/connected'):
        app.add_api_route(prefix + '/settings', settings, methods=['GET', 'PATCH'])
        app.add_api_route(prefix + '/settings/connection', connection, methods=['GET', 'PATCH'])
        app.add_api_route(prefix + '/settings/commands/{command}', receipt, methods=['GET'])
        app.add_api_route(prefix + '/updates', update, methods=['POST'])


def install_control_settings(app, runtime, state, engineering, config):
    if root := connected_settings_root(config):
        install_connected_settings(app, root, config)
    from desktop.phone_policies import runtime_active
    current = {'workers': config.max_workers, 'active_chats': config.max_active_chats,
               'backend': runtime.LLAMA_BASE.rstrip('/'), 'model': '',
               'public_repository_maps': config.public_repository_maps,
               'debug_mode': bool(getattr(runtime.gateway_debug, 'enabled', False)),
               'validation_profile': runtime_active()}

    def observed():
        ready = engineering.readiness()
        checks = ready.get('checks', {})
        prerequisites = []
        for key, available, label in [('journal', 'ready', 'durable_storage'),
                                     ('host_github_connection', 'configured', 'github_connection'),
                                     ('worker', 'running', 'engineering_worker')]:
            if key in checks and checks[key] != available:
                prerequisites.append(label)
        if checks.get('draining'):
            prerequisites.append('runtime_draining')
        if not ready.get('ready') and not prerequisites:
            prerequisites.append('engineering_runtime')
        return {'effective': dict(current), 'blocking_jobs': blocking_jobs(runtime, engineering),
                'pending_work': pending_work(runtime, engineering), 'runtime': state.snapshot(),
                'engineering_ready': ready.get('ready') is True and not prerequisites,
                'engineering_prerequisites': prerequisites,
                'implementation_prerequisites': ['validation_policy_for_implementation'] if checks.get('validation_policy') == 'missing' else []}

    @app.get('/gateway/admin/settings')
    async def get_settings():
        return await asyncio.to_thread(observed)

    from v14.retained_work_control import RetainedWorkControl, RetainedWorkError
    from v14.jobs import DurableCommitError
    retained_controls = RetainedWorkControl(runtime, engineering)

    async def retained_dispatch(request, job_id=None, command_id=None):
        # Host supervision is a separate local authority. Original clients and
        # paired management proxies cannot cancel another caller's retained work.
        user = getattr(request.state, 'principal', None)
        if not user or user.id != 'local:manager' or not user.local_admin:
            return JSONResponse({'error': {'code': 'supervisor_required'}}, status_code=403)
        try:
            if request.method == 'POST':
                raw = await request.body()
                if len(raw) > 8192:
                    return JSONResponse({'error': {'code': 'cancel_command_too_large'}}, status_code=413)
                try:
                    body = json.loads(raw)
                except (ValueError, UnicodeError):
                    return JSONResponse({'error': {'code': 'invalid_cancel_command'}}, status_code=400)
                result = await asyncio.to_thread(retained_controls.cancel, job_id, body)
            elif command_id is not None:
                result = await asyncio.to_thread(retained_controls.receipt, job_id, command_id)
            elif job_id is None:
                result = await asyncio.to_thread(retained_controls.listing, request.query_params.get('cursor'))
            else:
                result = await asyncio.to_thread(retained_controls.read, job_id)
            return result if result else JSONResponse({'error': {'code': 'retained_work_not_found'}}, status_code=404)
        except RetainedWorkError as error:
            return JSONResponse({'error': {'code': error.code, 'message': str(error)}}, status_code=error.status)
        except ValueError:
            return JSONResponse({'error': {'code': 'retained_work_changed', 'message': 'Read the original job and cancellation command before another action.'}}, status_code=409)
        except (sqlite3.Error, OSError, DurableCommitError):
            return JSONResponse({'error': {'code': 'retained_work_storage_unavailable',
                'message': 'The stop outcome is unconfirmed. Read the original cancellation command when storage is available.'}}, status_code=503)

    @app.get('/gateway/admin/retained-work')
    async def retained_inventory(request: Request):
        return await retained_dispatch(request)

    @app.get('/gateway/admin/retained-work/{job_id}')
    async def retained_work(job_id: str, request: Request):
        return await retained_dispatch(request, job_id)

    @app.post('/gateway/admin/retained-work/{job_id}/cancel')
    async def cancel_retained_work(job_id: str, request: Request):
        return await retained_dispatch(request, job_id)

    @app.get('/gateway/admin/retained-work/{job_id}/cancel/{command_id}')
    async def retained_cancel_receipt(job_id: str, command_id: str, request: Request):
        return await retained_dispatch(request, job_id, command_id)

    def activate(values):
        if not isinstance(values, dict) or set(values) != set(current) - {'validation_profile'}:
            raise ValueError('Unsupported effective configuration')
        from desktop.control_store import ControlError, validate_changes
        # Full shared validation also runs before the supervisor journals intent.
        try:
            validate_changes(dict(values), dict(values, engineering_enabled=True, batch_mode='standard',
                             local_validation_workers=1, start_at_login=True, update_mode='notify', update_interval_hours=24),
                             local=True, listener=(config.host, config.port, config.allowed_hosts))
        except ControlError as error:
            if error.code == 'invalid_backend':
                return {'status': 'setup_required', 'reason': 'invalid_backend',
                        'error': {'code': 'invalid_backend', 'message': str(error)}, **observed()}
            raise ValueError('Invalid runtime settings') from None
        if values['backend'] != current['backend'] or values['model'] != current['model']:
            try:
                probe_backend(values['backend'], values['model'])
            except (requests.RequestException, ValueError):
                return {'status': 'setup_required', 'reason': 'probe_failed', **observed()}
        if not prepare(runtime, state, engineering):
            return {'status': 'waiting_for_jobs', **observed()}
        try:
            with state.capacity:
                # The same admission lock remains drained while all values change.
                if values['public_repository_maps'] and engineering.public_graph is None:
                    from v14.code_graph.service import GraphService
                    from v14.code_graph.public_github import PublicGitHubReader
                    from v14.identity import principal_can_graph
                    graph = GraphService(engineering.store, PublicGitHubReader(), public=True)
                    graph.authorized = lambda principal: config.public_repository_maps and principal_can_graph(config, principal, runtime.device_store)
                    engineering.public_graph = graph
                    engineering.graph.public_service = graph
                    graph.start_jobs()
                runtime.LLAMA_BASE = values['backend']
                runtime.gateway_default_model = values['model']
                state.max_workers = values['workers']
                object.__setattr__(config, 'max_workers', values['workers'])
                object.__setattr__(config, 'max_active_chats', values['active_chats'])
                object.__setattr__(config, 'public_repository_maps', values['public_repository_maps'])
                runtime.gateway_debug.enabled = values['debug_mode']
                current.update(values)
                state.capacity.notify_all()
        finally:
            with state.lock:
                state.maintenance_generation += 1
                state.draining = runtime.gateway_draining = False
        return {'status': 'applied', **observed()}

    @app.post('/gateway/admin/settings')
    async def apply_settings(request: Request):
        raw = await request.body()
        if len(raw) > 8192:
            return JSONResponse({'error': {'code': 'settings_too_large'}}, status_code=413)
        try:
            result = await asyncio.to_thread(activate, json.loads(raw))
            return JSONResponse(result, status_code=200 if result['status'] == 'applied' else 409)
        except (ValueError, TypeError, KeyError):
            return JSONResponse({'error': {'code': 'invalid_settings'}}, status_code=400)
