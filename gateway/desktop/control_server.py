"""Separate TLS service. No shared-LAN identity can access supervisor settings."""
import asyncio
import json
import re
import threading
import time
from urllib.parse import quote, urlencode

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from desktop.configuration import authority, control_host
from desktop.control_security import verify_signature
from desktop.control_store import ControlError

SAFE_ID = r'[A-Za-z0-9:_-]{1,200}'
READ_EXACT = {'/gateway/identity', '/gateway/job-catalog', '/gateway/dashboard', '/gateway/capabilities',
              '/gateway/ready', '/models', '/gateway/conversations', '/gateway/saved-messages',
              '/gateway/engineering/capabilities', '/gateway/engineering/admission-settings',
              '/gateway/engineering/copilot/capabilities', '/gateway/engineering/copilot/tasks',
              '/gateway/engineering/jobs'}
READ_PATTERNS = [r'/gateway/conversations/' + SAFE_ID + r'(?:/messages)?', r'/gateway/commands/' + SAFE_ID,
                 r'/gateway/engineering/copilot/tasks/' + SAFE_ID,
                 r'/gateway/engineering/repository-operations/' + SAFE_ID,
                 r'/gateway/engineering/bindings/' + SAFE_ID + r'/capabilities',
                 r'/gateway/engineering/diagnostics/jobs/' + SAFE_ID,
                 r'/gateway/jobs/' + SAFE_ID + r'(?:/(?:result|events))?',
                 r'/gateway/engineering/jobs/' + SAFE_ID + r'(?:/(?:batch-plan|qualification|checkpoints|operations|evidence))?',
                 r'/gateway/engineering/jobs/' + SAFE_ID + r'/(?:artifacts|checkpoints|evidence)/' + SAFE_ID]
POST_EXACT = {'/gateway/models/prepare', '/gateway/engineering/bindings', '/gateway/engineering/diagnostics/jobs',
              '/gateway/engineering/graph', '/gateway/engineering/profile-checks', '/gateway/engineering/preflight', '/gateway/engineering/jobs',
              '/gateway/engineering/copilot/tasks', '/gateway/engineering/repository-operations'}


def proxy_permission(route, method, payload, grant):
    """Keep newly added engine surfaces closed until their exact contract is reviewed."""
    if any(x in route for x in ('..', '\\', '%')):
        raise ControlError('control_route_not_admitted', 403)
    admitted = method == 'GET' and (route in READ_EXACT or any(re.fullmatch(pattern, route) for pattern in READ_PATTERNS))
    if method == 'POST':
        admitted = route in POST_EXACT or bool(re.fullmatch(r'/gateway/conversations/' + SAFE_ID + '/messages', route))
        admitted = admitted or bool(re.fullmatch(r'/gateway/engineering/jobs/' + SAFE_ID + r'/(?:pause|resume|cancel|approve|rewind)', route))
        admitted = admitted or bool(re.fullmatch(r'/gateway/jobs/' + SAFE_ID + r'/cancel', route))
    elif method == 'PATCH':
        admitted = bool(re.fullmatch(r'/gateway/conversations/' + SAFE_ID + '/metadata', route))
    elif method == 'PUT':
        admitted = bool(re.fullmatch(r'/gateway/conversations/' + SAFE_ID + '/bookmarks/' + SAFE_ID, route))
    if not admitted:
        raise ControlError('control_route_not_admitted', 403)
    if method != 'GET' and grant['role'] != 'owner':
        raise ControlError('owner_role_required', 403)
    repositories = set(json.loads(grant['repositories']))
    if route == '/gateway/engineering/repository-operations' and method == 'POST':
        if payload.get('operation') != 'clone_repository' or 'destination_owner' in payload or 'destination_repo' in payload:
            raise ControlError('host_approval_required', 403, 'Creating or importing a destination requires approval on the PC')
        source = str(payload.get('source_owner', '')) + '/' + str(payload.get('source_repo', ''))
        if source.lower() not in repositories:
            raise ControlError('repository_permission_required', 403)
    queue = [payload]
    count = 0
    while queue:
        node = queue.pop()
        count += 1
        if count > 4096:
            raise ControlError('control_request_too_complex')
        if isinstance(node, dict):
            if 'repository' in node and (not isinstance(node['repository'], str) or node['repository'].lower() not in repositories):
                raise ControlError('repository_permission_required', 403)
            queue.extend(value for value in node.values() if isinstance(value, (dict, list)))
        elif isinstance(node, list):
            queue.extend(value for value in node if isinstance(value, (dict, list)))


def create_app(controller):
    store = controller.store
    app = FastAPI(docs_url=None, openapi_url=None)

    @app.middleware('http')
    async def admit(request, call_next):
        try:
            headers = request.headers
            sensitive = {'authorization', 'host', 'x-gateway-installation-id', 'x-gateway-device-time',
                         'x-gateway-device-nonce', 'x-gateway-device-signature'}
            pairs = [(key.decode().lower(), value) for key, value in request.scope.get('headers', [])]
            if any(sum(key == name for key, _ in pairs) != 1 for name in sensitive):
                raise ControlError('required_control_header_missing_or_duplicated', 400)
            if request.url.scheme != 'https' or headers.get('origin') or headers.get('cookie') or headers.get('sec-fetch-site'):
                raise ControlError('paired_tls_channel_required', 403)
            if any(key.startswith('x-forwarded-') or key.startswith('tailscale-') or key in {'forwarded', 'x-gateway-management', 'x-gateway-control-principal'} for key, _ in pairs):
                raise ControlError('forwarded_identity_refused', 403)
            if headers['host'] not in controller.allowed_hosts:
                raise ControlError('invalid_host', 403)
            body = bytearray()
            try:
                async with asyncio.timeout(15):
                    async for part in request.stream():
                        body.extend(part)
                        if len(body) > 512 * 1024:
                            raise ControlError('control_request_too_large', 413)
            except TimeoutError:
                raise ControlError('control_request_timeout', 408) from None
            raw = bytes(body)
            request._body = raw
            token = headers['authorization'][7:] if headers['authorization'].startswith('Bearer ') else ''
            if not token or len(token) > 256:
                raise ControlError('device_credential_required', 401)
            with store.tx() as db:
                identity = store.installation(db)
            pairing = request.url.path in {'/gateway/pairing/claim', '/gateway/pairing/finish', '/gateway/connect'}
            if pairing:
                if request.method != 'POST':
                    raise ControlError('invalid_pairing_method', 405)
                payload = json.loads(raw)
                public_key = payload.get('public_key') if isinstance(payload, dict) else None
                if not isinstance(public_key, str):
                    raise ControlError('device_key_required')
                await asyncio.to_thread(store.pairing_key_allowed, token, public_key)
                grant = None
            else:
                grant = await asyncio.to_thread(store.authenticate, token)
                public_key = grant['public_key']
            path = (request.scope.get('raw_path') or request.url.path.encode('utf-8')).decode('ascii')
            query = request.scope.get('query_string', b'').decode('ascii')
            path += '?' + query if query else ''
            await asyncio.to_thread(verify_signature, store, headers, request.method, path, raw, public_key, identity)
            request.state.control_grant, request.state.control_token = grant, token
            response = await call_next(request)
        except ControlError as error:
            response = JSONResponse({'error': {'code': error.code, 'message': str(error)}}, status_code=error.status)
        except (ValueError, TypeError, KeyError, UnicodeError):
            response = JSONResponse({'error': {'code': 'invalid_control_request'}}, status_code=400)
        response.headers['Cache-Control'] = 'no-store'
        return response

    def principal(request):
        return 'paired:' + request.state.control_grant['id']

    async def execute(request, action):
        try:
            return await asyncio.to_thread(action)
        except ControlError as error:
            result = {'error': {'code': error.code, 'message': str(error)}}
            if error.code == 'settings_changed_elsewhere':
                result['snapshot'] = await asyncio.to_thread(store.snapshot, principal(request))
            return JSONResponse(result, status_code=error.status)

    @app.post('/gateway/connect')
    async def connect(request: Request):
        payload = await request.json()
        def issue():
            if not isinstance(payload, dict) or set(payload) != {'public_key'}:
                raise ControlError('invalid_connection_request')
            if not (port := controller.connection_port()):
                raise ControlError('gateway_address_changed', 409)
            value = store.connect_address(request.state.control_token, payload['public_key'], controller.secret)
            return dict(value, endpoint=controller.endpoint, fingerprint=controller.fingerprint, gateway_port=port)
        return await execute(request, issue)

    @app.post('/gateway/pairing/claim')
    async def claim(request: Request):
        payload = await request.json()
        return await execute(request, lambda: store.pairing_claim(request.state.control_token, payload))

    @app.post('/gateway/pairing/finish')
    async def finish(request: Request):
        payload = await request.json()
        try:
            value, status = await asyncio.to_thread(store.pairing_finish, request.state.control_token, payload, controller.secret)
            if status == 200:
                value.update(endpoint=controller.endpoint, fingerprint=controller.fingerprint)
                if value.get('engineering_connection') and (port := controller.connection_port()):
                    value['gateway_port'] = port
            return JSONResponse(value, status_code=status)
        except ControlError as error:
            return JSONResponse({'error': {'code': error.code}}, status_code=error.status)

    @app.get('/gateway/settings')
    async def settings(request: Request):
        return await execute(request, lambda: controller.phone_snapshot(principal(request)))

    @app.post('/gateway/updates')
    async def update(request: Request):
        payload = await request.json()
        if not isinstance(payload, dict) or set(payload) not in ({'action'}, {'action', 'target'}):
            raise ControlError('invalid_update_request')
        command = dict(payload, principal=principal(request))
        def dispatch():
            from desktop.manager import ManagerRequestError, request as send
            controller.phone_update_action(command)
            try:
                send(controller.supervisor.root, 'phone-update', payload=command)
            except ManagerRequestError as error:
                raise ControlError(error.code or 'update_not_accepted', error.status or 409) from None
            return {'accepted': True}
        return await execute(request, dispatch)

    @app.patch('/gateway/settings')
    async def change(request: Request):
        payload = await request.json()
        result = await execute(request, lambda: controller.patch(principal(request), payload))
        if isinstance(result, dict) and result.get('status') == 'rejected':
            return JSONResponse(result, status_code=result['http_status'])
        return result

    @app.get('/gateway/settings/commands/{command_id}')
    async def receipt(command_id: str, request: Request):
        return await execute(request, lambda: store.receipt(principal(request), command_id))

    @app.delete('/gateway/devices/self')
    async def revoke(request: Request):
        return await execute(request, lambda: store.revoke(request.state.control_grant['id']))

    @app.get('/gateway/devices/self')
    async def own_grant(request: Request):
        value = request.state.control_grant
        return {'device_id': value['id'], 'principal_id': principal(request), 'role': value['role'], 'revoked': False}

    @app.api_route('/{path:path}', methods=['GET', 'POST', 'PATCH', 'PUT', 'DELETE'])
    async def proxy(path: str, request: Request):
        # Only existing principal-scoped API families. Administrative, filesystem,
        # arbitrary MCP and inference proxy surfaces cannot be reached this way.
        route = '/' + path
        grant = request.state.control_grant
        raw = await request.body()
        try:
            payload = json.loads(raw) if raw else dict(request.query_params)
            if not isinstance(payload, dict):
                raise ValueError()
            proxy_permission(route, request.method, payload, grant)
        except ControlError as error:
            return JSONResponse({'error': {'code': error.code, 'message': str(error)}}, status_code=error.status)
        except ValueError:
            return JSONResponse({'error': {'code': 'invalid_control_request'}}, status_code=400)
        target = 'http://' + authority(control_host(controller.supervisor.cfg), controller.supervisor.cfg['port']) + route
        forwarded = {'X-Gateway-Management': controller.supervisor.token, 'X-Gateway-Control-Principal': principal(request),
                     'X-Gateway-Control-Endpoint': controller.endpoint,
                     'X-Gateway-Installation-ID': request.headers['x-gateway-installation-id'],
                     'X-Gateway-Principal-ID': principal(request), 'Content-Type': 'application/json'}
        # Observed profile identity remains attached to the same request; no auth
        # or forwarded identity from Android is relayed to the engine.
        for key in ('x-gateway-profile-id', 'x-gateway-profile-name-base64', 'idempotency-key', 'x-gateway-user-action'):
            if key in request.headers:
                forwarded[key] = request.headers[key]
        client = httpx.AsyncClient(trust_env=False, follow_redirects=False, timeout=60)
        try:
            outgoing = client.build_request(request.method, target, params=request.query_params, content=raw, headers=forwarded)
            result = await client.send(outgoing, stream=True)
        except httpx.HTTPError:
            await client.aclose()
            return JSONResponse({'error': {'code': 'runtime_unavailable', 'command_outcome_unknown': request.method != 'GET'}}, status_code=503)
        async def stream():
            size, started, checked = 0, time.monotonic(), 0
            try:
                async for chunk in result.aiter_bytes():
                    now = time.monotonic()
                    size += len(chunk)
                    if size > 16 * 1024 * 1024 or now - started > 900:
                        raise RuntimeError('Control stream exceeded its bound; reconnect using the original receipt')
                    if now - checked > 5:
                        if not await asyncio.to_thread(store.grant, principal(request)):
                            raise RuntimeError('Control grant revoked')
                        checked = now
                    yield chunk
            finally:
                await result.aclose()
                await client.aclose()
        response_headers = {name: result.headers[name] for name in ('content-type', 'retry-after', 'x-gateway-instance-id',
                            'x-gateway-id', 'x-gateway-principal-id', 'x-gateway-admission-error', 'x-gateway-command-id',
                            'x-gateway-conversation-id', 'x-gateway-detach-safe') if name in result.headers}
        return StreamingResponse(stream(), status_code=result.status_code, headers=response_headers)

    return app


def pairing_link(value, endpoint, fingerprint):
    fields = {'endpoint': endpoint, 'installation_id': value['installation_id'],
              'fingerprint': fingerprint, 'code': value['code'], 'expires': value['expires_at']}
    if 'gateway_port' in value:
        fields['gateway_port'] = value['gateway_port']
    return 'gptmobile://gateway-control?' + urlencode(fields)


class ControlServer:
    def __init__(self, controller, host, port, tls):
        import uvicorn
        self.server = uvicorn.Server(uvicorn.Config(create_app(controller), host=host, port=port,
            proxy_headers=False, ssl_certfile=tls['certificate'], ssl_keyfile=tls['key'],
            ssl_keyfile_password=tls['password'], access_log=False, log_level='warning',
            timeout_graceful_shutdown=10, limit_concurrency=32, timeout_keep_alive=5))
        self.thread = threading.Thread(target=self.server.run, name='gateway-owner-controls', daemon=True)

    def start(self):
        self.thread.start()

    def close(self):
        self.server.should_exit = True
        self.thread.join(timeout=12)
