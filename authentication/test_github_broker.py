import concurrent.futures
import json
import sqlite3
import tempfile
import unittest
import urllib.parse
import uuid
from pathlib import Path
from unittest.mock import patch

from authentication.github_broker import AuthorizationError, Broker, Config, Transactions, challenge_for


class BrokerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.clock = [1000]
        self.config = Config("Iv1.public", "server-secret", "https://auth.example.org/oauth/github/callback", "https://auth.example.org", Path(self.temp.name) / "auth.sqlite")
        self.calls = []
        self.result = {"access_token": "ghu_test", "token_type": "bearer", "expires_in": 28800, "refresh_token": "ghr_test", "refresh_token_expires_in": 15897600}
        self.broker = Broker(self.config, self.exchange, lambda: self.clock[0])
        self.verifier = "v" * 43

    def exchange(self, payload):
        self.calls.append(payload)
        return self.result

    def start(self):
        return self.broker.begin(challenge_for(self.verifier))["state"]

    def test_authorization_uses_fixed_registration_and_s256(self):
        start = self.broker.begin(challenge_for(self.verifier))
        uri = urllib.parse.urlsplit(start["authorizationUrl"])
        self.assertEqual("github.com", uri.hostname)
        query = urllib.parse.parse_qs(uri.query)
        self.assertEqual(["S256"], query["code_challenge_method"])
        self.assertEqual([self.config.callback_url], query["redirect_uri"])
        self.assertNotIn("server-secret", json.dumps(start))
        self.assertNotIn("server-secret", repr(self.config))

    def test_transactions_close_connections_on_success_and_rejection(self):
        real_connect = sqlite3.connect
        retained = []
        def tracked(*args, **kwargs):
            connection = real_connect(*args, **kwargs)
            retained.append(connection)
            return connection
        with patch("authentication.github_broker.sqlite3.connect", side_effect=tracked):
            broker = Broker(self.config, self.exchange, lambda: self.clock[0])
            state = broker.begin(challenge_for(self.verifier))["state"]
            with self.assertRaises(AuthorizationError):
                broker.complete(state, "x" * 43, "code")
            broker.complete(state, self.verifier, "code")
            with self.assertRaises(AuthorizationError):
                broker.complete(state, self.verifier, "code")
            operation = str(uuid.uuid4())
            broker.refresh(operation, "ghr_close_test")
            with self.assertRaises(AuthorizationError):
                broker.refresh(operation, "ghr_close_test")
        self.assertTrue(retained)
        # Holding every reference defeats GC and reproduces the Windows file lock.
        for connection in retained:
            with self.assertRaises(sqlite3.ProgrammingError):
                connection.execute("SELECT 1")

    def test_transaction_survives_restart_and_is_consumed_exactly_once(self):
        state = self.start()
        restarted = Broker(self.config, self.exchange, lambda: self.clock[0])
        self.assertEqual("ghu_test", restarted.complete(state, self.verifier, "code")["access_token"])
        with self.assertRaises(AuthorizationError):
            self.broker.complete(state, self.verifier, "code")
        self.assertEqual(1, len(self.calls))

    def test_wrong_verifier_and_expired_state_never_reach_github(self):
        state = self.start()
        with self.assertRaises(AuthorizationError):
            self.broker.complete(state, "x" * 43, "code")
        self.clock[0] += 600
        with self.assertRaises(AuthorizationError):
            self.broker.complete(state, self.verifier, "code")
        self.assertEqual([], self.calls)

    def test_concurrent_exchanges_do_not_duplicate_execution(self):
        state = self.start()
        def attempt(_):
            try:
                self.broker.complete(state, self.verifier, "code")
                return True
            except AuthorizationError:
                return False
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(1, sum(pool.map(attempt, range(8))))
        self.assertEqual(1, len(self.calls))

    def test_uncertain_exchange_is_not_replayed_or_leaked(self):
        state = self.start()
        def timeout(payload):
            raise TimeoutError("server-secret and sensitive-code")
        self.broker.exchange = timeout
        with self.assertRaisesRegex(AuthorizationError, "start sign-in again") as caught:
            self.broker.complete(state, self.verifier, "sensitive-code")
        self.assertNotIn("server-secret", str(caught.exception))
        self.broker.exchange = self.exchange
        with self.assertRaises(AuthorizationError):
            self.broker.complete(state, self.verifier, "sensitive-code")
        self.assertEqual([], self.calls)

    def test_credentials_verifier_codes_and_raw_state_are_not_persisted(self):
        state = self.start()
        self.broker.complete(state, self.verifier, "secret-code")
        data = self.config.database.read_bytes()
        for secret in (state, self.verifier, "secret-code", "server-secret", "ghu_test", "ghr_test"):
            self.assertNotIn(secret.encode(), data)

    def test_upstream_errors_do_not_echo_descriptions(self):
        self.result = {"error": "access_denied", "error_description": "credential-leak"}
        with self.assertRaises(AuthorizationError) as caught:
            self.broker.complete(self.start(), self.verifier, "code")
        self.assertNotIn("credential-leak", str(caught.exception))

    def test_non_https_and_dynamic_callbacks_are_rejected(self):
        for callback in ("http://example.org/callback", "https://user:secret@example.org/callback", "https://example.org/callback?redirect=attacker", "https://example.org/callback#token"):
            with self.assertRaises(ValueError):
                Config("Iv1.public", "secret", callback, self.config.public_url, self.config.database)

    def test_refresh_rotation_is_bound_to_one_operation_and_never_replayed(self):
        operation = str(uuid.uuid4())
        result = self.broker.refresh(operation, "ghr_input")
        self.assertEqual("ghr_test", result["refresh_token"])
        self.assertEqual("refresh_token", self.calls[0]["grant_type"])
        for op, token in ((operation, "ghr_input"), (str(uuid.uuid4()), "ghr_input"), (operation, "ghr_different")):
            with self.assertRaises(AuthorizationError):
                self.broker.refresh(op, token)
        self.assertEqual(1, len(self.calls))
        self.assertNotIn(b"ghr_input", self.config.database.read_bytes())

    def test_refresh_timeout_requires_reconnection_without_revealing_credentials(self):
        def timeout(_):
            raise TimeoutError("ghr_sensitive")
        self.broker.exchange = timeout
        operation = str(uuid.uuid4())
        with self.assertRaises(AuthorizationError) as failure:
            self.broker.refresh(operation, "ghr_sensitive")
        self.assertNotIn("ghr_sensitive", str(failure.exception))
        self.broker.exchange = self.exchange
        with self.assertRaises(AuthorizationError):
            self.broker.refresh(operation, "ghr_sensitive")
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
