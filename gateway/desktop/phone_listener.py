"""Optional TLS-only companion listener sharing the engine event loop and journal."""
import asyncio
from contextlib import asynccontextmanager
import hashlib
import json
import logging
from pathlib import Path
import ssl
import time

from fastapi import Request
from fastapi.responses import JSONResponse
from uvicorn import Config, Server
from uvicorn.protocols.http.h11_impl import H11Protocol

from desktop import phone_access, phone_settings
from desktop.configuration import atomic_json, load
from v14.identity import Principal
from v14.state_lock import StateDirectoryAlreadyOwned


async def read_json(request):
    data = bytearray()
    async for block in request.stream():
        if len(block) > 8192 - len(data):
            raise ValueError('Request exceeds limit.')
        data.extend(block)
    return json.loads(data)


def protocol(root, cfg, store, effective, readiness=None):
    endpoint = f"{cfg['host']}:{cfg['port']}"
    class PairedHTTP(H11Protocol):
        def connection_made(self, transport):
            super().connection_made(transport)
            peer = transport.get_extra_info('ssl_object').getpeercert(binary_form=True)
            fingerprint = hashlib.sha256(peer).hexdigest() if peer else None
            downstream = self.app

            async def handle(scope, receive, send):
                request = Request(scope, receive)
                path = scope['path'].removeprefix('/v1')
                def grant():
                    with phone_access.database(root) as db:
                        return db.execute('SELECT id,role,cert_hash=?,revoked FROM grants WHERE (cert_hash=? OR data_cert=?) AND revoked!=1', (fingerprint, fingerprint, fingerprint)).fetchone()
                async def reply(data, status=200):
                    await JSONResponse(data, status_code=status, headers={'Cache-Control': 'no-store'})(scope, receive, send)
                if request.headers.getlist('host') != [endpoint] or any(k in request.headers for k in ('origin','cookie','forwarded','x-forwarded-for','sec-fetch-site','x-gateway-management')):
                    return await reply({'error': 'invalid_control_origin'}, 403)
                try:
                    if path == '/gateway/owner/pair' and request.method == 'POST' and fingerprint is None:
                        return await reply(await asyncio.to_thread(phone_access.redeem, root, await read_json(request)))
                    device = await asyncio.to_thread(grant) if fingerprint else None
                    if not device:
                        return await reply({'error': 'paired_device_required'}, 403)
                    ident, role, management, status = device
                    if status == 2:
                        return await reply({'error':'awaiting_windows_approval'}, 428)
                    if path.startswith('/gateway/owner/') and not management:
                        return await reply({'error': 'management_grant_required'}, 403)
                    if path == '/gateway/owner/settings' and request.method in {'GET','POST'}:
                        body = await read_json(request) if request.method == 'POST' else None
                        def apply(db):
                            args = (root, db, store, ident, role, effective)
                            if request.method == 'GET':
                                return phone_settings.snapshot(*args, command=request.query_params.get("command_id"))
                            return phone_settings.change(*args, body)
                        result = await asyncio.to_thread(store.tx, apply)
                        if readiness:
                            health = await asyncio.to_thread(readiness)
                            result['engineering_ready'] = health.get('ready', False)
                            checks = health.get('components', health.get('checks', {}))
                            result['engineering_checks'] = {key: str(checks[key])[:60] for key in ('journal','worker','host_github_connection','draining') if key in checks}
                        return await reply(result)
                    if path == '/gateway/owner/revoke' and request.method == 'POST':
                        await asyncio.to_thread(phone_access.revoke, root, ident)
                        return await reply({'revoked': True})
                    if management or role != 'owner' or path.startswith('/gateway/admin') or path.startswith('/gateway/owner') or path == '/gateway/engineering/admission-settings' and request.method != 'GET':
                        return await reply({'error': 'scope_not_granted'}, 403)
                    scope['gateway.paired'] = Principal(ident, frozenset({'chat','jobs','tools','engineering'}))
                    await downstream(scope, receive, send)
                except phone_settings.Changed:
                    return await reply({'error':'settings_changed'}, 409)
                except PermissionError:
                    return await reply({'error':'permission_denied'}, 403)
                except (ValueError, KeyError, TypeError):
                    return await reply({'error':'invalid_request'}, 400)
            self.app = handle
    return PairedHTTP


def companion_lifespan(base, root, store, readiness=None):
    @asynccontextmanager
    async def lifespan(app):
        async with base(app):
            if not root or not (Path(root) / 'settings.json').is_file():
                yield
                return
            root_path = Path(root)
            config_path = phone_access.directory(root_path) / 'listener.json'
            running = load(root_path)
            from desktop.configuration import public_repository_maps
            effective = {k: running[k] for k in ('workers','active_chats','debug_mode','backend')}
            effective['public_repository_maps'] = public_repository_maps(root_path)
            from desktop.phone_policies import active as active_policy
            effective['validation_profile'] = active_policy(root_path)
            async def observe():
                server = None
                owner = None
                active = None
                heartbeat = 0
                def report(state, config):
                    try:
                        atomic_json(config_path.with_name('listener-status.json'),
                                    {'state': state, 'host': config['host'], 'port': config['port'], 'observed': time.time()})
                    except OSError:
                        logging.getLogger(__name__).warning('Could not save phone listener status')
                try:
                    while True:
                        cfg = None
                        try:
                            cfg = json.loads(config_path.read_text()) if config_path.exists() else None
                            if cfg != active:
                                if server:
                                    server.close()
                                    await server.wait_closed()
                                    for connection in list(owner.server_state.connections):
                                        connection.shutdown()
                                    server = None
                                    active = None
                                if cfg:
                                    folder = phone_access.directory(root_path)
                                    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                                    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
                                    secret = await asyncio.to_thread(phone_access.listener_password, root_path)
                                    ctx.load_cert_chain(str(folder / ('server-' + cfg['host'] + '.pem')), password=secret)
                                    ctx.load_verify_locations(cafile=str(folder / 'authority.pem'))
                                    ctx.verify_mode = ssl.CERT_OPTIONAL
                                    uv = Config(app, http=protocol(root_path, cfg, store, effective, readiness), ws='none', lifespan='off',
                                                access_log=False, log_config=None, proxy_headers=False, limit_concurrency=32)
                                    uv.load()
                                    owner = Server(uv)
                                    loop = asyncio.get_running_loop()
                                    server = await loop.create_server(lambda: uv.http_protocol_class(config=uv, server_state=owner.server_state, app_state={}),
                                        host=cfg['host'], port=cfg['port'], ssl=ctx, ssl_handshake_timeout=5)
                                    active = cfg
                                    heartbeat = 0
                            if server and active and time.monotonic() >= heartbeat:
                                report('listening', active)
                                heartbeat = time.monotonic() + 10
                        except (OSError, ValueError, KeyError, StateDirectoryAlreadyOwned):
                            if isinstance(cfg, dict) and 'port' in cfg and 'host' in cfg:
                                report('unavailable', cfg)
                            logging.getLogger(__name__).warning('Phone listener configuration or bind unavailable')
                        await asyncio.sleep(5)
                finally:
                    if server:
                        if active:
                            report('stopped', active)
                        server.close()
                        await server.wait_closed()
                        for connection in list(owner.server_state.connections):
                            connection.shutdown()
                    if owner:
                        for task in list(owner.server_state.tasks):
                            task.cancel()
            observer = asyncio.create_task(observe())
            try:
                yield
            finally:
                observer.cancel()
                await asyncio.gather(observer, return_exceptions=True)
    return lifespan
