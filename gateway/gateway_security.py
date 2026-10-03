"""Local pairing, revocable device grants and durable job ownership. No external service."""
import argparse
import asyncio
import contextvars
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from pathlib import Path
from contextlib import contextmanager
from urllib.parse import urlencode, urlparse

from fastapi.responses import JSONResponse

current_device = contextvars.ContextVar("gateway_device", default=None)


class DeviceStore:
    def __init__(self, path=None):
        self.path = Path(path or os.getenv("GATEWAY_AUTH_DB") or Path(__file__).with_name("gateway_auth.sqlite3"))

    @contextmanager
    def connect(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=10)
        db.execute("PRAGMA busy_timeout=10000")
        db.executescript("""
            CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT NOT NULL,
              token_hash TEXT NOT NULL UNIQUE, revoked INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS pairing(code_hash TEXT PRIMARY KEY, name TEXT NOT NULL,
              api_url TEXT NOT NULL, model TEXT NOT NULL, expires INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS ownership(job_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, created INTEGER NOT NULL);
        """)
        try:
            self.path.chmod(0o600)
        except OSError:
            pass
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def digest(value):
        return hashlib.sha256(value.encode()).hexdigest()

    def authenticate(self, token):
        if not token or len(token) > 256:
            return None
        with self.connect() as db:
            row = db.execute("SELECT id, token_hash FROM devices WHERE token_hash=? AND revoked=0", (self.digest(token),)).fetchone()
        return row[0] if row and hmac.compare_digest(row[1], self.digest(token)) else None

    def issue(self, name):
        token, device = secrets.token_urlsafe(32), secrets.token_hex(16)
        with self.connect() as db:
            db.execute("INSERT INTO devices VALUES(?,?,?,0,?)", (device, name[:100], self.digest(token), int(time.time())))
        return device, token

    def pair(self, name, api_url, model):
        parsed = urlparse(api_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
            raise ValueError("Supply the gateway's reachable HTTP(S) base URL without credentials or query parameters.")
        if not model or len(model) > 200:
            raise ValueError("Supply a valid model ID.")
        code, expiry = secrets.token_urlsafe(32), int(time.time()) + 300
        with self.connect() as db:
            db.execute("DELETE FROM pairing WHERE expires <= ?", (int(time.time()),))
            db.execute("INSERT INTO pairing VALUES(?,?,?,?,?)", (self.digest(code), name[:100], api_url.rstrip("/"), model, expiry))
        endpoint = api_url.rstrip("/").removesuffix("/v1") + "/gateway/pair"
        return "gptmobile://pair?" + urlencode({"endpoint": endpoint, "code": code, "expires": expiry})

    def redeem(self, code):
        if not code or len(code) != 43:
            return None
        # Consume the code and issue the credential in one transaction; concurrent fetches cannot both succeed.
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT name,api_url,model,expires FROM pairing WHERE code_hash=? AND expires>?", (self.digest(code), int(time.time()))).fetchone()
            if row is None:
                return None
            token, device = secrets.token_urlsafe(32), secrets.token_hex(16)
            db.execute("DELETE FROM pairing WHERE code_hash=?", (self.digest(code),))
            db.execute("INSERT INTO devices VALUES(?,?,?,0,?)", (device, row[0], self.digest(token), int(time.time())))
        return {"version": 1, "name": row[0], "provider": "LLAMA", "apiUrl": row[1], "model": row[2], "expiresAt": row[3], "token": token}

    def owns(self, job_id, device=None):
        device = device or current_device.get()
        if not device:
            return False
        with self.connect() as db:
            row = db.execute("SELECT device_id FROM ownership WHERE job_id=?", (job_id,)).fetchone()
        return bool(row and hmac.compare_digest(row[0], device))

    def claim(self, job_id, device=None):
        device = device or current_device.get()
        if not device:
            raise PermissionError("A paired device is required.")
        with self.connect() as db:
            db.execute("INSERT OR IGNORE INTO ownership VALUES(?,?,?)", (job_id, device, int(time.time())))
            row = db.execute("SELECT device_id FROM ownership WHERE job_id=?", (job_id,)).fetchone()
            if row[0] != device:
                raise PermissionError("This job belongs to another device.")

    def list_devices(self):
        with self.connect() as db:
            return db.execute("SELECT id,name,revoked,created FROM devices ORDER BY created DESC").fetchall()

    def revoke(self, device):
        with self.connect() as db:
            db.execute("UPDATE devices SET revoked=1 WHERE id=?", (device,))


device_store = DeviceStore()


class DeviceAuthentication:
    """Pure ASGI middleware preserves streaming and context through child tasks."""
    def __init__(self, app, store=None):
        self.app, self.store = app, store or device_store

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        path = scope.get("path", "")
        headers = dict(scope.get("headers", []))
        authorization = headers.get(b"authorization", b"").decode("latin1")
        credential = authorization[7:] if authorization.lower().startswith("bearer ") else ""
        if path == "/gateway/pair" and scope["method"] == "GET":
            config = await asyncio.to_thread(self.store.redeem, credential)
            response = JSONResponse(config or {"detail": "Pairing code expired, invalid or already used."}, status_code=200 if config else 401, headers={"Cache-Control": "no-store"})
            return await response(scope, receive, send)
        device = await asyncio.to_thread(self.store.authenticate, credential)
        if not device:
            return await JSONResponse({"detail": "Pair this device with the gateway and configure its API token."}, status_code=401, headers={"WWW-Authenticate": "Bearer", "Cache-Control": "no-store"})(scope, receive, send)
        # Encoded paths have already been normalized by the ASGI server.
        parts = path.removeprefix("/v1").strip("/").split("/")
        if len(parts) >= 3 and parts[:2] == ["gateway", "jobs"] and not await asyncio.to_thread(self.store.owns, parts[2], device):
            return await JSONResponse({"detail": "Unknown gateway job"}, status_code=404)(scope, receive, send)
        marker = current_device.set(device)
        try:
            await self.app(scope, receive, send)
        finally:
            current_device.reset(marker)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pair = commands.add_parser("pair", help="Print a one-time, five-minute Android pairing link")
    pair.add_argument("--name", default="My gateway")
    pair.add_argument("--url", required=True)
    pair.add_argument("--model", required=True)
    issue = commands.add_parser("issue", help="Print a new device credential once for manual setup")
    issue.add_argument("--name", required=True)
    commands.add_parser("list", help="List devices without credentials")
    revoke = commands.add_parser("revoke")
    revoke.add_argument("device")
    args = parser.parse_args()
    if args.command == "pair":
        print(device_store.pair(args.name, args.url, args.model))
    elif args.command == "issue":
        device, token = device_store.issue(args.name)
        print(json.dumps({"device": device, "token": token}))
    elif args.command == "list":
        print(json.dumps(device_store.list_devices()))
    else:
        device_store.revoke(args.device)


if __name__ == "__main__":
    main()
