"""Pure ASGI admission; principal context also scopes legacy jobs and caches."""
import asyncio
import hashlib
import hmac
import ipaddress
import secrets
from dataclasses import dataclass

from fastapi.responses import JSONResponse
from gateway_security import current_device, device_store


@dataclass(frozen=True)
class Principal:
    id: str
    scopes: frozenset
    local_admin: bool = False


class PrincipalResolver:
    def __init__(self, config, store):
        self.config, self.store = config, store

    async def resolve(self, scope, headers):
        # Uvicorn must run with proxy_headers=False: check actual socket peer.
        try:
            local_peer = ipaddress.ip_address(scope.get("client", ("", 0))[0]).is_loopback
        except ValueError:
            local_peer = False
        if not local_peer:
            return None
        if self.config.legacy_bearer and headers.get("authorization", "").lower().startswith("bearer "):
            device = await asyncio.to_thread(self.store.authenticate, headers["authorization"][7:])
            return Principal(device, frozenset({"chat", "jobs", "tools"}), self.config.mode == "loopback") if device else None
        if self.config.mode == "loopback":
            # Forwarding headers on the direct listener are never identity proof.
            if any(k.startswith("tailscale-") or k in {"forwarded", "x-forwarded-for", "x-forwarded-host"} for k in headers):
                return None
            return Principal("local:single-user", frozenset({"chat", "jobs", "tools"}), True)
        grant = self.config.trusted_users.get(headers.get("tailscale-user-login", ""))
        if not grant:
            return None
        return Principal("tailnet:" + grant["principalId"], frozenset(grant["scopes"]))


class Admission:
    def __init__(self, app, config, store=None):
        self.app, self.config, self.store = app, config, store or device_store
        self.resolver = PrincipalResolver(config, self.store)
        self.csrf_key = secrets.token_bytes(32)

    async def __call__(self, scope, receive, send):
        if scope["type"] == "lifespan":
            return await self.app(scope, receive, send)
        if scope["type"] != "http":
            if scope["type"] == "websocket":
                await send({"type": "websocket.close", "code": 1008})
            return  # WebSockets are not an admitted gateway surface.
        async def deny(code, status=403):
            await JSONResponse({"error": {"code": code}}, status_code=status, headers={"Cache-Control": "no-store", "X-Gateway-Admission-Error": code})(scope, receive, send)
        pairs = [(k.decode("latin1").lower(), v.decode("latin1")) for k, v in scope.get("headers", [])]
        sensitive = {"host", "origin", "authorization", "tailscale-user-login", "x-gateway-csrf"}
        if any(sum(k == name for k, _ in pairs) > 1 for name in sensitive):
            return await deny("duplicate_admission_header", 400)
        headers = dict(pairs)
        if headers.get("host", "") not in self.config.allowed_hosts:
            return await deny("invalid_host")
        origin = headers.get("origin")
        if origin is not None and origin not in self.config.allowed_origins:
            return await deny("invalid_origin")
        principal = await self.resolver.resolve(scope, headers)
        if principal is None:
            return await deny("transport_identity_required", 401)
        path = scope.get("path", "").removeprefix("/v1")
        if path.startswith("/gateway/admin/") or path.startswith("/gateway/mcp/restart/") or path == "/gateway/tools/refresh":
            if not principal.local_admin:
                return await deny("local_admin_required")
        if self.config.mode == "trusted_proxy":
            public_reads = {"/models", "/gateway/capabilities", "/gateway/ready", "/gateway/v13", "/gateway/v14", "/gateway/connections", "/gateway/tools", "/gateway/session"}
            job_path = path == "/gateway/jobs" or path.startswith("/gateway/jobs/")
            if scope["method"] in {"GET", "HEAD"}:
                if path not in public_reads and not job_path:
                    return await deny("remote_surface_not_admitted")
            elif path != "/chat/completions" and not (job_path and path.endswith("/cancel") and scope["method"] == "POST"):
                return await deny("local_admin_required")
        required = "jobs" if path.startswith("/gateway/jobs") else "tools" if path.startswith(("/gateway/tools", "/gateway/mcp", "/tools")) else "chat"
        if required not in principal.scopes:
            return await deny("scope_not_granted")
        # The retained runtime can select PC tools automatically. A limited user
        # cannot use chat until a separately tested tool-free runtime is available.
        if path == "/chat/completions" and "tools" not in principal.scopes:
            return await deny("tool_scope_required_for_runtime")
        csrf = hmac.new(self.csrf_key, principal.id.encode(), hashlib.sha256).hexdigest()
        if scope["method"] not in {"GET", "HEAD", "OPTIONS"} and (origin is not None or "cookie" in headers or "sec-fetch-site" in headers):
            if not hmac.compare_digest(headers.get("x-gateway-csrf", ""), csrf):
                return await deny("browser_csrf_required")
        if path == "/gateway/session" and scope["method"] == "GET":
            return await JSONResponse({"csrfToken": csrf}, headers={"Cache-Control": "no-store"})(scope, receive, send)
        parts = path.strip("/").split("/")
        if len(parts) >= 3 and parts[:2] == ["gateway", "jobs"] and not await asyncio.to_thread(self.store.owns, parts[2], principal.id):
            return await deny("unknown_job", 404)
        scope.setdefault("state", {})["principal"] = principal
        marker = current_device.set(principal.id)
        async def private_send(message):
            if message["type"] == "http.response.start":
                message["headers"] = [(k, v) for k, v in message.get("headers", []) if k.lower() != b"cache-control"] + [(b"cache-control", b"no-store")]
            await send(message)
        try:
            await self.app(scope, receive, private_send)
        finally:
            current_device.reset(marker)
