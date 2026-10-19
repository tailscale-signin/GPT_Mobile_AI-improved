"""Bounded MCP stdio façade. Execution grants are authenticated metadata, never tool arguments."""
from __future__ import annotations

import argparse
import concurrent.futures
import hmac
import json
import os
import sys
import threading
from dataclasses import asdict
from pathlib import Path

from . import __version__
from .contracts import CrawlerError, WorkGrant, canonical_json, request, require, tool_definitions
from .coordinator import Coordinator
from .store import Store

PROTOCOLS = ("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
MAX_INPUT = 64 * 1024


def sign_grant(grant: WorkGrant, key: bytes):
    payload = asdict(grant)
    return {"payload": payload, "signature": hmac.digest(key, canonical_json(payload).encode(), "sha256").hex()}


def verify_grant(value, key):
    require(type(value) is dict and set(value) == {"payload", "signature"}, "An authenticated acquisition grant is required.", "PERMISSION_DENIED")
    payload, signature = value["payload"], value["signature"]
    require(type(payload) is dict and isinstance(signature, str), "Malformed acquisition grant.", "PERMISSION_DENIED")
    expected = hmac.digest(key, canonical_json(payload).encode(), "sha256").hex()
    require(hmac.compare_digest(signature, expected), "Acquisition grant could not be authenticated.", "PERMISSION_DENIED")
    try:
        return WorkGrant(**payload)
    except (TypeError, ValueError):
        raise CrawlerError("PERMISSION_DENIED", "Malformed acquisition grant.") from None


class Server:
    def __init__(self, coordinator, grant_key):
        require(len(grant_key) >= 32, "Use a grant authentication key of at least 32 bytes.")
        self.coordinator, self.grant_key = coordinator, grant_key
        self.lock = threading.RLock()
        self.active, self.early_cancellations = {}, set()

    def cancel_request(self, request_id):
        key = canonical_json(request_id)
        with self.lock:
            active = self.active.get(key)
            if active is None:
                if len(self.early_cancellations) < 128:
                    self.early_cancellations.add(key)
                return
            active["cancelled"].set()
            if active.get("job"):
                self.coordinator.cancel(active["job"], active["grant"])

    @staticmethod
    def envelope(operation, request_id, data=None, error=None):
        envelope = {"schemaVersion": 1, "operation": operation, "requestId": request_id,
                    **({"error": error} if error else {"data": data})}
        return {"isError": error is not None, "structuredContent": envelope,
                "content": [{"type": "text", "text": canonical_json(envelope)}]}

    def call(self, operation, arguments, metadata, request_id):
        key = canonical_json(request_id)
        registered = False
        try:
            require(type(metadata) is dict, "Tool metadata must be an object.")
            args = request(operation, arguments)
            grant = verify_grant(metadata.get("gptmobile/crawlerGrant"), self.grant_key)
            with self.lock:
                require(key not in self.active, "Request ID already active.", "REQUEST_ID_CONFLICT")
                cancelled = threading.Event()
                if key in self.early_cancellations:
                    self.early_cancellations.remove(key)
                    cancelled.set()
                self.active[key] = {"cancelled": cancelled, "grant": grant}
                registered = True
            require(not cancelled.is_set(), "Request cancelled before dispatch.", "CANCELLED")

            def on_job(job):
                with self.lock:
                    self.active[key]["job"] = job
                    if cancelled.is_set():
                        self.coordinator.cancel(job, grant)

            def measure(data):
                response = {"jsonrpc": "2.0", "id": request_id, "result": self.envelope(operation, request_id, data)}
                return len(canonical_json(response).encode("utf-8")) + 1

            if operation == "fetch":
                data = self.coordinator.fetch(args, grant, cancelled, on_job, measure)
            elif operation == "start":
                data = self.coordinator.start(args, grant)
            elif operation == "status":
                data = self.coordinator.store.status(args["jobId"], grant)
            elif operation == "cancel":
                data = self.coordinator.cancel(args["jobId"], grant)
            else:
                data = self.coordinator.store.results(args["jobId"], grant, args["format"], args["outputTokens"], args["outputBytes"], args.get("cursor"), measure)
            return self.envelope(operation, request_id, data)
        except CrawlerError as error:
            return self.envelope(operation, request_id, error={"code": error.code, "message": error.message, **error.details})
        finally:
            if registered:
                with self.lock:
                    self.active.pop(key, None)

    def rpc(self, message):
        require(type(message) is dict and message.get("jsonrpc") == "2.0", "Invalid JSON-RPC request.")
        method, params = message.get("method"), message.get("params") or {}
        require(type(params) is dict, "RPC params must be an object.")
        if method == "initialize":
            protocol = params.get("protocolVersion")
            require(protocol in PROTOCOLS, "Unsupported MCP protocol.", "SCHEMA_INCOMPATIBLE")
            return {"protocolVersion": protocol, "capabilities": {"tools": {}, "resources": {}}, "serverInfo": {"name": "gptmobile-crawler", "version": __version__}}
        if method == "ping":
            return {}
        if method == "tools/list":
            return {"tools": tool_definitions()}
        if method == "resources/list":
            return {"resources": [{"uri": "crawler://health", "name": "Crawler readiness", "mimeType": "application/json"}]}
        if method == "resources/read":
            require(params.get("uri") == "crawler://health", "Unknown resource.")
            health = {"schemaVersion": 1, "version": __version__, "engines": self.coordinator.extractor.health(),
                      "limits": {"httpSlots": 6, "backgroundSlots": 4, "interactiveSlots": 2, "perDomainSlots": 2, "domainIntervalSeconds": 1,
                                 "maxDecodedBytesPerResponse": self.coordinator.network.decoded_limit},
                      "automaticRecovery": "paused; uncertain requests require reconciliation"}
            return {"contents": [{"uri": "crawler://health", "mimeType": "application/json", "text": canonical_json(health)}]}
        if method == "tools/call":
            require(type(message.get("id")) in (int, str) and len(str(message["id"])) <= 128, "Invalid request ID.")
            return self.call(params.get("name"), params.get("arguments", {}), params.get("_meta") or {}, message.get("id"))
        raise CrawlerError("METHOD_NOT_FOUND", "Unsupported MCP method.")


def main():
    parser = argparse.ArgumentParser(description="GPT Mobile Crawler MCP stdio companion")
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--grant-key-file", type=Path, required=True)
    args = parser.parse_args()
    args.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    # A second owner must never run startup recovery against an active acquisition process.
    import fcntl
    owner = os.open(args.data_dir / "owner.lock", os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(owner, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(owner)
        raise CrawlerError("OWNER_ACTIVE", "A crawler process already owns this data directory.") from None
    if os.name == "posix":
        require(args.grant_key_file.stat().st_mode & 0o077 == 0, "Grant key file must be private to its owner.")
    key = args.grant_key_file.read_bytes()
    store = Store(args.data_dir / "crawler.sqlite")
    store.recover()
    coordinator = Coordinator(store)
    server = Server(coordinator, key)
    output_lock = threading.Lock()
    outstanding = threading.BoundedSemaphore(16)

    def respond(message):
        try:
            try:
                response = {"jsonrpc": "2.0", "id": message["id"], "result": server.rpc(message)}
            except CrawlerError as failure:
                response = {"jsonrpc": "2.0", "id": message.get("id"), "error": {"code": -32601 if failure.code == "METHOD_NOT_FOUND" else -32602, "message": failure.message}}
            except Exception:
                response = {"jsonrpc": "2.0", "id": message.get("id"), "error": {"code": -32603, "message": "Crawler could not complete this operation; retained work remains available."}}
            with output_lock:
                sys.stdout.write(canonical_json(response) + "\n")
                sys.stdout.flush()
        finally:
            outstanding.release()

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8, thread_name_prefix="crawler-rpc") as rpc:
            while True:
                line = sys.stdin.buffer.readline(MAX_INPUT + 1)
                if not line:
                    break
                if len(line) > MAX_INPUT:
                    while line and not line.endswith(b"\n"):
                        line = sys.stdin.buffer.readline(MAX_INPUT + 1)
                    continue
                try:
                    message = json.loads(line)
                    if isinstance(message, dict) and message.get("method") == "notifications/cancelled":
                        params = message.get("params")
                        if isinstance(params, dict) and type(params.get("requestId")) in (str, int):
                            server.cancel_request(params["requestId"])
                        continue
                    if not isinstance(message, dict) or "id" not in message:
                        continue
                except (ValueError, RecursionError):
                    continue
                outstanding.acquire()
                rpc.submit(respond, message)
    finally:
        coordinator.close()
        store.close()
        os.close(owner)


if __name__ == "__main__":
    main()
