"""Authenticated, stateless Streamable HTTP MCP transport over the same durable owner.

POST returns JSON directly; GET/SSE is intentionally unsupported. Remote listeners
require TLS. A local reverse proxy may terminate TLS in front of a loopback listener.
"""
from __future__ import annotations

import hmac
import ipaddress
import json
import ssl
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .contracts import CrawlerError, canonical_json, require
from .server import MAX_INPUT, PROTOCOLS, verify_grant


class CrawlerHttpServer(ThreadingHTTPServer):
    daemon_threads = False
    block_on_close = True

    def __init__(self, address, facade, token, tls=None, origins=(), max_requests=16):
        require(isinstance(token, str) and len(token) >= 32 and token.isascii() and not any(c.isspace() for c in token), "HTTP token must contain at least 32 non-whitespace ASCII characters.")
        require(ipaddress.ip_address(address[0]).is_loopback or tls is not None, "Remote HTTP listeners require TLS.")
        self.facade, self.token = facade, token
        self.origins = frozenset(origins)
        self.slots = threading.BoundedSemaphore(max_requests)
        if ":" in address[0]:
            self.address_family = socket.AF_INET6
        super().__init__(address, Handler)
        if tls is not None:
            require(isinstance(tls, ssl.SSLContext), "Invalid TLS context.")
            self.socket = tls.wrap_socket(self.socket, server_side=True, do_handshake_on_connect=False)

    def process_request(self, request, client_address):
        # Bound threads before allocation, including peers stalled in request headers.
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "GPTMobileCrawler"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, *_):
        # No query strings, headers, signed grants or source text in HTTP access logs.
        pass

    def reply(self, status, value=None, headers=None):
        body = b"" if value is None else canonical_json(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.send_header("X-Content-Type-Options", "nosniff")
        if body:
            self.send_header("Content-Type", "application/json; charset=utf-8")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        if body:
            self.wfile.write(body)
        self.close_connection = True

    def authorize(self):
        if self.path != "/mcp":
            self.reply(404)
            return False
        auth = self.headers.get_all("Authorization", [])
        if len(auth) != 1 or not hmac.compare_digest(auth[0].encode(), ("Bearer " + self.server.token).encode()):
            self.reply(401, headers={"WWW-Authenticate": "Bearer"})
            return False
        origin = self.headers.get_all("Origin", [])
        if origin and (len(origin) != 1 or origin[0] not in self.server.origins):
            self.reply(403)
            return False
        protocol = self.headers.get("MCP-Protocol-Version")
        if protocol is not None and protocol not in PROTOCOLS:
            self.reply(400, {"error": "Unsupported MCP protocol."})
            return False
        return True

    def do_GET(self):
        if self.authorize():
            self.reply(405, headers={"Allow": "POST"})

    do_DELETE = do_GET
    do_OPTIONS = do_GET

    def do_POST(self):
        if not self.authorize():
            return
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") is not None or len(lengths) != 1 or not lengths[0].isdigit():
            self.reply(400, {"error": "A single bounded Content-Length is required."})
            return
        length = int(lengths[0])
        if not 0 < length <= MAX_INPUT:
            self.reply(413)
            return
        if self.headers.get_content_type() != "application/json":
            self.reply(415)
            return
        message = None
        try:
            body = self.rfile.read(length)
            require(len(body) == length, "Incomplete request body.")
            message = json.loads(body, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))
            require(type(message) is dict and message.get("jsonrpc") == "2.0", "Expected one JSON-RPC request.")
            request_id = message.get("id")
            if "id" not in message:
                method = message.get("method")
                if method == "notifications/cancelled":
                    params = message.get("params") or {}
                    require(type(params) is dict and type(params.get("requestId")) in (str, int), "Invalid cancellation.")
                    metadata = params.get("_meta") or {}
                    require(type(metadata) is dict, "Invalid cancellation metadata.")
                    grant = verify_grant(metadata.get("gptmobile/crawlerGrant"), self.server.facade.grant_key)
                    self.server.facade.cancel_request(params["requestId"], grant)
                else:
                    require(method == "notifications/initialized", "Unsupported notification.")
                self.reply(202)
                return
            require(type(request_id) in (str, int) and len(str(request_id)) <= 128, "Invalid request ID.")
            # The facade owns fetch deadlines. Header/body timeouts must not cut off admitted work.
            result = self.server.facade.rpc(message)
            self.reply(200, {"jsonrpc": "2.0", "id": request_id, "result": result})
        except (ValueError, RecursionError, UnicodeError):
            self.reply(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Invalid JSON."}})
        except CrawlerError as error:
            self.reply(400 if error.code != "PERMISSION_DENIED" else 403, {"jsonrpc": "2.0", "id": message.get("id") if type(message) is dict else None,
                       "error": {"code": -32601 if error.code == "METHOD_NOT_FOUND" else -32602, "message": error.message}})
        except (TimeoutError, ConnectionError, BrokenPipeError):
            self.close_connection = True
        except Exception:
            self.reply(500, {"error": "Operation failed; retained jobs remain available."})
