"""Versioned public requests and trusted (never model-supplied) work grants."""
from __future__ import annotations

import hashlib
import ipaddress
import json
import re
from dataclasses import asdict, dataclass
from urllib.parse import urlsplit, urlunsplit


class CrawlerError(Exception):
    def __init__(self, code: str, message: str, details=None):
        self.code, self.message, self.details = code, message, details or {}
        super().__init__(message)


def canonical_json(value) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def digest(value: str | bytes) -> str:
    return hashlib.sha256(value.encode("utf-8") if isinstance(value, str) else value).hexdigest()


def require(condition, message, code="INVALID_ARGUMENTS"):
    if not condition:
        raise CrawlerError(code, message)


def url_identity(value: str) -> str:
    require(isinstance(value, str) and 1 <= len(value) <= 8192, "URL must be a bounded string.")
    require(not any(ord(c) < 33 or ord(c) == 127 for c in value), "URL contains whitespace or control characters.")
    try:
        url = urlsplit(value)
        port = url.port
        host = (url.hostname or "").encode("idna").decode("ascii").lower()
    except (ValueError, UnicodeError):
        raise CrawlerError("INVALID_URL", "Malformed URL.") from None
    require(url.scheme in ("https", "http") and host and url.username is None and url.password is None, "Only HTTP(S) URLs without embedded credentials are supported.", "INVALID_URL")
    require(port in (None, 80, 443), "Only standard web ports are supported.", "INVALID_URL")
    require(not host.endswith((".localhost", ".local", ".internal")) and host != "localhost", "Private destinations are not allowed.", "SOURCE_FORBIDDEN")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        require(bool(re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host)) and "." in host, "Invalid public hostname.", "SOURCE_FORBIDDEN")
    else:
        require(address.is_global, "Private destinations are not allowed.", "SOURCE_FORBIDDEN")
    netloc = f"[{host}]" if ":" in host else host
    if port is not None and port != (443 if url.scheme == "https" else 80):
        netloc += f":{port}"
    # Queries, case-sensitive paths and SPA fragments are part of source identity.
    return urlunsplit((url.scheme, netloc, url.path or "/", url.query, url.fragment))


def bounded_int(value, name, low, high):
    require(type(value) is int and low <= value <= high, f"{name} must be an integer from {low} to {high}.")
    return value


def text(value, name, maximum=128):
    require(isinstance(value, str) and 1 <= len(value) <= maximum and not any(ord(c) < 32 for c in value), f"Invalid {name}.")
    return value


@dataclass(frozen=True)
class WorkGrant:
    principal: str
    grant_id: str
    root_run_id: str
    work_item_id: str
    policy_fingerprint: str
    generation: int
    max_requests: int
    max_downloaded_bytes: int
    expires_at: float
    credential_scope: str = "public"
    ephemeral: bool = False

    def __post_init__(self):
        for name in ("principal", "grant_id", "root_run_id", "work_item_id", "policy_fingerprint", "credential_scope"):
            text(getattr(self, name), name, 256)
        bounded_int(self.generation, "generation", 0, 2**63 - 1)
        bounded_int(self.max_requests, "request grant", 0, 2**31 - 1)
        bounded_int(self.max_downloaded_bytes, "byte grant", 0, 2**63 - 1)
        require(type(self.expires_at) in (int, float) and 0 < self.expires_at < 1e12, "Invalid grant expiry.")

    @property
    def scope(self):
        return digest(canonical_json({"principal": self.principal, "credentials": self.credential_scope, "policy": self.policy_fingerprint}))


DEFAULTS = {"mode": "smart", "sourceGoal": 20, "maxPages": 100, "maxDepth": 3,
            "maxPhysicalRequests": 1000, "maxDownloadedBytes": 250 * 1024 * 1024,
            "deadlineMs": 900_000, "format": "text", "outputTokens": 3000, "outputBytes": 64_000}
RANGES = {"sourceGoal": (1, 1_000_000), "maxPages": (1, 1_000_000), "maxDepth": (0, 100),
          "maxPhysicalRequests": (1, 2**31 - 1), "maxDownloadedBytes": (1, 2**53 - 1),
          "deadlineMs": (1, 86_400_000), "outputTokens": (1, 100_000), "outputBytes": (1, 1_000_000)}


def request(operation: str, arguments: dict) -> dict:
    require(type(arguments) is dict, "Arguments must be an object.")
    common_output = {"format", "outputTokens", "outputBytes"}
    fields = {
        "fetch": {"url", "query", "refresh", "mode", "deadlineMs"} | common_output,
        "start": {"seeds", "resumeFromJobId", "scope", "idempotencyKey"} | set(DEFAULTS),
        "status": {"jobId"}, "results": {"jobId", "cursor"} | common_output,
        "cancel": {"jobId"},
    }
    require(operation in fields, "Unknown operation.")
    require(not arguments.keys() - fields[operation], "Unknown argument; caller identity and grants are trusted metadata.")
    result = dict(arguments)
    if operation in ("fetch", "start", "results"):
        keys = set(DEFAULTS) if operation == "start" else common_output | ({"mode", "deadlineMs"} if operation == "fetch" else set())
        for key in keys:
            result.setdefault(key, 25_000 if operation == "fetch" and key == "deadlineMs" else DEFAULTS[key])
        require(result["format"] in ("text", "markdown"), "Unsupported format.")
        for key, (low, high) in RANGES.items():
            if key in result:
                bounded_int(result[key], key, low, high)
    if operation in ("fetch", "start"):
        require(result["mode"] in ("quick", "smart", "deep"), "Unknown crawl mode.")
    if operation == "fetch":
        require(result["mode"] != "deep", "Use start for deep crawling.")
        result["url"] = url_identity(result.get("url"))
        if "query" in result:
            text(result["query"], "query", 1000)
        require(type(result.get("refresh", False)) is bool, "refresh must be boolean.")
    elif operation == "start":
        text(result.get("idempotencyKey"), "idempotencyKey")
        require(("seeds" in result) != ("resumeFromJobId" in result), "Choose seeds or resumeFromJobId, exclusively.")
        if "seeds" in result:
            seeds = result["seeds"]
            require(type(seeds) is list and 1 <= len(seeds) <= 1000, "Provide 1–1000 seed URLs.")
            result["seeds"] = list(dict.fromkeys(url_identity(url) for url in seeds))
            hosts = sorted({urlsplit(url).hostname for url in result["seeds"]})
            scope = result.get("scope", {"allowedHosts": hosts})
            require(type(scope) is dict and set(scope) == {"allowedHosts"}, "scope must specify allowedHosts.")
            require(type(scope["allowedHosts"]) is list and 1 <= len(scope["allowedHosts"]) <= 1000, "Invalid allowedHosts.")
            allowed = sorted({urlsplit(url_identity("https://" + text(host, "host", 253))).hostname for host in scope["allowedHosts"]})
            require(set(hosts) <= set(allowed), "Seeds must be inside the accepted host scope.")
            result["scope"] = {"allowedHosts": allowed}
        else:
            text(result["resumeFromJobId"], "resumeFromJobId")
            require("scope" not in result, "Resume retains the original source scope.")
    else:
        text(result.get("jobId"), "jobId")
        if "cursor" in result:
            text(result["cursor"], "cursor", 4096)
    return result


def tool_definitions():
    string = {"type": "string", "minLength": 1, "maxLength": 128}
    output = {"format": {"type": "string", "enum": ["text", "markdown"], "default": "text"}}
    output.update({key: {"type": "integer", "minimum": low, "maximum": high, "default": DEFAULTS[key]}
                   for key, (low, high) in RANGES.items() if key in ("outputTokens", "outputBytes")})
    start = {key: {"type": "integer", "minimum": low, "maximum": high, "default": DEFAULTS[key]}
             for key, (low, high) in RANGES.items()}
    mode = {"type": "string", "enum": ["quick", "smart", "deep"], "default": "smart"}
    url = {"type": "string", "minLength": 1, "maxLength": 8192}
    start.update(output, mode=mode, seeds={"type": "array", "minItems": 1, "maxItems": 1000, "items": url},
                 resumeFromJobId=string, idempotencyKey=string,
                 scope={"type": "object", "additionalProperties": False, "required": ["allowedHosts"],
                        "properties": {"allowedHosts": {"type": "array", "minItems": 1, "maxItems": 1000, "items": {"type": "string", "maxLength": 253}}}})
    definitions = {
        "fetch": ({"url": url, "query": {"type": "string", "minLength": 1, "maxLength": 1000}, "refresh": {"type": "boolean"},
                   "mode": dict(mode, enum=["quick", "smart"]), "deadlineMs": dict(start["deadlineMs"], default=25_000), **output}, ["url"]),
        "start": (start, ["idempotencyKey"]),
        "status": ({"jobId": string}, ["jobId"]),
        "results": ({"jobId": string, "cursor": {"type": "string", "minLength": 1, "maxLength": 4096}, **output}, ["jobId"]),
        "cancel": ({"jobId": string}, ["jobId"]),
    }
    tools = []
    for name, (properties, required) in definitions.items():
        schema = {"type": "object", "additionalProperties": False, "properties": properties, "required": required}
        if name == "start":
            schema["oneOf"] = [{"required": ["seeds"], "not": {"required": ["resumeFromJobId"]}}, {"required": ["resumeFromJobId"], "not": {"required": ["seeds"]}}]
        tools.append({"name": name, "description": f"Crawler {name}. Schema v1. Acquisition is not scientific appraisal; retained evidence and actual work are reported separately.",
                      "inputSchema": schema, "annotations": {"readOnlyHint": name in ("fetch", "status", "results"), "idempotentHint": name != "fetch"},
                      "_meta": {"gptmobile/crawlerSchema": 1, "gptmobile/operation": name}})
    return tools
