"""Client-owned GitHub fast path and bounded, credential-fenced immutable reads."""
import collections
import hashlib
import json
import math
import threading
import time

NATIVE_TOOLS = frozenset({
    'github_context', 'github_search', 'github_read', 'github_pr', 'github_ci',
    'github_watch_checks', 'github_stage_patch', 'github_commit', 'github_write', 'github_release', 'github_issue',
})


def native_github_request(payload):
    """Use explicit client declarations, never keywords in untrusted repository text."""
    return any(
        isinstance(tool, dict) and isinstance(tool.get('function'), dict)
        and str(tool['function'].get('name', '')).split('__', 1)[0] in NATIVE_TOOLS
        for tool in payload.get('tools', []) or []
    )


class GitHubPerformance:
    def __init__(self):
        self.lock = threading.RLock()
        self.counts = collections.Counter()
        self.latencies = collections.deque(maxlen=256)
        self.operations = collections.Counter()

    def record(self, elapsed_ms, status, result):
        with self.lock:
            self.counts['active'] -= 1
            self.counts['successes' if status == 200 else 'failures'] += 1
            self.latencies.append(elapsed_ms)
            if isinstance(result, dict):
                usage = result.get('usage') or {}
                known = all(isinstance(usage.get(k), int) and not isinstance(usage[k], bool) and usage[k] >= 0
                            for k in ('prompt_tokens', 'completion_tokens'))
                if known:
                    self.counts['usageSamples'] += 1
                    for key in ('prompt_tokens', 'completion_tokens'):
                        self.counts[key] += usage[key]
                for choice in result.get('choices', []) or []:
                    for call in (choice.get('message') or {}).get('tool_calls', []) or []:
                        operation = str((call.get('function') or {}).get('name', '')).split('__', 1)[0]
                        if operation in NATIVE_TOOLS:
                            self.operations[operation] += 1

    def snapshot(self):
        with self.lock:
            samples = sorted(self.latencies)
            percentile = lambda p: round(samples[max(0, math.ceil(len(samples) * p) - 1)], 2) if samples else None
            counts = dict(self.counts)
            completed = counts.get('successes', 0) + counts.get('failures', 0)
            return {'scope': 'process-local admitted GitHub model rounds; excludes client tool execution and queue wait',
                    'counts': counts, 'returnedToolCalls': dict(self.operations),
                    'successRate': counts.get('successes', 0) / completed if completed else None,
                    'latencySamples': len(samples), 'p50Ms': percentile(.5), 'p95Ms': percentile(.95),
                    'usageComplete': counts.get('usageSamples', 0) == completed if completed else False}


def install_github_fast_path(runtime):
    metrics = GitHubPerformance()
    runtime.gateway_github_performance = metrics
    original = runtime.process_chat_payload

    def process(payload, cancel_event=None, progress_callback=None, hard_cancel_event=None):
        if not native_github_request(payload):
            return original(payload, cancel_event, progress_callback, hard_cancel_event)
        incoming = dict(payload)
        policy = dict(incoming.get('_gateway_performance') or {})
        # The app supplied these tools and owns their approval/credential boundary.
        # One model round returns every tool call to it, without MCP discovery.
        policy['github_client_owned'] = True
        incoming['_gateway_performance'] = policy
        with metrics.lock:
            metrics.counts['requests'] += 1
            metrics.counts['active'] += 1
            metrics.counts['catalogDiscoverySkipped'] += 1
        started = time.monotonic()
        status, result = 500, None
        try:
            status, result = original(incoming, cancel_event, progress_callback, hard_cancel_event)
            if isinstance(result, dict):
                result = dict(result, github_evidence_policy={'schema': 'github.evidence.v1',
                    'publication': 'private_draft', 'verification_owner': 'client',
                    'reason': 'Native tool receipts are owned by Android. A model round cannot certify them.'})
            return status, result
        finally:
            metrics.record((time.monotonic() - started) * 1000, status, result)

    runtime.process_chat_payload = process


class ImmutableGitHubReads:
    """Singleflight and LRU only after schema/auth admission and immutable classification.

    No retries, mutable observation caching, or write coalescing. A follower's
    cancellation does not cancel the producer. All waiters have a bounded wait.
    """
    def __init__(self, max_bytes=8 * 1024 * 1024, max_entries=64, max_flights=16, wait_seconds=30):
        self.lock = threading.RLock()
        self.cache = collections.OrderedDict()
        self.flights = {}
        self.bytes = 0
        self.max_bytes, self.max_entries, self.max_flights = max_bytes, max_entries, max_flights
        self.wait_seconds = wait_seconds
        self.metrics = collections.Counter()

    @staticmethod
    def key(principal, binding, arguments):
        return hashlib.sha256(json.dumps([principal, binding, arguments], sort_keys=True, separators=(',', ':')).encode()).hexdigest()

    def execute(self, key, invoke, cancel_event=None):
        if cancel_event is not None and cancel_event.is_set():
            raise InterruptedError('GitHub read canceled before dispatch')
        with self.lock:
            cached = self.cache.get(key)
            if cached is not None:
                self.cache.move_to_end(key)
                self.metrics['cacheHits'] += 1
                return json.loads(cached)
            flight = self.flights.get(key)
            producer = flight is None
            if producer:
                if len(self.flights) >= self.max_flights:
                    self.metrics['capacityRejected'] += 1
                    raise TimeoutError('GitHub read capacity occupied; no request was dispatched')
                flight = {'event': threading.Event()}
                self.flights[key] = flight
                self.metrics['misses'] += 1
            else:
                self.metrics['joinedReads'] += 1
        if not producer:
            deadline = time.monotonic() + self.wait_seconds
            while not flight['event'].wait(min(.05, max(0, deadline - time.monotonic()))):
                if cancel_event is not None and cancel_event.is_set():
                    raise InterruptedError('GitHub read waiter canceled')
                if time.monotonic() >= deadline:
                    raise TimeoutError('GitHub shared read wait expired; producer remains owned')
            if cancel_event is not None and cancel_event.is_set():
                raise InterruptedError('GitHub read waiter canceled')
            if flight.get('failed'):
                raise RuntimeError('Shared GitHub read failed; no automatic replay was attempted')
            return json.loads(flight['result'])
        try:
            result = invoke()
            # Bound the retained representation; never cache an MCP error.
            raw = json.dumps(result, ensure_ascii=False, separators=(',', ':')).encode()
            with self.lock:
                if isinstance(result, dict) and not result.get('isError') and len(raw) <= self.max_bytes:
                    self.cache[key] = raw
                    self.bytes += len(raw)
                    while self.bytes > self.max_bytes or len(self.cache) > self.max_entries:
                        _, evicted = self.cache.popitem(last=False)
                        self.bytes -= len(evicted)
                        self.metrics['evictions'] += 1
                if len(raw) <= self.max_bytes:
                    flight['result'] = raw
                else:
                    flight['failed'] = True
                    self.metrics['oversizeResults'] += 1
            return result
        except BaseException:
            with self.lock:
                flight['failed'] = True
                self.metrics['failures'] += 1
            raise
        finally:
            with self.lock:
                self.flights.pop(key, None)
                flight['event'].set()

    def snapshot(self):
        with self.lock:
            return {**self.metrics, 'entries': len(self.cache), 'bytes': self.bytes,
                    'activeReads': len(self.flights), 'maxBytes': self.max_bytes,
                    'maxConcurrentReads': self.max_flights, 'automaticRetries': 0}
