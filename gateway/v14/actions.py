"""Durable, principal/job-scoped records for external tool side effects."""
from __future__ import annotations

import hashlib
import json
import threading
import time
import re
from contextvars import ContextVar


current_gateway_job_id = ContextVar("gateway_action_job_id", default=None)
current_gateway_tool_call_id = ContextVar("gateway_action_call_id", default=None)

MAX_ACTION_ARGUMENT_BYTES = 128 * 1024
MAX_ACTION_RESULT_BYTES = 512 * 1024
MAX_ACTION_ROWS = 100_000


class ActionLedgerError(RuntimeError):
    """Base class for fail-closed action-ledger results."""


class ActionLedgerUnavailable(ActionLedgerError):
    pass


class ActionConflict(ActionLedgerError):
    pass


class ExternalOutcomeUnknown(ActionLedgerError):
    pass


class ActionResultUnavailable(ActionLedgerError):
    pass


class IdempotencyConflict(ActionLedgerError):
    pass


class IdempotencyExpired(ActionLedgerError):
    pass


class ActionReplay:
    def __init__(self, result):
        self.result = result


def canonical_json(value: object, *, maximum_bytes: int) -> tuple[str, str]:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    raw = encoded.encode("utf-8")
    if len(raw) > maximum_bytes:
        raise ActionLedgerError("Action payload exceeds the durable ledger limit")
    return encoded, hashlib.sha256(raw).hexdigest()


class ActionLedger:
    """Keep intent, frozen binding, arguments and result certainty in SQLite."""

    def __init__(self, runtime):
        self.runtime = runtime

    def _connect(self):
        if not getattr(self.runtime, "GATEWAY_DURABLE_JOBS", False):
            raise ActionLedgerUnavailable("Durable action storage is required before tool dispatch")
        if not getattr(self.runtime, "durable_job_initialized", False):
            if not self.runtime.init_durable_job_store():
                raise ActionLedgerUnavailable("Durable action storage is unavailable")
        return self.runtime._durable_connect()

    @staticmethod
    def _create_table(db):
        db.execute("""CREATE TABLE IF NOT EXISTS gateway_actions (
            principal_id TEXT NOT NULL,
            job_id TEXT NOT NULL,
            action_id TEXT NOT NULL,
            binding_json TEXT NOT NULL,
            binding_digest TEXT NOT NULL,
            argument_digest TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('dispatching','completed','completed_omitted','uncertain')),
            certainty TEXT NOT NULL CHECK(certainty IN ('known','unknown')),
            result_json TEXT,
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL,
            PRIMARY KEY(principal_id, job_id, action_id)
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS index_gateway_actions_updated ON gateway_actions(updated_at)")

    @staticmethod
    def _create_request_table(db):
        db.execute("""CREATE TABLE IF NOT EXISTS gateway_requests (
            principal_id TEXT NOT NULL,
            api_scope TEXT NOT NULL,
            idempotency_key_digest TEXT NOT NULL,
            request_digest TEXT NOT NULL,
            canonicalization_version INTEGER NOT NULL,
            job_id TEXT NOT NULL,
            admitted_at REAL NOT NULL,
            expires_at REAL NOT NULL,
            tombstone_until REAL NOT NULL,
            PRIMARY KEY(principal_id, api_scope, idempotency_key_digest)
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS index_gateway_requests_expiry ON gateway_requests(expires_at)")

    def admit_request(self, principal_id: str, api_scope: str, key: str, payload: object,
                      proposed_job_id: str, *, ttl_seconds: int = 86400) -> tuple[str, bool]:
        """Reserve a principal-scoped request key; expired rows remain tombstones."""
        if not principal_id or not api_scope or not proposed_job_id:
            raise ActionLedgerUnavailable("A trusted principal and run identity are required")
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9._~-]{1,128}", key):
            raise ValueError("Idempotency-Key must contain 1 to 128 safe ASCII characters")
        if not 1 <= int(ttl_seconds) <= 7 * 86400:
            raise ValueError("Idempotency retention is outside the supported range")
        _, request_digest = canonical_json({"version": 1, "payload": payload}, maximum_bytes=1024 * 1024)
        key_digest = hashlib.sha256(key.encode("ascii")).hexdigest()
        lock = getattr(self.runtime, "durable_job_lock", None)
        if lock is None:
            raise ActionLedgerUnavailable("Durable request lock is unavailable")
        now = time.time()
        with lock:
            db = self._connect()
            try:
                self._create_request_table(db)
                with db:
                    db.execute("BEGIN IMMEDIATE")
                    row = db.execute("SELECT request_digest,job_id,expires_at FROM gateway_requests WHERE principal_id=? AND api_scope=? AND idempotency_key_digest=?",
                                     (principal_id, api_scope, key_digest)).fetchone()
                    if row:
                        old_digest, old_job_id, expires_at = row
                        if old_digest != request_digest:
                            raise IdempotencyConflict("Idempotency key was already used for a different request")
                        if now >= float(expires_at):
                            raise IdempotencyExpired("Idempotency key has expired; submit with a new key")
                        return str(old_job_id), True
                    expires_at = now + int(ttl_seconds)
                    # Tombstones last through explicit retention after the replay window.
                    tombstone_until = expires_at + 30 * 86400
                    db.execute("INSERT INTO gateway_requests(principal_id,api_scope,idempotency_key_digest,request_digest,canonicalization_version,job_id,admitted_at,expires_at,tombstone_until) VALUES(?,?,?,?,1,?,?,?,?)",
                               (principal_id, api_scope, key_digest, request_digest, proposed_job_id, now, expires_at, tombstone_until))
                return str(proposed_job_id), False
            except (IdempotencyConflict, IdempotencyExpired, ActionLedgerError, ValueError):
                raise
            except Exception as exc:
                raise ActionLedgerUnavailable("Could not durably admit idempotent request") from exc
            finally:
                db.close()

    def begin(self, principal_id: str, job_id: str, action_id: str, binding: dict, arguments: object):
        if not principal_id or principal_id == "unowned" or not job_id or not action_id:
            raise ActionLedgerUnavailable("A trusted principal, gateway job and tool call ID are required")
        binding_json, binding_digest = canonical_json(binding, maximum_bytes=16 * 1024)
        _, argument_digest = canonical_json(arguments, maximum_bytes=MAX_ACTION_ARGUMENT_BYTES)
        lock = getattr(self.runtime, "durable_job_lock", None)
        if lock is None:
            raise ActionLedgerUnavailable("Durable action lock is unavailable")
        with lock:
            db = self._connect()
            try:
                self._create_table(db)
                with db:
                    db.execute("BEGIN IMMEDIATE")
                    row = db.execute("SELECT binding_digest,argument_digest,state,certainty,result_json FROM gateway_actions WHERE principal_id=? AND job_id=? AND action_id=?",
                                     (principal_id, job_id, action_id)).fetchone()
                    if row:
                        old_binding, old_arguments, state, certainty, result_json = row
                        if old_binding != binding_digest or old_arguments != argument_digest:
                            raise ActionConflict("Tool call ID was already used with different arguments or binding")
                        if state == "completed" and certainty == "known" and result_json is not None:
                            return ActionReplay(json.loads(result_json))
                        if state == "completed_omitted":
                            raise ActionResultUnavailable("The prior external result was recorded but exceeded the replay limit")
                        raise ExternalOutcomeUnknown("A prior dispatch has no safely replayable result")
                    count = db.execute("SELECT COUNT(*) FROM gateway_actions").fetchone()[0]
                    if count >= MAX_ACTION_ROWS:
                        raise ActionLedgerUnavailable("Durable action ledger reached its configured capacity")
                    now = time.time()
                    db.execute("INSERT INTO gateway_actions(principal_id,job_id,action_id,binding_json,binding_digest,argument_digest,state,certainty,result_json,created_at,updated_at) VALUES(?,?,?,?,?,?, 'dispatching','unknown',NULL,?,?)",
                               (principal_id, job_id, action_id, binding_json, binding_digest, argument_digest, now, now))
                return None
            except ActionLedgerError:
                raise
            except Exception as exc:
                raise ActionLedgerUnavailable("Could not durably record tool dispatch intent") from exc
            finally:
                db.close()

    def finish(self, principal_id: str, job_id: str, action_id: str, result: object) -> object:
        try:
            result_json, _ = canonical_json(result, maximum_bytes=MAX_ACTION_RESULT_BYTES)
            state, certainty = "completed", "known"
        except (TypeError, ValueError, ActionLedgerError):
            result_json, state, certainty = None, "completed_omitted", "known"
        lock = getattr(self.runtime, "durable_job_lock", None)
        try:
            with lock:
                db = self._connect()
                try:
                    with db:
                        changed = db.execute("UPDATE gateway_actions SET state=?,certainty=?,result_json=?,updated_at=? WHERE principal_id=? AND job_id=? AND action_id=? AND state='dispatching'",
                                             (state, certainty, result_json, time.time(), principal_id, job_id, action_id)).rowcount
                        if changed != 1:
                            raise ActionLedgerUnavailable("Tool result has no matching durable dispatch intent")
                finally:
                    db.close()
        except Exception as exc:
            # The external service may already have committed. A persistence
            # failure is therefore uncertainty, never permission to call again.
            raise ExternalOutcomeUnknown("Tool completed but its result could not be durably committed") from exc
        return result

    def uncertain(self, principal_id: str, job_id: str, action_id: str) -> None:
        lock = getattr(self.runtime, "durable_job_lock", None)
        try:
            with lock:
                db = self._connect()
                try:
                    with db:
                        db.execute("UPDATE gateway_actions SET state='uncertain',certainty='unknown',updated_at=? WHERE principal_id=? AND job_id=? AND action_id=? AND state='dispatching'",
                                   (time.time(), principal_id, job_id, action_id))
                finally:
                    db.close()
        except Exception:
            # Keep the intent row dispatching if even uncertainty cannot be
            # committed; duplicate admission still fails closed on that state.
            pass


class ActionContext:
    def __init__(self, job_id: str | None, call_id: str | None):
        self.job_id, self.call_id = job_id, call_id
        self._job_token = self._call_token = None

    def __enter__(self):
        self._job_token = current_gateway_job_id.set(self.job_id)
        self._call_token = current_gateway_tool_call_id.set(self.call_id)
        return self

    def __exit__(self, *_):
        current_gateway_tool_call_id.reset(self._call_token)
        current_gateway_job_id.reset(self._job_token)


def action_context(job_id: str | None, call_id: str | None) -> ActionContext:
    return ActionContext(job_id, call_id)
