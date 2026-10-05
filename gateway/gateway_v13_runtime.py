"""Gateway v13 protocol/context primitives; no network or model dependencies."""
from __future__ import annotations

import copy
import json
import re
import time


class GatewayContractError(ValueError):
    def __init__(self, message, code="invalid_request", status=400):
        super().__init__(message)
        self.code = code
        self.status = status


def visible_answer_text(value):
    """Reasoning-only, whitespace and tool markup are not a user-visible answer."""
    content = value.get("content") if isinstance(value, dict) else value
    if not isinstance(content, str):
        return ""
    text = re.sub(r"<(think|analysis|reasoning)(?:\s[^>]*)?>.*?(?:</\1>|$)", "", content, flags=re.I | re.S)
    text = re.sub(r"<tool_call>.*?(?:</tool_call>|$)", "", text, flags=re.I | re.S)
    return re.sub(r"</(?:think|analysis|reasoning)>", "", text, flags=re.I).strip()


def partition_owned_tool_calls(calls, routes):
    """Keep call IDs and order. No unknown call may leak into a client handoff."""
    if len(calls) != len(routes):
        raise GatewayContractError("Tool ownership count mismatch.", "invalid_tool_ownership", 502)
    local, client = [], []
    for call, (name, owner) in zip(calls, routes):
        if call.get("function", {}).get("name") != name or owner not in {"gateway_mcp", "llama_native", "client_owned"}:
            raise GatewayContractError("No authorized executor owns a requested tool.", "unauthorized_tool_call", 502)
        (client if owner == "client_owned" else local).append(copy.deepcopy(call))
    return local, client


def validate_chat_request(payload):
    if not isinstance(payload, dict):
        raise GatewayContractError("The request body must be a JSON object.")
    if not isinstance(payload.get("model"), str) or not payload["model"].strip():
        raise GatewayContractError("A non-empty model is required.")
    if payload.get("n", 1) != 1:
        raise GatewayContractError("This gateway supports one completion per request (n=1).")
    messages = payload.get("messages")
    if not isinstance(messages, list) or not messages:
        raise GatewayContractError("A non-empty messages array is required.")
    for message in messages:
        if not isinstance(message, dict) or message.get("role") not in {"system", "developer", "user", "assistant", "tool"}:
            raise GatewayContractError("Every message needs a supported role.")
        if message.get("content") is not None and not isinstance(message["content"], (str, list)):
            raise GatewayContractError("Message content must be text or multimodal blocks.")
    names = set()
    tools = payload.get("tools", [])
    if not isinstance(tools, list):
        raise GatewayContractError("tools must be an array.")
    for tool in tools:
        fn = tool.get("function") if isinstance(tool, dict) else None
        if not isinstance(fn, dict) or not isinstance(fn.get("name"), str) or not fn["name"]:
            raise GatewayContractError("Every tool needs a function name.")
        if fn["name"] in names:
            raise GatewayContractError("Duplicate tool names are ambiguous.", "duplicate_tool_name")
        names.add(fn["name"])
    for key in ("max_tokens", "max_completion_tokens", "requestedOutputCap", "requested_output_cap", "delegation_output_cap"):
        if key in payload and payload[key] is not None:
            if isinstance(payload[key], bool) or not isinstance(payload[key], int) or payload[key] <= 0:
                raise GatewayContractError(f"{key} must be a positive integer.")
    choice = payload.get("tool_choice")
    if isinstance(choice, dict):
        name = (choice.get("function") or {}).get("name") if isinstance(choice.get("function"), dict) else None
        if name not in names:
            raise GatewayContractError("Forced tool choice is not present in the supplied catalog.")
    return payload


def compact_history(messages, emergency=False):
    """Keep the original goal and recent turns; never split a tool exchange.

    Latest user/follow-up messages and their evidence remain verbatim. Hidden
    assistant reasoning from older turns is unnecessary for replay. No user text
    is shortened and caller-owned messages are never modified.
    """
    source = copy.deepcopy(messages)
    segments, current = [], []
    for message in source:
        if message.get("role") == "user" and any(m.get("role") == "user" for m in current):
            segments.append(current)
            current = []
        current.append(message)
    if current:
        segments.append(current)
    keep = 4 if emergency else 8
    retained = segments if len(segments) <= keep else [segments[0]] + segments[-(keep - 1):]
    omitted = len(segments) - len(retained)
    # Preserve all instruction messages at their original relative boundary, even
    # if the user turn containing one was omitted.
    selected_ids = {id(message) for segment in retained for message in segment}
    latest_ids = {id(message) for message in (segments[-1] if segments else [])}
    result = []
    for message in source:
        if id(message) not in selected_ids and message.get("role") not in {"system", "developer"}:
            continue
        if id(message) not in latest_ids and message.get("role") == "assistant":
            message.pop("reasoning_content", None)
            message.pop("reasoning", None)
        result.append(message)
    if omitted:
        result.insert(0, {"role": "system", "content": f"Context note: {omitted} older turns were omitted. The original goal and recent turns are preserved. Do not infer omitted evidence."})
    return result


def fit_context(payload, n_ctx, counter, default_output=12288, emergency=False):
    """Fit history without silently reducing an explicit final output request."""
    original = copy.deepcopy(payload)
    candidate = copy.deepcopy(payload)
    safety = min(1024, max(64, n_ctx // 32))
    requested = candidate.get("max_tokens")
    explicit = isinstance(requested, int) and not isinstance(requested, bool) and requested > 0
    reserve = requested if explicit else min(default_output, max(1, n_ctx // 4))
    if reserve + safety >= n_ctx:
        raise GatewayContractError("The requested output budget leaves no input capacity in this model context.", "context_budget_exceeded", 422)
    before, mode = counter(candidate)
    tokens = before
    budget = n_ctx - safety - reserve
    if emergency or tokens > budget:
        candidate["messages"] = compact_history(original.get("messages", []), emergency=emergency)
        tokens, mode = counter(candidate)
    if tokens > budget and not emergency:
        candidate["messages"] = compact_history(original.get("messages", []), emergency=True)
        tokens, mode = counter(candidate)
    if tokens > budget:
        raise GatewayContractError(
            f"Context needs {tokens} input + {reserve} output + {safety} safety tokens; model capacity is {n_ctx}. "
            "The original goal, recent evidence and authorized tools were not silently truncated. Use a larger context or a focused subtask.",
            "context_budget_exceeded", 422,
        )
    candidate["max_tokens"] = reserve
    payload.clear()
    payload.update(candidate)
    return {"changed": candidate != original, "tokens": tokens, "original_tokens": before,
            "n_ctx": n_ctx, "soft_limit": budget, "hard_limit": budget,
            "max_tokens": reserve, "count_mode": mode, "explicit_output_preserved": explicit,
            "saved_input_tokens": max(0, before - tokens)}


def _unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON object key")
        result[key] = value
    return result


def validate_completion_tools(data, payload):
    choices = data.get("choices") if isinstance(data, dict) else None
    if not isinstance(choices, list) or not choices:
        raise GatewayContractError("Backend returned no completion choice.", "malformed_completion", 502)
    if len(choices) != 1:
        raise GatewayContractError("Multiple completion choices are not supported.", "multiple_choices", 502)
    catalog = {t["function"]["name"]: t["function"] for t in payload.get("tools", [])}
    seen = set()
    for choice in choices:
        if not isinstance(choice, dict) or not isinstance(choice.get("message"), dict):
            raise GatewayContractError("Malformed completion message.", "malformed_completion", 502)
        message = choice["message"]
        for key in ("content", "reasoning_content", "reasoning"):
            if message.get(key) is not None and not isinstance(message[key], str):
                raise GatewayContractError("Completion text must be a string.", "malformed_completion", 502)
        calls = message.get("tool_calls")
        calls = [] if calls is None else calls
        if not isinstance(calls, list):
            raise GatewayContractError("Malformed tool_calls payload.", "invalid_tool_call", 502)
        for call in calls:
            fn = call.get("function", {}) if isinstance(call, dict) else {}
            if not isinstance(fn, dict):
                raise GatewayContractError("Malformed tool function.", "invalid_tool_call", 502)
            name, call_id = fn.get("name"), call.get("id") if isinstance(call, dict) else None
            if payload.get("tool_choice") == "none" or not isinstance(name, str) or name not in catalog:
                raise GatewayContractError("Backend requested a tool outside the active catalog.", "unauthorized_tool_call", 502)
            if not isinstance(call_id, str) or not call_id or call_id in seen:
                raise GatewayContractError("Tool call IDs must be present and unique.", "invalid_tool_call", 502)
            seen.add(call_id)
            try:
                args = json.loads(fn.get("arguments", ""), object_pairs_hook=_unique_json_object)
            except (ValueError, TypeError):
                raise GatewayContractError("Tool arguments are incomplete, ambiguous or invalid JSON.", "invalid_tool_arguments", 502)
            if not isinstance(args, dict):
                raise GatewayContractError("Tool arguments must be a JSON object.", "invalid_tool_arguments", 502)
            required = catalog[name].get("parameters", {}).get("required", [])
            if any(key not in args for key in required):
                raise GatewayContractError("Tool arguments omit a required field.", "invalid_tool_arguments", 502)
            if choice.get("finish_reason") not in {"tool_calls", "stop"}:
                raise GatewayContractError("An unfinished tool call cannot be executed.", "incomplete_tool_call", 502)
    return data


class CompletionStream:
    """Reassemble OpenAI SSE deltas without executing partial tool fragments."""
    def __init__(self):
        self.message = {"role": "assistant", "content": ""}
        self.calls = {}
        self.meta = {}
        self.finish = None
        self.received = False
        self.visible_characters = 0
        self.reasoning_characters = 0
        self.tool_characters = 0

    @property
    def generated_characters(self):
        return self.visible_characters + self.reasoning_characters + self.tool_characters

    @property
    def observed_characters(self):
        return self.generated_characters

    def accept(self, data):
        if not isinstance(data, dict):
            raise GatewayContractError("Invalid SSE JSON object.", "malformed_stream", 502)
        if data.get("error"):
            raise GatewayContractError("Backend stream failed: " + str(data["error"]), "upstream_stream_error", 502)
        for key in ("id", "model", "created", "usage", "timings"):
            if key in data:
                if key in {"usage", "timings"} and isinstance(data[key], dict):
                    previous = self.meta.get(key)
                    self.meta[key] = {**(previous if isinstance(previous, dict) else {}), **data[key]}
                elif data[key] is not None:
                    self.meta[key] = data[key]
        choices = data.get("choices", [])
        if not isinstance(choices, list):
            raise GatewayContractError("Malformed streamed choices.", "malformed_stream", 502)
        for choice in choices:
            if not isinstance(choice, dict):
                raise GatewayContractError("Malformed streamed choice.", "malformed_stream", 502)
            if choice.get("index", 0) != 0:
                raise GatewayContractError("Multiple streamed choices are not supported.", "multiple_choices", 502)
            delta = choice.get("delta")
            if delta is None:
                delta = choice.get("message")
            if delta is None:
                delta = {}
            if not isinstance(delta, dict):
                raise GatewayContractError("Malformed streamed delta.", "malformed_stream", 502)
            for key in ("content", "reasoning_content", "reasoning"):
                part = delta.get(key)
                if part is not None and not isinstance(part, str):
                    raise GatewayContractError("Streamed text must be a string.", "malformed_stream", 502)
                if isinstance(part, str):
                    if self.finish is not None and part:
                        raise GatewayContractError("Output arrived after the finish marker.", "malformed_stream", 502)
                    self.message[key] = self.message.get(key, "") + part
                    if key == "content":
                        self.visible_characters = len(visible_answer_text(self.message[key]))
                    else:
                        self.reasoning_characters += len(part)
                    self.received = self.received or bool(part)
            fragments = delta.get("tool_calls")
            fragments = [] if fragments is None else fragments
            if not isinstance(fragments, list):
                raise GatewayContractError("Malformed streamed tool calls.", "invalid_tool_call", 502)
            for fragment in fragments:
                if self.finish is not None or not isinstance(fragment, dict):
                    raise GatewayContractError("Invalid or late tool fragment.", "invalid_tool_call", 502)
                index = fragment.get("index", 0)
                if isinstance(index, bool) or not isinstance(index, int) or index < 0 or index > 127:
                    raise GatewayContractError("Invalid streamed tool index.", "invalid_tool_call", 502)
                call = self.calls.setdefault(index, {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                if fragment.get("id") is not None:
                    if not isinstance(fragment["id"], str) or (call["id"] and call["id"] != fragment["id"]):
                        raise GatewayContractError("Tool ID changed or is invalid within one stream.", "invalid_tool_call", 502)
                    call["id"] = fragment["id"]
                fn = fragment.get("function")
                fn = {} if fn is None else fn
                if not isinstance(fn, dict):
                    raise GatewayContractError("Malformed streamed tool function.", "invalid_tool_call", 502)
                for key in ("name", "arguments"):
                    if fn.get(key) is not None and not isinstance(fn[key], str):
                        raise GatewayContractError("Malformed streamed tool text.", "invalid_tool_call", 502)
                    if isinstance(fn.get(key), str):
                        call["function"][key] += fn[key]
                        self.tool_characters += len(fn[key])
                self.received = True
            if choice.get("finish_reason") is not None:
                finish = choice["finish_reason"]
                if not isinstance(finish, str) or not finish or (self.finish is not None and self.finish != finish):
                    raise GatewayContractError("Conflicting or invalid finish marker.", "malformed_stream", 502)
                self.finish = finish

    def mark_done(self):
        # Some compatible servers send only [DONE]. Accept that explicit terminal
        # signal for text, never as authority to execute unfinished tool fragments.
        if self.finish is None and not self.calls:
            self.finish = "stop"

    def result(self):
        if self.finish is None:
            raise GatewayContractError("Backend stream ended before a finish marker; partial tool calls were discarded.", "incomplete_stream", 502)
        message = dict(self.message)
        if self.calls:
            message["tool_calls"] = [self.calls[i] for i in sorted(self.calls)]
        return {**self.meta, "object": "chat.completion", "choices": [{"index": 0, "message": message, "finish_reason": self.finish}]}


def consume_sse(lines, on_progress=lambda state: None, cancelled=lambda: False):
    state = CompletionStream()
    fields = []
    for raw in lines:
        if cancelled():
            raise InterruptedError("Model request cancelled while receiving output.")
        try:
            line = raw.decode("utf-8") if isinstance(raw, bytes) else raw
        except UnicodeDecodeError as exc:
            raise GatewayContractError("Invalid UTF-8 in backend stream.", "malformed_stream", 502) from exc
        if not isinstance(line, str):
            raise GatewayContractError("Invalid backend stream line.", "malformed_stream", 502)
        line = line.rstrip("\r\n")
        if line.startswith("data:"):
            value = line[5:].lstrip()
            if value.strip() == "[DONE]" and not fields:
                state.mark_done()
                break
            fields.append(value)
        elif not line and fields:
            value = "\n".join(fields)
            fields.clear()
            try:
                data = json.loads(value)
            except ValueError as exc:
                raise GatewayContractError("Invalid JSON in backend SSE event.", "malformed_stream", 502) from exc
            state.accept(data)
            on_progress(state)
    if cancelled():
        raise InterruptedError("Model request cancelled while receiving output.")
    if fields:
        raise GatewayContractError("Backend stream ended inside an SSE event.", "incomplete_stream", 502)
    return state.result()


def completion_chunks(data, chunk_chars=2048):
    """Deliver every output character, with usage once and one terminal marker."""
    if isinstance(chunk_chars, bool) or not isinstance(chunk_chars, int) or chunk_chars <= 0:
        raise ValueError("chunk_chars must be a positive integer")

    def encode(value):
        return "data: " + json.dumps(value, ensure_ascii=False) + "\n\n"
    if data.get("error"):
        yield encode({"error": data["error"]})
        yield "data: [DONE]\n\n"
        return
    base = {"id": data.get("id", "chatcmpl-gateway"), "object": "chat.completion.chunk",
            "created": data.get("created", int(time.time())), "model": data.get("model", "")}
    choices = data.get("choices") or [{}]
    message = choices[0].get("message") or {}
    if not visible_answer_text(message) and not message.get("tool_calls"):
        yield encode({"error": {"message": "Backend completed without a visible answer or executable tool call.", "code": "empty_completion"}})
        yield "data: [DONE]\n\n"
        return

    def event(delta, finish=None):
        return {**base, "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
    yield encode(event({"role": "assistant"}))
    for key in ("reasoning_content", "reasoning", "content"):
        text = message.get(key) or ""
        for offset in range(0, len(text), chunk_chars):
            yield encode(event({key: text[offset:offset + chunk_chars]}))
    for index, call in enumerate(message.get("tool_calls") or []):
        fn = call.get("function") or {}
        yield encode(event({"tool_calls": [{"index": index, "id": call["id"], "type": "function", "function": {"name": fn["name"], "arguments": ""}}]}))
        args = fn.get("arguments", "{}")
        for offset in range(0, len(args), chunk_chars):
            yield encode(event({"tool_calls": [{"index": index, "function": {"arguments": args[offset:offset + chunk_chars]}}]}))
    terminal = event({}, choices[0].get("finish_reason") or "stop")
    for key in ("usage", "timings"):
        if key in data:
            terminal[key] = data[key]
    yield encode(terminal)
    yield "data: [DONE]\n\n"
