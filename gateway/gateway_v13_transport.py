"""Cancellable streamed backend transport used by the v13 model-slot gate."""
import copy
import json
import socket
import threading
import time

import requests
from gateway_v13_runtime import GatewayContractError, consume_sse, validate_completion_tools


def post_chat(session, url, payload, *, connect_timeout, idle_timeout,
              deadline_seconds, cancelled, progress, terminal_grace_seconds=1.0):
    request = copy.deepcopy(payload)
    request["stream"] = True
    request["stream_options"] = {"include_usage": True}
    started = time.monotonic()
    finished = threading.Event()
    expired = threading.Event()
    stopped = threading.Event()
    terminal_drained = threading.Event()
    observed_state = {"state": None, "finished_at": None}
    response = session.post(url, json=request, stream=True,
                            timeout=(connect_timeout, min(idle_timeout, deadline_seconds)))

    def interrupt_response():
        # HTTPResponse.close alone can wait for a blocking reader's lock. Interrupt
        # the underlying socket first when urllib3 exposes one; other transports
        # still receive close and retain the normal finite socket timeout.
        raw = getattr(response, "raw", None)
        fp = getattr(getattr(raw, "_fp", None), "fp", None)
        sock = getattr(getattr(fp, "raw", None), "_sock", None)
        try:
            if isinstance(sock, socket.socket):
                sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        response.close()

    def watch():
        while not finished.wait(0.1):
            if cancelled():
                stopped.set()
            elif time.monotonic() - started >= deadline_seconds:
                expired.set()
            elif observed_state["finished_at"] is not None and time.monotonic() - observed_state["finished_at"] >= terminal_grace_seconds:
                terminal_drained.set()
            else:
                continue
            interrupt_response()
            return

    watcher = threading.Thread(target=watch, name="gateway-v13-model-watchdog", daemon=True)
    watcher.start()
    last_progress = 0.0
    last_size = -1

    def observed(state):
        nonlocal last_progress, last_size
        now = time.monotonic()
        observed_state["state"] = state
        if state.finish is not None and observed_state["finished_at"] is None:
            observed_state["finished_at"] = now
        size = state.observed_characters + len(state.calls)
        if state.received and size != last_size and (last_progress == 0 or now - last_progress >= 0.25):
            progress({"phase": "model_output", "event": "model_output_progress", "status": "running",
                      "stage": "generating", "message": "Receiving model output",
                      "elapsed_seconds": now - started, "output_characters": state.visible_characters,
                      "tool_calls_pending": len(state.calls), "observed_output": True})
            last_progress, last_size = now, size

    try:
        if response.status_code != 200:
            # Preserve an upstream HTTP error for the existing compatibility fallback.
            response.content
            return response
        content_type = response.headers.get("content-type", "").lower()
        if "text/event-stream" in content_type:
            try:
                data = consume_sse(response.iter_lines(chunk_size=256), observed,
                                   lambda: cancelled() or stopped.is_set() or expired.is_set())
            except Exception as exc:
                state = observed_state["state"]
                recoverable_close = not isinstance(exc, GatewayContractError) or exc.code == "incomplete_stream"
                if not terminal_drained.is_set() or state is None or state.finish is None or not recoverable_close:
                    raise
                data = state.result()
        else:
            data = response.json()
        if stopped.is_set() or cancelled():
            raise InterruptedError("Model request cancelled.")
        if expired.is_set():
            raise GatewayContractError("Model exceeded its configured wall-clock deadline.", "model_deadline_exceeded", 504)
        validate_completion_tools(data, payload)
        result = requests.Response()
        result.status_code = 200
        result.headers["content-type"] = "application/json"
        result.encoding = "utf-8"
        result._content = json.dumps(data, ensure_ascii=False).encode("utf-8")
        return result
    except Exception as exc:
        if stopped.is_set() or cancelled():
            raise InterruptedError("Model request cancelled.") from exc
        if expired.is_set():
            raise GatewayContractError("Model exceeded its configured wall-clock deadline.", "model_deadline_exceeded", 504) from exc
        # Never replay a stream after any backend response: tool fragments may have
        # arrived, and retrying could spend another full generation invisibly.
        if isinstance(exc, requests.RequestException):
            raise GatewayContractError("Backend stream interrupted; no partial tool call was executed.", "upstream_stream_interrupted", 502) from exc
        raise
    finally:
        finished.set()
        response.close()
