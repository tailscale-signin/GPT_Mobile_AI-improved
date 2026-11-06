import concurrent.futures
import hashlib
import tempfile
import threading
import unittest
import urllib.parse
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from authentication.app import create_app
from authentication.github_broker import Config, challenge_for
from authentication.ticket_broker import TicketBroker, TicketError


class TicketBrokerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = Config('Iv1.test', 'test-broker-secret', 'https://auth.example.org/oauth/github/callback',
                             'https://auth.example.org', Path(self.temp.name) / 'oauth.sqlite',
                             'https://app.example.org/oauth/github/complete')
        self.now, self.calls = 1000, []
        self.verifier, self.session = 'v' * 43, 's' * 43
        self.account = hashlib.sha256(b'connection-1').hexdigest()
        def exchange(payload):
            self.calls.append(payload)
            return {'access_token': 'ghu_secret', 'token_type': 'bearer', 'expires_in': 3600,
                    'refresh_token': 'ghr_secret', 'refresh_token_expires_in': 7200}
        self.exchange = exchange
        self.broker = TicketBroker(self.config, self.exchange, lambda: self.now)

    def begin(self):
        return self.broker.begin_ticket(challenge_for(self.verifier), self.session, self.account)

    def ticket(self):
        state = self.begin()['state']
        callback = self.broker.callback(state, 'private-code')
        values = urllib.parse.parse_qs(urllib.parse.urlsplit(callback).query)
        return state, values['ticket'][0]

    def redeem(self, pair, broker=None):
        return (broker or self.broker).redeem(*pair, self.verifier, self.session, self.account)

    def test_broker_callback_and_app_link_are_separate_and_code_never_leaves_broker(self):
        start = self.begin()
        self.assertEqual(2, start['protocolVersion'])
        self.assertEqual(self.config.callback_url, start['redirectUri'])
        self.assertEqual(self.config.app_link_url, start['appLinkUri'])
        url = self.broker.callback(start['state'], 'private-code')
        self.assertTrue(url.startswith(self.config.app_link_url + '?'))
        self.assertNotIn('private-code', url)
        self.assertNotIn('ghu_', url)
        self.assertEqual(url, self.broker.callback(start['state'], 'private-code'))
        with self.assertRaises(TicketError):
            self.broker.callback(start['state'], 'changed-code')

    def test_lost_response_is_replayed_after_restart_without_a_second_code_exchange(self):
        pair = self.ticket()
        first = self.redeem(pair)
        self.now += 30
        restarted = TicketBroker(self.config, self.exchange, lambda: self.now)
        repeated = self.redeem(pair, restarted)
        self.assertEqual(first['access_token'], repeated['access_token'])
        self.assertEqual(3570, repeated['expires_in'])
        self.assertEqual(7170, repeated['refresh_token_expires_in'])
        self.assertEqual(1, len(self.calls))

    def test_concurrent_redemption_has_one_dispatch(self):
        pair = self.ticket()
        def attempt(_):
            try:
                return self.redeem(pair)
            except TicketError as error:
                self.assertEqual('RECONCILIATION_REQUIRED', error.code)
                return None
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(attempt, range(8)))
        self.assertEqual(1, len(self.calls))
        self.assertEqual('ghu_secret', self.redeem(pair)['access_token'])

    def test_wrong_session_account_ticket_and_pkce_never_dispatch_or_consume(self):
        pair = self.ticket()
        attempts = [(pair[0], pair[1], 'x' * 43, self.session, self.account),
                    (pair[0], pair[1], self.verifier, 'x' * 43, self.account),
                    (pair[0], pair[1], self.verifier, self.session, '0' * 64),
                    (pair[0], 'x' * 43, self.verifier, self.session, self.account)]
        for args in attempts:
            with self.assertRaises(TicketError):
                self.broker.redeem(*args)
        self.assertEqual([], self.calls)
        self.redeem(pair)
        self.assertEqual(1, len(self.calls))

    def test_pending_and_ticket_expiry_never_dispatch(self):
        pair = self.ticket()
        self.now += 120
        with self.assertRaises(TicketError) as caught:
            self.redeem(pair)
        self.assertEqual('TICKET_EXPIRED', caught.exception.code)
        self.now += 500
        with self.assertRaises(TicketError):
            self.broker.callback(pair[0], 'private-code')
        self.assertEqual([], self.calls)

    def test_uncertain_exchange_is_never_reissued_and_exceptions_are_sanitized(self):
        pair = self.ticket()
        def failure(payload):
            self.calls.append(payload)
            raise TimeoutError('test-broker-secret private-code')
        self.broker.exchange = failure
        with self.assertRaises(TicketError) as caught:
            self.redeem(pair)
        self.assertNotIn('private-code', str(caught.exception))
        restarted = TicketBroker(self.config, self.exchange, lambda: self.now)
        with self.assertRaises(TicketError):
            self.redeem(pair, restarted)
        self.assertEqual(1, len(self.calls))

    def test_disk_failure_before_claim_prevents_dispatch(self):
        pair = self.ticket()
        with patch.object(self.broker.transactions, 'connect', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.redeem(pair)
        self.assertEqual([], self.calls)
        self.redeem(pair)
        self.assertEqual(1, len(self.calls))

    def test_failure_to_persist_outcome_leaves_durable_uncertainty(self):
        pair = self.ticket()
        original = self.broker.seal
        def full(value, identity, purpose):
            if purpose == 'outcome':
                raise OSError('disk full')
            return original(value, identity, purpose)
        with patch.object(self.broker, 'seal', side_effect=full):
            with self.assertRaises(TicketError):
                self.redeem(pair)
        with self.assertRaises(TicketError):
            self.redeem(pair)
        self.assertEqual(1, len(self.calls))

    def test_ciphertext_is_bound_to_session_and_secrets_are_absent_from_database(self):
        pair = self.ticket()
        self.redeem(pair)
        data = self.config.database.read_bytes()
        for secret in (*pair, self.session, self.verifier, 'private-code', 'test-broker-secret', 'ghu_secret', 'ghr_secret'):
            self.assertNotIn(secret.encode(), data)
        identity = self.broker.transactions.digest(pair[0])
        with self.broker.transactions.connect() as db:
            blob = db.execute('SELECT outcome FROM oauth_tickets WHERE state_hash=?', (identity,)).fetchone()[0]
        with self.assertRaises(TicketError):
            self.broker.unseal(blob, 'another-session', 'outcome')

    def test_callback_http_contract_has_no_store_and_rejects_duplicate_state(self):
        client = TestClient(create_app(self.config, self.exchange), base_url=self.config.public_url)
        response = client.post('/v2/github/oauth/start', json={'codeChallenge': challenge_for(self.verifier),
                               'clientSessionId': self.session, 'accountIntent': self.account})
        state = response.json()['state']
        response = client.get('/oauth/github/callback', params={'state': state, 'code': 'private-code'}, follow_redirects=False)
        self.assertEqual(303, response.status_code)
        self.assertEqual('no-store', response.headers['cache-control'])
        target = response.headers['location']
        self.assertNotIn('private-code', target)
        ticket = urllib.parse.parse_qs(urllib.parse.urlsplit(target).query)['ticket'][0]
        params = {'state': state, 'ticket': ticket, 'codeVerifier': self.verifier,
                  'clientSessionId': self.session, 'accountIntent': self.account}
        self.assertEqual(200, client.post('/v2/github/oauth/redeem', json=params).status_code)
        self.assertEqual(200, client.post('/v2/github/oauth/redeem', json=params).status_code)
        self.assertEqual(1, len(self.calls))
        self.assertEqual(400, client.get('/oauth/github/callback?state=x&state=y&code=private-code').status_code)


if __name__ == '__main__':
    unittest.main()
