"""Cancellable, bounded backend transport used by the v13 model-slot gate."""
from __future__ import annotations

import copy
import json
import threading
import time

import requests

from gateway_v13_runtime import GatewayContractError, consume_sse, validate_completion_tools, visible_answer_text


def _nonnegative_int(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _repair_output_cap(payload, data):
    caps = [payload.get(key) for key in ("max_tokens", "max_completion_tokens")]
    caps = [cap for cap in caps if _nonnegative_int(cap) and cap > 0]
    if not caps:
        return 1024
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


def _combined_completion(first, final, reason):
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
    result["gateway_recovery"] = {"attempts": 2, "reason": reason, "usage_complete": "total_tokens" in usage}
    return result


def _completion_issue(data):
    message = data["choices"][0]["message"]
    if len(message.get("tool_calls") or []) > 1:
        return "mixed_tool_batch"
    if not message.get("tool_calls") and not visible_answer_text(message):
        return "empty_completion"
    return None


def post_chat(session, url, payload, *, connect_timeout, idle_timeout, deadline_seconds, cancelled, progress, finish_grace_seconds=2.0):
    """One model round, at most one repair, and one shared deadline/output allowance.

    Sequential model tool calls prevent gateway/client ownership mixtures. A single
    composite web_search call can still fan out to multiple authorized engines.
    This layer never executes tools or replays tool side effects.
    """
    if deadline_seconds <= 0 or finish_grace_seconds <= 0:
        raise ValueError("Transport deadlines must be positive")
    deadline_at = time.monotonic() + deadline_seconds
    request = copy.deepcopy(payload)
    for key in ("stream_options", "chat_template_kwargs"):
        if request.get(key) is not None and not isinstance(request[key], dict):
            raise GatewayContractError(f"{key} must be an object.")
    request["stream"] = True
    request["stream_options"] = {**(request.get("stream_options") or {}), "include_usage": True}
    if request.get("tools"):
        request["parallel_tool_calls"] = False
    first, repair_reason = None, None
    for attempt in range(2):
        response, data = _post_once(
            session, url, request, connect_timeout, idle_timeout, deadline_at,
            cancelled, progress, finish_grace_seconds,
        )
        if response is not None:
            return response
        validate_completion_tools(data, request)
        issue = _completion_issue(data)
        if issue is None:
            if first is not None:
                data = _combined_completion(first, data, repair_reason)
            result = requests.Response()
            result.status_code = 200
            result.headers["content-type"] = "application/json"
            result.encoding = "utf-8"
            result._content = json.dumps(data, ensure_ascii=False).encode("utf-8")
            return result
        if attempt:
            message = "Backend did not return a visible answer after one bounded repair." if issue == "empty_completion" else "Backend ignored sequential tool calling; no tool from this batch was executed."
            raise GatewayContractError(message, issue, 502)
        choice = request.get("tool_choice")
        if issue == "empty_completion" and (isinstance(choice, dict) or choice == "required"):
            raise GatewayContractError("Backend omitted the required tool call.", "missing_required_tool_call", 502)
        cap = _repair_output_cap(payload, data)
        first, repair_reason = data, issue
        # Preserve the original goal and completed tool evidence. Never inject the
        # discarded tool batch or hidden reasoning into conversation history.
        request["messages"] = copy.deepcopy(payload.get("messages", []))
        instruction = "Return exactly one tool call this round. Continue other tools in later rounds; never mix execution owners."
        if issue == "empty_completion":
            instruction = "Return a concise visible answer using available evidence. State missing evidence honestly. Do not output hidden reasoning or request more tools."
            request.pop("tools", None)
            request["tool_choice"] = "none"
            request["chat_template_kwargs"] = {**(request.get("chat_template_kwargs") or {}), "enable_thinking": False}
            request["reasoning_effort"] = "none"
        request["messages"].append({"role": "system", "content": instruction})
        request["parallel_tool_calls"] = False
        request["max_tokens"] = cap
        if "max_completion_tokens" in request:
            request["max_completion_tokens"] = cap
        progress({"phase": "model_recovery", "event": "model_recovery", "status": "running", "stage": "recovering", "message": "Preparing one bounded completion repair", "reason": issue, "attempt": 2, "remaining_output_cap": cap})
    raise AssertionError("Unreachable completion state")


def _post_once(session, url, request, connect_timeout, idle_timeout, deadline_at, cancelled, progress, finish_grace_seconds):
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
        nonlocal last_progress, last_size, last_state, terminal_seen_at
        last_state = state
        now = time.monotonic()
        if state.finish is not None and terminal_seen_at is None:
            terminal_seen_at = now
        size = state.generated_characters + len(state.calls)
        if state.received and size != last_size and (last_progress == 0.0 or now - last_progress >= 0.25):
            progress({"phase": "model_output", "event": "model_output_progress", "status": "running", "stage": "generating", "message": "Receiving model output", "elapsed_seconds": round(now - started, 3), "output_characters": state.visible_characters, "reasoning_characters": state.reasoning_characters, "tool_calls_pending": len(state.calls), "observed_output": True})
            last_progress, last_size = now, size

    try:
        response = session.post(url, json=request, stream=True, timeout=(connect, idle))
        watcher = threading.Thread(target=watch, daemon=True, name="gateway-backend-deadline")
        watcher.start()
        if response.status_code != 200:
            response.content
            if cancelled():
                raise InterruptedError("Model request cancelled.")
            if time.monotonic() >= deadline_at:
                raise GatewayContractError("Backend model deadline exceeded.", "backend_deadline_exceeded", 504)
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
        if stopped.is_set() or cancelled():
            raise InterruptedError("Model request cancelled.")
        if expired.is_set() or time.monotonic() >= deadline_at:
            raise GatewayContractError("Backend model deadline exceeded.", "backend_deadline_exceeded", 504)
        return None, data
    except Exception as exc:
        if stopped.is_set() or cancelled():
            raise InterruptedError("Model request cancelled.") from exc
        if expired.is_set() or time.monotonic() >= deadline_at:
            raise GatewayContractError("Backend model deadline exceeded.", "backend_deadline_exceeded", 504) from exc
        if isinstance(exc, requests.Timeout):
            raise GatewayContractError("Backend timed out before completing the response.", "backend_timeout", 504) from exc
        if isinstance(exc, requests.RequestException):
            raise GatewayContractError("Backend connection or stream failed; no partial tool call was executed.", "backend_stream_interrupted", 502) from exc
        raise
    finally:
        finished.set()
        if response is not None:
            response.close()
        if watcher is not None:
            watcher.join(timeout=0.2)
