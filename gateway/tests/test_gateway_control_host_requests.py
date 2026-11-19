"""Bounded PC review notes retain their owner and receipt without executing an action."""
import base64
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.configuration import DEFAULTS, atomic_json
from desktop.control_settings import Controller
from desktop.control_store import ControlError, ControlStore, HOST_REQUESTS


class HostRequestTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)
        self.store = ControlStore(self.root)
        with self.store.tx() as db:
            db.execute('CREATE TABLE gateway_installation (key TEXT PRIMARY KEY,value TEXT NOT NULL)')
            db.execute("INSERT INTO gateway_installation VALUES ('gateway_id','host-review-pc')")
        self.supervisor = SimpleNamespace(root=self.root, cfg=dict(DEFAULTS),
                                          boot=Mock(), stop=Mock(), restart=Mock())
        self.controller = Controller(self.supervisor)
        self.addCleanup(self.controller.close)
        self.owner = self.pair()

    def pair(self, fields=None, role='owner'):
        key = ec.generate_private_key(ec.SECP256R1())
        public = base64.b64encode(key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        invitation = self.store.pairing_create(dict(name='Review test phone', role=role,
            fields=['host_request', 'workers'] if fields is None else fields, repositories=['owner/repo']))
        claim = self.store.pairing_claim(invitation['code'], dict(installation_id='host-review-pc',
            device_name='Review test phone', public_key=public))
        self.store.pairing_confirm(claim['pairing_id'], True)
        grant, status = self.store.pairing_finish(invitation['code'],
            dict(pairing_id=claim['pairing_id'], public_key=public), b't' * 32)
        self.assertEqual(200, status)
        return grant['principal_id']

    def command(self, changes, owner=None):
        principal = owner or self.owner
        return dict(installation_id='host-review-pc', command_id=uuid.uuid4().hex,
                    expected_revision=self.store.snapshot(principal)['revision'], changes=changes)

    def test_each_note_is_durable_and_only_local_acknowledgement_marks_it_reviewed(self):
        settings = (self.root / 'settings.json').read_bytes()
        self.assertEqual({'Review phone network access', 'Open engineering setup', 'Review pending restart'}, set(HOST_REQUESTS))
        with patch('desktop.control_settings.requests.Session', side_effect=AssertionError('A review note must not contact or operate the runtime')):
            for note in sorted(HOST_REQUESTS):
                with self.subTest(note=note):
                    command = self.command({'host_request': note})
                    receipt = self.store.patch(self.owner, command)
                    self.assertEqual('waiting_pc', receipt['status'])
                    self.assertNotIn('host_request', receipt['snapshot']['desired'])
                    reopened = ControlStore(self.root)
                    replayed = reopened.patch(self.owner, command)
                    self.assertEqual((receipt['revision'], command['command_id']),
                                     (replayed['revision'], replayed['command_id']))
                    self.assertEqual(receipt['revision'], replayed['snapshot']['revision'])
                    listed = self.controller.local('control-host-requests', {})['items']
                    pending = [item for item in listed if item['command_id'] == command['command_id']]
                    self.assertEqual(1, len(pending))
                    self.assertEqual((self.owner, note, 'waiting_pc'),
                                     (pending[0]['principal_id'], pending[0]['request'], pending[0]['status']))
                    acknowledgement = dict(installation_id='host-review-pc', principal_id=self.owner,
                                           command_id=command['command_id'])
                    reviewed = self.controller.local('control-host-request-review', acknowledgement)
                    self.assertEqual('reviewed', reviewed['status'])
                    self.assertEqual('reviewed', reopened.receipt(self.owner, command['command_id'])['status'])
                    self.assertEqual('reviewed', self.controller.local('control-host-request-review', acknowledgement)['status'])
        self.assertEqual(settings, (self.root / 'settings.json').read_bytes())
        for action in (self.supervisor.boot, self.supervisor.stop, self.supervisor.restart):
            action.assert_not_called()

    def test_waiting_note_does_not_block_settings_and_does_not_leak_to_other_owner(self):
        command = self.command({'host_request': 'Review pending restart'})
        receipt = self.store.patch(self.owner, command)
        capacity = self.store.patch(self.owner, self.command({'workers': 1}))
        self.assertNotEqual('rejected', capacity['status'])
        self.assertEqual(1, capacity['snapshot']['desired']['workers'])
        self.assertEqual('waiting_pc', capacity['snapshot']['host_requests'][0]['status'])
        second = self.store.patch(self.owner, self.command({'host_request': 'Open engineering setup'}))
        self.assertEqual(('rejected', 'host_request_pending'), (second['status'], second['error']['code']))
        self.assertEqual(second, self.store.receipt(self.owner, second['command_id']))
        other = self.pair()
        self.assertEqual([], self.store.snapshot(other)['host_requests'])
        with self.assertRaises(ControlError) as missing:
            self.store.receipt(other, command['command_id'])
        self.assertEqual(('command_not_found', 404), (missing.exception.code, missing.exception.status))
        with self.assertRaises(ControlError) as changed:
            self.store.patch(self.owner, dict(command, changes={'host_request': 'Open engineering setup'}))
        self.assertEqual('command_identity_conflict', changed.exception.code)
        self.assertEqual(receipt['revision'], self.store.receipt(self.owner, command['command_id'])['revision'])

    def test_note_requires_explicit_owner_grant_and_rejects_non_actions_durably(self):
        for principal in (self.pair(fields=['workers']), self.pair(role='viewer')):
            receipt = self.store.patch(principal, self.command({'host_request': 'Review pending restart'}, principal))
            self.assertEqual(('rejected', 'field_permission_required'), (receipt['status'], receipt['error']['code']))
            self.assertEqual(receipt, self.store.receipt(principal, receipt['command_id']))
        command = self.command({'host_request': 'Restart now'})
        receipt = self.store.patch(self.owner, command)
        self.assertEqual(('rejected', 'invalid_host_request'), (receipt['status'], receipt['error']['code']))
        self.assertEqual(receipt, ControlStore(self.root).patch(self.owner, command))
        self.assertEqual([], self.store.snapshot(self.owner)['host_requests'])


if __name__ == '__main__':
    unittest.main()
