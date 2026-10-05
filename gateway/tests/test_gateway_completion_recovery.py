"""Deterministic v13 stream/completion regressions; no model or network needed."""
import copy
import json
import sys
import threading
import time
import unittest
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_v13_runtime import (CompletionStream, GatewayContractError,
                                completion_chunks, consume_sse,
                                validate_completion_tools, visible_answer_text)
from gateway_v13_transport import post_chat


def completion(text="Answer", calls=None, used=10):
    message = {"role": "assistant", "content": text}
    if calls is not None:
        message["tool_calls"] = calls
    data = {"choices": [{"index": 0, "message": message,
                         "finish_reason": "tool_calls" if calls else "stop"}]}
    if used is not None:
        data["usage"] = {"prompt_tokens": 20, "completion_tokens": used, "total_tokens": 20 + used}
    return data


def tool(name, identifier):
    return {"id": identifier, "type": "function", "function": {"name": name, "arguments": '{"query":"test"}'}}


def payload():
    return {"model": "local", "messages": [{"role": "user", "content": "Find evidence"}],
            "max_tokens": 100, "tools": [{"type": "function", "function": {
                "name": name, "parameters": {"type": "object", "required": ["query"]}}}
                for name in ("gateway_search", "client_search")]}


def lines(*events, done=True):
    for event in events:
        yield "data: " + json.dumps(event)
        yield ""
    if done:
        yield "data: [DONE]"  # Deliberately no blank line or socket EOF required.


class Response:
    def __init__(self, data=None, stream=None, stall=False):
        self.status_code = 200
        self.headers = {"content-type": "text/event-stream" if stream is not None else "application/json"}
        self.data, self.stream, self.stall = data, stream, stall
        self.closed = threading.Event()
        self.content = b""

    def json(self):
        return self.data

    def iter_lines(self, **kwargs):
        yield from self.stream
        if self.stall:
            if not self.closed.wait(2):
                raise AssertionError("Transport failed to close completed/stalled stream")
            raise requests.ConnectionError("Stream closed")

    def close(self):
        self.closed.set()


class Session:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def post(self, url, **kwargs):
        self.calls.append(copy.deepcopy(kwargs))
        return self.responses.pop(0)


class CompletionRecoveryTests(unittest.TestCase):
    def run_chat(self, session, body=None, **options):
        config = dict(connect_timeout=1, idle_timeout=1, deadline_seconds=3,
                      cancelled=lambda: False, progress=lambda event: None,
                      finish_grace_seconds=0.06)
        config.update(options)
        return post_chat(session, "http://backend", body or payload(), **config)

    def test_single_tool_policy_does_not_mutate_caller(self):
        body = payload()
        original = copy.deepcopy(body)
        session = Session(Response(completion()))
        self.run_chat(session, body)
        self.assertFalse(session.calls[0]["json"]["parallel_tool_calls"])
        self.assertTrue(session.calls[0]["json"]["stream_options"]["include_usage"])
        self.assertEqual(original, body)

    def test_empty_answer_repairs_once_and_accounts_for_both_attempts(self):
        first = completion("", used=30)
        first["choices"][0]["message"]["reasoning_content"] = "private thoughts"
        session = Session(Response(first), Response(completion("Visible answer", used=20)))
        actual = self.run_chat(session).json()
        self.assertEqual("Visible answer", actual["choices"][0]["message"]["content"])
        repair = session.calls[1]["json"]
        self.assertNotIn("tools", repair)
        self.assertEqual("none", repair["tool_choice"])
        self.assertEqual(70, repair["max_tokens"])
        self.assertFalse(repair["chat_template_kwargs"]["enable_thinking"])
        self.assertNotIn("private thoughts", str(repair))
        self.assertEqual({"prompt_tokens": 40, "completion_tokens": 50, "total_tokens": 90}, actual["usage"])
        self.assertEqual(2, actual["gateway_recovery"]["attempts"])

    def test_repeated_empty_is_terminal_without_third_request(self):
        session = Session(Response(completion("")), Response(completion("")))
        with self.assertRaises(GatewayContractError) as error:
            self.run_chat(session)
        self.assertEqual("empty_completion", error.exception.code)
        self.assertEqual(2, len(session.calls))

    def test_exhausted_or_unknown_output_usage_never_starts_unbudgeted_retry(self):
        for used, code in [(100, "completion_repair_budget_exhausted"), (None, "completion_repair_usage_unavailable")]:
            with self.subTest(used=used):
                session = Session(Response(completion("", used=used)))
                with self.assertRaises(GatewayContractError) as error:
                    self.run_chat(session)
                self.assertEqual(code, error.exception.code)
                self.assertEqual(1, len(session.calls))

    def test_mixed_batch_is_regenerated_as_single_unexecuted_call(self):
        mixed = [tool("gateway_search", "g"), tool("client_search", "c")]
        session = Session(Response(completion(None, mixed, 40)), Response(completion(None, [mixed[0]], 15)))
        actual = self.run_chat(session).json()
        self.assertEqual([mixed[0]], actual["choices"][0]["message"]["tool_calls"])
        repair = session.calls[1]["json"]
        self.assertEqual(payload()["tools"], repair["tools"])
        self.assertFalse(repair["parallel_tool_calls"])
        self.assertEqual(60, repair["max_tokens"])
        self.assertEqual(["user", "system"], [item["role"] for item in repair["messages"]])
        self.assertEqual(55, actual["usage"]["completion_tokens"])

    def test_repeated_multi_call_batch_fails_without_executing_any_call(self):
        mixed = completion(None, [tool("gateway_search", "g"), tool("client_search", "c")])
        session = Session(Response(mixed), Response(mixed))
        with self.assertRaises(GatewayContractError) as error:
            self.run_chat(session)
        self.assertEqual("mixed_tool_batch", error.exception.code)
        self.assertEqual(2, len(session.calls))

    def test_unknown_tools_never_become_repair_authorization(self):
        session = Session(Response(completion(None, [tool("unauthorized", "x")])))
        with self.assertRaises(GatewayContractError) as error:
            self.run_chat(session)
        self.assertEqual("unauthorized_tool_call", error.exception.code)
        self.assertEqual(1, len(session.calls))

    def test_forced_tool_is_not_downgraded_to_text_repair(self):
        body = payload()
        body["tool_choice"] = "required"
        session = Session(Response(completion("")))
        with self.assertRaises(GatewayContractError) as error:
            self.run_chat(session, body)
        self.assertEqual("missing_required_tool_call", error.exception.code)
        self.assertEqual(1, len(session.calls))

    def test_finish_marker_closes_open_socket_but_keeps_usage(self):
        response = Response(stream=lines(
            {"choices": [{"delta": {"content": "answer"}, "finish_reason": "stop"}]},
            {"choices": [], "usage": {"prompt_tokens": 5, "completion_tokens": 2}}, done=False), stall=True)
        started = time.monotonic()
        result = self.run_chat(Session(response)).json()
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual("answer", result["choices"][0]["message"]["content"])
        self.assertEqual(2, result["usage"]["completion_tokens"])
        self.assertTrue(response.closed.is_set())

    def test_cancel_before_post_sends_nothing(self):
        session = Session()
        with self.assertRaises(InterruptedError):
            self.run_chat(session, cancelled=lambda: True)
        self.assertFalse(session.calls)

    def test_deadline_closes_stream_without_finish_marker(self):
        response = Response(stream=lines({"choices": [{"delta": {"content": "partial"}}]}, done=False), stall=True)
        with self.assertRaises(GatewayContractError) as error:
            self.run_chat(Session(response), deadline_seconds=0.08)
        self.assertEqual("backend_deadline_exceeded", error.exception.code)
        self.assertTrue(response.closed.is_set())

    def test_cancel_mid_stream_closes_transport(self):
        cancelled = threading.Event()
        response = Response(stream=lines({"choices": [{"delta": {"content": "partial"}}]}, done=False), stall=True)
        timer = threading.Timer(0.05, cancelled.set)
        timer.start()
        try:
            with self.assertRaises(InterruptedError):
                self.run_chat(Session(response), cancelled=cancelled.is_set)
            self.assertTrue(response.closed.is_set())
        finally:
            timer.join()

    def test_bare_done_terminates_without_requesting_another_line(self):
        def stream():
            yield from lines({"choices": [{"delta": {"content": "answer"}}]})
            raise AssertionError("Read after DONE")
        self.assertEqual("answer", consume_sse(stream())["choices"][0]["message"]["content"])

    def test_done_does_not_authorize_unfinished_tool_fragments(self):
        with self.assertRaises(GatewayContractError) as error:
            consume_sse(lines({"choices": [{"delta": {"tool_calls": [dict(tool("gateway_search", "a"), index=0)]}}]}))
        self.assertEqual("incomplete_stream", error.exception.code)

    def test_eof_without_finish_is_not_success(self):
        with self.assertRaises(GatewayContractError):
            consume_sse(lines({"choices": [{"delta": {"content": "partial"}}]}, done=False))

    def test_reasoning_progress_is_not_visible_answer_progress(self):
        state = CompletionStream()
        state.accept({"choices": [{"delta": {"reasoning_content": "thinking"}}]})
        self.assertEqual(0, state.visible_characters)
        self.assertEqual(8, state.reasoning_characters)
        self.assertEqual(8, state.generated_characters)
        self.assertEqual("", visible_answer_text(state.message))
        self.assertEqual("", visible_answer_text({"content": "<think>hidden</think>"}))
        self.assertEqual("answer", visible_answer_text({"content": "<think>hidden</think> answer"}))

    def test_invalid_stream_shapes_are_protocol_errors(self):
        for data in [{"choices": None}, {"choices": [None]}, {"choices": [{"delta": "bad"}]}, {"choices": [{"delta": []}]},
                     {"choices": [{"delta": {"tool_calls": {}}}]},
                     {"choices": [{"delta": {"tool_calls": [{"index": True}]}}]}]:
            with self.subTest(data=data), self.assertRaises(GatewayContractError):
                consume_sse(lines(data))

    def test_duplicate_argument_keys_are_rejected(self):
        call = tool("gateway_search", "a")
        call["function"]["arguments"] = '{"query":"first","query":"second"}'
        with self.assertRaises(GatewayContractError) as error:
            validate_completion_tools(completion(None, [call]), payload())
        self.assertEqual("invalid_tool_arguments", error.exception.code)

    def test_lossless_unicode_delivery_and_usage_exactly_once(self):
        data = completion("括弧()[] / 🌸\n" * 1000)
        chunks = list(completion_chunks(data, chunk_chars=7))
        parsed = [json.loads(chunk[6:]) for chunk in chunks[:-1]]
        self.assertEqual(data["choices"][0]["message"]["content"], "".join(item["choices"][0]["delta"].get("content", "") for item in parsed))
        self.assertEqual(1, sum("usage" in item for item in parsed))
        self.assertEqual(1, chunks.count("data: [DONE]\n\n"))

    def test_empty_delivery_is_one_error_then_done(self):
        chunks = list(completion_chunks(completion("")))
        self.assertEqual(2, len(chunks))
        self.assertEqual("empty_completion", json.loads(chunks[0][6:])["error"]["code"])
        self.assertEqual("data: [DONE]\n\n", chunks[-1])

    def test_unknown_repair_usage_is_not_presented_as_exact_combined_total(self):
        body = payload()
        body.pop("max_tokens")
        session = Session(Response(completion("", used=None)), Response(completion("answer", used=12)))
        result = self.run_chat(session, body).json()
        self.assertNotIn("usage", result)
        self.assertFalse(result["gateway_recovery"]["usage_complete"])


if __name__ == "__main__":
    unittest.main()
