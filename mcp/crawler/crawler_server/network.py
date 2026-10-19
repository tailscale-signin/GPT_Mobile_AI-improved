"""The only HTTP egress path: validated, DNS-pinned, scoped, counted and cancellable."""
from __future__ import annotations

import http.client
import ipaddress
import socket
import ssl
import threading
import time
import zlib
from email.utils import parsedate_to_datetime
from dataclasses import dataclass, field
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.robotparser import RobotFileParser

from .contracts import CrawlerError, require, url_identity

USER_AGENT = "GPTMobileCrawler/1.0"


def retry_after_deadline(value, now):
    value = value.strip()
    if value.isascii() and value.isdigit():
        # Keep long server cooldowns; the largest representable date is effectively indefinite.
        return min(253402300799.0, now + int(value)) if len(value) < 16 else 253402300799.0
    try:
        date = parsedate_to_datetime(value)
        if date.tzinfo is not None:
            return max(now, date.timestamp())
    except (ValueError, TypeError, OverflowError):
        pass
    return now + 60


@dataclass(frozen=True)
class CapturedResponse:
    requested_url: str
    resolved_url: str
    status: int
    headers: dict
    body: bytes
    attempts: tuple[str, ...]
    truncated: bool = False
    fetched_at: float = field(default_factory=time.time)


def public_addresses(host, port):
    try:
        addresses = sorted({row[4][0] for row in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)})
    except OSError:
        raise CrawlerError("NETWORK_ERROR", "The source hostname could not be resolved.") from None
    require(addresses and all(ipaddress.ip_address(value).is_global for value in addresses), "Source resolves to a private or reserved address.", "SOURCE_FORBIDDEN")
    return addresses


class PinnedConnection(http.client.HTTPConnection):
    def __init__(self, host, address, port, timeout, tls):
        super().__init__(host, port, timeout=timeout)
        self.address, self.tls = address, tls

    def connect(self):
        raw = socket.create_connection((self.address, self.port), self.timeout)
        try:
            self.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=self.host) if self.tls else raw
        except BaseException:
            raw.close()
            raise


class NetworkBoundary:
    def __init__(self, store, max_http=6, per_domain=2, interval=1.0, decoded_limit=32 * 1024 * 1024,
                 resolver=public_addresses, connection=PinnedConnection, clock=time.monotonic):
        self.store, self.resolver, self.connection, self.clock = store, resolver, connection, clock
        self.slots = threading.BoundedSemaphore(max_http)
        self.per_domain, self.interval, self.decoded_limit = per_domain, interval, decoded_limit
        self.lock = threading.RLock()
        self.domain_slots, self.next_start, self.failures = {}, {}, {}
        self.job_locks, self.connections = {}, {}

    def cancel(self, job):
        with self.lock:
            connections = list(self.connections.get(job, ()))
        for connection in connections:
            try:
                if connection.sock:
                    connection.sock.shutdown(socket.SHUT_RDWR)
                connection.close()
            except OSError:
                pass

    def _wait(self, semaphore, cancelled):
        while not semaphore.acquire(timeout=0.1):
            if cancelled.is_set():
                raise CrawlerError("CANCELLED", "Read cancelled.")

    def _scope(self, url, hosts):
        normalized = url_identity(url)
        require(urlsplit(normalized).hostname in hosts, "Destination is outside the accepted host scope.", "SOURCE_FORBIDDEN")
        return normalized

    def fetch(self, job, grant, generation, url, hosts, cancelled, *, check_robots=True):
        with self.lock:
            job_lock = self.job_locks.setdefault(grant.grant_id, threading.RLock())
        # Serial per-grant reads make the shared byte allowance enforceable without
        # reserving a fresh byte budget for each engine or redirect.
        with job_lock:
            normalized = self._scope(url, hosts)
            attempts = []
            current = normalized
            for _ in range(6):
                if check_robots:
                    origin = urlsplit(current)
                    robots_url = urlunsplit((origin.scheme, origin.netloc, "/robots.txt", "", ""))
                    robots = self.fetch(job, grant, generation, robots_url, hosts, cancelled, check_robots=False)
                    attempts.extend(robots.attempts)
                    if robots.status in (401, 403):
                        raise CrawlerError("ROBOTS_DISALLOWED", "The source denies crawler access.")
                    require(robots.status < 500 and not robots.truncated, "Robots policy is unavailable; retry within an approved grant.", "ROBOTS_UNAVAILABLE")
                    parser = RobotFileParser()
                    parser.parse(robots.body.decode("utf-8", errors="replace").splitlines() if robots.status < 400 else [])
                    require(parser.can_fetch(USER_AGENT, current), "Robots policy disallows this URL.", "ROBOTS_DISALLOWED")
                response = self._read(job, grant, generation, current, hosts, cancelled)
                attempts.extend(response.attempts)
                if response.status in (301, 302, 303, 307, 308):
                    target = self._scope(urljoin(current, response.headers.get("location", "")), hosts)
                    require(target != current, "Redirect loop.", "REDIRECT_LIMIT")
                    current = target
                    continue
                return CapturedResponse(normalized, current, response.status, response.headers, response.body, tuple(attempts), response.truncated, response.fetched_at)
            raise CrawlerError("REDIRECT_LIMIT", "The source exceeded the declared five-redirect limit.")

    def _read(self, job, grant, generation, url, hosts, cancelled):
        self._scope(url, hosts)
        require(not cancelled.is_set(), "Read cancelled.", "CANCELLED")
        split = urlsplit(url)
        host = split.hostname
        port = split.port or (443 if split.scheme == "https" else 80)
        self.store.remaining_bytes(job, grant, generation)
        addresses = self.resolver(host, port)
        # Validate injected/custom resolvers too, and pin the socket to that result.
        require(addresses and all(ipaddress.ip_address(value).is_global for value in addresses), "Private destination refused.", "SOURCE_FORBIDDEN")
        with self.lock:
            domain = self.domain_slots.setdefault(host, threading.BoundedSemaphore(self.per_domain))
        self._wait(self.slots, cancelled)
        domain_acquired = False
        conn, attempt = None, None
        try:
            self._wait(domain, cancelled)
            domain_acquired = True
            # Pace actual dispatch after acquiring both permits: queued workers cannot
            # bunch together when a busy domain or global slot becomes available.
            while True:
                require(self.store.clock() >= self.store.domain_cooldown(host), "Source is cooling down after blocking or repeated failures.", "RATE_LIMITED")
                with self.lock:
                    delay = max(0, self.next_start.get(host, 0) - self.clock())
                    if delay == 0:
                        self.next_start[host] = self.clock() + self.interval
                        break
                require(not cancelled.wait(min(delay, 0.25)), "Read cancelled.", "CANCELLED")
                self.store.remaining_bytes(job, grant, generation)
            require(not cancelled.is_set(), "Read cancelled.", "CANCELLED")
            self.store.remaining_bytes(job, grant, generation)
            timeout = max(0.05, min(25, grant.expires_at - self.store.clock()))
            conn = self.connection(host, addresses[0], port, timeout, split.scheme == "https")
            with self.lock:
                self.connections.setdefault(job, set()).add(conn)
            attempt = self.store.reserve_request(job, grant, generation, url)
            path = urlunsplit(("", "", split.path or "/", split.query, ""))
            conn.request("GET", path, headers={"User-Agent": USER_AGENT, "Accept-Encoding": "gzip, deflate", "Accept": "text/html,application/pdf,text/plain;q=0.9,*/*;q=0.1"})
            reply = conn.getresponse()
            headers = {key.lower(): value for key, value in reply.getheaders()}
            encoding = headers.get("content-encoding", "identity").lower()
            require(encoding in ("gzip", "deflate", "identity"), "Unsupported content encoding.", "PARSE_FAILED")
            decoder = zlib.decompressobj(16 + zlib.MAX_WBITS if encoding == "gzip" else zlib.MAX_WBITS) if encoding != "identity" else None
            body = bytearray()
            truncated = False
            while True:
                require(not cancelled.is_set(), "Read cancelled.", "CANCELLED")
                remaining = self.store.remaining_bytes(job, grant, generation)
                if remaining == 0 or len(body) >= self.decoded_limit:
                    truncated = True
                    break
                chunk = reply.read1(min(16_384, remaining, self.decoded_limit - len(body)))
                if not chunk:
                    break
                # Record transferred body bytes before decoding; malformed compression
                # must not erase already performed physical work.
                self.store.record_bytes(attempt, len(chunk), 0)
                content = decoder.decompress(chunk, self.decoded_limit - len(body)) if decoder else chunk
                self.store.record_bytes(attempt, 0, len(content))
                body.extend(content)
            if decoder and not truncated:
                require(decoder.eof, "Compressed response ended before its complete frame.", "PARSE_FAILED")
            with self.lock:
                if reply.status in (429, 503):
                    self.store.defer_domain(host, retry_after_deadline(headers.get("retry-after", ""), self.store.clock()))
                self.failures[host] = 0 if reply.status < 500 else self.failures.get(host, 0) + 1
                if self.failures[host] >= 3:
                    self.store.defer_domain(host, self.store.clock() + 60)
            self.store.finish_attempt(attempt)
            return CapturedResponse(url, url, reply.status, headers, bytes(body), (attempt,), truncated)
        except (OSError, http.client.HTTPException, zlib.error) as failure:
            if attempt:
                self.store.finish_attempt(attempt, "NETWORK_TIMEOUT" if isinstance(failure, TimeoutError) else "NETWORK_ERROR", uncertain=True)
            raise CrawlerError("CANCELLED" if cancelled.is_set() else "NETWORK_ERROR", "Source transport interrupted; reconcile the retained job before another acquisition.") from None
        except BaseException:
            if attempt:
                self.store.finish_attempt(attempt, "INTERRUPTED", uncertain=True)
            raise
        finally:
            if conn:
                conn.close()
                with self.lock:
                    self.connections.get(job, set()).discard(conn)
            if domain_acquired:
                domain.release()
            self.slots.release()
