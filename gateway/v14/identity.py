"""Pure ASGI admission; principal context also scopes legacy jobs and caches."""
import asyncio
import hashlib
import hmac
import ipaddress
import secrets
from urllib.parse import urlsplit
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
        path = scope.get('path', '').removeprefix('/v1')
        if path in {"/gateway/admin/maintenance", "/gateway/admin/dashboard"} or (
                path == '/gateway/admin/debug/export' and 'x-gateway-management' in headers):
            # A specific-interface listener is reached through that interface on
            # the same PC. The independent supervisor secret is still mandatory.
            server = scope.get("server") or ("", 0)
            client = scope.get("client") or ("", 0)
            local_control = local_peer or (client[0] == server[0] == self.config.host and self.config.host not in {"0.0.0.0", "::"})
            token = self.config.management_token
            forwarded = any(k.startswith("tailscale-") or k in {"forwarded", "x-forwarded-for", "x-forwarded-host", "origin", "cookie", "sec-fetch-site"} for k in headers)
            if not local_control or forwarded or not token or not hmac.compare_digest(headers.get("x-gateway-management", "").encode(), token.encode()):
                return None
            return Principal("local:manager", frozenset({"chat"}), True)
        if self.config.mode == "open_network":
            # Explicit key-free network mode has one shared identity. Client
            # headers never select a user, job owner or administrative privilege.
            scopes = {"chat", "jobs", "tools"}
            if self.config.remote_engineering:
                scopes.add("engineering")
            return Principal("network:shared", frozenset(scopes))
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


def principal_can_chat(config, principal, devices):
    """Revalidate a retained admission immediately before first execution."""
    if config.mode == 'open_network':
        return principal == 'network:shared'
    if config.mode == 'loopback':
        return principal == 'local:single-user' or bool(config.legacy_bearer and any(row[0] == principal and not row[2] for row in devices.list_devices()))
    return any(principal == 'tailnet:' + grant['principalId'] and {'chat', 'tools'} <= set(grant.get('scopes', [])) for grant in config.trusted_users.values())


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
        sensitive = {"host", "origin", "authorization", "tailscale-user-login", "x-gateway-csrf", "x-gateway-management",
                     "x-gateway-profile-id", "x-gateway-profile-name-base64", "x-gateway-conversation-retention",
                     "x-gateway-installation-id", "x-gateway-principal-id"}
        if any(sum(k == name for k, _ in pairs) > 1 for name in sensitive):
            return await deny("duplicate_admission_header", 400)
        headers = dict(pairs)
        host = headers.get("host", "")
        # Open network mode accepts the actual destination IP plus explicitly
        # configured hostnames. Arbitrary DNS names would allow DNS rebinding.
        socket_host = False
        if self.config.mode == "open_network":
            try:
                authority = urlsplit("http://" + host)
                server = scope.get("server") or ("", 0)
                socket_host = bool(authority.hostname and
                    ipaddress.ip_address(authority.hostname) == ipaddress.ip_address(server[0]) and
                    (authority.port or 80) == server[1] and not authority.username and not authority.password and
                    not authority.path and not authority.query and not authority.fragment and
                    not any(c.isspace() or c in "\\@" for c in host))
            except ValueError:
                pass
        if host not in self.config.allowed_hosts and not socket_host:
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
        engineering_path = path.startswith("/gateway/engineering/")
        conversation_path = path == '/gateway/conversations' or path.startswith('/gateway/conversations/')
        catalog_path = path in {'/gateway/identity', '/gateway/job-catalog'} or path.startswith('/gateway/commands/')
        diagnostics_path = path in {"/gateway/debug/events", "/gateway/debug/export"}
        diagnostics_write = path == "/gateway/debug/events" and scope["method"] == "POST"
        if engineering_path and not principal.local_admin and "engineering" not in principal.scopes:
            return await deny("engineering_scope_required")
        if self.config.mode in {"trusted_proxy", "open_network"} and not principal.local_admin:
            public_reads = {"/models", "/gateway/capabilities", "/gateway/ready", "/gateway/live", "/gateway/v13", "/gateway/v14", "/gateway/v15", "/gateway/v16", "/gateway/connections", "/gateway/tools", "/gateway/session", "/gateway/dashboard"}
            job_path = path == "/gateway/jobs" or path.startswith("/gateway/jobs/")
            if scope["method"] in {"GET", "HEAD"}:
                if path not in public_reads and not job_path and not engineering_path and not diagnostics_path and not conversation_path and not catalog_path:
                    return await deny("remote_surface_not_admitted")
            elif not engineering_path and not (conversation_path and path.endswith('/messages') and scope['method'] == 'POST') and not diagnostics_write and path != "/chat/completions" and not (job_path and path.endswith("/cancel") and scope["method"] == "POST"):
                return await deny("local_admin_required")
        required = "engineering" if engineering_path and not principal.local_admin else "jobs" if path.startswith("/gateway/jobs") or conversation_path or catalog_path else "tools" if path.startswith(("/gateway/tools", "/gateway/mcp", "/tools")) else "chat"
        granted = required in principal.scopes or diagnostics_path and 'diagnostics' in principal.scopes
        if conversation_path or catalog_path:
            granted = bool(principal.scopes) if path == '/gateway/identity' else principal.local_admin or bool(principal.scopes & {'jobs', 'engineering'})
            if scope['method'] == 'POST':
                granted = principal.local_admin or 'engineering' in principal.scopes
        if not granted:
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
