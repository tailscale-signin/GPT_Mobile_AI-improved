"""Installed-app adapter for independent supplied-answer benchmark grading.

The existing principal-scoped desktop client owns authentication and response
validation. This adapter limits it to public tasks, original-request readback,
saved captures, supplied-answer executable grading and explicit retirement.
Product/provider generation and live GitHub qualification are never admitted.
The canonical grader and native product contracts are the installed modules;
a source checkout, Git executable and alternate Python are not prerequisites.
"""
from contextlib import closing
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import sqlite3
from types import SimpleNamespace
from urllib.parse import urlsplit

import grade_external_reference as external
from desktop.conversation_client import Client


OPERATIONS = ('export', 'prepare', 'submit', 'status', 'grade', 'read-capture', 'retire')
API = '/gateway/benchmarks'
MAX_INPUT = 8 * 1024 * 1024
SOURCE_ROOT = Path(__file__).resolve().parents[1]
REQUEST_FIELDS = frozenset({
    'request_id', 'task_id', 'task_pack', 'seed', 'fixture_digest', 'grader_digest',
    'environment_digest', 'proposal_digest', 'generation_receipt', 'evidence_lane',
})
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in
                         ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', 'fc00::/7', '100.64.0.0/10'))


class BridgeError(ValueError):
    """A safe, actionable error whose text contains no imported private content."""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def reject(code, message):
    raise BridgeError(code, message)


def strict_json(text):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                reject("duplicate_json_key", "JSON objects must not contain duplicate keys.")
            result[key] = value
        return result

    def constant(_):
        reject("nonfinite_json", "NaN and infinity are not valid benchmark evidence.")

    return json.loads(text, object_pairs_hook=object_pairs, parse_constant=constant)


def read_json(path, maximum=MAX_INPUT):
    with Path(path).open("rb") as stream:
        raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        reject("input_too_large", "A structured input exceeds the campaign size limit.")
    return strict_json(raw.decode("utf-8"))


def is_within(path, parent):
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def absolute_path(value, *, repo=None, exists=False, directory=False):
    if not isinstance(value, (str, Path)) or not str(value) or "\x00" in str(value):
        reject("invalid_path", "An absolute local path is required.")
    path = Path(value)
    if not path.is_absolute():
        reject("relative_path", "Campaign paths must be absolute.")
    path = path.resolve()
    if repo is not None and is_within(path, repo):
        reject("path_inside_repository", "Keep campaign evidence and keys outside the installed application source.")
    if exists and (not path.is_dir() if directory else not path.is_file()):
        reject("missing_input", "A required local input is missing or is the wrong file type.")
    return path


def canonical_origin(value):
    """Accept installed private, loopback and Tailscale origins; never DNS or metadata IPs."""
    if not isinstance(value, str) or not value or value != value.strip() or any(ord(c) < 32 for c in value):
        reject("invalid_origin", "Use an explicit HTTP(S) origin with a literal private, loopback or Tailscale IP.")
    try:
        parsed = urlsplit(value)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username is not None
                or parsed.password is not None or parsed.path not in {"", "/"} or parsed.query or parsed.fragment
                or "%" in parsed.hostname):
            raise ValueError()
        address = ipaddress.ip_address(parsed.hostname)
        tested = address.ipv4_mapped if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped else address
        if not tested.is_loopback and not any(tested in block for block in PRIVATE_NETWORKS):
            raise ValueError()
        port = parsed.port if parsed.port is not None else 80 if parsed.scheme == "http" else 443
        if not 1 <= port <= 65535:
            raise ValueError()
    except (ValueError, TypeError):
        reject("invalid_origin", "Only literal loopback, private-network or Tailscale Gateway origins are allowed.")
    host = "[" + str(address) + "]" if address.version == 6 else str(address)
    return parsed.scheme + "://" + host + ":" + str(port)


def same_reference(value, references):
    """Exact JSON identity, including booleans versus numbers."""
    try:
        encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)
        return any(encoded == json.dumps(row, sort_keys=True, separators=(",", ":"), allow_nan=False)
                   for row in references)
    except (ValueError, TypeError):
        return False


def is_digest(value):
    return isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value) is not None


def validate_frozen_request(request, references):
    if (not isinstance(request, dict) or set(request) != REQUEST_FIELDS
            or request.get("evidence_lane") != "EXECUTABLE"
            or not same_reference(request.get("generation_receipt"), references)
            or re.fullmatch(r"extref_[a-f0-9]{32}", str(request.get("request_id"))) is None
            or not isinstance(request.get("task_id"), str)
            or re.fullmatch(r"[a-z0-9-]{1,100}", request["task_id"]) is None
            or request.get("task_pack") not in {"python", "repository"}
            or type(request.get("seed")) is not int or not 0 <= request["seed"] <= 2**31 - 1
            or any(not is_digest(request.get(key))
                   for key in ("fixture_digest", "grader_digest", "environment_digest", "proposal_digest"))):
        reject("invalid_frozen_trial", "The trial is not an immutable supplied-answer EXECUTABLE reference.")


def guarded_client_class(base_class, allowed_origin, operation, references, expected_owner_digest=None):
    allowed = canonical_origin(allowed_origin) if allowed_origin is not None else None
    if expected_owner_digest is not None and not is_digest(expected_owner_digest):
        reject('invalid_owner_digest', 'The campaign Gateway owner binding is invalid.')

    class GuardedClient(base_class):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self.campaign_origin = allowed or canonical_origin(self.origin)
            if canonical_origin(self.origin) != self.campaign_origin:
                reject("gateway_origin_changed", "Installed Gateway origin differs from the frozen campaign origin.")
            self.bridge_fixture_rows = {}
            self.bridge_retire_request_id = None

        def identity(self):
            observed = super().identity()
            if (expected_owner_digest is not None and
                    external.digest(external.scope_key(observed)) != expected_owner_digest):
                reject('gateway_owner_changed', 'Restore the Gateway and account registered for this campaign.')
            return observed

        def request(self, method, path, *, params=None, payload=None, user_action=None):
            if canonical_origin(self.origin) != self.campaign_origin:
                reject("gateway_origin_changed", "Gateway origin changed during the operation.")
            if operation == "prepare":
                reject("network_forbidden", "Preparing a local trial must never contact Gateway.")
            if method not in {"GET", "POST"} or user_action is not None or not isinstance(path, str):
                reject("endpoint_forbidden", "This campaign operation does not permit that request.")
            permitted = False
            if method == "GET" and payload is None:
                if path == "/gateway/identity" and not params:
                    permitted = True
                elif re.fullmatch(r"/gateway/benchmarks/fixtures/[a-z0-9-]{1,100}", path):
                    permitted = (operation in {"export", "submit"} and isinstance(params, dict)
                                 and set(params) == {"seed", "task_pack"}
                                 and type(params.get("seed")) is int and 0 <= params["seed"] <= 2**31 - 1
                                 and params.get("task_pack") in {"python", "repository"})
                elif path == API:
                    permitted = (operation in {"submit", "status", "grade", "retire"}
                                 and isinstance(params, dict) and set(params) == {"request_id"}
                                 and re.fullmatch(r"extref_[a-f0-9]{32}", str(params.get("request_id"))) is not None)
                elif re.fullmatch(r"/gateway/conversations/[A-Za-z0-9_-]{1,256}(?:/messages)?", path):
                    permitted = operation == "read-capture" and (not params or
                                isinstance(params, dict) and set(params) <= {"limit", "after_cursor"}
                                and params.get("limit") == 100
                                and ("after_cursor" not in params or isinstance(params["after_cursor"], str)))
            elif method == "POST" and not params:
                if operation == "submit" and path == API + "/evaluate" and isinstance(payload, dict):
                    if set(payload) == REQUEST_FIELDS | {"proposal"}:
                        request = {key: payload[key] for key in REQUEST_FIELDS}
                        validate_frozen_request(request, references)
                        answer = payload["proposal"]
                        permitted = (isinstance(answer, str) and len(answer.encode("utf-8")) <= 65536
                                     and hashlib.sha256(answer.encode("utf-8")).hexdigest() == request["proposal_digest"])
                elif operation == "retire" and path == API + "/cancel-request":
                    permitted = (isinstance(payload, dict) and set(payload) == {"request_id"}
                                 and self.bridge_retire_request_id is not None
                                 and payload["request_id"] == self.bridge_retire_request_id)
            if not permitted:
                reject("endpoint_forbidden", "Only frozen supplied-answer evaluation, original-request cancellation and scoped readback are permitted.")
            result = super().request(method, path, params=params, payload=payload, user_action=user_action)
            if method == "GET" and path.startswith(API + "/fixtures/"):
                self.bridge_fixture_rows[path] = result
            return result

    return GuardedClient


def require_fields(body, required):
    if not isinstance(body, dict) or set(body) != set(required):
        reject("invalid_operation_body", "The operation body has missing or unsupported fields.")


def load_trial(path, references):
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as db:
        row = db.execute("SELECT plan,attempted FROM trial WHERE id=1").fetchone()
    if row is None or type(row[1]) is not int or row[1] not in {0, 1}:
        reject("invalid_frozen_trial", "The retained trial has no valid immutable admission record.")
    saved = strict_json(row[0])
    if (not isinstance(saved, dict) or set(saved) != {"owner", "publicTaskDigest", "request"}
            or any(not is_digest(saved.get(key)) for key in ("owner", "publicTaskDigest"))):
        reject("invalid_frozen_trial", "The retained trial has invalid owner or task identities.")
    validate_frozen_request(saved["request"], references)
    return saved


def execute_operation(root, operation, body, client_class):
    refs = [external.reference(source) for source in external.LABELS]

    def input_path(name, exists=True):
        return absolute_path(body[name], repo=SOURCE_ROOT, exists=exists)

    if operation == "export":
        require_fields(body, {"tasks"})
        if not isinstance(body["tasks"], list) or not 1 <= len(body["tasks"]) <= 500:
            reject("invalid_task_list", "Export requires between 1 and 500 registered public tasks.")
        requested = []
        seen = set()
        for row in body["tasks"]:
            require_fields(row, {"id", "pack", "seed", "family"})
            if (not isinstance(row["id"], str) or re.fullmatch(r"[a-z0-9-]{1,100}", row["id"]) is None
                    or row["pack"] not in {"python", "repository"}
                    or type(row["seed"]) is not int or not 0 <= row["seed"] <= 2**31 - 1
                    or not isinstance(row["family"], str) or not row["family"].strip()):
                reject("invalid_task_list", "A requested task has an invalid ID, pack, seed or family.")
            ident = (row["id"], row["pack"], row["seed"])
            if ident in seen:
                reject("duplicate_task", "A task may appear only once in one export.")
            seen.add(ident)
            requested.append(row)
        client = client_class(root)
        owner = external.digest(external.scope_key(client.identity()))
        tasks = []
        for row in requested:
            task = external.public_task(client, row["id"], row["seed"], row["pack"])
            observed = client.bridge_fixture_rows.get(API + "/fixtures/" + row["id"], {})
            if observed.get("family") != row["family"]:
                reject("task_family_changed", "A registered task family differs from the authoritative public fixture.")
            tasks.append(dict(task, gatewayOwnerDigest=owner, family=observed["family"]))
        if external.digest(external.scope_key(client.identity())) != owner:
            reject("gateway_owner_changed", "Gateway or owner changed during task export.")
        return {"tasks": tasks, "ownerDigest": owner}

    if operation == "prepare":
        require_fields(body, {"task", "source", "candidate", "trial"})
        task_path, answer_path, trial_path = input_path("task"), input_path("candidate"), input_path("trial", exists=False)
        if body["source"] not in external.LABELS:
            reject("unknown_product", "Select one of the four supported product labels.")
        task = read_json(task_path, 2 * 1024 * 1024)
        if (not isinstance(task, dict) or not set(external.TASK_KEYS).issubset(task)
                or not isinstance(task.get("id"), str) or re.fullmatch(r"[a-z0-9-]{1,100}", task["id"]) is None
                or task.get("taskPack") not in {"python", "repository"}
                or type(task.get("seed")) is not int or not 0 <= task["seed"] <= 2**31 - 1
                or not isinstance(task.get("prompt"), str) or not isinstance(task.get("outputSchema"), dict)
                or any(not is_digest(task.get(key))
                       for key in ("gatewayOwnerDigest", "taskDigest", "graderDigest", "environmentDigest"))):
            reject("invalid_public_task", "Use an intact public task exported for the original Gateway owner.")
        answer = external.read_file(answer_path, 65536)
        return {"request_id": external.prepare(trial_path, task, body["source"], answer)}

    if operation in {"submit", "status", "retire"}:
        require_fields(body, {"trial", "candidate"} if operation == "submit" else {"trial"})
        trial = input_path("trial")
        saved = load_trial(trial, refs)
        candidate = external.read_file(input_path("candidate"), 65536) if operation == "submit" else None
        client = client_class(root)
        if operation != "retire":
            return external.grade(client, trial, candidate)
        # Canonical status validates owner, request and any original run before
        # retiring the same request. It performs no model dispatch or resubmit.
        status = external.grade(client, trial)
        ident = saved["request"]["request_id"]
        if status.get("request_id") != ident:
            reject("request_identity_changed", "Readback differs from the original immutable trial.")
        client.bridge_retire_request_id = ident
        result = client.request("POST", API + "/cancel-request", payload={"request_id": ident})
        if external.digest(external.scope_key(client.identity())) != saved["owner"]:
            reject("gateway_owner_changed", "Gateway owner changed; cancellation state remains unconfirmed.")
        if (not isinstance(result, dict) or result.get("submissionState") not in {"OBSERVED", "REMOVED", "NOT_OBSERVED"}
                or any(result.get(key, ident) != ident for key in ("request_id", "requestId"))):
            reject("invalid_retirement_receipt", "Cancellation readback is unconfirmed; retain the original trial.")
        return dict(result, request_id=ident)

    if operation == "grade":
        require_fields(body, {"trial", "plan", "observation", "observer_public", "grader_key"})
        paths = {key: input_path(key) for key in body}
        load_trial(paths["trial"], refs)
        # Reject ambiguous JSON before the unmodified upstream parser reads it.
        read_json(paths["plan"])
        read_json(paths["observation"])
        from benchmarks import product_cli
        native_args = SimpleNamespace(command="grade-native", root=root, **paths)
        return product_cli.execute(native_args, client_class, external.grade, external.read_file)

    if operation == "read-capture":
        require_fields(body, {"conversation_id"})
        ident = body["conversation_id"]
        if not isinstance(ident, str) or re.fullmatch(r"[A-Za-z0-9_-]{1,256}", ident) is None:
            reject("invalid_conversation_id", "Use the exact saved Gateway conversation ID.")
        client = client_class(root)
        owner = external.digest(external.scope_key(client.identity()))
        result = client.read(ident)
        if external.digest(external.scope_key(client.identity())) != owner:
            reject("gateway_owner_changed", "Gateway owner changed during saved conversation readback.")
        return result
    reject("unknown_operation", "Unsupported campaign operation.")


def execute(root, operation, body, *, allowed_origin=None, expected_owner_digest=None, client_factory=Client):
    """Perform one bounded operation using the installed Gateway implementation.

    All paths in ``body`` are absolute. ``prepare`` remains entirely local.
    The caller persists campaign files and serializes session changes; this
    adapter retains the canonical SQLite trial and its single admission intent.
    ``expected_owner_digest`` binds task/capture reads to the desktop campaign's
    owner before those reads occur; trials additionally retain their own owner.
    An injected client class is used only by offline contract tests.
    """
    if operation not in OPERATIONS:
        reject('unknown_operation', 'Unsupported campaign operation.')
    root = absolute_path(root, exists=operation != 'prepare', directory=True)
    references = [external.reference(source) for source in external.LABELS]
    client_class = guarded_client_class(client_factory, allowed_origin, operation, references, expected_owner_digest)
    try:
        return execute_operation(root, operation, body, client_class)
    except BridgeError:
        raise
    except Exception as error:
        # Upstream exception text can contain capture data or private paths.
        # Keep the original trial, including ambiguous admission, for readback.
        code = getattr(error, 'code', None)
        if not isinstance(code, str) or re.fullmatch(r'[A-Za-z0-9_-]{1,100}', code) is None:
            code = 'campaign_gateway_failed'
        raise BridgeError(code, 'The Gateway could not confirm this operation. Keep the original trial and '
                          'use readback for uncertain admission; check local Gateway diagnostics.') from None
