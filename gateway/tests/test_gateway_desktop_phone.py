"""Exercise the actual companion TLS listener, mTLS separation and settings journal."""
import asyncio
import base64
from contextlib import asynccontextmanager, closing
import hashlib
import json
from pathlib import Path
import socket
import sqlite3
import ssl
import sys
import tempfile
import unittest
from types import SimpleNamespace
from urllib.parse import parse_qs, urlparse
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from desktop import phone_access, phone_settings
from desktop.configuration import DEFAULTS, atomic_json, load
from desktop.phone_listener import companion_lifespan
from v14.conversations import Conversations
from v14.engineering import EngineeringStore
from fastapi.responses import JSONResponse


class Store:
    admission_settings = staticmethod(EngineeringStore.admission_settings)
    conversations = Conversations

    def __init__(self, root):
        self.path = root / 'data' / 'jobs.sqlite3'
        self.path.parent.mkdir(exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE gateway_installation(key TEXT PRIMARY KEY, value TEXT)')
            db.execute("INSERT INTO gateway_installation VALUES ('gateway_id','test-installation')")
            db.execute('CREATE TABLE engineering_admission_settings(principal TEXT PRIMARY KEY, enabled INTEGER, revision INTEGER)')

    def tx(self, fn):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('BEGIN IMMEDIATE')
            return fn(db)


@asynccontextmanager
async def base(app):
    yield


class PairedControls(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)
        self.store = Store(self.root)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            self.port = sock.getsockname()[1]
        self.host = '192.168.2.8'
        # Bind on a real local socket. The enrolled certificate and HTTP authority use the PC's advertised LAN address.
        self.cfg = phone_access.configure(self.root, self.host, self.port)
        loop = asyncio.get_running_loop()
        create = loop.create_server
        async def loopback_socket(factory, **kwargs):
            kwargs['host'] = '127.0.0.1'
            return await create(factory, **kwargs)
        from unittest.mock import patch
        patched = patch.object(loop, 'create_server', loopback_socket)
        patched.start()
        self.addCleanup(patched.stop)
        async def engine(scope, receive, send):
            await JSONResponse({'owner': scope['gateway.paired'].id})(scope, receive, send)
        self.lifecycle = companion_lifespan(base, self.root, self.store)(engine)
        await self.lifecycle.__aenter__()
        self.addAsyncCleanup(self.lifecycle.__aexit__, None, None, None)
        self.ctx = ssl.create_default_context(cafile=str(phone_access.directory(self.root) / 'authority.pem'))
        for _ in range(100):
            try:
                await self.call('/gateway/owner/settings')
                break
            except ConnectionRefusedError:
                await asyncio.sleep(.01)

    async def call(self, path, body=None, ctx=None):
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port, ssl=ctx or self.ctx, server_hostname=self.host)
        self.assertEqual(self.cfg['pin'], hashlib.sha256(writer.get_extra_info('ssl_object').getpeercert(binary_form=True)).hexdigest())
        packet = json.dumps(body).encode() if body is not None else b''
        method = 'POST' if body is not None else 'GET'
        writer.write(f'{method} {path} HTTP/1.1\r\nHost: {self.host}:{self.port}\r\nContent-Type: application/json\r\nContent-Length: {len(packet)}\r\nConnection: close\r\n\r\n'.encode() + packet)
        await writer.drain()
        result = await asyncio.wait_for(reader.read(), 5)
        writer.close()
        await writer.wait_closed()
        headers, data = result.split(b'\r\n\r\n', 1)
        return int(headers.split()[1]), json.loads(data)

    async def pair(self, role='owner'):
        link = phone_access.invite(self.root, role)
        code = parse_qs(urlparse(link).query)['code'][0]
        key = ec.generate_private_key(ec.SECP256R1())
        public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        body = dict(public_key=base64.b64encode(public).decode(), name='Test phone', code=code)
        status, grant = await self.call('/gateway/owner/pair', body)
        self.assertEqual(200, status)
        self.assertEqual(403, (await self.call('/gateway/owner/pair', body))[0])
        contexts = []
        for field in ('certificate','data_certificate'):
            cert = x509.load_der_x509_certificate(base64.b64decode(grant[field]))
            target = self.root / (field + '.pem')
            target.write_bytes(cert.public_bytes(serialization.Encoding.PEM) + key.private_bytes(
                serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            ctx = ssl.create_default_context(cafile=str(phone_access.directory(self.root) / 'authority.pem'))
            ctx.load_cert_chain(str(target))
            contexts.append(ctx)
        return grant['device'], *contexts

    async def test_owner_scopes_replay_stale_changes_deferred_worker_and_revocation(self):
        path = '/gateway/owner/settings'
        self.assertEqual(403, (await self.call(path))[0])
        device, management, data = await self.pair()
        self.assertEqual(403, (await self.call(path, ctx=data))[0])
        self.assertEqual(403, (await self.call('/gateway/identity', ctx=management))[0])
        self.assertEqual(device, (await self.call('/gateway/identity', ctx=data))[1]['owner'])
        status, initial = await self.call(path, ctx=management)
        self.assertEqual(200, status)
        self.assertFalse(initial['desired']['engineering'])
        def command(obs, **patch):
            return dict(command_id=uuid4().hex, installation=obs['installation'], device=device, revision=obs['revision'], changes=patch)
        enable = command(initial, engineering=True)
        status, applied = await self.call(path, enable, management)
        self.assertEqual(200, status)
        self.assertTrue(applied['effective']['engineering'])
        self.assertEqual('applied', (await self.call(path, enable, management))[1]['command_state'])
        self.assertEqual(409, (await self.call(path, command(initial, workers=4), management))[0])
        self.assertEqual(409, (await self.call(path, dict(enable, changes={'engineering':False}), management))[0])
        self.assertEqual(400, (await self.call(path, command(applied, engineering=1), management))[0])
        status, queued = await self.call(path, command(applied, workers=4), management)
        self.assertEqual(200, status)
        self.assertEqual((4,2), (queued['desired']['workers'], queued['effective']['workers']))
        phone_settings.project(self.root, lambda *_: None)
        self.assertEqual(4, load(self.root)['workers'])
        self.assertTrue(phone_settings.needs_activation(self.root, dict(DEFAULTS, public_repository_maps=True)))
        self.assertFalse(phone_settings.needs_activation(self.root, dict(DEFAULTS, workers=4, public_repository_maps=True)))
        phone_access.revoke(self.root, device)
        self.assertEqual(403, (await self.call(path, ctx=management))[0])
        self.assertEqual(403, (await self.call('/gateway/identity', ctx=data))[0])

    async def test_viewer_and_other_device_cannot_change_owner(self):
        owner, control, _ = await self.pair()
        viewer, see, _ = await self.pair('viewer')
        path = '/gateway/owner/settings'
        self.assertEqual('viewer', (await self.call(path, ctx=see))[1]['role'])
        self.assertEqual(403, (await self.call(path, {}, see))[0])
        _, initial = await self.call(path, ctx=control)
        wrong = dict(command_id=uuid4().hex, installation=initial['installation'], device=viewer,
                     revision=initial['revision'], changes={'engineering':True})
        self.assertEqual(409, (await self.call(path, wrong, control))[0])
        self.assertNotEqual(viewer, owner)
