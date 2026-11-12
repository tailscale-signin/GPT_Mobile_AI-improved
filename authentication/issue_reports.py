"""Publish approved Android issue reports with durable GitHub write reconciliation."""

from __future__ import annotations

import base64
import hashlib
import json
import re
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import closing, contextmanager
from datetime import datetime, timezone

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from .github_broker import NoRedirect


REPOSITORY = "tailscale-signin/GPT_Mobile_AI-improved"
REPORT_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
FEATURES = {"app": "App", "chat": "Chat", "voice": "Voice Chat", "gateway": "Gateway", "local": "Local models", "tools": "Tools"}
ERROR_CODES = {
    "APP_ERROR",
    "CHAT_FAILED",
    "NO_VISIBLE_ANSWER",
    "VOICE_SESSION_FAILED",
    "VOICE_PLAYBACK_FAILED",
    "VOICE_NO_SPEECH",
    "VOICE_MICROPHONE_PERMISSION_REQUIRED",
    "VOICE_RECOGNIZER_UNAVAILABLE",
    "VOICE_AUDIO_QUEUE_OVERFLOW",
    "VOICE_RECOGNIZER_FAILED",
    "VOICE_RECOGNIZER_RATE_LIMITED",
    "VOICE_RECOGNIZER_NETWORK_FAILED",
    "VOICE_RECOGNIZER_TIMED_OUT",
    "VOICE_MODEL_REQUIRED",
    "VOICE_RECOGNIZER_LANGUAGE_UNAVAILABLE",
    "VOICE_MICROPHONE_BUSY",
    "VOICE_RECOGNIZER_MEMORY_PRESSURE",
    "LOCAL_PREFILL_TIMEOUT",
    "LOCAL_SERVICE_UNREACHABLE",
    "GATEWAY_UNREACHABLE",
    "PROVIDER_RATE_LIMITED",
    "JVM_CRASH",
    "NATIVE_CRASH",
    "APP_NOT_RESPONDING",
    "STARTUP_FAILED",
    "TRANSPORT_EOF",
    "TRANSPORT_ERROR",
    "TRANSPORT_UNKNOWN",
    "DNS_RESOLUTION_FAILED",
    "NETWORK_DISCONNECTED",
    "CONNECTION_ABORTED",
    "NETWORK_TIMEOUT",
    "TLS_FAILURE",
    "AUTH_REQUIRED",
    "RATE_LIMITED",
    "READ_DEADLINE",
    "DISPATCH_OUTCOME_UNKNOWN",
    "TIMEOUT_OUTCOME_UNKNOWN",
    "TOOL_RESULT_ID_MISMATCH",
    "GATEWAY_TOOL_OUTCOME_UNKNOWN",
    "REMOTE_OUTCOME_UNKNOWN",
    "PARSING_ERROR",
    "TOOL_FAILURE",
    "OUTPUT_LIMIT",
    "INCOMPLETE_PROVIDER_STREAM",
    "CONNECTION_REFUSED",
    "CONNECTION_TIMEOUT",
    "READ_TIMEOUT",
    "INVALID_ARGUMENTS",
    "TOOL_UNAVAILABLE",
    "TOOL_OUTCOME_UNKNOWN",
    "RECONCILIATION_REQUIRED",
    "DISPATCH_POLICY_CHANGED",
    "POLICY_STALE",
    "GATEWAY_OUTCOME_UNKNOWN",
    "GITHUB_AUTHORIZATION_UNVERIFIED",
    "GITHUB_TASK_INCOMPLETE",
    "GITHUB_EVIDENCE_REQUIRED",
    "GITHUB_CIRCUIT_OPEN",
    "GITHUB_TRANSIENT_NETWORK",
    "GITHUB_TRANSIENT_SERVER",
    "GITHUB_PRIMARY_RATE_LIMITED",
    "GITHUB_SECONDARY_RATE_LIMITED",
    "GITHUB_SSO_REQUIRED",
    "GITHUB_BRANCH_RULES_BLOCKED",
    "GITHUB_ENDPOINT_PERMISSION_REQUIRED",
    "GITHUB_FORBIDDEN_UNKNOWN",
    "GITHUB_NOT_FOUND_OR_INACCESSIBLE",
    "GITHUB_CONFLICT",
    "GITHUB_VALIDATION_FAILED",
    "GITHUB_HTTP_ERROR",
    "GITHUB_OPERATION_FAILED",
    "GITHUB_LIVE_OBSERVATION_REQUIRED",
    "GITHUB_REF_UNAVAILABLE",
    "GITHUB_REPOSITORY_UNAVAILABLE",
    "GITHUB_PAGINATION_STALLED",
    "GITHUB_PAGINATION_INCOMPLETE",
    "PERMISSION_DENIED",
    "PERMISSION_REVOKED",
    "GRAPH_CONFIGURATION_REQUIRED",
    "REPOSITORY_GRAPH_UNAVAILABLE",
    "REPOSITORY_TARGET_REQUIRED",
    "REPOSITORY_TARGET_UNVERIFIED",
    "FIXTURE_RECONCILE_REQUIRED",
    "APPROVAL_PUBLICATION_UNCONFIRMED",
    "MCP_AUTHENTICATION_REQUIRED",
    "MCP_ENDPOINT_CONFIGURATION",
    "MCP_INITIALIZATION_SCHEMA_INVALID",
    "MCP_DISCOVERY_SCHEMA_INVALID",
    "MCP_DISCOVERY_CONFIGURATION",
    "MCP_PROTOCOL_VERSION_UNSUPPORTED",
    "MCP_PROTOCOL_ERROR",
    "MCP_CATALOG_TIMEOUT",
    "MCP_CONNECTION_FAILED",
    "MCP_DISCOVERY_FAILED",
    "MCP_EXECUTION_FAILED",
    "MCP_LOCAL_CONNECTION_INVALID",
    "MCP_LOCAL_ENDPOINT_INVALID",
    "MCP_LOCAL_AUTH_TYPE_INVALID",
    "MCP_LOCAL_CREDENTIAL_MISSING",
    "MCP_LOCAL_CREDENTIAL_INVALID",
    "MCP_LOCAL_CREDENTIAL_REFRESH_REQUIRED",
    "REQUIRED_TOOL_ACQUISITION_FAILED",
    "REQUIRED_TOOL_ACQUISITION_PENDING",
    "REQUIRED_TOOL_ACQUISITION_TIMED_OUT",
    "REQUIRED_TOOL_AMBIGUOUS_CONFIGURATION",
    "REQUIRED_TOOL_AUTHORIZATION_REQUIRED",
    "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE",
    "SELECTED_MODEL_NOT_LOADED",
    "SELECTED_MODEL_LOADING",
    "SELECTED_MODEL_NOT_AVAILABLE",
    "REASONING_OFF_UNSUPPORTED",
    "REASONING_ON_UNSUPPORTED",
    "REASONING_CAPABILITY_UNVERIFIED",
    "REASONING_CAPABILITY_UNAVAILABLE",
    "REASONING_CAPABILITY_TIMEOUT",
    "SAVED_VIEW_UNAVAILABLE",
    "COMPLETION_REPAIR_BUDGET_EXHAUSTED",
    "GATEWAY_REMOTE_SURFACE_NOT_ADMITTED",
    "GATEWAY_MISSING_SCOPE",
    "GATEWAY_TRANSPORT_IDENTITY_REQUIRED",
    "GATEWAY_IDENTITY_MISMATCH",
    "GATEWAY_UNSUPPORTED_CONTRACT",
    "GATEWAY_INVALID_RESPONSE",
    "GATEWAY_INVALID_CONNECTION",
    "GATEWAY_STORAGE_UNAVAILABLE",
    "GATEWAY_CURSOR_EXPIRED",
    "GATEWAY_TRANSPORT_INTERRUPTED",
    "GATEWAY_SOURCE_MISMATCH",
    "GATEWAY_HTTP_REJECTED",
    "GATEWAY_ENGINEERING_STORAGE_DISABLED",
    "GATEWAY_ENGINEERING_STORAGE_UNAVAILABLE",
    "GATEWAY_ENGINEERING_GITHUB_REQUIRED",
    "GATEWAY_ENGINEERING_DRAINING",
    "GATEWAY_ENGINEERING_WORKER_UNAVAILABLE",
    "GATEWAY_ENGINEERING_UNAVAILABLE",
    "GATEWAY_CAPABILITY_UNAVAILABLE",
    "GATEWAY_ISOLATION_UNAVAILABLE",
}
SAFE_VERSION = re.compile(r"[a-zA-Z0-9._+-]{1,48}")
SAFE_DEVICE = re.compile(r"[a-zA-Z0-9 ._()+-]{1,80}")
SAFE_FRAME = re.compile(r"(?:dev\.(?:chungjungsoo|melo)\.gptmobile|android|com\.android|java|kotlin)\.[\w.$<>]{1,145}:[0-9]{1,6}", re.ASCII)
SAFE_EVENT = re.compile(r"-[0-9]{1,5}ms [DIWEF] (?:app|chat|voice|gateway|local|tools)(?: [A-Z_]{1,64})?")
SAFE_TYPE = re.compile(r"[A-Za-z][A-Za-z0-9_$]{0,63}")
SECRET = re.compile(
    r"(?i)\b(?:bearer\s+[^\s,;]+|(?:gh[pousr]_|github_pat_|sk-|hf_|xox[baprs]-)[a-z0-9_-]{8,}"
    r"|(?:authorization|api[_ -]?key|password|(?:access|refresh)[_ -]?token|client[_ -]?secret)\s*[:=]\s*[^\s,;]+)"
)
RETRY_LEASE = 120
ADMISSION_WINDOW = 600


class ReportError(Exception):
    def __init__(self, status, code, retry_after=0):
        self.status, self.code, self.retry_after = status, code, retry_after
        super().__init__(code)


def _field(values, key, rule, default=None):
    value = values.get(key, default)
    if not isinstance(value, str) or not rule.fullmatch(value):
        raise ReportError(422, "INVALID_REPORT")
    return value


def _text(values, key, limit):
    value = values.get(key)
    if not isinstance(value, str) or not 0 < len(value.strip()) <= limit:
        raise ReportError(422, "INVALID_REPORT")
    value = "".join(c for c in value if c in "\n\t" or c.isprintable()).strip()
    if not value:
        raise ReportError(422, "INVALID_REPORT")
    return SECRET.sub("[redacted]", value)


def _lines(values, key, limit, valid):
    value = values.get(key, [])
    if not isinstance(value, list) or len(value) > limit or any(not isinstance(line, str) or not valid(line) for line in value):
        raise ReportError(422, "INVALID_REPORT")
    return value


def marker(report_id):
    return f"<!-- gpt-mobile-report:{report_id} -->"


def issue_from_report(values):
    allowed = {"reportId", "source", "title", "description", "version", "build", "commit", "android", "sdk", "device",
               "feature", "errorCode", "errorType", "frames", "events"}
    if not isinstance(values, dict) or set(values) - allowed:
        raise ReportError(422, "INVALID_REPORT")
    report_id = _field(values, "reportId", REPORT_ID)
    version = _field(values, "version", SAFE_VERSION)
    commit = _field(values, "commit", re.compile(r"(?:[a-f0-9]{7,40}|unknown)"))
    android = _field(values, "android", SAFE_VERSION)
    device = _field(values, "device", SAFE_DEVICE)
    build, sdk = values.get("build"), values.get("sdk")
    if type(build) is not int or not 0 < build <= 2_147_483_647 or type(sdk) is not int or not 1 <= sdk <= 999:
        raise ReportError(422, "INVALID_REPORT")
    events = _lines(values, "events", 16, SAFE_EVENT.fullmatch)
    if any(len(event.split()) == 4 and event.split()[3] not in ERROR_CODES for event in events):
        raise ReportError(422, "INVALID_REPORT")
    source = values.get("source")
    if source == "manual" and not ({"feature", "errorCode", "errorType", "frames"} & values.keys()):
        title = " ".join(_text(values, "title", 120).split()).replace("@", "＠")
        description = _text(values, "description", 1200)
        # Indented code keeps links, HTML, mentions and hidden report markers in user text inert.
        body = "### What happened\n\n" + "\n".join("    " + line for line in description.split("\n"))
    elif source == "debug" and not ({"title", "description"} & values.keys()):
        feature, code = values.get("feature"), values.get("errorCode")
        if not isinstance(feature, str) or feature not in FEATURES or not isinstance(code, str) or code not in ERROR_CODES:
            raise ReportError(422, "INVALID_REPORT")
        title = f"[Debug] {FEATURES[feature]} · {code}"
        body = f"### Error\n\n{FEATURES[feature]} · `{code}`"
        if "errorType" in values:
            body += "\n\nException type · `" + _field(values, "errorType", SAFE_TYPE) + "`"
        frames = _lines(values, "frames", 8, SAFE_FRAME.fullmatch)
        if frames:
            body += "\n\n### Stack frames\n\n" + "\n".join("    " + frame for frame in frames)
    else:
        raise ReportError(422, "INVALID_REPORT")
    body += f"\n\n### App and device\n\n- App {version} (build {build}, commit `{commit}`)\n- Android {android} (API {sdk}) · {device}"
    if events:
        body += "\n\n### Recent app events\n\n" + "\n".join("    " + event for event in events)
    return report_id, {"title": title, "body": f"{body}\n\n{marker(report_id)}"}


class GitHubRequestError(Exception):
    def __init__(self, status=0, retry_after=0):
        self.status, self.retry_after = status, retry_after


class GitHubIssuePublisher:
    def __init__(self, app_id, installation_id, app_slug, private_key_file, clock=time.time):
        if not (app_id.isdecimal() and installation_id.isdecimal() and re.fullmatch(r"[a-z0-9-]{1,100}", app_slug)):
            raise ValueError("Configure the reporting App ID, installation ID and slug")
        self.app_id = app_id
        self.installation_id = installation_id
        self.bot_login = app_slug + "[bot]"
        self.key = serialization.load_pem_private_key(private_key_file.read_bytes(), password=None)
        if not isinstance(self.key, rsa.RSAPrivateKey):
            raise ValueError("The reporting GitHub App requires an RSA private key")
        self.clock = clock
        self.opener = urllib.request.build_opener(NoRedirect)
        self.token_lock = threading.Lock()
        self.token = ""
        self.token_expiry = 0

    def _request(self, path, token, payload=None):
        request = urllib.request.Request(
            "https://api.github.com" + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                     "Content-Type": "application/json", "User-Agent": "GPT-Mobile-Issue-Reporter/1",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        try:
            with self.opener.open(request, timeout=15) as response:
                data = response.read(2_000_001)
                if len(data) > 2_000_000:
                    raise GitHubRequestError()
                return json.loads(data)
        except urllib.error.HTTPError as error:
            with closing(error):
                retry = error.headers.get("Retry-After", "")
                reset = error.headers.get("X-RateLimit-Reset", "")
                seconds = int(retry) if retry.isdecimal() and len(retry) <= 8 else 0
                if error.headers.get("X-RateLimit-Remaining") == "0" and reset.isdecimal() and len(reset) <= 12:
                    seconds = max(seconds, int(reset) - int(self.clock()), 60)
                raise GitHubRequestError(error.code, seconds) from None
        except (OSError, ValueError, UnicodeError):
            raise GitHubRequestError() from None

    def _installation_token(self):
        def encoded(value):
            return base64.urlsafe_b64encode(value).rstrip(b"=")

        with self.token_lock:
            now = int(self.clock())
            if self.token_expiry > now + 60:
                return self.token
            claims = json.dumps({"iat": now - 60, "exp": now + 540, "iss": self.app_id}).encode()
            data = encoded(b'{"alg":"RS256","typ":"JWT"}') + b"." + encoded(claims)
            signature = self.key.sign(data, padding.PKCS1v15(), hashes.SHA256())
            jwt = (data + b"." + encoded(signature)).decode("ascii")
            try:
                result = self._request(
                    f"/app/installations/{self.installation_id}/access_tokens", jwt,
                    {"repositories": [REPOSITORY.split("/")[1]], "permissions": {"issues": "write"}},
                )
                expiry = datetime.fromisoformat(result["expires_at"].replace("Z", "+00:00")).timestamp()
                token = result["token"]
                if not isinstance(token, str) or not re.fullmatch(r"[!-~]{1,4096}", token) or expiry <= now + 60:
                    raise ValueError()
            except GitHubRequestError as error:
                # The request never reached issue creation.
                raise ReportError(503, "GITHUB_UNAVAILABLE", max(60, error.retry_after)) from None
            except (KeyError, TypeError, ValueError, AttributeError):
                raise ReportError(503, "GITHUB_UNAVAILABLE", 60) from None
            self.token, self.token_expiry = token, expiry
            return token

    def create(self, issue):
        token = self._installation_token()
        try:
            result = self._request(f"/repos/{REPOSITORY}/issues", token, issue)
        except GitHubRequestError as error:
            if error.status == 401:
                with self.token_lock:
                    if self.token == token:
                        self.token_expiry = 0
            if error.status in (401, 403, 404, 410, 429):
                raise ReportError(503, "GITHUB_UNAVAILABLE", max(60, error.retry_after)) from None
            if error.status == 422:
                raise ReportError(422, "GITHUB_REJECTED") from None
            raise
        number = result.get("number") if isinstance(result, dict) else None
        if type(number) is not int or not 0 < number <= 2_147_483_647:
            raise GitHubRequestError()
        return number

    def find(self, report_id, created):
        since = datetime.fromtimestamp(created - 300, timezone.utc).isoformat()
        token = self._installation_token()
        for page in range(1, 4):
            query = urllib.parse.urlencode({"state": "all", "sort": "created", "direction": "desc", "since": since, "per_page": 100, "page": page})
            results = self._request(f"/repos/{REPOSITORY}/issues?{query}", token)
            if not isinstance(results, list):
                raise GitHubRequestError()
            for issue in results:
                if not isinstance(issue, dict) or "pull_request" in issue:
                    continue
                user = issue.get("user")
                if not isinstance(user, dict) or user.get("login") != self.bot_login:
                    continue
                number, body = issue.get("number"), issue.get("body")
                if type(number) is int and 0 < number <= 2_147_483_647 and isinstance(body, str) and body.rstrip().endswith("\n" + marker(report_id)):
                    return number
            if len(results) < 100:
                break
        return None


class IssueReporter:
    def __init__(self, path, publisher, clock=time.time):
        self.path, self.publisher, self.clock = path, publisher, clock
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self._db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, digest TEXT, phase TEXT, created INTEGER, retry_at INTEGER, number INTEGER)")
            db.execute("CREATE TABLE IF NOT EXISTS report_admission (client TEXT, created INTEGER)")
            db.execute("CREATE TABLE IF NOT EXISTS github_cooldown (id INTEGER PRIMARY KEY, retry_at INTEGER)")
        path.chmod(0o600)

    @contextmanager
    def _db(self):
        with closing(sqlite3.connect(self.path, timeout=5)) as db, db:
            yield db

    def submit(self, values, client):
        report_id, issue = issue_from_report(values)
        digest = hashlib.sha256(json.dumps(issue, sort_keys=True).encode()).hexdigest()
        now = int(self.clock())
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT digest, phase, created, retry_at, number FROM reports WHERE id = ?", (report_id,)).fetchone()
            if row is None:
                db.execute("DELETE FROM report_admission WHERE created <= ?", (now - ADMISSION_WINDOW,))
                count, caller = db.execute("SELECT COUNT(*), COUNT(CASE WHEN client = ? THEN 1 END) FROM report_admission", (client,)).fetchone()
                if count >= 30 or caller >= 4:
                    raise ReportError(429, "RATE_LIMITED", ADMISSION_WINDOW)
                db.execute("INSERT INTO report_admission VALUES (?, ?)", (client, now))
                db.execute("INSERT INTO reports VALUES (?, ?, 'ready', ?, 0, NULL)", (report_id, digest, now))
                row = (digest, "ready", now, 0, None)
            original, phase, created, retry_at, number = row
            if original != digest:
                raise ReportError(409, "REPORT_ID_CONFLICT")
            if phase == "done":
                return number
            if phase == "rejected":
                raise ReportError(422, "GITHUB_REJECTED")
            upstream = db.execute("SELECT retry_at FROM github_cooldown WHERE id = 1").fetchone()
            wait = max(retry_at, upstream[0] if upstream else 0) - now
            if wait > 0:
                raise ReportError(503, "RETRY_LATER", wait)
            # Commit the dispatch lease before making a network call. Expired or
            # ambiguous leases can only reconcile the original write.
            lease = now + RETRY_LEASE
            db.execute("UPDATE reports SET phase = 'dispatching', retry_at = ? WHERE id = ?", (lease, report_id))

        try:
            number = self.publisher.create(issue) if phase == "ready" else self.publisher.find(report_id, created)
        except ReportError as error:
            retry = int(self.clock()) + error.retry_after
            with self._db() as db:
                next_phase = phase if error.status != 422 else "rejected"
                db.execute("UPDATE reports SET phase = ?, retry_at = ? WHERE id = ? AND phase = 'dispatching' AND retry_at = ?",
                           (next_phase, retry, report_id, lease))
                if error.retry_after:
                    self._cooldown(db, retry)
            raise
        except GitHubRequestError as error:
            if error.retry_after:
                with self._db() as db:
                    self._cooldown(db, int(self.clock()) + error.retry_after)
            raise ReportError(503, "AWAITING_GITHUB_RECEIPT", max(RETRY_LEASE, error.retry_after)) from None

        if number is None:
            raise ReportError(503, "AWAITING_GITHUB_RECEIPT", RETRY_LEASE)
        with self._db() as db:
            db.execute("UPDATE reports SET phase = 'done', number = ?, retry_at = 0 WHERE id = ? AND phase != 'done'", (number, report_id))
        return number

    @staticmethod
    def _cooldown(db, retry_at):
        db.execute("INSERT INTO github_cooldown VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET retry_at = MAX(retry_at, excluded.retry_at)", (retry_at,))
