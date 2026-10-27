import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from authentication.app import create_app
from authentication.github_broker import Config, challenge_for


class HttpBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.calls = []
        def exchange(payload):
            self.calls.append(payload)
            return {"access_token": "ghu_test", "token_type": "bearer"}
        self.app = create_app(Config("Iv1.test", "server-secret", "https://auth.example.org/oauth/github/callback", "https://auth.example.org", Path(self.temp.name) / "db"), exchange)
        self.client = TestClient(self.app, base_url="https://auth.example.org")

    def test_exchange_returns_credentials_only_over_https_with_no_store(self):
        verifier = "v" * 43
        start = self.client.post("/v1/github/oauth/start", json={"codeChallenge": challenge_for(verifier)}).json()
        response = self.client.post("/v1/github/oauth/exchange", json={"state": start["state"], "codeVerifier": verifier, "code": "code"})
        self.assertEqual(200, response.status_code)
        self.assertEqual("ghu_test", response.json()["access_token"])
        self.assertEqual("no-store", response.headers["cache-control"])
        self.assertEqual(1, len(self.calls))
        replay = self.client.post("/v1/github/oauth/exchange", json={"state": start["state"], "codeVerifier": verifier, "code": "code"})
        self.assertEqual(400, replay.status_code)
        self.assertEqual(1, len(self.calls))

    def test_http_wrong_hosts_and_cross_origin_requests_are_rejected(self):
        for url in ("http://auth.example.org", "https://attacker.example.org"):
            self.assertEqual(403, TestClient(self.app, base_url=url).get("/health/ready").status_code)
        response = self.client.post("/v1/github/oauth/start", json={"codeChallenge": "x" * 43}, headers={"Origin": "https://attacker.example.org"})
        self.assertEqual(403, response.status_code)

    def test_malformed_input_never_echoes_secrets_or_accepts_dynamic_configuration(self):
        for body in ({"client_secret": "do-not-echo"}, {"state": "do-not-echo", "code": 123}, {"codeChallenge": "x" * 43, "redirectUri": "https://attacker.example.org"}):
            response = self.client.post("/v1/github/oauth/start", json=body)
            self.assertEqual(400, response.status_code)
            self.assertNotIn("do-not-echo", response.text)
        response = self.client.post("/v1/github/oauth/start", content="do-not-echo" * 1000, headers={"Content-Type": "application/json"})
        self.assertEqual(400, response.status_code)
        self.assertNotIn("do-not-echo", response.text)
        self.assertEqual([], self.calls)

    def test_public_endpoint_rate_limit_is_bounded_and_actionable(self):
        for _ in range(60):
            self.assertEqual(200, self.client.get("/health/ready").status_code)
        response = self.client.get("/health/ready")
        self.assertEqual(429, response.status_code)
        self.assertEqual("600", response.headers["retry-after"])


if __name__ == "__main__":
    unittest.main()
