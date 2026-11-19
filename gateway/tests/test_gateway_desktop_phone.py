"""Preserve the companion prototype's safety cases on the canonical TLS service.

The unreleased mTLS/phone:* prototype is superseded by one supervisor-owned
settings ledger and signed paired:* grants. These cases keep owner/viewer
isolation, delivery replay, stale edits, deferred worker activation, revocation,
and management/inference separation on a real TLS socket.
"""
import json
import unittest
import uuid

import test_gateway_control_tls as transport_fixture


class CanonicalPhoneControlsTests(unittest.TestCase):
    def setUp(self):
        self.transport = transport_fixture.PairedControlTlsTests()
        self.addCleanup(self.transport.doCleanups)
        self.transport.setUp()
        self.control = self.transport.control

    def command(self, snapshot, **changes):
        return {'installation_id': snapshot['installation_id'], 'expected_revision': snapshot['revision'],
                'command_id': uuid.uuid4().hex, 'changes': changes}

    def pair_viewer(self):
        client = self.transport
        invitation = self.control.local('control-pairing-create', {
            'name': 'Viewer transport test', 'role': 'viewer', 'repositories': ['owner/approved']})
        claim = client.call(invitation['code'], 'POST', '/gateway/pairing/claim', {
            'installation_id': invitation['installation_id'], 'device_name': 'Viewer', 'public_key': client.public})
        self.assertEqual(200, claim.status_code, claim.text)
        finish = {'pairing_id': claim.json()['pairing_id'], 'public_key': client.public}
        self.control.local('control-pairing-confirm', {'pairing_id': finish['pairing_id'], 'approved': True})
        response = client.call(invitation['code'], 'POST', '/gateway/pairing/finish', finish)
        self.assertEqual(200, response.status_code, response.text)
        return response.json()

    def test_owner_replay_stale_edits_deferred_capacity_and_revocation(self):
        client = self.transport
        grant = client.pair()
        path = '/gateway/settings'
        self.assertEqual(401, client.call('ordinary-profile-token', 'GET', path).status_code)
        snapshot = client.call(grant['token'], 'GET', path).json()
        self.assertFalse(snapshot['desired']['engineering_enabled'])
        enable = self.command(snapshot, engineering_enabled=True)
        accepted = client.call(grant['token'], 'PATCH', path, enable)
        self.assertEqual(200, accepted.status_code, accepted.text)
        self.assertTrue(accepted.json()['snapshot']['effective']['engineering_enabled'])
        replay = client.call(grant['token'], 'PATCH', path, enable)
        self.assertEqual(accepted.json()['revision'], replay.json()['revision'])
        self.assertEqual(409, client.call(grant['token'], 'PATCH', path,
            dict(enable, changes={'engineering_enabled': False})).status_code)
        self.assertEqual(409, client.call(grant['token'], 'PATCH', path,
            self.command(snapshot, workers=4)).status_code)
        current = client.call(grant['token'], 'GET', path).json()
        self.assertEqual(400, client.call(grant['token'], 'PATCH', path,
            self.command(current, engineering_enabled=1)).status_code)
        capacity = self.command(current, workers=4)
        queued = client.call(grant['token'], 'PATCH', path, capacity)
        self.assertEqual(200, queued.status_code, queued.text)
        self.assertEqual((4, 2), (queued.json()['snapshot']['desired']['workers'], queued.json()['snapshot']['effective']['workers']))
        baseline = {key: current['effective'][key] for key in
                    ('workers', 'active_chats', 'backend', 'model', 'public_repository_maps', 'debug_mode')}
        self.control.runtime = lambda values=None: {'status': 'waiting_for_jobs', 'effective': baseline,
            'blocking_jobs': ['accepted-original-job'], 'engineering_ready': True, 'engineering_prerequisites': []}
        self.control.reconcile()
        receipt = client.call(grant['token'], 'GET', path + '/commands/' + capacity['command_id']).json()
        self.assertEqual('waiting_for_jobs', receipt['status'])
        self.assertEqual(['accepted-original-job'], receipt['snapshot']['pending'][0]['blocking_jobs'])
        self.assertEqual(2, json.loads((client.root / 'settings.json').read_text())['workers'])
        def idle(values=None):
            if values:
                baseline.update(values)
            return {'status': 'applied', 'effective': baseline, 'blocking_jobs': [],
                    'engineering_ready': True, 'engineering_prerequisites': []}
        self.control.runtime = idle
        self.control.reconcile()
        self.assertEqual(4, json.loads((client.root / 'settings.json').read_text())['workers'])
        final = client.call(grant['token'], 'GET', path + '/commands/' + capacity['command_id']).json()
        self.assertEqual('applied', final['status'])
        self.assertEqual(capacity['command_id'], final['command_id'])
        self.control.local('control-device-revoke', {'device_id': grant['device_id']})
        self.assertEqual(401, client.call(grant['token'], 'GET', path).status_code)

    def test_viewer_owner_identity_and_management_inference_separation(self):
        client = self.transport
        owner = client.pair()
        viewer = self.pair_viewer()
        path = '/gateway/settings'
        observed = client.call(viewer['token'], 'GET', path).json()
        self.assertEqual('viewer', observed['role'])
        self.assertEqual([], observed['writable_fields'])
        denied = client.call(viewer['token'], 'PATCH', path, self.command(observed, engineering_enabled=True))
        self.assertEqual(403, denied.status_code)
        self.assertEqual('not_dispatched', denied.json()['dispatch_state'])
        owner_snapshot = client.call(owner['token'], 'GET', path).json()
        forged = dict(self.command(owner_snapshot, engineering_enabled=True), principal_id=viewer['principal_id'])
        self.assertEqual(400, client.call(owner['token'], 'PATCH', path, forged).status_code)
        replaced = dict(self.command(owner_snapshot, engineering_enabled=True), installation_id='other-installation')
        self.assertEqual(409, client.call(owner['token'], 'PATCH', path, replaced).status_code)
        self.assertFalse(client.call(owner['token'], 'GET', path).json()['desired']['engineering_enabled'])
        self.assertNotEqual(owner['principal_id'], viewer['principal_id'])
        self.assertEqual(403, client.call(owner['token'], 'POST', '/v1/chat/completions', {'model': 'fixture'}).status_code)
        self.assertEqual(403, client.call(owner['token'], 'GET', '/gateway/admin/dashboard').status_code)
        self.control.local('control-device-revoke', {'device_id': viewer['device_id']})
        self.assertEqual(401, client.call(viewer['token'], 'GET', path).status_code)
        self.assertEqual(200, client.call(owner['token'], 'GET', path).status_code)


if __name__ == '__main__':
    unittest.main()
