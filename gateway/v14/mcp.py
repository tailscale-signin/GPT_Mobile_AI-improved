"""Supervised legacy MCP stdio transport; no speculative protocol fallback."""
import json
import queue
import threading
import time
import uuid

LEGACY_VERSIONS = frozenset({'2024-11-05', '2025-03-26', '2025-06-18', '2025-11-25'})


class CircuitOpen(RuntimeError):
    pass


class Circuit:
    def __init__(self, threshold=3, cooldown=30, clock=time.monotonic):
        self.threshold, self.cooldown, self.clock = threshold, cooldown, clock
        self.lock = threading.Lock()
        self.failures, self.until, self.probing = 0, 0, False

    def enter(self):
        with self.lock:
            if self.failures >= self.threshold:
                if self.clock() < self.until or self.probing:
                    raise CircuitOpen('MCP connection cooling down after repeated transport failures')
                self.probing = True

    def finish(self, failed):
        with self.lock:
            self.probing = False
            self.failures = self.failures + 1 if failed else 0
            if self.failures >= self.threshold:
                self.until = self.clock() + self.cooldown
            elif not failed:
                self.until = 0

    def snapshot(self):
        with self.lock:
            return {'state': 'half-open' if self.probing else 'open' if self.failures >= self.threshold else 'closed',
                    'consecutiveFailures': self.failures, 'retryAfterSeconds': max(0, round(self.until - self.clock(), 1))}


def install_mcp_resilience(runtime):
    base = runtime.PersistentMCPStdioSession

    class SupervisedSession(base):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self.circuit = Circuit()
            self.deadline = None
            self.queue_lock = threading.Lock()
            self.queued_bytes = 0

        def _spawn(self):
            with self.queue_lock:
                self.queued_bytes = 0
            super()._spawn()

        def _reader_loop(self):
            proc, output = self.process, self.stdout_queue
            try:
                while proc and proc.stdout:
                    limit = min(runtime.MCP_MAX_STDOUT_LINE_CHARS, 4 * 1024 * 1024)
                    line = proc.stdout.readline(limit + 1)
                    if not line:
                        output.put_nowait({'__transport__': 'eof'})
                        return
                    size = len(line.encode('utf-8'))
                    with self.queue_lock:
                        overflow = self.queued_bytes + size > 8 * 1024 * 1024
                    if len(line) > limit or output.qsize() >= 128 or overflow:
                        # Never block a reader indefinitely or allocate an unbounded queue.
                        output.put_nowait({'__transport__': 'bounded_output_exceeded'})
                        proc.kill()
                        return
                    try:
                        message = json.loads(line)
                    except (ValueError, RecursionError):
                        continue  # Do not log arbitrary tool stdout or secrets.
                    with self.queue_lock:
                        self.queued_bytes += size
                    output.put_nowait((size, message))
            except (OSError, ValueError):
                output.put_nowait({'__transport__': 'reader_failed'})

        def _stderr_loop(self):
            proc = self.process
            try:
                while proc and proc.stderr:
                    # Drain diagnostics in bounded chunks; never allocate an
                    # arbitrary stderr line or relay credentials into gateway logs.
                    if not proc.stderr.readline(4096):
                        return
            except (OSError, ValueError):
                pass

        def _wait_for_response(self, expected_id, inactivity_timeout, absolute_timeout,
                               progress_token=None, progress_callback=None, cancel_event=None):
            end = min(time.monotonic() + absolute_timeout, self.deadline or float('inf'))
            idle = time.monotonic() + inactivity_timeout
            while True:
                if cancel_event is not None and cancel_event.is_set():
                    raise InterruptedError('MCP request cancelled')
                remaining = min(end, idle) - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('MCP response deadline exceeded')
                try:
                    message = self.stdout_queue.get(timeout=min(0.1, remaining))
                except queue.Empty:
                    if not self.is_alive():
                        raise RuntimeError('MCP process exited')
                    continue
                if isinstance(message, tuple):
                    size, message = message
                    with self.queue_lock:
                        self.queued_bytes -= size
                if not isinstance(message, dict):
                    continue
                if '__transport__' in message:
                    raise RuntimeError('MCP transport closed or exceeded its output limit')
                if message.get('jsonrpc') != '2.0':
                    continue
                method = message.get('method')
                if isinstance(method, str):
                    if message.get('id') is not None:
                        # Legacy servers may issue ping; sampling/elicitation are not granted.
                        reply = {'jsonrpc': '2.0', 'id': message['id']}
                        reply.update({'result': {}} if method == 'ping' else {'error': {'code': -32601, 'message': 'Client capability not available'}})
                        self._send_json(reply)
                    else:
                        self._handle_notification(message, progress_token, progress_callback)
                        params = message.get('params', {})
                        if method == 'notifications/progress' and progress_token is not None and isinstance(params, dict) and params.get('progressToken') == progress_token:
                            idle = time.monotonic() + inactivity_timeout
                    continue
                if type(message.get('id')) is type(expected_id) and message['id'] == expected_id:
                    if ('result' in message) == ('error' in message):
                        raise RuntimeError('Malformed MCP response')
                    return message
                # Stale IDs and unrelated activity cannot extend either deadline.

        def _initialize(self):
            requested = self.cfg.get('protocolVersion', runtime.MCP_DEFAULT_PROTOCOL_VERSION)
            if requested not in LEGACY_VERSIONS:
                raise ValueError('Configured MCP revision requires a separate modern adapter')
            request_id = self._next_id()
            self._send_json({'jsonrpc': '2.0', 'id': request_id, 'method': 'initialize', 'params': {
                'protocolVersion': requested, 'capabilities': {},
                'clientInfo': {'name': 'private-gateway', 'version': runtime.GATEWAY_VERSION}}})
            response = self._wait_for_response(request_id, runtime.MCP_STARTUP_TIMEOUT_SECONDS, runtime.MCP_STARTUP_TIMEOUT_SECONDS)
            result = response.get('result', {})
            if not isinstance(result, dict) or result.get('protocolVersion') not in LEGACY_VERSIONS:
                raise ValueError('No supported legacy MCP protocol; modern adapter is not enabled')
            self.protocol_version = result['protocolVersion']
            self.server_info = result.get('serverInfo', {})
            self.server_capabilities = result.get('capabilities', {})
            self._send_json({'jsonrpc': '2.0', 'method': 'notifications/initialized'})
            runtime.logger.info('Initialized supervised MCP session: %s protocol=%s', self.server_name, self.protocol_version)

        def call(self, method, params=None, inactivity_timeout=60, absolute_timeout=180,
                 progress_callback=None, cancel_event=None):
            end = time.monotonic() + max(0.01, min(float(absolute_timeout), 900))
            acquired = False
            while time.monotonic() < end:
                if cancel_event is not None and cancel_event.is_set():
                    raise InterruptedError('MCP cancelled before dispatch')
                if self.call_lock.acquire(timeout=min(0.1, max(0, end - time.monotonic()))):
                    acquired = True
                    break
            if not acquired:
                raise TimeoutError('MCP queue deadline exceeded before dispatch')
            request_id, timer, entered = None, None, False
            try:
                if getattr(runtime, 'gateway_draining', False):
                    raise InterruptedError('Gateway is draining; MCP dispatch stopped')
                self.circuit.enter()
                entered = True
                self.deadline = end
                self.ensure_started()
                if getattr(runtime, 'gateway_draining', False):
                    raise InterruptedError('Gateway stopped during MCP startup')
                if cancel_event is not None and cancel_event.is_set():
                    raise InterruptedError('MCP cancelled before dispatch')
                remaining = end - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('MCP startup consumed request deadline')
                proc = self.process
                def expire():
                    if proc.poll() is None:
                        try:
                            proc.kill()  # Also releases a blocked stdin write.
                        except OSError:
                            pass
                timer = threading.Timer(remaining, expire)
                timer.daemon = True
                timer.start()
                request_id = self._next_id()
                arguments = dict(params or {})
                token = uuid.uuid4().hex if method == 'tools/call' else None
                if token:
                    arguments['_meta'] = dict(arguments.get('_meta') or {}, progressToken=token)
                self.calls += 1
                runtime.increment_mcp_metric('rpc_calls')
                self._send_json({'jsonrpc': '2.0', 'id': request_id, 'method': method, 'params': arguments})
                response = self._wait_for_response(request_id, max(0.01, float(inactivity_timeout)), remaining,
                                                   token, progress_callback, cancel_event)
                self.last_used_monotonic = time.monotonic()
                self.circuit.finish(False)  # A valid RPC error still proves transport health.
                entered = False
                if 'error' in response:
                    raise ValueError('MCP server rejected the RPC: ' + str(response['error'].get('code', 'unknown')))
                return response['result']
            except CircuitOpen:
                raise
            except BaseException as exc:
                if entered:
                    self.circuit.finish(not isinstance(exc, InterruptedError))
                    self.failures += 1
                    runtime.increment_mcp_metric('rpc_failures')
                    if isinstance(exc, TimeoutError):
                        runtime.increment_mcp_metric('rpc_timeouts')
                    if request_id is not None and isinstance(exc, (TimeoutError, InterruptedError)):
                        self._cancel_request_best_effort(request_id, type(exc).__name__)
                    self.close()  # Lazy reconnect on next call; never replay tools/call.
                raise
            finally:
                if timer is not None:
                    timer.cancel()
                    timer.join()
                self.deadline = None
                self.call_lock.release()

        def close(self, terminate=True):
            proc = self.process
            self.process = None
            if proc is None:
                return
            def stop():
                try:
                    if proc.poll() is None:
                        proc.kill()
                except OSError:
                    pass
            # Closing a TextIO pipe may wait on a concurrent write. Bound that
            # wait too, so shutdown cannot sit behind a blocked child forever.
            timer = threading.Timer(0.2, stop)
            timer.daemon = True
            timer.start()
            try:
                if proc.stdin:
                    try:
                        proc.stdin.close()
                    except (OSError, ValueError):
                        pass
                if terminate or proc.poll() is not None:
                    try:
                        proc.wait(timeout=0.3)
                    except Exception:
                        stop()
                        proc.wait(timeout=1)
            finally:
                timer.cancel()
                timer.join()
            for reader in (self.stdout_thread, self.stderr_thread):
                if reader is not None and reader is not threading.current_thread():
                    reader.join(timeout=0.2)
            if proc.poll() is not None:
                for stream in (proc.stdout, proc.stderr):
                    if stream:
                        stream.close()

        def _cancel_request_best_effort(self, request_id, reason):
            proc = self.process
            def expire():
                try:
                    if proc is not None and proc.poll() is None:
                        proc.kill()
                except OSError:
                    pass
            timer = threading.Timer(0.2, expire)
            timer.daemon = True
            timer.start()
            try:
                super()._cancel_request_best_effort(request_id, reason)
            finally:
                timer.cancel()
                timer.join()

        def public_status(self):
            result = super().public_status()
            result['circuit'] = self.circuit.snapshot()
            return result

    runtime.PersistentMCPStdioSession = SupervisedSession
    # One retry owner: higher layers must not guess idempotency from tool names.
    runtime.SAFE_READ_TRANSIENT_RETRY = False
    def call(server_name, method, params=None, progress_callback=None, cancel_event=None):
        session = runtime.get_mcp_session(server_name)
        cfg = session.cfg
        return session.call(method, params, inactivity_timeout=float(cfg.get('timeoutSeconds', runtime.MCP_CALL_INACTIVITY_TIMEOUT_SECONDS)),
                            absolute_timeout=float(cfg.get('absoluteTimeoutSeconds', runtime.MCP_CALL_ABSOLUTE_TIMEOUT_SECONDS)),
                            progress_callback=progress_callback, cancel_event=cancel_event)
    runtime.mcp_call = call
