"""Private transport configuration. Never infer trust from a private subnet."""
import ipaddress
import json
import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Config:
    mode: str = "loopback"
    host: str = "127.0.0.1"
    port: int = 8090
    allowed_hosts: tuple = ("127.0.0.1:8090", "localhost:8090", "[::1]:8090")
    allowed_origins: tuple = ("http://127.0.0.1:8090", "http://localhost:8090", "http://[::1]:8090")
    trusted_users: dict = field(default_factory=dict)
    legacy_bearer: bool = False

    def validate(self):
        if self.mode not in {"loopback", "trusted_proxy"}:
            raise ValueError("GATEWAY_AUTH_MODE must be loopback or trusted_proxy")
        try:
            local = ipaddress.ip_address(self.host).is_loopback
        except ValueError:
            local = False
        if not local:
            raise ValueError("Bind to a literal loopback address; use Tailscale Serve for phone access")
        if not 1 <= self.port <= 65535:
            raise ValueError("Invalid gateway port")
        if not self.allowed_hosts or any(not isinstance(h, str) or "*" in h or "/" in h or "@" in h for h in self.allowed_hosts):
            raise ValueError("Configure exact allowed Host authorities, without wildcards")
        for origin in self.allowed_origins:
            parsed = urlsplit(origin)
            if parsed.scheme not in {"http", "https"} or parsed.netloc not in self.allowed_hosts or parsed.path or parsed.query or parsed.fragment or parsed.username:
                raise ValueError("Origins must be exact HTTP(S) origins of allowed hosts")
        if self.mode == "trusted_proxy" and not self.trusted_users:
            raise ValueError("trusted_proxy requires GATEWAY_TRUSTED_USERS and a dedicated loopback listener behind Tailscale Serve")
        for user, grant in self.trusted_users.items():
            if not isinstance(user, str) or not user or not isinstance(grant, dict) or not isinstance(grant.get("principalId"), str) or not grant["principalId"] or len(grant["principalId"]) > 128:
                raise ValueError("Each trusted user requires an explicit principalId and scopes")
            scopes = grant.get("scopes", [])
            if not isinstance(scopes, list) or not set(scopes) <= {"chat", "jobs", "tools"}:
                raise ValueError("Trusted user scopes are chat, jobs, tools; admin is local only")
        return self

    @classmethod
    def from_env(cls):
        port = int(os.getenv("GATEWAY_PORT", "8090"))
        hosts = tuple(json.loads(os.getenv("GATEWAY_ALLOWED_HOSTS", json.dumps([f"127.0.0.1:{port}", f"localhost:{port}", f"[::1]:{port}"]))))
        origins = tuple(json.loads(os.getenv("GATEWAY_ALLOWED_ORIGINS", json.dumps(["http://" + h for h in hosts]))))
        return cls(mode=os.getenv("GATEWAY_AUTH_MODE", "loopback"), host=os.getenv("GATEWAY_HOST", "127.0.0.1"), port=port,
                   allowed_hosts=hosts, allowed_origins=origins,
                   trusted_users=json.loads(os.getenv("GATEWAY_TRUSTED_USERS", "{}")),
                   legacy_bearer=os.getenv("GATEWAY_LEGACY_BEARER", "false").lower() == "true").validate()
