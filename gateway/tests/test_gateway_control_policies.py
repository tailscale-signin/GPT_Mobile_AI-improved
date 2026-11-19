"""Approved host choices share the durable control ledger and idle activation."""
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_gateway_control_settings as fixture
from desktop.configuration import DEFAULTS
from desktop.control_settings import Controller
from desktop.control_store import ControlError, ControlStore, RUNTIME_FIELDS
from desktop import phone_policies
from desktop.validation_policy import load_policy_snapshot, save_policy_snapshot


class ApprovedHostChoiceTests(unittest.TestCase):
    setUp = fixture.ControlSettingsTests.setUp
    paired = fixture.ControlSettingsTests.paired
    command = fixture.ControlSettingsTests.command

    def policy(self, version, repository='owner/repo'):
        value = {repository: {'mirror': str(self.root / 'mirror'),
                             'image': 'fixture@sha256:' + version * 64,
                             'commands': [['python3', '-m', 'unittest']]}}
        current = load_policy_snapshot(self.root)
        save_policy_snapshot(self.root, value, enabled=True, expected_revision=current['revision'])
        return value

    def controller(self):
        return Controller(SimpleNamespace(root=self.root, cfg=dict(DEFAULTS)))

    def test_approved_profile_waits_for_original_jobs_and_observed_restart(self):
        self.policy('a')
        controller = self.controller()
        first = controller.local('control-validation-approve', {})
        self.policy('b')
        second = controller.local('control-validation-approve', {})
        _, grant = self.paired()
        owner = grant['principal_id']
        snapshot = self.store.snapshot(owner)
        command = self.command({'validation_profile': first['profile_id']}, snapshot['revision'])
        accepted = self.store.patch(owner, command)
        baseline = {key: snapshot['effective'][key] for key in RUNTIME_FIELDS}
        baseline['validation_profile'] = second['profile_id']
        controller.runtime = lambda values=None: {'status': 'waiting_for_jobs', 'effective': baseline,
            'blocking_jobs': ['original-engineering-job'], 'engineering_ready': True, 'engineering_prerequisites': []}
        controller.reconcile()
        self.assertFalse(controller.restart_requested)
        self.assertEqual(second['profile_id'], phone_policies.active(self.root))
        waiting = self.store.receipt(owner, command['command_id'])
        pending = next(item for item in waiting['snapshot']['pending'] if item['field'] == 'validation_profile')
        self.assertEqual(['original-engineering-job'], pending['blocking_jobs'])
        self.assertEqual('waiting_for_jobs', waiting['status'])

        def idle(values=None):
            if values:
                baseline.update(values)
            return {'status': 'applied', 'effective': baseline, 'blocking_jobs': [],
                    'engineering_ready': True, 'engineering_prerequisites': []}
        reopened = self.controller()
        reopened.runtime = idle
        reopened.reconcile()
        self.assertTrue(reopened.restart_requested)
        self.assertEqual(first['profile_id'], phone_policies.active(self.root))
        still_running = self.store.receipt(owner, command['command_id'])
        self.assertEqual(second['profile_id'], still_running['snapshot']['effective']['validation_profile'])
        self.assertEqual(accepted['revision'], still_running['snapshot']['revision'])
        self.assertEqual('awaiting_restart', next(item for item in still_running['snapshot']['pending']
            if item['field'] == 'validation_profile')['reason'])
        baseline['validation_profile'] = first['profile_id']
        after_restart = self.controller()
        after_restart.runtime = idle
        after_restart.reconcile()
        self.assertFalse(after_restart.restart_requested)
        final = self.store.receipt(owner, command['command_id'])
        self.assertEqual(('applied', command['command_id'], accepted['revision']),
                         (final['status'], final['command_id'], final['revision']))
        replay = ControlStore(self.root).patch(owner, command)
        self.assertEqual((final['status'], final['command_id'], final['revision']),
                         (replay['status'], replay['command_id'], replay['revision']))
        with self.store.tx() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM gateway_control_commands').fetchone()[0])

    def test_host_policy_edit_supersedes_waiting_phone_selection_and_cas_rejects_it(self):
        self.policy('a')
        first = self.controller().local('control-validation-approve', {})
        self.policy('b')
        _, grant = self.paired()
        owner = grant['principal_id']
        snapshot = self.store.snapshot(owner)
        command = self.command({'validation_profile': first['profile_id']}, snapshot['revision'])
        accepted = self.store.patch(owner, command)
        latest_document = self.policy('c')
        latest_hash = phone_policies.active(self.root)
        with self.assertRaises(ControlError) as error:
            phone_policies.activate(self.store, first['profile_id'])
        self.assertEqual('validation_policy_changed', error.exception.code)
        self.assertEqual(latest_document, load_policy_snapshot(self.root)['repositories'])
        superseded = self.store.receipt(owner, command['command_id'])
        self.assertEqual('superseded', superseded['status'])
        self.assertEqual(latest_hash, superseded['snapshot']['desired']['validation_profile'])
        self.assertGreater(superseded['snapshot']['revision'], accepted['revision'])

    def test_profile_options_and_writes_require_explicit_repository_and_field_grants(self):
        self.policy('a')
        allowed = self.controller().local('control-validation-approve', {})
        self.policy('b', 'private/other')
        hidden = self.controller().local('control-validation-approve', {})
        _, grant = self.paired()
        owner = grant['principal_id']
        snapshot = self.store.snapshot(owner)
        self.assertEqual({allowed['profile_id']: allowed['label']}, snapshot['validation_profiles'])
        self.assertNotIn(hidden['label'], json.dumps(snapshot['validation_profiles']))
        denied = self.store.patch(owner, self.command({'validation_profile': hidden['profile_id']}, snapshot['revision']))
        self.assertEqual(('rejected', 'repository_permission_required'), (denied['status'], denied['error']['code']))
        _, limited = self.paired(fields=['engineering_enabled'])
        denied = self.store.patch(limited['principal_id'], self.command({'validation_profile': allowed['profile_id']}, snapshot['revision']))
        self.assertEqual('field_permission_required', denied['error']['code'])
        with self.store.tx() as db:
            db.execute('UPDATE gateway_control_validation_profiles SET document=? WHERE id=?', ('{}', allowed['profile_id']))
        command = self.command({'validation_profile': allowed['profile_id']}, snapshot['revision'])
        denied = self.store.patch(owner, command)
        self.assertEqual('validation_profile_changed', denied['error']['code'])
        self.assertEqual(denied, self.store.receipt(owner, command['command_id']))

    def test_backend_catalog_approval_does_not_switch_and_phone_cannot_add_origins(self):
        controller = self.controller()
        _, grant = self.paired()
        owner = grant['principal_id']
        origin = 'http://127.0.0.1:18081'
        rejected = self.store.patch(owner, self.command({'backend': origin}))
        self.assertEqual('host_approval_required', rejected['error']['code'])
        with patch('v14.control_settings.probe_backend') as probe:
            result = controller.local('control-backend-approve', {'backend': origin})
            self.assertEqual({'backend': origin, 'approved': True}, result)
            probe.assert_called_once_with(origin, '')
            with self.assertRaises(ControlError) as error:
                controller.local('control-backend-approve', {'backend': 'http://127.0.0.1:8090'})
            self.assertEqual('invalid_backend', error.exception.code)
            self.assertEqual(1, probe.call_count)
        snapshot = self.store.snapshot(owner)
        self.assertEqual(DEFAULTS['backend'], snapshot['desired']['backend'])
        self.assertEqual(DEFAULTS['backend'], snapshot['effective']['backend'])
        self.assertIn(origin, snapshot['approved_backends'])
        _, limited = self.paired(fields=['engineering_enabled'])
        denied = self.store.patch(limited['principal_id'], self.command({'backend': origin}))
        self.assertEqual('field_permission_required', denied['error']['code'])
        accepted = self.store.patch(owner, self.command({'backend': origin}))
        self.assertEqual(origin, accepted['snapshot']['desired']['backend'])
        baseline = {key: snapshot['effective'][key] for key in RUNTIME_FIELDS}
        controller.runtime = lambda values=None: {'status': 'setup_required', 'reason': 'probe_failed',
            'effective': baseline, 'blocking_jobs': [], 'engineering_ready': True, 'engineering_prerequisites': []}
        controller.reconcile()
        failed = self.store.receipt(owner, accepted['command_id'])
        self.assertEqual('setup_required', failed['status'])
        self.assertEqual(DEFAULTS['backend'], failed['snapshot']['effective']['backend'])

    def test_malformed_enumerated_values_are_durable_rejections(self):
        _, grant = self.paired()
        for field in ('host_request', 'batch_mode', 'update_mode'):
            for value in ([], {}):
                with self.subTest(field=field, value=value):
                    command = self.command({field: value})
                    rejected = self.store.patch(grant['principal_id'], command)
                    self.assertEqual('rejected', rejected['status'])
                    self.assertEqual('not_dispatched', rejected['dispatch_state'])
                    self.assertEqual(0, rejected['revision'])
                    self.assertEqual(rejected, ControlStore(self.root).patch(grant['principal_id'], command))
                    self.assertEqual(rejected, self.store.receipt(grant['principal_id'], command['command_id']))


if __name__ == '__main__':
    unittest.main()
