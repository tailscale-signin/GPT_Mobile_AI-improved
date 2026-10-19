"""Durable frontier and accounting. No transaction performs network or parser work."""
from __future__ import annotations

import base64
import hmac
import json
import secrets
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import asdict
from pathlib import Path
from urllib.parse import urlsplit

from .contracts import CrawlerError, WorkGrant, canonical_json, digest, require

TERMINAL = frozenset(("completed", "cancelled", "failed"))


class Store:
    def __init__(self, path, clock=time.time, service_bytes=512 * 1024 * 1024, principal_bytes=128 * 1024 * 1024):
        self.clock = clock
        self.service_bytes, self.principal_bytes = service_bytes, principal_bytes
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(path), isolation_level=None, check_same_thread=False, timeout=30)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        previous_version = self.db.execute("PRAGMA user_version").fetchone()[0]
        require(previous_version in (0, 1, 2, 3), "Crawler store requires a compatible service version.", "SCHEMA_INCOMPATIBLE")
        require(self.db.execute("PRAGMA quick_check").fetchone()[0] == "ok", "Crawler store integrity check failed; retain the database for recovery.", "SCHEMA_INCOMPATIBLE")
        if 0 < previous_version < 3 and str(path) != ":memory:":
            backup_path = Path(str(path) + f".schema{previous_version}.{uuid.uuid4().hex}.backup")
            target = sqlite3.connect(str(backup_path))
            try:
                self.db.backup(target)
                require(target.execute("PRAGMA quick_check").fetchone()[0] == "ok", "Pre-migration backup verification failed.", "SCHEMA_INCOMPATIBLE")
            finally:
                target.close()
            backup_path.chmod(0o600)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS grants (
                id TEXT PRIMARY KEY, payload TEXT NOT NULL, requests INTEGER NOT NULL DEFAULT 0,
                downloaded INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, principal TEXT NOT NULL, scope TEXT NOT NULL,
                idempotency_key TEXT NOT NULL, fingerprint TEXT NOT NULL, parent_id TEXT,
                requested TEXT NOT NULL, effective TEXT NOT NULL, grant_json TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'queued', outcome TEXT, reason TEXT,
                generation INTEGER NOT NULL DEFAULT 0, created REAL NOT NULL,
                deadline REAL NOT NULL, requests INTEGER NOT NULL DEFAULT 0,
                downloaded INTEGER NOT NULL DEFAULT 0, decoded INTEGER NOT NULL DEFAULT 0,
                receipt_seq INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0,
                UNIQUE(principal, idempotency_key));
            CREATE TABLE IF NOT EXISTS frontier (
                job_id TEXT NOT NULL REFERENCES jobs(id), url TEXT NOT NULL, depth INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'queued', lease TEXT, lease_until REAL,
                generation INTEGER NOT NULL DEFAULT 0, error TEXT,
                PRIMARY KEY(job_id,url));
            CREATE TABLE IF NOT EXISTS attempts (
                id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES jobs(id), url TEXT NOT NULL,
                generation INTEGER NOT NULL, state TEXT NOT NULL, downloaded INTEGER NOT NULL DEFAULT 0,
                decoded INTEGER NOT NULL DEFAULT 0, error TEXT, created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS documents (
                revision TEXT NOT NULL, job_id TEXT NOT NULL REFERENCES jobs(id), source_id TEXT NOT NULL,
                metadata TEXT NOT NULL, text TEXT NOT NULL, artifact BLOB NOT NULL,
                inherited INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(job_id,revision));
            CREATE TABLE IF NOT EXISTS chunks (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL REFERENCES jobs(id),
                revision TEXT NOT NULL, chunk_id TEXT NOT NULL, start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL, text TEXT NOT NULL, UNIQUE(job_id,chunk_id));
            CREATE TABLE IF NOT EXISTS retained_jobs (
                job_id TEXT NOT NULL REFERENCES jobs(id), owner TEXT NOT NULL,
                PRIMARY KEY(job_id,owner));
            CREATE TABLE IF NOT EXISTS content_objects (
                scope TEXT NOT NULL, revision TEXT NOT NULL, principal TEXT NOT NULL, text TEXT NOT NULL,
                PRIMARY KEY(scope,revision));
            CREATE TABLE IF NOT EXISTS artifact_objects (
                scope TEXT NOT NULL, hash TEXT NOT NULL, principal TEXT NOT NULL, body BLOB NOT NULL,
                PRIMARY KEY(scope,hash));
            CREATE TABLE IF NOT EXISTS snapshots (
                id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES jobs(id), scope TEXT NOT NULL,
                watermark INTEGER NOT NULL, metadata TEXT NOT NULL, created REAL NOT NULL);
        """)
        with self.transaction() as db:
            if previous_version < 2:
                for row in db.execute("SELECT grant_json,SUM(requests) AS requests,SUM(downloaded) AS downloaded FROM jobs GROUP BY grant_json").fetchall():
                    grant_id = json.loads(row["grant_json"])["grant_id"]
                    require(not db.execute("SELECT 1 FROM grants WHERE id=?", (grant_id,)).fetchone(),
                            "Existing grant identity has conflicting authorizations.", "SCHEMA_INCOMPATIBLE")
                    db.execute("INSERT INTO grants VALUES (?,?,?,?)", (grant_id, row["grant_json"], row["requests"], row["downloaded"]))
                db.execute("PRAGMA user_version=2")
            if previous_version < 3:
                for row in db.execute("SELECT d.*,j.scope,j.principal FROM documents d JOIN jobs j ON d.job_id=j.id").fetchall():
                    metadata = json.loads(row["metadata"])
                    metadata["artifactHash"] = digest(row["artifact"])
                    self._save_objects(db, row["scope"], row["principal"], row["revision"], row["text"], row["artifact"], enforce_quota=False)
                    db.execute("UPDATE documents SET metadata=?,text='',artifact=x'' WHERE job_id=? AND revision=?", (canonical_json(metadata), row["job_id"], row["revision"]))
                db.execute("UPDATE chunks SET text=''")
                db.execute("PRAGMA user_version=3")
            db.execute("INSERT OR IGNORE INTO settings VALUES ('cursor_secret',?)", (secrets.token_hex(32),))
        self.cursor_secret = bytes.fromhex(self.db.execute("SELECT value FROM settings WHERE key='cursor_secret'").fetchone()[0])
        if str(path) != ":memory:":
            Path(path).chmod(0o600)

    def _save_objects(self, db, scope, principal, revision, text, artifact, enforce_quota=True):
        existing = db.execute("SELECT text FROM content_objects WHERE scope=? AND revision=?", (scope, revision)).fetchone()
        require(existing is None or existing[0] == text, "Immutable document identity changed.", "PARSE_FAILED")
        artifact_hash = digest(artifact)
        known_artifact = db.execute("SELECT 1 FROM artifact_objects WHERE scope=? AND hash=?", (scope, artifact_hash)).fetchone()
        added = (len(text.encode()) if existing is None else 0) + (len(artifact) if known_artifact is None else 0)
        if enforce_quota:
            for owner, ceiling in ((None, self.service_bytes), (principal, self.principal_bytes)):
                where, parameters = ("", ()) if owner is None else (" WHERE principal=?", (owner,))
                used = db.execute("SELECT COALESCE(SUM(length(CAST(text AS BLOB))),0) FROM content_objects" + where, parameters).fetchone()[0]
                used += db.execute("SELECT COALESCE(SUM(length(body)),0) FROM artifact_objects" + where, parameters).fetchone()[0]
                require(used + added <= ceiling, "Retained content storage allowance reached; free unused jobs or increase the configured allowance.", "STORAGE_FULL")
        db.execute("INSERT OR IGNORE INTO content_objects VALUES (?,?,?,?)", (scope, revision, principal, text))
        db.execute("INSERT OR IGNORE INTO artifact_objects VALUES (?,?,?,?)", (scope, artifact_hash, principal, artifact))
        return artifact_hash

    def _collect_objects(self, db):
        db.execute("DELETE FROM content_objects WHERE NOT EXISTS (SELECT 1 FROM documents d JOIN jobs j ON j.id=d.job_id WHERE j.scope=content_objects.scope AND d.revision=content_objects.revision)")
        db.execute("DELETE FROM artifact_objects WHERE NOT EXISTS (SELECT 1 FROM documents d JOIN jobs j ON j.id=d.job_id WHERE j.scope=artifact_objects.scope AND json_extract(d.metadata,'$.artifactHash')=artifact_objects.hash)")

    def domain_cooldown(self, host):
        with self.lock:
            row = self.db.execute("SELECT value FROM settings WHERE key=?", ("cooldown:" + host,)).fetchone()
            return float(row[0]) if row else 0.0

    def defer_domain(self, host, until):
        with self.transaction() as db:
            key = "cooldown:" + host
            row = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
            deadline = max(until, float(row[0]) if row else 0.0)
            db.execute("INSERT INTO settings(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, str(deadline)))
            return deadline

    def close(self):
        with self.lock:
            self.db.close()

    @contextmanager
    def transaction(self):
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield self.db
                self.db.execute("COMMIT")
            except BaseException:
                self.db.execute("ROLLBACK")
                raise

    def _job(self, db, job_id, grant):
        job = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        require(job is not None and job["principal"] == grant.principal and job["scope"] == grant.scope, "Job is not available in this access scope.", "PERMISSION_DENIED")
        require(not job["deleted"], "The retained job was deleted.", "RESULTS_EXPIRED")
        return job

    def _active(self, job, generation):
        require(job["state"] not in TERMINAL and not job["deleted"] and job["generation"] == generation, "Work was cancelled or superseded.", "CANCELLED")
        require(self.clock() < job["deadline"], "Accepted deadline or parent lease expired.", "BUDGET_EXHAUSTED")

    def start(self, requested, grant: WorkGrant):
        self.expire_jobs()
        require(grant.expires_at > self.clock(), "Parent grant expired.", "PERMISSION_DENIED")
        fingerprint = digest(canonical_json({"request": requested, "scope": grant.scope}))
        with self.transaction() as db:
            previous = db.execute("SELECT * FROM jobs WHERE principal=? AND idempotency_key=?", (grant.principal, requested["idempotencyKey"])).fetchone()
            if previous:
                require(previous["fingerprint"] == fingerprint, "Idempotency key already belongs to different work.", "IDEMPOTENCY_CONFLICT")
                require(not previous["deleted"], "Prior work was deleted; use a new accepted request.", "RESULTS_EXPIRED")
                return previous["id"]
            payload = canonical_json(asdict(grant))
            existing_grant = db.execute("SELECT payload FROM grants WHERE id=?", (grant.grant_id,)).fetchone()
            require(existing_grant is None or existing_grant[0] == payload, "Grant identity belongs to different authorization.", "PERMISSION_DENIED")
            db.execute("INSERT OR IGNORE INTO grants (id,payload) VALUES (?,?)", (grant.grant_id, payload))
            effective = dict(requested)
            effective["maxPhysicalRequests"] = min(requested["maxPhysicalRequests"], grant.max_requests)
            effective["maxDownloadedBytes"] = min(requested["maxDownloadedBytes"], grant.max_downloaded_bytes)
            reasons = ["accepted_parent_grant"] if any(effective[k] != requested[k] for k in ("maxPhysicalRequests", "maxDownloadedBytes")) else []
            effective["limitReasons"] = reasons
            parent = None
            seeds = requested.get("seeds", [])
            if "resumeFromJobId" in requested:
                parent = self._job(db, requested["resumeFromJobId"], grant)
                require(parent["state"] in TERMINAL, "Only a terminal job may be resumed.", "JOB_NOT_TERMINAL")
                require(not db.execute("SELECT 1 FROM attempts WHERE job_id=? AND state IN ('reserved','uncertain')", (parent["id"],)).fetchone(),
                        "Reconcile the original job's uncertain physical work before resuming.", "ACCOUNTING_UNAVAILABLE")
                effective["scope"] = json.loads(parent["effective"])["scope"]
                seeds = [row[0] for row in db.execute("SELECT url FROM frontier WHERE job_id=? AND state!='done' ORDER BY depth,url", (parent["id"],))]
            job_id = uuid.uuid4().hex
            deadline = min(self.clock() + requested["deadlineMs"] / 1000, grant.expires_at)
            db.execute("INSERT INTO jobs (id,principal,scope,idempotency_key,fingerprint,parent_id,requested,effective,grant_json,created,deadline) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                       (job_id, grant.principal, grant.scope, requested["idempotencyKey"], fingerprint, parent["id"] if parent else None,
                        canonical_json(requested), canonical_json(effective), canonical_json(asdict(grant)), self.clock(), deadline))
            for url in seeds[:effective["maxPages"]]:
                db.execute("INSERT INTO frontier (job_id,url,depth) VALUES (?,?,0)", (job_id, url))
            if parent:
                db.execute("INSERT INTO documents SELECT revision,?,source_id,metadata,text,artifact,1 FROM documents WHERE job_id=?", (job_id, parent["id"]))
                db.execute("INSERT INTO chunks (job_id,revision,chunk_id,start_offset,end_offset,text) SELECT ?,revision,chunk_id,start_offset,end_offset,text FROM chunks WHERE job_id=? ORDER BY sequence", (job_id, parent["id"]))
            return job_id

    def claim(self, job_id, grant, lease_seconds=30):
        with self.transaction() as db:
            job = self._job(db, job_id, grant)
            self._active(job, job["generation"])
            # A job has one acquisition owner. Six jobs can still use six HTTP slots.
            require(not db.execute("SELECT 1 FROM frontier WHERE job_id=? AND state='running'", (job_id,)).fetchone(), "A worker already owns this job.", "JOB_BUSY")
            row = db.execute("SELECT * FROM frontier WHERE job_id=? AND state='queued' ORDER BY depth,url LIMIT 1", (job_id,)).fetchone()
            if not row:
                return None
            lease = uuid.uuid4().hex
            db.execute("UPDATE frontier SET state='running',lease=?,lease_until=?,generation=? WHERE job_id=? AND url=?", (lease, self.clock() + lease_seconds, job["generation"], job_id, row["url"]))
            db.execute("UPDATE jobs SET state='running' WHERE id=?", (job_id,))
            return {**dict(row), "lease": lease, "generation": job["generation"]}

    def reserve_request(self, job_id, grant, generation, url):
        with self.transaction() as db:
            job = self._job(db, job_id, grant)
            self._active(job, generation)
            require(job["grant_json"] == canonical_json(asdict(grant)), "Work authorization changed.", "PERMISSION_DENIED")
            parent = db.execute("SELECT * FROM grants WHERE id=?", (grant.grant_id,)).fetchone()
            require(parent is not None and parent["requests"] < grant.max_requests and parent["downloaded"] < grant.max_downloaded_bytes,
                    "Shared parent physical allowance reached.", "BUDGET_EXHAUSTED")
            limits = json.loads(job["effective"])
            require(job["requests"] < limits["maxPhysicalRequests"] and job["downloaded"] < limits["maxDownloadedBytes"], "Physical request or downloaded body allowance reached.", "BUDGET_EXHAUSTED")
            attempt = uuid.uuid4().hex
            db.execute("INSERT INTO attempts (id,job_id,url,generation,state,created) VALUES (?,?,?,?,'reserved',?)", (attempt, job_id, url, generation, self.clock()))
            db.execute("UPDATE jobs SET requests=requests+1,receipt_seq=receipt_seq+1 WHERE id=?", (job_id,))
            db.execute("UPDATE grants SET requests=requests+1 WHERE id=?", (grant.grant_id,))
            return attempt

    def remaining_bytes(self, job_id, grant, generation):
        with self.lock:
            job = self._job(self.db, job_id, grant)
            self._active(job, generation)
            require(job["grant_json"] == canonical_json(asdict(grant)), "Work authorization changed.", "PERMISSION_DENIED")
            parent = self.db.execute("SELECT downloaded FROM grants WHERE id=?", (grant.grant_id,)).fetchone()
            return min(json.loads(job["effective"])["maxDownloadedBytes"] - job["downloaded"], grant.max_downloaded_bytes - parent[0])

    def record_bytes(self, attempt, downloaded, decoded):
        require(type(downloaded) is int and type(decoded) is int and downloaded >= 0 and decoded >= 0, "Invalid physical byte receipt.", "ACCOUNTING_UNAVAILABLE")
        with self.transaction() as db:
            row = db.execute("SELECT * FROM attempts WHERE id=?", (attempt,)).fetchone()
            require(row is not None and row["state"] == "reserved", "Physical attempt is not open.", "ACCOUNTING_UNAVAILABLE")
            # Bytes observed after a cancellation still count; publication remains fenced.
            db.execute("UPDATE attempts SET downloaded=downloaded+?,decoded=decoded+? WHERE id=?", (downloaded, decoded, attempt))
            db.execute("UPDATE jobs SET downloaded=downloaded+?,decoded=decoded+?,receipt_seq=receipt_seq+1 WHERE id=?", (downloaded, decoded, row["job_id"]))
            parent = json.loads(db.execute("SELECT grant_json FROM jobs WHERE id=?", (row["job_id"],)).fetchone()[0])["grant_id"]
            db.execute("UPDATE grants SET downloaded=downloaded+? WHERE id=?", (downloaded, parent))

    def finish_attempt(self, attempt, error=None, uncertain=False):
        with self.transaction() as db:
            row = db.execute("SELECT * FROM attempts WHERE id=?", (attempt,)).fetchone()
            require(row is not None, "Unknown attempt receipt.", "ACCOUNTING_UNAVAILABLE")
            if row["state"] != "reserved":
                return
            db.execute("UPDATE attempts SET state=?,error=? WHERE id=?", ("uncertain" if uncertain else "finished", error, attempt))
            db.execute("UPDATE jobs SET receipt_seq=receipt_seq+1 WHERE id=?", (row["job_id"],))

    def publish(self, claim, grant, document, artifact, links):
        job_id = claim["job_id"]
        with self.transaction() as db:
            job = self._job(db, job_id, grant)
            self._active(job, claim["generation"])
            owner = db.execute("SELECT * FROM frontier WHERE job_id=? AND url=?", (job_id, claim["url"])).fetchone()
            require(owner["state"] == "running" and owner["lease"] == claim["lease"] and owner["lease_until"] > self.clock(), "Worker lease expired before publication.", "LEASE_EXPIRED")
            require(not db.execute("SELECT 1 FROM attempts WHERE job_id=? AND state IN ('reserved','uncertain')", (job_id,)).fetchone(), "Reconcile physical work before publishing or retrying.", "ACCOUNTING_UNAVAILABLE")
            body = document["text"]
            revision = document["documentRevision"]
            require(document["contentHash"] == digest(body), "Extraction content hash mismatch.", "PARSE_FAILED")
            metadata = {key: value for key, value in document.items() if key != "text"}
            metadata["artifactHash"] = self._save_objects(db, grant.scope, grant.principal, revision, body, artifact)
            inserted = db.execute("INSERT OR IGNORE INTO documents VALUES (?,?,?,?,?,?,0)", (revision, job_id, document["sourceId"], canonical_json(metadata), "", b"")).rowcount
            if inserted:
                for start in range(0, len(body), 2048):
                    end = min(start + 2048, len(body))
                    chunk_id = digest(f"{revision}:{start}:{end}")
                    db.execute("INSERT INTO chunks (job_id,revision,chunk_id,start_offset,end_offset,text) VALUES (?,?,?,?,?,?)", (job_id, revision, chunk_id, start, end, ""))
            db.execute("UPDATE frontier SET state='done',lease=NULL,lease_until=NULL WHERE job_id=? AND url=?", (job_id, claim["url"]))
            settings = json.loads(job["effective"])
            count = db.execute("SELECT COUNT(*) FROM frontier WHERE job_id=?", (job_id,)).fetchone()[0]
            if claim["depth"] < settings["maxDepth"] and settings["mode"] == "deep":
                for url in links:
                    if count >= settings["maxPages"]:
                        break
                    if urlsplit(url).hostname in settings["scope"]["allowedHosts"]:
                        count += db.execute("INSERT OR IGNORE INTO frontier (job_id,url,depth) VALUES (?,?,?)", (job_id, url, claim["depth"] + 1)).rowcount
            return revision

    def fail_claim(self, claim, code):
        with self.transaction() as db:
            db.execute("UPDATE frontier SET state='failed',error=?,lease=NULL,lease_until=NULL WHERE job_id=? AND url=? AND lease=? AND generation=?", (code, claim["job_id"], claim["url"], claim["lease"], claim["generation"]))

    def finish(self, job_id, grant, reason):
        with self.transaction() as db:
            job = self._job(db, job_id, grant)
            if job["state"] in TERMINAL:
                return
            require(not db.execute("SELECT 1 FROM frontier WHERE job_id=? AND state='running'", (job_id,)).fetchone(), "A worker is still active.", "JOB_BUSY")
            count = db.execute("SELECT COUNT(*) FROM documents WHERE job_id=? AND inherited=0", (job_id,)).fetchone()[0]
            goal = json.loads(job["effective"])["sourceGoal"]
            outcome = "complete" if count >= goal else "partial" if count else "empty"
            db.execute("UPDATE jobs SET state='completed',outcome=?,reason=?,receipt_seq=receipt_seq+1 WHERE id=?", (outcome, reason, job_id))

    def cancel(self, job_id, grant):
        with self.transaction() as db:
            job = self._job(db, job_id, grant)
            if job["state"] not in TERMINAL:
                db.execute("UPDATE jobs SET state='cancelled',outcome='partial',reason='user_cancelled',generation=generation+1,receipt_seq=receipt_seq+1 WHERE id=?", (job_id,))
                db.execute("UPDATE frontier SET state='cancelled' WHERE job_id=? AND state IN ('running','queued')", (job_id,))
        return self.status(job_id, grant)

    def recover(self):
        """Startup never replays an unconfirmed network request or revives a terminal job."""
        with self.transaction() as db:
            for job in db.execute("SELECT * FROM jobs WHERE state NOT IN ('completed','cancelled','failed')").fetchall():
                db.execute("UPDATE attempts SET state='uncertain',error='process_interrupted' WHERE job_id=? AND state='reserved'", (job["id"],))
                db.execute("UPDATE frontier SET state='failed',error='process_interrupted',lease=NULL,lease_until=NULL WHERE job_id=? AND state='running'", (job["id"],))
                db.execute("UPDATE jobs SET state='failed',outcome='partial',reason='ACCOUNTING_UNAVAILABLE',generation=generation+1,receipt_seq=receipt_seq+1 WHERE id=?", (job["id"],))

    def status(self, job_id, grant):
        with self.lock:
            job = self._job(self.db, job_id, grant)
            counts = dict(self.db.execute("SELECT state,COUNT(*) FROM frontier WHERE job_id=? GROUP BY state", (job_id,)).fetchall())
            documents = dict(self.db.execute("SELECT inherited,COUNT(*) FROM documents WHERE job_id=? GROUP BY inherited", (job_id,)).fetchall())
            uncertain = self.db.execute("SELECT COUNT(*) FROM attempts WHERE job_id=? AND state IN ('reserved','uncertain')", (job_id,)).fetchone()[0]
            return {"jobId": job_id, "state": job["state"], "outcome": job["outcome"], "stopReason": job["reason"],
                    "requestedSettings": json.loads(job["requested"]), "effectiveSettings": json.loads(job["effective"]),
                    "progress": {"admittedUrls": sum(counts.values()), "activeWorkers": counts.get("running", 0), "pendingUrls": counts.get("queued", 0),
                                 "failedUrls": counts.get("failed", 0), "newDocuments": documents.get(0, 0), "inheritedDocuments": documents.get(1, 0)},
                    "accounting": {"receiptId": f"{job_id}:{job['receipt_seq']}", "sequence": job["receipt_seq"], "grantId": json.loads(job["grant_json"])["grant_id"],
                                   "physicalRequests": job["requests"], "downloadedBytes": job["downloaded"], "decodedBytes": job["decoded"],
                                   "uncertainRequests": uncertain, "final": job["state"] in TERMINAL and uncertain == 0},
                    "resultsAvailable": bool(documents), "resumeEligible": job["state"] in TERMINAL and uncertain == 0}

    def _encode_cursor(self, payload):
        raw = canonical_json(payload).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=") + "." + hmac.digest(self.cursor_secret, raw, "sha256").hex()

    def _decode_cursor(self, cursor):
        try:
            encoded, signature = cursor.split(".")
            raw = base64.b64decode(encoded + "=" * (-len(encoded) % 4), altchars=b"-_", validate=True)
            require(hmac.compare_digest(signature, hmac.digest(self.cursor_secret, raw, "sha256").hex()), "Cursor integrity check failed.", "INVALID_CURSOR")
            return json.loads(raw)
        except (ValueError, TypeError, KeyError):
            raise CrawlerError("INVALID_CURSOR", "Malformed cursor.") from None

    def results(self, job_id, grant, format="text", output_tokens=3000, output_bytes=64000, cursor=None, measure=None):
        # The MCP caller supplies the size of the complete JSON-RPC/content envelope,
        # including escaped duplicate compatibility text, not just evidence payloads.
        measure = measure or (lambda value: len(canonical_json(value).encode()))
        with self.transaction() as db:
            self._job(db, job_id, grant)
            if cursor:
                token = self._decode_cursor(cursor)
                require(token.get("job") == job_id and token.get("scope") == grant.scope and token.get("format") == format and token.get("version") == 1,
                        "Cursor belongs to different work, access scope or format.", "INVALID_CURSOR")
                snapshot = db.execute("SELECT * FROM snapshots WHERE id=?", (token["snapshot"],)).fetchone()
                require(snapshot is not None and self.clock() - snapshot["created"] < 86400, "Result snapshot expired.", "CURSOR_EXPIRED")
                after = token["after"]
            else:
                snapshot_id = uuid.uuid4().hex
                watermark = db.execute("SELECT COALESCE(MAX(sequence),0) FROM chunks WHERE job_id=?", (job_id,)).fetchone()[0]
                metadata = self.status(job_id, grant)
                db.execute("INSERT INTO snapshots VALUES (?,?,?,?,?,?)", (snapshot_id, job_id, grant.scope, watermark, canonical_json(metadata), self.clock()))
                snapshot = db.execute("SELECT * FROM snapshots WHERE id=?", (snapshot_id,)).fetchone()
                after = 0
            rows = db.execute("SELECT c.sequence,c.chunk_id,c.start_offset,c.end_offset,substr(o.text,c.start_offset+1,c.end_offset-c.start_offset) AS text,d.metadata FROM chunks c JOIN documents d ON c.job_id=d.job_id AND c.revision=d.revision JOIN jobs j ON j.id=c.job_id JOIN content_objects o ON o.scope=j.scope AND o.revision=c.revision WHERE c.job_id=? AND sequence>? AND sequence<=? ORDER BY sequence", (job_id, after, snapshot["watermark"])).fetchall()
            base = {"snapshotId": snapshot["id"], "jobId": job_id, "chunks": [], "format": format,
                    "jobTerminal": json.loads(snapshot["metadata"])["state"] in TERMINAL,
                    "accounting": json.loads(snapshot["metadata"])["accounting"], "tokenEstimateMethod": "utf8_bytes_divided_by_three"}
            def envelope(sequence, more):
                return {**base, "hasMoreWithinSnapshot": more, "outputTruncated": more,
                        "nextCursor": self._encode_cursor({"version": 1, "job": job_id, "snapshot": snapshot["id"], "scope": grant.scope, "format": format, "after": sequence}) if more else None}
            bound = min(output_bytes, output_tokens * 3)
            require(measure(envelope(after, bool(rows))) <= bound, "Output budget cannot fit the result envelope.", "OUTPUT_LIMIT_TOO_SMALL")
            sequence = after
            for index, row in enumerate(rows):
                base["chunks"].append({"chunkId": row["chunk_id"], "startOffset": row["start_offset"], "endOffset": row["end_offset"],
                                       "text": row["text"], "document": json.loads(row["metadata"])})
                if measure(envelope(row["sequence"], index < len(rows) - 1)) > bound:
                    base["chunks"].pop()
                    require(bool(base["chunks"]), "Output budget cannot fit one evidence chunk; increase the visible output limit.", "OUTPUT_LIMIT_TOO_SMALL")
                    break
                sequence = row["sequence"]
            return envelope(sequence, sequence < snapshot["watermark"])

    def retain(self, job_id, grant, owner):
        """Trusted import bridge pins evidence until the archive has copied or released it."""
        require(isinstance(owner, str) and 0 < len(owner) <= 256, "Invalid retained evidence owner.")
        with self.transaction() as db:
            job = self._job(db, job_id, grant)
            require(not json.loads(job["grant_json"]).get("ephemeral", False), "Temporary evidence must be copied into an explicitly authorized archive.", "PERMISSION_DENIED")
            db.execute("INSERT OR IGNORE INTO retained_jobs VALUES (?,?)", (job_id, owner))

    def release(self, job_id, grant, owner):
        with self.transaction() as db:
            self._job(db, job_id, grant)
            db.execute("DELETE FROM retained_jobs WHERE job_id=? AND owner=?", (job_id, owner))

    def expire_jobs(self, terminal_seconds=7 * 86400, ephemeral_seconds=3600):
        require(terminal_seconds > 0 and ephemeral_seconds > 0, "Retention durations must be positive.")
        with self.transaction() as db:
            rows = db.execute("SELECT * FROM jobs WHERE deleted=0 AND NOT EXISTS (SELECT 1 FROM retained_jobs r WHERE r.job_id=jobs.id)").fetchall()
            expired = []
            for row in rows:
                temporary = json.loads(row["grant_json"]).get("ephemeral", False)
                age = self.clock() - row["created"]
                if (temporary and age >= ephemeral_seconds) or (row["state"] in TERMINAL and age >= terminal_seconds):
                    self._delete_job(db, row["id"], "retention_expired")
                    expired.append(row["id"])
            db.execute("DELETE FROM snapshots WHERE created<?", (self.clock() - 86400,))
            self._collect_objects(db)
            return expired

    def _delete_job(self, db, job_id, reason):
        db.execute("UPDATE jobs SET deleted=1,generation=generation+1,state='cancelled',reason=? WHERE id=?", (reason, job_id))
        db.execute("UPDATE frontier SET state='cancelled',lease=NULL,lease_until=NULL WHERE job_id=?", (job_id,))
        for table in ("chunks", "documents", "snapshots", "retained_jobs"):
            db.execute(f"DELETE FROM {table} WHERE job_id=?", (job_id,))

    def delete(self, job_id, grant):
        with self.transaction() as db:
            self._job(db, job_id, grant)
            self._delete_job(db, job_id, "deleted")
            self._collect_objects(db)
