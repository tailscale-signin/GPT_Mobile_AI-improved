"""Exercise the production control listener over a real loopback TLS socket.

This verifies transport integration, not a physical Android/Windows pairing.
"""
import base64
import hashlib
import json
from pathlib import Path
import socket
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
import uuid

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.configuration import DEFAULTS, atomic_json
from desktop.control_settings import Controller


class PairedControlTlsTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)
        self.control = Controller(SimpleNamespace(root=self.root, cfg=dict(DEFAULTS)))
        with self.control.store.tx() as db:
            db.execute('CREATE TABLE gateway_installation (key TEXT PRIMARY KEY,value TEXT NOT NULL)')
            db.execute("INSERT INTO gateway_installation VALUES ('gateway_id','tls-integration-pc')")
        self.control.store.observe_runtime(True, [])
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        self.control.enable({'host': '127.0.0.1', 'port': port})
        self.addCleanup(self.control.close)
        deadline = time.monotonic() + 5
        while not self.control.description()['enabled'] and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertTrue(self.control.description()['enabled'], 'The production TLS listener did not start')
        self.client = requests.Session()
        self.client.trust_env = False
        self.addCleanup(self.client.close)
        self.certificate = str(self.root / 'data' / 'control-certificate.pem')
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public = base64.b64encode(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def headers(self, token, method, path, body):
        stamp, nonce = str(int(time.time())), uuid.uuid4().hex
        message = '\n'.join(('gptmobile-control-v1', 'tls-integration-pc', method, path,
                             stamp, nonce, hashlib.sha256(body).hexdigest())).encode()
        signature = base64.b64encode(self.key.sign(message, ec.ECDSA(hashes.SHA256()))).decode()
        return {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                'X-Gateway-Installation-ID': 'tls-integration-pc',
                'X-Gateway-Device-Time': stamp, 'X-Gateway-Device-Nonce': nonce,
                'X-Gateway-Device-Signature': signature}

    def call(self, token, method, path, value=None, headers=None):
        body = json.dumps(value, separators=(',', ':')).encode() if value is not None else b''
        return self.client.request(method, self.control.endpoint + path, data=body,
                                   headers=headers or self.headers(token, method, path, body),
                                   verify=self.certificate, timeout=(3, 5), allow_redirects=False)

    def pair(self):
        invitation = self.control.local('control-pairing-create', {
            'name': 'Transport test', 'repositories': ['owner/approved']})
        response = self.call(invitation['code'], 'POST', '/gateway/pairing/claim', {
            'installation_id': invitation['installation_id'], 'device_name': 'Test device',
            'public_key': self.public})
        self.assertEqual(200, response.status_code, response.text)
        finish = {'pairing_id': response.json()['pairing_id'], 'public_key': self.public}
        self.assertEqual(202, self.call(invitation['code'], 'POST', '/gateway/pairing/finish', finish).status_code)
        self.control.local('control-pairing-confirm', {'pairing_id': finish['pairing_id'], 'approved': True})
        response = self.call(invitation['code'], 'POST', '/gateway/pairing/finish', finish)
        self.assertEqual(200, response.status_code, response.text)
        grant = response.json()
        self.assertEqual(invitation['fingerprint'], grant['fingerprint'])
        return grant

    def test_real_tls_pair_shared_revision_original_receipt_and_revocation(self):
        grant = self.pair()
        initial = self.call(grant['token'], 'GET', '/gateway/settings').json()
        command = {'installation_id': grant['installation_id'], 'expected_revision': initial['revision'],
                   'command_id': uuid.uuid4().hex, 'changes': {'engineering_enabled': True}}
        # Deliberately discard the mutation response; only a read reconciles it.
        response = self.call(grant['token'], 'PATCH', '/gateway/settings', command)
        self.assertEqual(200, response.status_code)
        response.close()
        receipt = self.call(grant['token'], 'GET', '/gateway/settings/commands/' + command['command_id']).json()
        self.assertEqual('applied', receipt['status'])
        self.assertEqual(command['command_id'], receipt['command_id'])
        self.assertEqual(initial['revision'] + 1, receipt['revision'])
        desktop = self.control.local('control-settings-read', {'owner_principal': grant['principal_id']})
        self.assertEqual(receipt['revision'], desktop['revision'])
        self.assertTrue(desktop['effective']['engineering_enabled'])
        stale = dict(command, command_id=uuid.uuid4().hex, changes={'engineering_enabled': False})
        rejected = self.call(grant['token'], 'PATCH', '/gateway/settings', stale)
        self.assertEqual(409, rejected.status_code)
        self.assertEqual('not_dispatched', rejected.json()['dispatch_state'])
        self.assertEqual('rejected', self.call(grant['token'], 'GET', '/gateway/settings/commands/' + stale['command_id']).json()['status'])
        self.control.local('control-device-revoke', {'device_id': grant['device_id']})
        self.assertEqual(401, self.call(grant['token'], 'GET', '/gateway/settings').status_code)

    def test_tls_trust_and_exact_signed_request_are_required(self):
        with self.assertRaises(requests.exceptions.SSLError):
            self.client.get(self.control.endpoint + '/gateway/settings', timeout=(3, 5))
        grant = self.pair()
        headers = self.headers(grant['token'], 'GET', '/gateway/settings', b'')
        self.assertEqual(200, self.call(grant['token'], 'GET', '/gateway/settings', headers=headers).status_code)
        replay = self.call(grant['token'], 'GET', '/gateway/settings', headers=headers)
        self.assertEqual(401, replay.status_code)
        self.assertEqual('request_replayed', replay.json()['error']['code'])
        wrong_key = ec.generate_private_key(ec.SECP256R1())
        self.key = wrong_key
        self.assertEqual(401, self.call(grant['token'], 'GET', '/gateway/settings').status_code)


if __name__ == '__main__':
    unittest.main()
