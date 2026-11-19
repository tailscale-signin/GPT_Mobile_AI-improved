import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient
from v14.private_inference import BASE, backend_json, install_private_inference, local_backend, text_request


class PrivateInferenceTest(unittest.TestCase):
    def test_backend_allows_only_fixed_loopback_model_server(self):
        for value in ("https://cloud.example/v1", "http://localhost.evil.test", "http://127.0.0.1/relay",
                      "http://user@127.0.0.1", "http://127.0.0.1?upstream=cloud", "http://10.0.0.2"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                local_backend(value)
        self.assertEqual(local_backend("http://localhost:8080/v1"), "http://127.0.0.1:8080")
        self.assertEqual(local_backend("http://[::1]:8080"), "http://[::1]:8080")

    def setUp(self):
        self.app = FastAPI()
        self.runtime = SimpleNamespace(LLAMA_BASE="http://localhost:8080")
        install_private_inference(self.app, self.runtime)
        self.client = TestClient(self.app)
        self.message = {"model": "test", "max_tokens": 50, "messages": [{"role": "user", "content": "private text"}]}

    def responses(self, model="test"):
        return [{"model_path": "D:/Models/local.gguf"}, {"data": [{"id": model}]}]

    def test_catalog_then_stateless_text(self):
        with patch("v14.private_inference.backend_json", new_callable=AsyncMock) as upstream:
            upstream.side_effect = self.responses() + self.responses() + [{"choices": [{"message": {"content": "ok"}}]}]
            self.assertEqual(self.client.get(BASE).json(), {"protocol": "private-local-text-v1", "provider_kind": "LLAMA", "models": ["test"]})
            self.assertEqual(self.client.post(BASE, json=self.message).json(), {"text": "ok"})
            body = upstream.await_args.args[-1]
            self.assertEqual(body, dict(self.message, stream=False, cache_prompt=False))

    def test_changed_model_incomplete_generation_and_non_public_payloads_are_withheld(self):
        choice = {"finish_reason": "stop", "message": {"role": "assistant", "content": "answer"}}
        cases = [{"model": "other", "choices": [choice]},
                 {"model": "test", "choices": [dict(choice, finish_reason="length")]},
                 {"choices": [dict(choice, message={"role": "tool", "content": "private"})]},
                 {"choices": [dict(choice, message={"content": "   "})]}]
        for reply in cases:
            with self.subTest(reply=reply), patch("v14.private_inference.backend_json", new_callable=AsyncMock) as upstream:
                upstream.side_effect = self.responses() + [reply]
                result = self.client.post(BASE, json=self.message)
                self.assertEqual(409, result.status_code)
                self.assertEqual({"error": "PRIVATE_LOCAL_ROUTE_UNAVAILABLE"}, result.json())

    def test_revoked_or_substituted_route_never_submits_content(self):
        with patch("v14.private_inference.backend_json", new_callable=AsyncMock) as upstream:
            upstream.side_effect = self.responses() + self.responses(model="changed")
            self.assertEqual(self.client.get(BASE).status_code, 200)
            self.assertEqual(self.client.post(BASE, json=self.message).status_code, 409)
            self.assertTrue(all(call.args[1] == "GET" for call in upstream.await_args_list))
            self.runtime.LLAMA_BASE = "https://external.example"
            self.assertEqual(self.client.post(BASE, json=self.message).status_code, 409)
            self.assertEqual(upstream.await_count, 4)

    def test_ollama_requires_local_weights_and_refuses_remote_aliases(self):
        self.runtime.GATEWAY_INFERENCE_PROVIDER = "OLLAMA"
        local = {"name": "test", "digest": "a" * 64, "size": 100, "details": {"format": "gguf"}}
        catalog = {"models": [local, dict(local, name="hidden-relay", remote_host="https://cloud.example")]}
        with patch("v14.private_inference.backend_json", new_callable=AsyncMock) as upstream:
            upstream.side_effect = [catalog, catalog, {"remote_model": "cloud", "details": {"format": "gguf"}}]
            self.assertEqual(self.client.get(BASE).json()["models"], ["test"])
            self.assertEqual(self.client.post(BASE, json=self.message).status_code, 409)
            self.assertTrue(all(call.args[2].endswith(("/api/tags", "/api/show")) for call in upstream.await_args_list))

    def test_plugins_and_nontext_content_rejected(self):
        for key in ("tools", "provider", "models", "store", "plugins", "gateway", "stream"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                text_request(dict(self.message, **{key: True}), ["test"])
        with self.assertRaises(ValueError):
            text_request(dict(self.message, messages=[{"role": "user", "content": [{"type": "image_url"}]}]), ["test"])

    def test_redirect_response_stops_before_second_host(self):
        async def run():
            calls = []
            def handler(request):
                calls.append(str(request.url))
                return httpx.Response(307, headers={"Location": "https://external.example/model"})
            async with httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False) as client:
                with self.assertRaises(httpx.HTTPStatusError):
                    await backend_json(client, "POST", "http://127.0.0.1/v1/chat/completions", self.message)
            self.assertEqual(calls, ["http://127.0.0.1/v1/chat/completions"])
        asyncio.run(run())
