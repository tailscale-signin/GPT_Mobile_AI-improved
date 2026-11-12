"""Cancellable, bounded backend transport used by the v13 model-slot gate."""
from __future__ import annotations

import copy
import hashlib
import json
import threading
import time

import requests

from gateway_v13_runtime import (GatewayContractError, backend_template_error, consume_sse, normalize_backend_messages,
                                validate_completion_tools, validate_tool_message_pairs, visible_answer_text)
from v14.inference_execution import before_physical_dispatch, observe_physical_response, selected_dispatch_headers
from v14.debug import diagnostic_trace_headers


def _nonnegative_int(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def backend_template_failure(response):
    """A deterministic template rejection is not a retryable HTTP 500 outage.

    Keep the original status/type/code and an evidence digest, not the backend's
    raw source excerpt, prompt or Jinja traceback in public failure metadata.
    """
    if response.status_code < 400:
        return None
    try:
        body = response.json()
    except (TypeError, ValueError):
        return None
    return backend_template_error(body.get("error", body) if isinstance(body, dict) else None, response.status_code)


def _observed_usage(data):
    usage = data.get("usage") if isinstance(data, dict) else None
    return {key: value for key, value in (usage or {}).items()
            if key in {"prompt_tokens", "completion_tokens", "total_tokens"} and _nonnegative_int(value)} if isinstance(usage, dict) else {}


ATTEMPT_ERROR_CODES = frozenset({
    "backend_deadline_exceeded", "backend_timeout", "backend_stream_interrupted", "backend_http_error",
    "backend_template_invalid", "upstream_stream_error", "incomplete_stream", "malformed_stream",
    "malformed_completion", "multiple_choices", "invalid_tool_call", "invalid_tool_arguments",
    "incomplete_tool_call", "unauthorized_tool_call", "empty_completion", "missing_required_tool_call",
    "mixed_tool_batch", "completion_repair_budget_unavailable", "completion_repair_usage_unavailable",
    "completion_repair_budget_exhausted", "context_budget_exceeded", "context_capacity_unverified",
})


def _output_allowance(payload):
    caps = [payload.get(key) for key in ("max_tokens", "max_completion_tokens")]
    return min((cap for cap in caps if _nonnegative_int(cap) and cap > 0), default=None)


def _completion_observation(receipt, data, *, completed):
    """Count observed output without retaining text or treating a missing body as empty."""
    choices = data.get("choices") if isinstance(data, dict) else None
    choice = choices[0] if isinstance(choices, list) and len(choices) == 1 and isinstance(choices[0], dict) else None
    message = choice.get("message") if choice is not None else None
    if not isinstance(message, dict):
        return
    finish = choice.get("finish_reason")
    receipt["finish_reason"] = ({"stop": "STOP", "end_turn": "STOP", "tool_calls": "TOOL_CALLS",
                                "tool_use": "TOOL_CALLS", "function_call": "TOOL_CALLS", "length": "LENGTH",
                                "max_tokens": "LENGTH", "output_limit": "LENGTH", "content_filter": "CONTENT_FILTER"}
                               .get(finish, "OTHER" if finish else "UNKNOWN") if isinstance(finish, str) else "UNKNOWN")
    def count(text):
        try:
            return len(text.encode("utf-8"))
        except UnicodeEncodeError:
            return None
    if message.get("content") is None or isinstance(message.get("content"), str):
        receipt["visible_output_bytes"] = count(visible_answer_text(message, preserve_whitespace=True))
    reasoning = [message.get(key) for key in ("reasoning_content", "reasoning")]
    if all(value is None or isinstance(value, str) for value in reasoning):
        receipt["reasoning_output_bytes"] = count("".join(value or "" for value in reasoning))
    if completed:
        receipt["status"] = "COMPLETED"


def _attempt_error(receipts, code):
    if receipts:
        receipts[-1]["terminal_error_code"] = code if code in ATTEMPT_ERROR_CODES else None


def _attempt_evidence(receipts):
    # This transport admits no more than two physical requests. These are backend
    # observations, separate from bytes delivered to the Android application.
    return {"schema_version": 1, "attempt_receipts": copy.deepcopy(list(receipts))}


def _failed_repair_response(response, payload, first, receipts):
    # Preserve HTTP/rate-limit semantics, never an arbitrary rejected backend
    # body. A failed second request cannot erase the first request's known work.
    recovery = _repair_failure_details(payload, [first], receipts)["gateway_recovery"]
    recovery["completed_attempts"] = recovery.pop("attempts")
    recovery.update(failed_attempt=len(receipts), failed_attempt_usage_available=False)
    result = requests.Response()
    result.status_code = response.status_code
    result.headers.update(response.headers)
    for name in ("content-length", "content-encoding", "transfer-encoding"):
        result.headers.pop(name, None)
    result.headers["content-type"] = "application/json"
    result.encoding = "utf-8"
    result._content = json.dumps({"error": {
        "message": "The backend rejected the bounded completion repair. Earlier attempt evidence is retained.",
        "code": str(response.status_code), "type": "gateway_backend_http_error",
        "failed_stage": "provider_generation", "backend_status": response.status_code,
        "gateway_recovery": recovery,
    }}).encode("utf-8")
    result.headers["content-length"] = str(len(result._content))
    return result


def _repair_failure_details(payload, attempts, receipts=()):
    caps = [payload.get(key) for key in ("max_tokens", "max_completion_tokens")]
    caps = [cap for cap in caps if _nonnegative_int(cap) and cap > 0]
    return {"gateway_recovery": {"attempts": len(attempts), "output_allowance": min(caps) if caps else None,
                                  "attempt_usage": [_observed_usage(data) for data in attempts],
                                  "attempt_backend_tokens": [{key: value for key, value in (data.get("timings") or {}).items()
                                                              if key in {"prompt_n", "predicted_n"} and _nonnegative_int(value)}
                                                             if isinstance(data.get("timings"), dict) else {} for data in attempts],
                                  "usage_origin": "PROVIDER_REPORTED", "usage_estimated": False,
                                  **_attempt_evidence(receipts)}}


def _repair_output_cap(payload, data):
    caps = [payload.get(key) for key in ("max_tokens", "max_completion_tokens")]
    caps = [cap for cap in caps if _nonnegative_int(cap) and cap > 0]
    if not caps:
        raise GatewayContractError(
            "No admitted output allowance is available for a budget-safe repair. No additional request was sent.",
            "completion_repair_budget_unavailable", 502,
        )
    usage = data.get("usage") or {}
    timings = data.get("timings") or {}
    used = usage.get("completion_tokens") if isinstance(usage, dict) else None
    if not _nonnegative_int(used) and isinstance(timings, dict):
        used = timings.get("predicted_n")
    if not _nonnegative_int(used):
        raise GatewayContractError(
            "Backend returned no usable completion and omitted usage needed for a budget-safe repair.",
            "completion_repair_usage_unavailable", 502,
        )
    remaining = min(caps) - used
    if remaining <= 0:
        raise GatewayContractError(
            "Backend exhausted the output allowance without a usable completion. No unbudgeted retry was started.",
            "completion_repair_budget_exhausted", 502,
        )
    return min(1024, remaining)


def _sum_usage(left, right):
    """Only report totals known for BOTH attempts, never partial totals as exact."""
    if not isinstance(left, dict) or not isinstance(right, dict):
        return {}
    result = {}
    for key in left.keys() & right.keys():
        if _nonnegative_int(left[key]) and _nonnegative_int(right[key]):
            result[key] = left[key] + right[key]
        elif isinstance(left[key], dict) and isinstance(right[key], dict):
            nested = _sum_usage(left[key], right[key])
            if nested:
                result[key] = nested
    if "prompt_tokens" in result and "completion_tokens" in result:
        result["total_tokens"] = result["prompt_tokens"] + result["completion_tokens"]
    else:
        result.pop("total_tokens", None)
    return result


def _combined_completion(first, final, reason, receipts=()):
    result = copy.deepcopy(final)
    result.pop("usage", None)
    usage = _sum_usage(first.get("usage"), final.get("usage"))
    if usage:
        result["usage"] = usage
    result.pop("timings", None)
    a, b = first.get("timings"), final.get("timings")
    if isinstance(a, dict) and isinstance(b, dict):
        timings = {}
        for key in ("prompt_n", "predicted_n", "prompt_ms", "predicted_ms"):
            if all(isinstance(item.get(key), (int, float)) and not isinstance(item.get(key), bool) and item[key] >= 0 for item in (a, b)):
                timings[key] = a[key] + b[key]
        for phase in ("prompt", "predicted"):
            count, milliseconds = timings.get(phase + "_n"), timings.get(phase + "_ms")
            if count and milliseconds:
                timings[phase + "_per_second"] = 1000 * count / milliseconds
                timings[phase + "_per_token_ms"] = milliseconds / count
        if timings:
            result["timings"] = timings
    result["gateway_recovery"] = {"attempts": 2, "reason": reason, "usage_complete": "total_tokens" in usage,
                                  **_attempt_evidence(receipts)}
    if isinstance(result.get("gateway_timing"), dict):
        result["gateway_timing"]["backend_attempts"] = [copy.deepcopy(data.get("gateway_timing", {})) for data in (first, final)]
    return result


def _completion_issue(data, payload):
    message = data["choices"][0]["message"]
    choice = payload.get("tool_choice")
    if not message.get("tool_calls") and (isinstance(choice, dict) or choice == "required"):
        return "missing_required_tool_call"
    if len(message.get("tool_calls") or []) > 1:
        return "mixed_tool_batch"
    if not message.get("tool_calls") and not visible_answer_text(message):
        return "empty_completion"
    return None


def post_chat(
    session,
    url,
    payload,
    *,
    connect_timeout,
    idle_timeout,
    deadline_seconds,
    cancelled,
    progress,
    finish_grace_seconds=2.0,
    terminal_grace_seconds=None,
    headers=None,
    prepare_repair=None,
    visible_output=None,
):
    """One model round, at most one repair, and one shared deadline/output allowance.

    Sequential model tool calls prevent gateway/client ownership mixtures. A single
    composite web_search call can still fan out to multiple authorized engines.
    This layer never executes tools or replays tool side effects.
    """
    if terminal_grace_seconds is not None:
        finish_grace_seconds = terminal_grace_seconds
    if deadline_seconds <= 0 or finish_grace_seconds <= 0:
        raise ValueError("Transport deadlines must be positive")
    deadline_at = time.monotonic() + deadline_seconds
    logical_started = time.monotonic()
    request = copy.deepcopy(payload)
    for key in ("stream_options", "chat_template_kwargs"):
        if request.get(key) is not None and not isinstance(request[key], dict):
            raise GatewayContractError(f"{key} must be an object.")
    request["stream"] = True
    request["stream_options"] = {**(request.get("stream_options") or {}), "include_usage": True}
    if request.get("tools"):
        request["parallel_tool_calls"] = False
    first, repair_reason = None, None
    attempt_receipts = []
    # Only an internal final-answer scope opts in. A round with tool authority
    # or structured output must be validated before any answer is published.
    if request.get('tools') or request.get('tool_choice') not in (None, 'none') or (request.get('response_format') or {}).get('type', 'text') != 'text':
        visible_output = None
    for attempt in range(2):
        data = None
        attempted_before = len(attempt_receipts)
        try:
            response, data = _post_once(
                session, url, request, connect_timeout, idle_timeout, deadline_at,
                cancelled, progress, finish_grace_seconds, headers, visible_output, attempt_receipts,
            )
            if response is not None:
                return response if first is None else _failed_repair_response(response, payload, first, attempt_receipts)
            validate_completion_tools(data, request)
        except GatewayContractError as error:
            if len(attempt_receipts) > attempted_before:
                _attempt_error(attempt_receipts, error.code)
            if first is not None:
                # A failed repair must not erase consumption already reported by
                # the first completion or invent usage for the failed attempt.
                completed = [first] + ([data] if data is not None else [])
                recovery = _repair_failure_details(payload, completed, attempt_receipts)["gateway_recovery"]
                recovery["completed_attempts"] = recovery.pop("attempts")
                recovery.update(failed_attempt=attempt + 1, failed_attempt_usage_available=data is not None)
                error.details.setdefault("gateway_recovery", recovery)
            else:
                error.details.setdefault("gateway_recovery", _repair_failure_details(payload, [data] if data is not None else [], attempt_receipts)["gateway_recovery"])
            error.details["gateway_recovery"].update(_attempt_evidence(attempt_receipts))
            raise
        issue = _completion_issue(data, request)
        if issue is None:
            if first is not None:
                data = _combined_completion(first, data, repair_reason, attempt_receipts)
            else:
                data["gateway_recovery"] = {"attempts": 1, **_attempt_evidence(attempt_receipts)}
            data["gateway_timing"]["logical_request_elapsed_ms"] = round((time.monotonic() - logical_started) * 1000, 3)
            result = requests.Response()
            result.status_code = 200
            result.headers["content-type"] = "application/json"
            result.encoding = "utf-8"
            result._content = json.dumps(data, ensure_ascii=False).encode("utf-8")
            return result
        if attempt:
            message = {
                "empty_completion": "Backend did not return a visible answer after one bounded repair.",
                "missing_required_tool_call": "Backend omitted the required tool call after one bounded repair. No tool was executed.",
                "mixed_tool_batch": "Backend ignored sequential tool calling; no tool from this batch was executed.",
            }[issue]
            _attempt_error(attempt_receipts, issue)
            raise GatewayContractError(message, issue, 502, details=_repair_failure_details(payload, [first, data], attempt_receipts))
        _attempt_error(attempt_receipts, issue)
        try:
            cap = _repair_output_cap(payload, data)
        except GatewayContractError as error:
            _attempt_error(attempt_receipts, error.code)
            error.details.update(_repair_failure_details(payload, [data], attempt_receipts))
            raise
        first, repair_reason = data, issue
        # Preserve the original goal and completed tool evidence. Never inject the
        # discarded tool batch or hidden reasoning into conversation history.
        instruction = "Return exactly one tool call this round. Continue other tools in later rounds; never mix execution owners."
        if issue == "missing_required_tool_call":
            instruction = "Return exactly one call satisfying the supplied tool_choice and tool schema. A text-only answer cannot complete this round."
        if issue == "empty_completion":
            instruction = "Return a concise visible answer using available evidence. State missing evidence honestly. Do not output hidden reasoning or request more tools."
            request.pop("tools", None)
            request["tool_choice"] = "none"
            controls = request.get("chat_template_kwargs") or {}
            explicit_on = any(controls.get(name) is True for name in ("enable_thinking", "thinking")) or request.get("reasoning_effort") not in (None, "auto", "none")
            if not explicit_on:
                control = "thinking" if "thinking" in controls else "enable_thinking"
                request["chat_template_kwargs"] = {**controls, control: False}
                request["reasoning_effort"] = "none"
        request["messages"] = normalize_backend_messages(payload.get("messages", []), instruction=instruction)
        request["parallel_tool_calls"] = False
        request["max_tokens"] = cap
        if "max_completion_tokens" in request:
            request["max_completion_tokens"] = cap
        if prepare_repair is not None:
            try:
                prepare_repair(request)
            except GatewayContractError as error:
                error.details.update(_repair_failure_details(payload, [data], attempt_receipts))
                raise
        progress({"phase": "model_recovery", "event": "model_recovery", "status": "running", "stage": "recovering", "message": "Preparing one bounded completion repair", "reason": issue, "attempt": 2, "remaining_output_cap": cap})
    raise AssertionError("Unreachable completion state")


def _post_once(session, url, request, connect_timeout, idle_timeout, deadline_at, cancelled, progress, finish_grace_seconds, headers=None, visible_output=None, attempt_receipts=None):
    if cancelled():
        raise InterruptedError("Model request cancelled before sending.")
    remaining = deadline_at - time.monotonic()
    if remaining <= 0:
        raise GatewayContractError("Backend model deadline exceeded.", "backend_deadline_exceeded", 504)
    connect = min(max(0.001, connect_timeout), remaining / 2)
    idle = min(max(0.001, idle_timeout), remaining - connect)
    started = time.monotonic()
    finished = threading.Event()
    expired = threading.Event()
    stopped = threading.Event()
    tail_finished = threading.Event()
    response, watcher, last_state = None, None, None
    terminal_seen_at = None
    last_progress, last_size = 0.0, -1
    backend_response_headers_ms = None
    backend_first_event_ms = None
    backend_first_output_ms = None
    backend_first_visible_output_ms = None
    backend_first_reasoning_output_ms = None
    attempt_receipt = None

    def watch():
        while not finished.wait(0.05):
            if cancelled():
                stopped.set()
            elif time.monotonic() >= deadline_at:
                expired.set()
            elif terminal_seen_at is not None and time.monotonic() - terminal_seen_at >= finish_grace_seconds:
                tail_finished.set()
            else:
                continue
            response.close()
            return

    def observed(state):
        nonlocal last_progress, last_size, last_state, terminal_seen_at, backend_first_output_ms
        nonlocal backend_first_visible_output_ms, backend_first_reasoning_output_ms, backend_first_event_ms
        last_state = state
        now = time.monotonic()
        if backend_first_event_ms is None:
            backend_first_event_ms = round((now - started) * 1000, 3)
        if state.finish is not None and terminal_seen_at is None:
            terminal_seen_at = now
        size = state.generated_characters + len(state.calls)
        if size > 0 and backend_first_output_ms is None:
            backend_first_output_ms = round((now - started) * 1000, 3)
            progress({"phase": "model_output", "event": "backend_first_output", "status": "running", "stage": "generating",
                      "message": "Backend generation started; response is being prepared",
                      "backend_first_output_ms": backend_first_output_ms, "user_visible": False})
        if state.reasoning_characters > 0 and backend_first_reasoning_output_ms is None:
            backend_first_reasoning_output_ms = round((now - started) * 1000, 3)
        if state.visible_characters > 0 and backend_first_visible_output_ms is None:
            backend_first_visible_output_ms = round((now - started) * 1000, 3)
            progress({"phase": "model_output", "event": "backend_first_visible_output", "status": "running", "stage": "generating",
                      "message": "Backend answer text received; completion validation is pending",
                      "backend_first_visible_output_ms": backend_first_visible_output_ms, "user_visible": False})
        if visible_output is not None:
            if state.calls:
                raise GatewayContractError('The backend returned a tool in a text-only final round. No tool was executed.',
                                           'unauthorized_tool_call', 502)
            visible_output(state.message.get('content') or '')
        if state.received and size != last_size and (last_progress == 0.0 or now - last_progress >= 0.25):
            progress({"phase": "model_output", "event": "model_output_progress", "status": "running", "stage": "generating", "message": "Receiving model output", "elapsed_seconds": round(now - started, 3), "output_characters": state.visible_characters, "reasoning_characters": state.reasoning_characters, "tool_calls_pending": len(state.calls), "observed_output": True})
            last_progress, last_size = now, size

    try:
        if "messages" in request:
            request["messages"] = normalize_backend_messages(request["messages"])
            validate_tool_message_pairs(request["messages"])
        before_physical_dispatch(url, request)
        dispatch_headers = {**(headers or {}), **diagnostic_trace_headers(), **selected_dispatch_headers()}
        started = time.monotonic()
        if attempt_receipts is not None:
            attempt_receipt = {"attempt": len(attempt_receipts) + 1, "output_allowance": _output_allowance(request),
                               "finish_reason": "UNKNOWN", "visible_output_bytes": None, "reasoning_output_bytes": None,
                               "terminal_error_code": None, "terminal_status_code": None,
                               "backend_response_observed": False, "status": "UNKNOWN"}
            attempt_receipts.append(attempt_receipt)
        response = session.post(url, json=request, stream=True, timeout=(connect, idle), headers=dispatch_headers, allow_redirects=False)
        if attempt_receipt is not None:
            attempt_receipt["backend_response_observed"] = True
            status = response.status_code
            attempt_receipt["terminal_status_code"] = status if type(status) is int and 100 <= status <= 599 else None
        backend_response_headers_ms = round((time.monotonic() - started) * 1000, 3)
        watcher = threading.Thread(target=watch, daemon=True, name="gateway-backend-deadline")
        watcher.start()
        if response.status_code != 200:
            if attempt_receipt is not None:
                attempt_receipt.update(status="FAILED", terminal_error_code="backend_http_error")
            response.content
            if cancelled():
                raise InterruptedError("Model request cancelled.")
            if time.monotonic() >= deadline_at:
                raise GatewayContractError("Backend model deadline exceeded.", "backend_deadline_exceeded", 504)
            rejection = backend_template_failure(response)
            if rejection is not None:
                raise rejection
            return response, None
        if "text/event-stream" in response.headers.get("content-type", "").lower():
            try:
                data = consume_sse(response.iter_lines(chunk_size=256), observed, lambda: cancelled() or stopped.is_set() or expired.is_set())
            except (requests.RequestException, OSError):
                # A complete finish marker is sufficient. Give a trailing usage
                # chunk a short grace period, not an unbounded wait for socket EOF.
                if not tail_finished.is_set() or last_state is None or last_state.finish is None:
                    raise
                data = last_state.result()
        else:
            data = response.json()
        if attempt_receipt is not None:
            _completion_observation(attempt_receipt, data, completed=True)
        if stopped.is_set() or cancelled():
            raise InterruptedError("Model request cancelled.")
        if expired.is_set() or time.monotonic() >= deadline_at:
            raise GatewayContractError("Backend model deadline exceeded.", "backend_deadline_exceeded", 504)
        data["gateway_timing"] = {"measurement_scope": "physical_backend_request",
                                  "delivery_mode": "incremental_final_answer" if visible_output is not None else "completion_buffered",
                                  "backend_response_headers_ms": backend_response_headers_ms,
                                  "backend_first_event_ms": backend_first_event_ms,
                                  "backend_first_output_ms": backend_first_output_ms,
                                  "backend_first_visible_output_ms": backend_first_visible_output_ms,
                                  "backend_first_reasoning_output_ms": backend_first_reasoning_output_ms,
                                  "backend_completion_ms": round((time.monotonic() - started) * 1000, 3),
                                  "first_user_visible_text_ms": None}
        reported = data.get("model")
        reported = reported if isinstance(reported, str) and 0 < len(reported) <= 512 and all(ord(c) >= 32 for c in reported) else None
        serialized_controls = {}
        for container, key in (("reasoning", "enabled"), ("chat_template_kwargs", "enable_thinking"), ("chat_template_kwargs", "thinking")):
            values = request.get(container)
            value = values.get(key) if isinstance(values, dict) else None
            if isinstance(value, bool):
                serialized_controls[f"{container}.{key}"] = value
        effort = request.get("reasoning_effort")
        if isinstance(effort, str) and effort in {"none", "minimal", "low", "medium", "high", "xhigh"}:
            serialized_controls["reasoning_effort"] = effort
        data["gateway_provenance"] = {"schema_version": 1, "origin": "BACKEND_RESPONSE",
                                      "backend_endpoint_digest": hashlib.sha256(url.encode("utf-8")).hexdigest(),
                                      "serialized_controls": serialized_controls, "effective_controls": None,
                                      "runtime_identity": None, "effective_settings_source": "NOT_MEASURED",
                                      "requested_model": request.get("model"), "reported_model": reported,
                                      "model_identity": "UNAVAILABLE" if reported is None else "MATCHED" if reported == request.get("model") else "DIFFERENT",
                                      "http_status": response.status_code, "accelerator": None}
        observe_physical_response(data, response)
        return None, data
    except Exception as exc:
        if attempt_receipt is not None and last_state is not None:
            _completion_observation(attempt_receipt, {"choices": [{"message": last_state.message, "finish_reason": last_state.finish}]}, completed=False)
        if attempt_receipt is not None and isinstance(exc, GatewayContractError) and exc.code in {"backend_template_invalid", "upstream_stream_error"}:
            attempt_receipt["status"] = "FAILED"
        partial = ({'partial_output_may_have_been_published': True, 'automatic_replay': False, 'retryable': False}
                   if visible_output is not None and last_state is not None and last_state.visible_characters else {})
        if stopped.is_set() or cancelled():
            raise InterruptedError("Model request cancelled.") from exc
        if expired.is_set() or time.monotonic() >= deadline_at:
            raise GatewayContractError("Backend model deadline exceeded.", "backend_deadline_exceeded", 504, details=partial) from exc
        if isinstance(exc, requests.Timeout):
            raise GatewayContractError("Backend timed out before completing the response.", "backend_timeout", 504, details=partial) from exc
        if isinstance(exc, requests.RequestException):
            raise GatewayContractError("Backend connection or stream failed; no partial tool call was executed.", "backend_stream_interrupted", 502, details=partial) from exc
        if isinstance(exc, GatewayContractError):
            exc.details.update(partial)
        if isinstance(exc, GatewayContractError) and exc.details.get("origin") == "backend" and response is not None:
            exc.details.setdefault("backend_status", response.status_code)
        raise
    finally:
        finished.set()
        if response is not None:
            response.close()
        if watcher is not None:
            watcher.join(timeout=0.2)
