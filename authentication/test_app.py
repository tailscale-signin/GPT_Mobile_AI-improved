import base64
import json
import re
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from fastapi.testclient import TestClient

from authentication.app import create_app
from authentication.github_broker import Config, challenge_for
from authentication.issue_report_app import create_app as create_report_app
from authentication.issue_reports import ERROR_CODES, GitHubIssuePublisher, GitHubRequestError, IssueReporter, REPOSITORY, ReportError, issue_from_report, marker


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


class IssueReportHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / "reports.sqlite3"
        self.issued = []
        self.found = None
        self.now = 1_800_000_000
        self.publish_error = None
        self.reporter = IssueReporter(self.database, self, lambda: self.now)
        self.origin = "https://reports.example.org"
        self.app = create_report_app(self.origin, self.reporter, b"rate-limit-test-material")
        self.client = TestClient(self.app, base_url=self.origin)

    def create(self, issue):
        self.issued.append(issue)
        if self.publish_error:
            raise self.publish_error
        return 42

    def find(self, report_id, created):
        return self.found

    def submit(self, report):
        return self.client.post("/v1/issue-reports", json=report)

    def metadata(self):
        return {"reportId": str(uuid4()), "version": "0.9.66.7", "build": 141, "commit": "af658d8",
                "android": "16", "sdk": 36, "device": "ASUS ROG Phone 9"}

    def manual(self):
        return {**self.metadata(), "source": "manual", "title": "Voice playback stopped",
                "description": "Playback stopped when opening Voice Chat."}

    def test_manual_report_and_retry_return_the_same_issue_after_restart(self):
        report = self.manual()
        report["description"] += "\nBearer placeholder-secret-value\n@someone\n" + marker(str(uuid4()))
        response = self.submit(report)
        self.assertEqual(200, response.status_code)
        self.assertEqual({"number": 42}, response.json())
        self.assertEqual("no-store", response.headers["cache-control"])
        restarted = IssueReporter(self.database, self, lambda: self.now)
        self.assertEqual(42, restarted.submit(report, "different-network"))
        self.assertEqual(1, len(self.issued))
        body = self.issued[0]["body"]
        self.assertIn("    @someone", body)
        self.assertIn("[redacted]", body)
        self.assertNotIn("placeholder-secret-value", body)
        self.assertTrue(body.endswith(marker(report["reportId"])))
        self.assertNotIn(b"Playback stopped", self.database.read_bytes())
        self.assertEqual(409, self.submit({**report, "title": "Changed report"}).status_code)

    def test_debug_only_accepts_public_fields_and_projected_events(self):
        report = {**self.metadata(), "source": "debug", "feature": "voice", "errorCode": "VOICE_PLAYBACK_FAILED",
                  "errorType": "IllegalStateException", "frames": ["android.media.AudioTrack.play:104"],
                  "events": ["-000ms E voice VOICE_PLAYBACK_FAILED", "-023ms I local"]}
        self.assertEqual(200, self.submit(report).status_code)
        self.assertIn("android.media.AudioTrack.play:104", self.issued[0]["body"])
        self.assertIn("Android 16 (API 36)", self.issued[0]["body"])
        self.assertIn("Recent app events", self.issued[0]["body"])
        for change in ({"events": ["raw private log"]}, {"events": ["-023ms E local PRIVATE_CODE"]},
                       {"frames": ["/data/data/private/path"]}, {"feature": []}, {"message": "private"},
                       {"repository": "unrelated/project"}, {"title": "arbitrary debug title"}):
            with self.subTest(change=change):
                self.assertEqual(422, self.submit({**report, "reportId": str(uuid4()), **change}).status_code)
        self.assertEqual(1, len(self.issued))

    def test_specific_voice_gateway_and_tool_failure_codes_survive_public_report_validation(self):
        for feature, code in (
            ("voice", "VOICE_MICROPHONE_PERMISSION_REQUIRED"),
            ("voice", "VOICE_RECOGNIZER_MEMORY_PRESSURE"),
            ("gateway", "GATEWAY_ENGINEERING_GITHUB_REQUIRED"),
            ("tools", "INVALID_ARGUMENTS"),
        ):
            with self.subTest(code=code):
                report = {**self.metadata(), "source": "debug", "feature": feature, "errorCode": code,
                          "events": [f"-023ms E {feature} {code}"]}
                self.assertEqual(200, self.submit(report).status_code)
                self.assertIn(code, self.issued[-1]["title"])
                self.assertIn(f"-023ms E {feature} {code}", self.issued[-1]["body"])

    def test_unknown_extended_failure_codes_remain_rejected(self):
        report = {**self.metadata(), "source": "debug", "feature": "voice", "errorCode": "VOICE_SESSION_FAILED"}
        for change in (
            {"errorCode": "PRIVATE_PROVIDER_TOKEN_VALUE"},
            {"events": ["-023ms E voice PRIVATE_PROVIDER_TOKEN_VALUE"]},
            {"errorCode": "VOICE_MICROPHONE_PERMISSION_REQUIRED private message"},
        ):
            with self.subTest(change=change):
                self.assertEqual(422, self.submit({**report, "reportId": str(uuid4()), **change}).status_code)
        self.assertFalse(self.issued)

    def test_android_public_codes_match_service_and_keep_the_failure_identity(self):
        source = (Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/IssueReportCodes.kt").read_text()
        declaration = source.split("val allowed = setOf(", 1)[1].split("\n    )", 1)[0]
        android_codes = set(re.findall(r'"([A-Z][A-Z0-9_]+)"', declaration))
        self.assertEqual(android_codes, ERROR_CODES)
        for code in sorted(android_codes):
            with self.subTest(code=code):
                report = {**self.metadata(), "source": "debug", "feature": "tools", "errorCode": code,
                          "events": [f"-023ms W tools {code}", f"-001ms E tools {code}"]}
                _, issue = issue_from_report(report)
                self.assertIn(code, issue["title"])
                self.assertIn(f"-023ms W tools {code}", issue["body"])
                self.assertIn(f"-001ms E tools {code}", issue["body"])
                self.assertIn(report["commit"], issue["body"])

    def test_typed_network_and_unknown_outcome_codes_survive_formatting(self):
        for code in ("DNS_RESOLUTION_FAILED", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "NETWORK_TIMEOUT",
                     "TLS_FAILURE", "AUTH_REQUIRED", "RATE_LIMITED", "READ_DEADLINE",
                     "DISPATCH_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN"):
            with self.subTest(code=code):
                report = {**self.metadata(), "source": "debug", "feature": "tools", "errorCode": code,
                          "events": [f"-023ms E tools {code}"]}
                _, issue = issue_from_report(report)
                self.assertIn(code, issue["title"])
                self.assertIn(f"-023ms E tools {code}", issue["body"])

    def test_uncertain_write_is_reconciled_without_another_post(self):
        report = self.manual()
        self.publish_error = GitHubRequestError()
        first = self.submit(report)
        self.assertEqual(503, first.status_code)
        self.assertEqual("AWAITING_GITHUB_RECEIPT", first.json()["error"])
        self.assertEqual(503, self.submit(report).status_code)
        self.now += 121
        restarted = IssueReporter(self.database, self, lambda: self.now)
        with self.assertRaises(ReportError) as pending:
            restarted.submit(report, "client")
        self.assertEqual(503, pending.exception.status)
        self.now += 121
        self.found = 77
        self.assertEqual(77, restarted.submit(report, "client"))
        self.assertEqual(1, len(self.issued))

    def test_concurrent_retry_shares_the_original_dispatch_lease(self):
        entered, release = threading.Event(), threading.Event()
        original_create = self.create

        def delayed(issue):
            entered.set()
            if not release.wait(5):
                raise AssertionError("Timed out waiting for concurrent submission")
            return original_create(issue)

        self.create = delayed
        report = self.manual()
        with ThreadPoolExecutor(1) as executor:
            initial = executor.submit(self.reporter.submit, report, "client")
            try:
                self.assertTrue(entered.wait(5))
                self.assertEqual(503, self.submit(report).status_code)
            finally:
                release.set()
            self.assertEqual(42, initial.result(timeout=5))
        self.assertEqual(42, self.submit(report).json()["number"])
        self.assertEqual(1, len(self.issued))

    def test_certain_rate_limit_rejection_retries_after_cooldown(self):
        report = self.manual()
        self.publish_error = ReportError(503, "GITHUB_UNAVAILABLE", 120)
        self.assertEqual("120", self.submit(report).headers["retry-after"])
        self.assertEqual(503, self.submit(self.manual()).status_code)
        self.assertEqual(1, len(self.issued))
        self.now += 120
        self.publish_error = None
        self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(2, len(self.issued))

    def test_http_origin_size_and_submission_limits(self):
        self.assertEqual(403, TestClient(self.app, base_url="http://reports.example.org").get("/health/ready").status_code)
        self.assertEqual(403, self.client.post("/v1/issue-reports", json=self.manual(), headers={"Origin": "https://elsewhere.example.org"}).status_code)
        self.assertEqual(413, self.client.post("/v1/issue-reports", content="x" * 8193, headers={"Content-Type": "application/json"}).status_code)
        self.assertEqual(415, self.client.post("/v1/issue-reports", data={"title": "hello"}).status_code)
        for _ in range(4):
            self.assertEqual(200, self.submit(self.manual()).status_code)
        self.assertEqual(429, self.submit(self.manual()).status_code)
        self.assertEqual(4, len(self.issued))

    def test_github_publisher_scopes_the_installation_and_matches_only_its_bot(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        pem = Path(self.temp.name) / "ephemeral-key.pem"
        pem.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        publisher = GitHubIssuePublisher("123", "456", "issue-test", pem, lambda: self.now)
        report_id = str(uuid4())
        posted = []

        def request(path, token, payload=None):
            if path == "/app/installations/456/access_tokens":
                header, claims, signature = token.split(".")
                key.public_key().verify(base64.urlsafe_b64decode(signature + "=="), f"{header}.{claims}".encode(),
                                        padding.PKCS1v15(), hashes.SHA256())
                self.assertEqual("123", json.loads(base64.urlsafe_b64decode(claims + "=="))["iss"])
                self.assertEqual({"repositories": [REPOSITORY.split("/")[1]], "permissions": {"issues": "write"}}, payload)
                return {"token": "installation-test", "expires_at": "2027-02-01T00:00:00Z"}
            self.assertEqual("installation-test", token)
            self.assertTrue(path.startswith(f"/repos/{REPOSITORY}/issues"))
            if payload is not None:
                posted.append(payload)
                return {"number": 42}
            body = "Report\n\n" + marker(report_id)
            return [{"number": 2, "body": body, "user": {"login": "unrelated"}},
                    {"number": 42, "body": body, "user": {"login": "issue-test[bot]"}}]

        publisher._request = request
        self.assertEqual(42, publisher.create({"title": "Report", "body": "Details"}))
        self.assertEqual(42, publisher.find(report_id, self.now))
        self.assertEqual(1, len(posted))


if __name__ == "__main__":
    unittest.main()
