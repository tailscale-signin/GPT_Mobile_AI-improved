"""Real app import/lifespan and HTTP authorization tests; no backend or MCP processes."""
import importlib
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from gateway_security import DeviceStore, current_device


class GatewayIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = DeviceStore(Path(self.temp.name) / "auth.sqlite3")
        with patch.dict(os.environ, {"MCP_PREWARM_ON_STARTUP": "false", "GATEWAY_JOB_DB": str(Path(self.temp.name) / "jobs.sqlite3")}):
            self.gateway = importlib.import_module("gateway_v12")
        self.a, self.token_a = self.store.issue("Phone A")
        self.b, self.token_b = self.store.issue("Phone B")
        self.patches = [patch("gateway_security.device_store", self.store), patch.object(self.gateway, "device_store", self.store),
                        patch.object(self.gateway, "start_gateway_background_services"), patch.object(self.gateway, "gateway_health", return_value={"status": "ok"})]
        for item in self.patches:
            item.start()
        self.gateway.app.middleware_stack = None
        self.client = TestClient(self.gateway.app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        for item in reversed(self.patches):
            item.stop()
        self.gateway.app.middleware_stack = None
        self.temp.cleanup()

    def test_clean_import_and_lifespan_require_credentials(self):
        self.assertEqual(self.client.get("/health").status_code, 401)
        self.assertEqual(self.client.get("/health", headers={"Authorization": "Bearer " + self.token_a}).json(), {"status": "ok"})
        for path in ["/gateway/jobs", "/gateway/mcp/sessions", "/gateway/docs", "/v1/models"]:
            self.assertEqual(self.client.get(path).status_code, 401)
        self.assertEqual(self.client.post("/gateway/mcp/restart/private").status_code, 401)
        self.assertEqual(self.client.post("/v1/chat/completions", json={}).status_code, 401)

    def test_revocation_is_immediate(self):
        self.store.revoke(self.a)
        self.assertEqual(self.client.get("/health", headers={"Authorization": "Bearer " + self.token_a}).status_code, 401)

    def test_pairing_is_one_time_and_token_is_not_stored_plaintext(self):
        link = self.store.pair("Phone", "http://127.0.0.1:8090/v1", "local-model")
        code = parse_qs(urlparse(link).query)["code"][0]
        headers = {"Authorization": "Bearer " + code}
        response = self.client.get("/gateway/pair", headers=headers)
        self.assertEqual(response.status_code, 200)
        config = response.json()
        self.assertEqual(config["provider"], "LLAMA")
        self.assertIsNotNone(self.store.authenticate(config["token"]))
        self.assertEqual(self.client.get("/gateway/pair", headers=headers).status_code, 401)
        self.assertNotIn(config["token"].encode(), self.store.path.read_bytes())

    def test_job_access_and_request_coalescing_are_device_scoped(self):
        self.store.claim("job-a", self.a)
        for path in ["/gateway/jobs/job-a", "/gateway/jobs/job-a/result", "/gateway/jobs/job-a/events"]:
            self.assertEqual(self.client.get(path, headers={"Authorization": "Bearer " + self.token_b}).status_code, 404)
        self.assertEqual(self.client.post("/gateway/jobs/job-a/cancel", headers={"Authorization": "Bearer " + self.token_b}).status_code, 404)
        with self.assertRaises(PermissionError):
            self.store.claim("job-a", self.b)
        marker = current_device.set(self.a)
        try:
            first = self.gateway.canonical_chat_request_fingerprint({"messages": []})
            current_device.set(self.b)
            second = self.gateway.canonical_chat_request_fingerprint({"messages": []})
        finally:
            current_device.reset(marker)
        self.assertNotEqual(first, second)

    def test_gateway_credential_is_not_forwarded_to_backend(self):
        self.assertEqual(self.gateway.build_proxy_headers({"Authorization": "Bearer secret", "Cookie": "secret", "Accept": "application/json"}), {"Accept": "application/json"})


if __name__ == "__main__":
    unittest.main()
